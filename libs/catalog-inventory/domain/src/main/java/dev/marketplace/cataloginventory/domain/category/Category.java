package dev.marketplace.cataloginventory.domain.category;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.DomainRuleViolation;
import dev.marketplace.cataloginventory.domain.Reason;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.invalid;
import static dev.marketplace.cataloginventory.domain.DomainRuleViolation.requireText;

/**
 * Nó da árvore de categorias do tenant. Atributos são herdados dos ancestrais;
 * um código de atributo
 * não pode se repetir ao longo do caminho (ancestrais, a própria categoria e
 * descendentes).
 */
public final class Category {
  public static final int MAX_DEPTH = 6;
  public static final int MAX_ATTRIBUTES = 50;
  public static final int NAME_MAX_LENGTH = 120;
  public static final String CODE_PATTERN = "[a-z0-9]+(-[a-z0-9]+)*";
  private static final Pattern CODE = Pattern.compile(CODE_PATTERN);
  private final UUID id;
  private final UUID tenantId;
  private List<UUID> ancestry;
  private final String code;
  private String name;
  private List<AttributeDefinition> attributes;
  private boolean active;
  private long version;

  /**
   * Ponto único de criação: aplica as invariantes do nó. Recusa com
   * {@code category-too-deep} se passar de {@link #MAX_DEPTH} níveis e com
   * {@code invalid-value} para código, nome ou atributos inválidos.
   */
  private Category(UUID id, UUID tenantId, List<UUID> ancestry, String code, String name,
      List<AttributeDefinition> attributes, boolean active, long version) {
    this.id = Objects.requireNonNull(id, "id obrigatório");
    this.tenantId = Objects.requireNonNull(tenantId, "tenant obrigatório");
    this.ancestry = List.copyOf(ancestry);
    if (this.ancestry.size() >= MAX_DEPTH)
      throw tooDeep();
    if (code == null || code.length() > 60 || !CODE.matcher(code).matches())
      throw invalid("Código da categoria deve ser kebab-case minúsculo com até 60 caracteres");
    this.code = code;
    this.name = requireName(name);
    this.attributes = requireAttributes(attributes);
    this.active = active;
    this.version = version;
  }

  /**
   * Cria categoria raiz ativa, na versão 0. Recusa com {@code invalid-value}
   * código, nome ou atributos próprios inválidos.
   */
  public static Category root(UUID id, UUID tenantId, String code, String name,
      List<AttributeDefinition> attributes) {
    return new Category(id, tenantId, List.of(), code, name, attributes, true, 0);
  }

  /** Cria subcategoria; {@code inherited} são os atributos efetivos do pai. */
  public static Category childOf(Category parent, UUID id, String code, String name,
      List<AttributeDefinition> attributes,
      List<AttributeDefinition> inherited) {
    if (!parent.active)
      throw new DomainRuleViolation(Reason.of(DomainReasons.CATEGORY_INACTIVE, parent.code),
          "Categoria pai " + parent.code + " está inativa");
    List<UUID> ancestry = new ArrayList<>(parent.ancestry);
    ancestry.add(parent.id);
    Category child = new Category(id, parent.tenantId, ancestry, code, name, attributes, true, 0);
    requireNoConflicts(ownCodes(child.attributes), codes(inherited));
    return child;
  }

  /**
   * Reconstrói a categoria persistida preservando {@code active} e
   * {@code version}. Reaplica só as validações do próprio nó; conflitos de
   * atributos com ancestrais não são rechecados.
   */
  public static Category restore(UUID id, UUID tenantId, List<UUID> ancestry, String code, String name,
      List<AttributeDefinition> attributes, boolean active, long version) {
    return new Category(id, tenantId, ancestry, code, name, attributes, active, version);
  }

  /**
   * Atualiza nome e atributos próprios. {@code inherited} são os atributos
   * efetivos do pai e
   * {@code descendantCodes} os códigos definidos por subcategorias, que não podem
   * passar a colidir.
   */
  public void revise(String name, List<AttributeDefinition> attributes, List<AttributeDefinition> inherited,
      Collection<String> descendantCodes) {
    List<AttributeDefinition> revised = requireAttributes(attributes);
    Set<String> taken = codes(inherited);
    taken.addAll(descendantCodes);
    requireNoConflicts(ownCodes(revised), taken);
    this.name = requireName(name);
    this.attributes = revised;
  }

  /**
   * Passa a categoria, com a subárvore, para baixo do fim de {@code newParentChain} (raiz até o novo pai;
   * vazia a torna raiz). {@code subtreeHeight} são os níveis abaixo dela e {@code descendantCodes} os códigos
   * das subcategorias. Recusa com {@code category-cycle}, {@code category-too-deep} ou
   * {@code attribute-conflict}; pai inativo é aceito.
   */
  public void moveUnder(List<Category> newParentChain, int subtreeHeight, Collection<String> descendantCodes) {
    if (newParentChain.stream().anyMatch(c -> c.id.equals(id)))
      throw new DomainRuleViolation(DomainReasons.CATEGORY_CYCLE,
          "Categoria não pode ficar abaixo de si mesma nem de uma subcategoria sua");
    if (newParentChain.size() + subtreeHeight >= MAX_DEPTH)
      throw tooDeep();
    Stream<String> subtreeCodes = Stream.concat(ownCodes(attributes), descendantCodes.stream().sorted());
    requireNoConflicts(subtreeCodes.distinct(), codes(effectiveAttributes(newParentChain)));
    this.ancestry = newParentChain.stream().map(Category::id).toList();
  }

  /**
   * Inativa a categoria; enquanto inativa, {@link #childOf} recusa criar
   * subcategorias ({@code category-inactive}).
   */
  public void deactivate() {
    active = false;
  }

  /** Reativa a categoria; sem efeito se já estiver ativa. */
  public void activate() {
    active = true;
  }

  /**
   * Confere que a categoria pode ser excluída pelas subcategorias diretas que tem; com alguma, recusa com
   * {@code category-has-children}. Os produtos são conferidos na gravação ({@code category-has-products}).
   */
  public void requireRemovable(int childCount) {
    if (childCount > 0)
      throw CategoryNotEmptyException.hasChildren(code);
  }

  /** Atributos efetivos de uma cadeia ordenada da raiz até a categoria. */
  public static List<AttributeDefinition> effectiveAttributes(List<Category> chainFromRoot) {
    return chainFromRoot.stream().flatMap(c -> c.attributes.stream()).toList();
  }

  /** Id do pai imediato; vazio quando a categoria é raiz. */
  public Optional<UUID> parentId() {
    return ancestry.isEmpty() ? Optional.empty() : Optional.of(ancestry.getLast());
  }

  public UUID id() {
    return id;
  }

  public UUID tenantId() {
    return tenantId;
  }

  public List<UUID> ancestry() {
    return ancestry;
  }

  public String code() {
    return code;
  }

  public String name() {
    return name;
  }

  public List<AttributeDefinition> attributes() {
    return attributes;
  }

  public boolean active() {
    return active;
  }

  public long version() {
    return version;
  }

  /** Adota a versão gravada pelo repositório após persistir a categoria. */
  public void markPersisted(long newVersion) {
    this.version = newVersion;
  }

  private static String requireName(String name) {
    return requireText(name, NAME_MAX_LENGTH,
        "Nome da categoria é obrigatório e tem até " + NAME_MAX_LENGTH + " caracteres");
  }

  /**
   * Copia a lista de forma imutável ({@code null} vira vazia); recusa com
   * {@code invalid-value} mais de {@link #MAX_ATTRIBUTES} atributos ou
   * códigos repetidos.
   */
  private static List<AttributeDefinition> requireAttributes(List<AttributeDefinition> attributes) {
    List<AttributeDefinition> copy = attributes == null ? List.of() : List.copyOf(attributes);
    if (copy.size() > MAX_ATTRIBUTES)
      throw invalid("Categoria aceita até " + MAX_ATTRIBUTES + " atributos próprios");
    Set<String> seen = new HashSet<>();
    for (AttributeDefinition a : copy) {
      if (!seen.add(a.code()))
        throw invalid("Atributo " + a.code() + " repetido na categoria");
    }
    return copy;
  }

  private static DomainRuleViolation tooDeep() {
    return new DomainRuleViolation(Reason.of(DomainReasons.CATEGORY_TOO_DEEP, MAX_DEPTH),
        "A árvore de categorias aceita até " + MAX_DEPTH + " níveis");
  }

  /**
   * Recusa com {@code attribute-conflict} se algum código de {@code candidates}
   * já estiver tomado; {@code reasons} traz
   * {@code attribute-conflict:<código>} por colisão.
   */
  private static void requireNoConflicts(Stream<String> candidates, Set<String> taken) {
    List<Reason> conflicts = candidates.filter(taken::contains)
        .map(c -> Reason.of(DomainReasons.ATTRIBUTE_CONFLICT, c)).toList();
    if (!conflicts.isEmpty())
      throw new DomainRuleViolation(DomainReasons.ATTRIBUTE_CONFLICT,
          "Atributos já definidos no caminho da categoria", conflicts);
  }

  private static Stream<String> ownCodes(List<AttributeDefinition> definitions) {
    return definitions.stream().map(AttributeDefinition::code);
  }

  private static Set<String> codes(List<AttributeDefinition> definitions) {
    Set<String> codes = new HashSet<>();
    definitions.forEach(d -> codes.add(d.code()));
    return codes;
  }
}
