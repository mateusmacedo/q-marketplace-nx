package dev.marketplace.cataloginventory.outbox;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationEventsTest {
    @Test
    void theV1ReasonStaysWithinTheContractLimit() {
        assertNull(IntegrationEvents.legacyReason(null));
        String fits = "x".repeat(EventContracts.REASON_MAX_LENGTH);
        assertSame(fits, IntegrationEvents.legacyReason(fits));
        String longer = "Categoria incorreta. " + fits;
        String abbreviated = IntegrationEvents.legacyReason(longer);
        assertEquals(EventContracts.REASON_MAX_LENGTH, abbreviated.length());
        assertTrue(abbreviated.startsWith("Categoria incorreta. x"));
        assertTrue(abbreviated.endsWith("…"));
    }

    @Test
    void theAbbreviationNeverSplitsASurrogatePair() {
        int max = EventContracts.REASON_MAX_LENGTH;
        String emoji = "\uD83D\uDE00";
        String split = "a".repeat(max - 2) + emoji + "b".repeat(10);
        String abbreviated = IntegrationEvents.legacyReason(split);
        assertEquals("a".repeat(max - 2) + "…", abbreviated, "o emoji que não cabe inteiro sai todo");
        assertEquals(abbreviated, new String(abbreviated.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        String whole = "a".repeat(max - 3) + emoji + "b".repeat(10);
        assertEquals("a".repeat(max - 3) + emoji + "…", IntegrationEvents.legacyReason(whole),
                "o emoji que cabe inteiro antes das reticências fica");
    }
}
