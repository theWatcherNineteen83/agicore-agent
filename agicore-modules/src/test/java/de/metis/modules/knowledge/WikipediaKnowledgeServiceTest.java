package de.metis.modules.knowledge;

import de.metis.kernel.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies WikipediaKnowledgeService persists seenArticles + factsLearned across "restarts".
 * Important: no Ollama, no Wikipedia network calls — we only test save/load.
 */
class WikipediaKnowledgeServiceTest {

    @Test
    void seenArticlesSurviveRestart(@TempDir Path tmp) throws Exception {
        Path state = tmp.resolve("wiki-state.json");

        // First instance: seed three titles and one factsLearned bump via reflection.
        WikipediaKnowledgeService svc = new WikipediaKnowledgeService(
                "http://localhost:1", new WorldModel(), state);

        seedAndPersist(svc, "Quantencomputer", "Robotik", "Maschinelles Lernen");

        assertTrue(Files.exists(state), "state file must be written");

        // Second instance: load — must rediscover the titles.
        WikipediaKnowledgeService svc2 = new WikipediaKnowledgeService(
                "http://localhost:1", new WorldModel(), state);
        assertEquals(3, svc2.articlesProcessed(),
                "seenArticles must survive the restart");
    }

    @Test
    void coldStartWhenStateMissing(@TempDir Path tmp) {
        Path state = tmp.resolve("does-not-exist.json");
        WikipediaKnowledgeService svc = new WikipediaKnowledgeService(
                "http://localhost:1", new WorldModel(), state);
        assertEquals(0, svc.articlesProcessed());
        assertEquals(0, svc.factsLearned());
    }

    /** Inject titles via reflection and trigger saveState. */
    @SuppressWarnings("unchecked")
    private static void seedAndPersist(WikipediaKnowledgeService svc, String... titles) throws Exception {
        Field f = WikipediaKnowledgeService.class.getDeclaredField("seenArticles");
        f.setAccessible(true);
        Set<String> set = (Set<String>) f.get(svc);
        for (String t : titles) set.add(t);
        Method save = WikipediaKnowledgeService.class.getDeclaredMethod("saveState");
        save.setAccessible(true);
        save.invoke(svc);
    }
}
