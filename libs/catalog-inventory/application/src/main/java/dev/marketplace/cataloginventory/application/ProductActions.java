package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.DomainReasons;
import dev.marketplace.cataloginventory.domain.Reason;
import dev.marketplace.cataloginventory.domain.product.Product;
import dev.marketplace.cataloginventory.domain.product.ProductTransition;
import dev.marketplace.cataloginventory.domain.product.PublishingIssue;
import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import io.smallrye.mutiny.Uni;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static dev.marketplace.cataloginventory.application.ProductAction.*;

/**
 * Ações do chamador sobre cada produto, derivadas das regras que os casos de
 * uso aplicam: transições, regras de edição do produto, capacidades e situação
 * KYB/KYC da loja. O cliente só desenha o resultado; o 409 e o 422 continuam
 * sendo a palavra final.
 */
public final class ProductActions {
    /** Ação que vale no estado e para o chamador, mas que {@code reason} impede agora. */
    public record Blocked(ProductAction action, Reason reason) {
    }

    /**
     * Ações liberadas e barradas, na ordem de {@link ProductAction}, e se salvar
     * conteúdo ou SKU devolve o produto à moderação.
     */
    public record Available(List<ProductAction> allowed, List<Blocked> blocked, boolean saveReturnsToModeration) {
        public Available {
            allowed = List.copyOf(allowed);
            blocked = List.copyOf(blocked);
        }

        public boolean allows(ProductAction action) {
            return allowed.contains(action);
        }
    }

    private final SellerEligibilityRepository sellers;

    public ProductActions(SellerEligibilityRepository sellers) {
        this.sellers = sellers;
    }

    /**
     * Situação KYB/KYC das lojas cujas ações dependem dela para o chamador
     * (retomada do seller, aprovação do moderador), numa consulta para todas;
     * nenhuma consulta quando nenhum produto pede.
     */
    public Uni<Map<UUID, SellerStatus>> eligibility(Actor actor, Collection<Product> products) {
        return Uni.createFrom().deferred(() -> {
            Set<UUID> stores = products.stream().filter(p -> dependsOnEligibility(actor, p)).map(Product::storeId)
                    .collect(Collectors.toSet());
            if (stores.isEmpty())
                return Uni.createFrom().item(Map.<UUID, SellerStatus>of());
            return sellers.statusesOf(actor.tenantId(), stores);
        });
    }

    /**
     * {@code eligibility} vem de {@link #eligibility}; loja fora dele conta como
     * {@code PENDING}. Com pendência em {@code issues}, envio e aprovação ficam
     * barrados por {@code product-not-publishable}; sem o cálculo, o 422 decide.
     */
    public static Available of(Actor actor, Product product, Map<UUID, SellerStatus> eligibility,
            Optional<List<PublishingIssue>> issues) {
        List<ProductAction> allowed = new ArrayList<>();
        List<Blocked> blocked = new ArrayList<>();
        SellerStatus seller = eligibility.getOrDefault(product.storeId(), SellerStatus.PENDING);
        Optional<Reason> incomplete = issues.filter(found -> !found.isEmpty())
                .map(found -> Reason.of(DomainReasons.PRODUCT_NOT_PUBLISHABLE));
        if (owns(actor, product)) {
            if (product.acceptsEdit()) {
                allowed.addAll(List.of(EDIT, CHANGE_CATEGORY));
                add(allowed, blocked, ADD_SKU, product.skuAdditionRefusal());
                add(allowed, blocked, REMOVE_SKU, product.skuRemovalRefusal());
            }
            if (product.acceptsOfferAndStockChanges())
                allowed.addAll(List.of(EDIT_OFFER, EDIT_STOCK));
            if (accepts(product, ProductTransition.SUBMIT))
                add(allowed, blocked, SUBMIT, incomplete);
            if (accepts(product, ProductTransition.PAUSE))
                allowed.add(PAUSE);
            if (accepts(product, ProductTransition.RESUME))
                add(allowed, blocked, RESUME, Product.ineligibility(seller));
            if (accepts(product, ProductTransition.ARCHIVE))
                allowed.add(ARCHIVE);
        }
        if (actor.can(Capability.MODERATE_PRODUCTS)) {
            // WHY: mesma ordem de Product.approve, que confere a completude antes da loja.
            if (accepts(product, ProductTransition.APPROVE))
                add(allowed, blocked, APPROVE, incomplete.or(() -> Product.ineligibility(seller)));
            if (accepts(product, ProductTransition.REJECT))
                allowed.add(REJECT);
            if (accepts(product, ProductTransition.BLOCK))
                allowed.add(BLOCK);
            if (accepts(product, ProductTransition.UNBLOCK))
                allowed.add(UNBLOCK);
        }
        return new Available(allowed, blocked, allowed.contains(EDIT) && product.editReturnsToModeration());
    }

    private static boolean dependsOnEligibility(Actor actor, Product product) {
        return owns(actor, product) && accepts(product, ProductTransition.RESUME)
                || actor.can(Capability.MODERATE_PRODUCTS) && accepts(product, ProductTransition.APPROVE);
    }

    private static boolean owns(Actor actor, Product product) {
        return actor.can(Capability.MANAGE_OWN_PRODUCTS) && actor.ownsStore(product.storeId());
    }

    private static boolean accepts(Product product, ProductTransition transition) {
        return transition.acceptsFrom(product.status());
    }

    private static void add(List<ProductAction> allowed, List<Blocked> blocked, ProductAction action,
            Optional<Reason> refusal) {
        refusal.ifPresentOrElse(reason -> blocked.add(new Blocked(action, reason)), () -> allowed.add(action));
    }
}
