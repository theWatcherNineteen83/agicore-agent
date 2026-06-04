package de.metis.modules.eval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityBoardTest {

    @Test
    void testContainsAllCapabilities() {
        var cb = new CapabilityBoard();
        assertEquals(7, cb.all().size());
    }

    @Test
    void testGovernancePasses() {
        var cb = new CapabilityBoard();
        var governance = cb.all().stream()
                .filter(c -> c.id().equals("governance_holds"))
                .findFirst().orElseThrow();
        assertEquals(CapabilityBoard.Status.PASS, governance.status());
    }

    @Test
    void testFailedCapabilities() {
        var cb = new CapabilityBoard();
        assertTrue(cb.failedCount() >= 4,
                "Should have at least 4 failing: " + cb.failedCount());
    }

    @Test
    void testUpdateStatus() {
        var cb = new CapabilityBoard();
        cb.update("rollback_works", CapabilityBoard.Status.FAIL, "injected regression test failed");
        var cap = cb.all().stream()
                .filter(c -> c.id().equals("rollback_works"))
                .findFirst().orElseThrow();
        assertEquals(CapabilityBoard.Status.FAIL, cap.status());
        assertNotNull(cap.lastVerified());
    }

    @Test
    void testUnknownCapability() {
        var cb = new CapabilityBoard();
        assertDoesNotThrow(() -> cb.update("unknown_cap", CapabilityBoard.Status.PASS, "test"));
    }

    @Test
    void testToJson() {
        var cb = new CapabilityBoard();
        String json = cb.toJson();
        assertTrue(json.contains("\"capabilities\""));
        assertTrue(json.contains("\"passed\":1"));
        assertTrue(json.contains("\"governance_holds\""));
    }

    @Test
    void testPassedFailedTotal() {
        var cb = new CapabilityBoard();
        assertEquals(cb.passedCount() + cb.failedCount()
                + (int) cb.all().stream().filter(c -> c.status() == CapabilityBoard.Status.UNTESTED).count(),
                cb.all().size());
    }
}
