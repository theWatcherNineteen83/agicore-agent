package de.metis.kernel.crawl;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for WebCrawlerService — lightweight Nutch-inspired crawler.
 */
class WebCrawlerServiceTest {

    @Test
    void serviceIsSingleton() {
        var s1 = WebCrawlerService.getInstance();
        var s2 = WebCrawlerService.getInstance();
        assertSame(s1, s2);
    }

    @Test
    void crawlInvalidUrlReturnsError() {
        var crawler = WebCrawlerService.getInstance();
        var result = crawler.crawl("not-a-valid-url://foo");
        assertFalse(result.success());
        assertTrue(result.error().contains("Invalid"));
    }

    @Test
    void crawlResultSummary() {
        var crawler = WebCrawlerService.getInstance();
        var result = crawler.crawl("not-a-valid-url://foo");
        String summary = result.summary();
        assertTrue(summary.contains("failed"),
                "Summary should indicate failure: " + summary);
    }

    @Test
    void crawledPageToDict() {
        var page = new WebCrawlerService.CrawledPage(
                "https://example.com/test",
                "Test Page",
                "Hello world page content here",
                1,
                java.time.Instant.now(),
                100
        );
        var dict = page.toDict();
        assertEquals("https://example.com/test", dict.get("url"));
        assertEquals("Test Page", dict.get("title"));
        assertEquals(1, dict.get("depth"));
        assertEquals(100, dict.get("contentLength"));
    }

    @Test
    void longBodyTextTruncatedInDict() {
        var page = new WebCrawlerService.CrawledPage(
                "https://example.com",
                "Long Page",
                "a".repeat(3000),
                0,
                java.time.Instant.now(),
                3000
        );
        var dict = page.toDict();
        String body = (String) dict.get("bodyText");
        assertTrue(body.endsWith("..."),
                "Long body should be truncated: " + body.length());
        assertTrue(body.length() <= 2003,
                "Truncated body should be <= 2003: " + body.length());
    }

    @Test
    void crawlResultSuccessPath() {
        var start = java.time.Instant.now();
        var result = new WebCrawlerService.CrawlResult(
                "https://example.com",
                java.util.List.of(),
                start,
                null,
                123,
                0
        );
        assertTrue(result.success());
        assertNull(result.error());
        assertEquals(123, result.elapsedMs());
        assertEquals(0, result.errors());
    }
}
