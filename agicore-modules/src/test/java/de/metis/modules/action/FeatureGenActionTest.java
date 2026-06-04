package de.metis.modules.action;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeatureGenActionTest {

    @Test
    void testName() {
        var fga = new FeatureGenAction("http://localhost:11434/api/generate", "test", ".");
        assertEquals("feature-gen", fga.name());
    }

    @Test
    void testExecuteWithoutGoalReturnsFail() {
        var fga = new FeatureGenAction("http://localhost:11434/api/generate", "test", ".");
        var result = fga.execute();
        assertFalse(result.success());
        assertNotNull(result.error());
        assertTrue(result.error().contains("No feature goal"));
    }

    @Test
    void testExecuteWithEmptyGoalReturnsFail() {
        var fga = new FeatureGenAction("http://localhost:11434/api/generate", "test", ".");
        // Don't set goal -> uses null/blank check
        var result = fga.execute();
        assertFalse(result.success());
        assertNotNull(result.error());
    }

    @Test
    void testDeriveTarget() throws Exception {
        // Use reflection to test deriveTarget
        var fga = new FeatureGenAction("http://localhost:11434/api/generate", "test", ".");
        var method = FeatureGenAction.class.getDeclaredMethod("deriveTarget", String.class);
        method.setAccessible(true);

        assertEquals("modules/planner/OllamaPlanner.java",
                method.invoke(fga, "improve planning efficiency"));
        assertEquals("kernel/goal/GoalManager.java",
                method.invoke(fga, "improve success rate for goals"));
        assertEquals("kernel/meta/MetaCognition.java",
                method.invoke(fga, "boost confidence meta cognition"));
        assertEquals("kernel/self/BugTracker.java",
                method.invoke(fga, "fix error handling bugs"));
        assertEquals("modules/knowledge/WikipediaKnowledgeService.java",
                method.invoke(fga, "improve knowledge beliefs"));
        assertEquals("modules/AgentMain.java",
                method.invoke(fga, "random unknown feature"));
    }
}
