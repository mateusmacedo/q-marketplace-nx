package dev.marketplace.cataloginventory;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static dev.marketplace.cataloginventory.Api.as;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class StoreEligibilityResourceTest {
  @Test
  void sellerReadsOwnStoreAndKybVerdictsDriveTheStatus() {
    Api api = new Api();
    as(api.seller).get("/stores/" + api.store + "/eligibility").then().statusCode(200).body("status",
        is("PENDING"));
    as(api.seller).get("/stores/" + UUID.randomUUID() + "/eligibility").then().statusCode(403);
    api.approvedStore();
    as(api.seller).get("/stores/" + api.store + "/eligibility").then().statusCode(200).body("status",
        is("APPROVED"));
    api.kybVerdict("ReviewRequired", "PENDING");
  }
}
