package de.metis.kernel.action;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WebSearchAction (DuckDuckGo).
 * <p>
 * These tests verify the action's contract:
 * - name() returns "websearch"
 * - category() returns "read"
 * - execute() returns non-null ActionResult
 * - query with results returns success
 * - empty/invalid query handles gracefully
 */
class WebSearchActionTest {

    @Test
    void nameIsWebsearch() {
        var action = new WebSearchAction("test");
        assertEquals("websearch", action.name());
    }

    @Test
    void categoryIsRead() {
        var action = new WebSearchAction("test");
        assertEquals("read", action.category());
    }

    @Test
    void executeReturnsResult() {
        var action = new WebSearchAction("Java programming language");
        var result = action.execute();
        assertNotNull(result);
        // Should find results for a well-known topic
        assertTrue(result.success(), "Web search should succeed: " + result.error());
        assertTrue(result.body().length() > 20, "Result body should have content");
        assertTrue(result.body().contains("Java"), "Result should mention query topic");
    }

    @Test
    void executeWithMaxResults() {
        var action = new WebSearchAction("Linux kernel", 3);
        var result = action.execute();
        assertNotNull(result);
        assertTrue(result.success());
    }

    @Test
    void handlesEmptyQuery() {
        var action = new WebSearchAction("");
        var result = action.execute();
        assertNotNull(result);
        // DuckDuckGo may return results or fail for empty query — either is valid
    }

    @Test
    void searchResultRecord() {
        var sr = new WebSearchAction.SearchResult("Test Title", "https://example.com", "A snippet", true);
        assertEquals("Test Title", sr.title());
        assertEquals("https://example.com", sr.url());
        assertEquals("A snippet", sr.snippet());
        assertTrue(sr.instantAnswer());
    }
}
