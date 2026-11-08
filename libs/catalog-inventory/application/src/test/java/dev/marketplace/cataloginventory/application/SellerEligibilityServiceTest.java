package dev.marketplace.cataloginventory.application;

import dev.marketplace.cataloginventory.domain.product.SellerStatus;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.marketplace.cataloginventory.application.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class SellerEligibilityServiceTest {
    final InMemoryStore store = new InMemoryStore();
    final SellerEligibilityService eligibility = new SellerEligibilityService(store);
    final UUID storeId = UUID.randomUUID();

    @Test
    void verdictsAreAppliedOnceAndOlderDecisionsAreIgnored() {
        SellerVerdict approved = verdict(storeId, SellerStatus.APPROVED);
        assertEquals(SellerVerdict.Outcome.APPLIED, item(eligibility.record(approved)));
        assertEquals(SellerVerdict.Outcome.DUPLICATE, item(eligibility.record(approved)));
        SellerVerdict older = new SellerVerdict(UUID.randomUUID(), TENANT, storeId, SellerStatus.REJECTED,
                NOW.minusSeconds(1));
        assertEquals(SellerVerdict.Outcome.STALE, item(eligibility.record(older)));
        assertEquals(SellerStatus.APPROVED, item(eligibility.get(seller(storeId), storeId)));
    }

    @Test
    void storeWithoutVerdictIsPendingAndOnlyItsSellerOrAuditorsRead() {
        assertEquals(SellerStatus.PENDING, item(eligibility.get(seller(storeId), storeId)));
        assertEquals(SellerStatus.PENDING, item(eligibility.get(admin(), storeId)));
        failure(eligibility.get(seller(storeId), UUID.randomUUID()), AccessDeniedException.class);
    }
}
