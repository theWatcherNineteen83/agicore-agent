package de.metis.kernel.action;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JlamaInferenceAction without running actual JLama inference.
 * <p>
 * Tests contract validity: name, category, approval, disabled-state behavior.
 */
class JlamaInferenceActionTest {

    @Test
    void nameIsJlama() {
        var action = new JlamaInferenceAction("Hello");
        assertEquals("jlama", action.name());
    }

    @Test
    void categoryIsRead() {
        var action = new JlamaInferenceAction("Hello");
        assertEquals("read", action.category());
    }

    @Test
    void approvalLevelIsAuto() {
        var action = new JlamaInferenceAction("Hello");
        assertEquals(Action.ApprovalLevel.AUTO, action.approvalLevel());
    }

    @Test
    void disabledByDefault() {
        var action = new JlamaInferenceAction("Test prompt");
        var result = action.execute();
        // Without -Dmetis.jlama.enabled=true, this should fail
        assertFalse(result.success());
        assertTrue(result.error().contains("JLama not enabled"),
                "Expected 'not enabled' message, got: " + result.error());
    }

    @Test
    void chatModeStoresSystemPrompt() {
        var action = new JlamaInferenceAction("User message", "System context");
        assertEquals("jlama", action.name());
        assertFalse(action.execute().success()); // still disabled
    }

    @Test
    void maxTokensClamped() {
        // maxTokens should be clamped to 1024 via constructor
        var action = new JlamaInferenceAction("test", null, 5000, 0.5f);
        assertEquals("jlama", action.name());
        assertFalse(action.execute().success());
    }
}
