package de.metis.modules.evolution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagTest {

    @Test
    void testRegisterNewFeature() {
        var ff = new FeatureFlag();
        var status = ff.register("test-feature", 0.8);
        assertNotNull(status);
        assertEquals("test-feature", status.id());
        assertFalse(status.enabled());
        assertEquals(0.8, status.preSuccessRate(), 0.001);
    }

    @Test
    void testCheckBeforeStable() {
        var ff = new FeatureFlag();
        ff.register("test-feature", 0.8);
        // Should not auto-enable before monitor period
        var status = ff.checkAndEnable("test-feature", 0.75);
        assertFalse(status.enabled());
    }

    @Test
    void testAutoRegistersOnFirstCheck() {
        var ff = new FeatureFlag();
        var status = ff.checkAndEnable("new-feature", 0.9);
        assertNotNull(status);
        assertEquals("new-feature", status.id());
        assertFalse(status.enabled());
    }

    @Test
    void testAllAndActiveCount() {
        var ff = new FeatureFlag();
        ff.register("a", 0.8);
        ff.register("b", 0.9);
        assertEquals(2, ff.all().size());
        assertEquals(0, ff.activeCount());
    }
}
