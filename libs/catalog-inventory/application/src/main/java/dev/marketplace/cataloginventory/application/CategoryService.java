package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.category.AttributeDefinition;
import dev.marketplace.cataloginventory.domain.category.Category;
import io.smallrye.mutiny.Uni;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Árvore de categorias do tenant: leitura para todos os chamadores, escrita
 * para quem administra a taxonomia.
 */
public final class CategoryService {
  /**
   * Tamanho padrão e máximo da página da árvore, maior que o das outras listas: os seletores leem a árvore inteira,
   * e cada página refaz a recursão desde as raízes.
   */
  public static final int TREE_PAGE_SIZE = 1000;
  /** Máximo de categorias lidas de uma vez pelo id. */
  public static final int MAX_IDS = 100;
  private static final Pattern PATH_KEY = Pattern.compile(Category.CODE_PATTERN + "(" + CategoryNode.PATH_SEPARATOR
      + Category.CODE_PATTERN + "){0," + (Category.MAX_DEPTH - 1) + "}");

  /**
   * Categoria pela cadeia da raiz até ela, com o número de subcategorias
   * diretas. Cadeia vazia (categoria inexistente no tenant) lança
   * {@link NotFoundException}.
   */
  public record CategoryView(List<Category> chain, int childCount) {
    public CategoryView {
      chain = List.copyOf(requireChain(chain));
    }

    /**
     * Visão para quem só usa os atributos efetivos e {@code usable}, como os
     * casos de uso de produto: não conta as subcategorias.
     */
    static CategoryView of(List<Category> chain) {
      return new CategoryView(chain, 0);
    }

    public Category category() {
      return chain.getLast();
    }

    /** Ancestrais da raiz até o pai; vazio na raiz. */
    public List<Category> ancestors() {
      return chain.subList(0, chain.size() - 1);
    }

    /** Atributos efetivos: os herdados dos ancestrais, da raiz para baixo, e depois os próprios. */
    public List<AttributeDefinition> effectiveAttributes() {
      return Category.effectiveAttributes(chain);
    }

    /** Indica se a categoria e todos os ancestrais estão ativos. */
    public boolean usable() {
      return chain.stream().allMatch(Category::active);
    }

    /** Nível na árvore, de 1 (raiz) até {@link Category#MAX_DEPTH}. */
    public int level() {
      return chain.size();
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
      if (!usable())
        throw new DomainRuleViolation(Reason.of(DomainReasons.CATEGORY_INACTIVE, category().code()),
            "Categoria " + category().code() + " ou um ancestral está inativo");
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
          .replaceWith(() -> new CategoryView(chain, 0)));
    });
  }

  /**
   * Busca a categoria com a cadeia até a raiz e o número de subcategorias
   * diretas. Qualquer chamador do tenant lê; inexistente lança
   * {@link NotFoundException}.
   */
  public Uni<CategoryView> get(Actor actor, UUID id) {
    return Uni.combine().all().unis(categories.chain(actor.tenantId(), id), childCount(actor, id)).asTuple()
        .map(read -> new CategoryView(read.getItem1(), read.getItem2()));
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
      return Uni.combine().all().unis(chain(actor, id), categories.descendantAttributeCodes(actor.tenantId(), id),
          childCount(actor, id))
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
            return categories.update(self, loaded)
                .replaceWith(() -> new CategoryView(chain, read.getItem3()));
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
          categories.descendantAttributeCodes(actor.tenantId(), id), childCount(actor, id))
          .asTuple().flatMap(read -> {
            Category self = read.getItem1().getLast();
            long loaded = self.version();
            Versions.requireMatch(ifMatch, loaded);
            if (self.parentId().equals(parentId))
              return Uni.createFrom().item(new CategoryView(read.getItem1(), read.getItem5()));
            List<UUID> previousAncestry = self.ancestry();
            self.moveUnder(read.getItem2(), read.getItem3(), read.getItem4());
            List<Category> chain = Stream.concat(read.getItem2().stream(), Stream.of(self)).toList();
            return categories.move(self, previousAncestry, loaded)
                .replaceWith(() -> new CategoryView(chain, read.getItem5()));
          });
    });
  }

  /**
   * Exclui a categoria vazia, sem subcategorias e sem nenhum produto, nem arquivado. Exige {@code MANAGE_TAXONOMY};
   * inexistente: 404; {@code ifMatch} divergente: 412; com subcategorias ou produtos: 409
   * ({@link dev.marketplace.cataloginventory.domain.category.CategoryNotEmptyException}). {@code ifMatch} ausente
   * dispensa a checagem.
   */
  public Uni<Void> delete(Actor actor, UUID id, Optional<Long> ifMatch) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_TAXONOMY);
      return remove(actor, id, ifMatch).replaceWithVoid();
    });
  }

  /**
   * Aplica {@code action} a cada categoria como o endpoint de um item, com a versão obrigatória como o If-Match dele
   * ({@link PreconditionRequiredException} no item sem ela); ativar ou desativar na situação atual não grava nada, e
   * a exclusão roda das subcategorias para as categorias pai, para um ramo vazio sair inteiro. Exige
   * {@code MANAGE_TAXONOMY}; itens fora de 1 a 100 ou com id repetido: {@link InvalidInputException}.
   */
  public Uni<List<Batch.Outcome<Category>>> batch(Actor actor, CategoryBatchAction action, List<Batch.Item> items) {
    return Uni.createFrom().deferred(() -> {
      actor.require(Capability.MANAGE_TAXONOMY);
      Batch.requireItems(items);
      BiFunction<UUID, Optional<Long>, Uni<Category>> operation = (id, version) -> {
        long expected = version.orElseThrow(PreconditionRequiredException::new);
        return switch (action) {
          case ACTIVATE -> setActive(actor, id, expected, true);
          case DEACTIVATE -> setActive(actor, id, expected, false);
          case DELETE -> remove(actor, id, Optional.of(expected));
        };
      };
      if (action != CategoryBatchAction.DELETE)
        return Batch.run(items, operation);
      return categories.depths(actor.tenantId(), items.stream().map(Batch.Item::id).toList())
          .flatMap(depths -> Batch.run(items,
              Comparator.comparing((Batch.Item item) -> depths.getOrDefault(item.id(), 0)).reversed(),
              operation));
    });
  }

  /**
   * Lista por cursor ({@code afterCode}: último código visto) as subcategorias diretas de {@code parentId} (raízes
   * quando vazio), inclusive inativas, com o caminho e o número de subcategorias de cada uma. {@code limit} fora de
   * 1 a 100: {@link InvalidInputException}; pai inexistente: 404.
   */
  public Uni<Page<CategoryNode>> children(Actor actor, Optional<UUID> parentId, Optional<String> afterCode,
      int limit) {
    return Page.fetch(limit, fetchLimit -> {
      Uni<List<Category>> ancestors = parentId.map(p -> chain(actor, p))
          .orElse(Uni.createFrom().item(List.of()));
      return ancestors.flatMap(path -> categories.children(actor.tenantId(), parentId, afterCode, fetchLimit)
          .flatMap(found -> nodes(actor, path, found)));
    }, node -> node.category().code());
  }

  /**
   * Lista por cursor ({@code afterPath}: a chave do último visto) todas as categorias do tenant na ordem da árvore,
   * com o caminho e o número de subcategorias de cada uma; com {@code usableOnly}, só as que aceitam produtos
   * novos. Cursor malformado ou {@code limit} fora de 1 a {@link #TREE_PAGE_SIZE}: {@link InvalidInputException}.
   */
  public Uni<Page<CategoryNode>> tree(Actor actor, Optional<String> afterPath, boolean usableOnly, int limit) {
    return Uni.createFrom().deferred(() -> {
      if (afterPath.filter(key -> !PATH_KEY.matcher(key).matches()).isPresent())
        throw new InvalidInputException("cursor inválido");
      return Page.fetch(limit, TREE_PAGE_SIZE,
          fetchLimit -> categories.tree(actor.tenantId(), afterPath, usableOnly, fetchLimit),
          CategoryNode::pathKey);
    });
  }

  /**
   * Categorias pedidas pelo id, em ordem de código, com o caminho e o número de subcategorias de cada uma; id
   * repetido conta uma vez, e o que não existe no tenant fica de fora. Qualquer chamador do tenant lê; fora de 1 a
   * {@link #MAX_IDS} ids: {@link InvalidInputException}.
   */
  public Uni<List<CategoryNode>> byIds(Actor actor, List<UUID> ids) {
    return Uni.createFrom().deferred(() -> {
      if (ids.isEmpty() || ids.size() > MAX_IDS)
        throw new InvalidInputException("ids deve ter de 1 a " + MAX_IDS + " itens");
      return categories.byIds(actor.tenantId(), Set.copyOf(ids));
    });
  }

  /** Exclui conferindo a versão e as subcategorias; devolve a categoria como estava antes. */
  private Uni<Category> remove(Actor actor, UUID id, Optional<Long> ifMatch) {
    return Uni.combine().all().unis(chain(actor, id), childCount(actor, id)).asTuple().flatMap(read -> {
      Category self = read.getItem1().getLast();
      long loaded = self.version();
      Versions.requireMatch(ifMatch, loaded);
      self.requireRemovable(read.getItem2());
      return categories.delete(self, loaded).replaceWith(self);
    });
  }

  /** Ativa ou desativa sem tocar em nome e atributos; já na situação pedida, devolve a categoria sem gravar. */
  private Uni<Category> setActive(Actor actor, UUID id, long expectedVersion, boolean active) {
    return chain(actor, id).flatMap(chain -> {
      Category self = chain.getLast();
      long loaded = self.version();
      Versions.requireMatch(Optional.of(expectedVersion), loaded);
      if (self.active() == active)
        return Uni.createFrom().item(self);
      if (active)
        self.activate();
      else
        self.deactivate();
      return categories.update(self, loaded).replaceWith(self);
    });
  }

  /** Nós das categorias {@code found}, filhas do fim de {@code path}, com uma consulta para todas as contagens. */
  private Uni<List<CategoryNode>> nodes(Actor actor, List<Category> path, List<Category> found) {
    if (found.isEmpty())
      return Uni.createFrom().item(List.of());
    List<CategoryNode.Ancestor> ancestors = path.stream().map(CategoryNode.Ancestor::of).toList();
    return categories.childCounts(actor.tenantId(), found.stream().map(Category::id).toList())
        .map(counts -> found.stream()
            .map(category -> new CategoryNode(category, ancestors, counts.getOrDefault(category.id(), 0)))
            .toList());
  }

  private Uni<Integer> childCount(Actor actor, UUID id) {
    return categories.childCounts(actor.tenantId(), List.of(id)).map(counts -> counts.getOrDefault(id, 0));
  }

  /**
   * Carrega a cadeia da raiz até a categoria; inexistente no tenant do
   * chamador lança {@link NotFoundException}.
   */
  private Uni<List<Category>> chain(Actor actor, UUID id) {
    return categories.chain(actor.tenantId(), id).map(CategoryView::requireChain);
  }
}
