package dev.marketplace.cataloginventory;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;

/** O papel api (ADR 020) atende HTTP sem tarefas agendadas e sem os canais Kafka, com o papel real sobre o perfil de teste. */
@QuarkusTest
@TestProfile(ApiRoleTest.ApiRole.class)
class ApiRoleTest {
  public static class ApiRole implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
      return "test,api";
    }
  }

  static final String[] TASKS = {"outbox-relay", "reservation-sweeper", "product-import", "sku-gauges",
      "messaging-purge"};

  @Inject
  Scheduler scheduler;

  @Test
  void apiRoleServesHttpWithoutScheduledTasksNorKafkaChannels() {
    for (String task : TASKS)
      assertNull(scheduler.getScheduledJob(task), task + " não pode rodar na api");
    given().get("/internal/info").then().statusCode(200);
    given().get("/q/health/ready").then().statusCode(200)
        .body(not(containsString("kyb-seller-verdicts")), not(containsString("catalog-events")));
  }
}
