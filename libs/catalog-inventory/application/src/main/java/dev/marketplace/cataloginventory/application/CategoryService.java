package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.Category;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Árvore de categorias do tenant: leitura para todos os chamadores, escrita
 * para quem administra a taxonomia.
 */
public final class CategoryService {
    /**
     * Categoria com os atributos efetivos (herdados e próprios) e {@code usable},
     * que indica se ela e todos os ancestrais estão ativos.
     */
    public record CategoryView(Category category, List<AttributeDefinition> effectiveAttributes, boolean usable) {
        /**
         * Monta a visão a partir da cadeia da raiz até a categoria; cadeia vazia
         * (categoria inexistente no tenant) lança {@link NotFoundException}.
         */
        static CategoryView of(List<Category> chain) {
            return new CategoryView(requireChain(chain).getLast(), Category.effectiveAttributes(chain),
                    chain.stream().allMatch(Category::active));
        }

        /** Devolve a cadeia; vazia lança {@link NotFoundException}. */
        static List<Category> requireChain(List<Category> chain) {
            if (chain.isEmpty())
                throw new NotFoundException("Categoria");
            return chain;
        }

        /**
         * Devolve a própria visão quando a categoria e todos os ancestrais estão
         * ativos; senão lança {@link DomainRuleViolation} com o código
         * {@code category-inactive}.
         */
        CategoryView requireActive() {
            if (!usable)
                throw new DomainRuleViolation("category-inactive",
                        "Categoria " + category.code() + " ou um ancestral está inativo");
            return this;
        }
    }

    private final CategoryRepository categories;

    public CategoryService(CategoryRepository categories) {
        this.categories = categories;
    }

    /**
     * Cria uma categoria raiz ou, com {@code parentId}, uma subcategoria. Exige
     * {@code MANAGE_TAXONOMY}. Pai inexistente: 404; inativo:
     * {@code category-inactive}; atributo repetido de ancestral:
     * {@code attribute-conflict}; código já usado: {@link DuplicateException}.
     */
    public Uni<CategoryView> create(Actor actor, Optional<UUID> parentId, String code, String name,
            List<AttributeDefinition> attributes) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_TAXONOMY);
            UUID id = UUID.randomUUID();
            Uni<List<Category>> built = parentId
                    .map(parent -> chain(actor, parent).map(chain -> {
                        Category child = Category.childOf(chain.getLast(), id, code, name, attributes,
                                Category.effectiveAttributes(chain));
                        return Stream.concat(chain.stream(), Stream.of(child)).toList();
                    }))
                    .orElseGet(() -> Uni.createFrom()
                            .item(() -> List.of(Category.root(id, actor.tenantId(), code, name, attributes))));
            return built.flatMap(chain -> categories.insert(chain.getLast())
                    .replaceWith(() -> CategoryView.of(chain)));
        });
    }

    /**
     * Busca a categoria com os atributos efetivos (herdados e próprios) e
     * {@code usable}, que indica se ela e todos os ancestrais estão ativos.
     * Qualquer chamador do tenant lê; inexistente lança
     * {@link NotFoundException}.
     */
    public Uni<CategoryView> get(Actor actor, UUID id) {
        return categories.chain(actor.tenantId(), id).map(CategoryView::of);
    }

    /**
     * Atualiza nome, atributos próprios e situação ({@code active}: ativa ou
     * desativa) e devolve a visão revisada. Exige {@code MANAGE_TAXONOMY};
     * atributo que colide com herdado ou de subcategoria lança
     * {@code attribute-conflict}. {@code ifMatch} ausente dispensa a checagem.
     */
    public Uni<CategoryView> revise(Actor actor, UUID id, Optional<Long> ifMatch, String name,
            List<AttributeDefinition> attributes, boolean active) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_TAXONOMY);
            return Uni.combine().all().unis(chain(actor, id), categories.descendantAttributeCodes(actor.tenantId(), id))
                    .asTuple().flatMap(read -> {
                        List<Category> chain = read.getItem1();
                        Category self = chain.getLast();
                        long loaded = self.version();
                        Versions.requireMatch(ifMatch, loaded);
                        List<AttributeDefinition> inherited = Category
                                .effectiveAttributes(chain.subList(0, chain.size() - 1));
                        self.revise(name, attributes, inherited, read.getItem2());
                        if (active)
                            self.activate();
                        else
                            self.deactivate();
                        return categories.update(self, loaded).replaceWith(() -> CategoryView.of(chain));
                    });
        });
    }

    /**
     * Move a categoria, com a subárvore, para baixo de {@code parentId} (vazio a torna raiz) e devolve a visão
     * recalculada; mover para o pai atual não grava nada. Exige {@code MANAGE_TAXONOMY}; pai inexistente: 404;
     * {@code ifMatch} divergente: 412. Produtos não mudam: atributo obrigatório herdado só é cobrado na próxima
     * edição ou envio de cada um.
     */
    public Uni<CategoryView> move(Actor actor, UUID id, Optional<Long> ifMatch, Optional<UUID> parentId) {
        return Uni.createFrom().deferred(() -> {
            actor.require(Capability.MANAGE_TAXONOMY);
            Uni<List<Category>> parentChain = parentId.map(parent -> chain(actor, parent))
                    .orElseGet(() -> Uni.createFrom().item(List.of()));
            return Uni.combine().all().unis(chain(actor, id), parentChain,
                    categories.subtreeHeight(actor.tenantId(), id),
                    categories.descendantAttributeCodes(actor.tenantId(), id))
                    .asTuple().flatMap(read -> {
                        Category self = read.getItem1().getLast();
                        long loaded = self.version();
                        Versions.requireMatch(ifMatch, loaded);
                        if (self.parentId().equals(parentId))
                            return Uni.createFrom().item(CategoryView.of(read.getItem1()));
                        List<UUID> previousAncestry = self.ancestry();
                        self.moveUnder(read.getItem2(), read.getItem3(), read.getItem4());
                        List<Category> chain = Stream.concat(read.getItem2().stream(), Stream.of(self)).toList();
                        return categories.move(self, previousAncestry, loaded)
                                .replaceWith(() -> CategoryView.of(chain));
                    });
        });
    }

    /**
     * Lista por cursor ({@code afterCode}: último código visto) as
     * subcategorias diretas de {@code parentId} (raízes quando vazio),
     * inclusive inativas. {@code limit} fora de 1 a 100:
     * {@link InvalidInputException}; pai inexistente: 404.
     */
    public Uni<Page<Category>> children(Actor actor, Optional<UUID> parentId, Optional<String> afterCode, int limit) {
        return Page.fetch(limit, fetchLimit -> {
            Uni<Void> parentExists = parentId.map(p -> chain(actor, p).replaceWithVoid())
                    .orElse(Uni.createFrom().voidItem());
            return parentExists
                    .flatMap(ignored -> categories.children(actor.tenantId(), parentId, afterCode, fetchLimit));
        }, Category::code);
    }

    /**
     * Carrega a cadeia da raiz até a categoria; inexistente no tenant do
     * chamador lança {@link NotFoundException}.
     */
    private Uni<List<Category>> chain(Actor actor, UUID id) {
        return categories.chain(actor.tenantId(), id).map(CategoryView::requireChain);
    }
}
