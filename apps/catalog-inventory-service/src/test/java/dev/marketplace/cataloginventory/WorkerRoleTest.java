package dev.marketplace.cataloginventory;

import io.quarkus.scheduler.Scheduler;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * O papel worker (ADR 020) roda as tarefas agendadas sem a porta HTTP de negócio, com o papel real sobre o perfil de
 * teste; os intervalos ganham valor aqui porque o perfil de teste desliga a maioria delas, e uma hora deixa cada
 * tarefa com um único disparo, o primeiro, durante o teste.
 */
@QuarkusTest
@TestProfile(WorkerRoleTest.WorkerRole.class)
class WorkerRoleTest {
  public static class WorkerRole implements QuarkusTestProfile {
    @Override
    public String getConfigProfile() {
      return "test,worker";
    }

    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("catalog.reservations.sweep-interval", "1h", "catalog.events.purge-interval", "1h",
          "catalog.imports.interval", "1h", "catalog.metrics.refresh-interval", "1h",
          "catalog.events.relay-interval", "1h");
    }
  }

  @Inject
  Scheduler scheduler;
  @ConfigProperty(name = "quarkus.http.host-enabled")
  boolean httpEnabled;

  @Test
  void workerRoleRunsTheScheduledTasksWithoutTheBusinessPort() {
    for (String task : ApiRoleTest.TASKS)
      assertNotNull(scheduler.getScheduledJob(task), task + " precisa rodar no worker");
    assertFalse(httpEnabled, "o worker não abre a porta HTTP de negócio");
    Awaitility.await().atMost(Duration.ofSeconds(15))
        .until(() -> scheduler.getScheduledJob("outbox-relay").getPreviousFireTime() != null);
  }
}
