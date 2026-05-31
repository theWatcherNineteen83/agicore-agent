package de.metis.kernel.action;

import de.metis.kernel.crawl.WebCrawlerService;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Multi-page web crawling for knowledge acquisition.
 * <p>
 * Crawls a seed URL, follows links within the domain, extracts readable text.
 * Nutch-inspired lightweight crawler — no Hadoop, no external service.
 * <p>
 * Category: read. Approval: AUTO.
 * <p>
 * For single-page extraction, use {@link NativeWebScraperAction} (faster).
 * For JavaScript-heavy pages, use {@link Crawl4AIAction} (headless browser).
 */
public class WebCrawlAction implements Action {

    private static final Logger LOG = Logger.getLogger(WebCrawlAction.class.getName());
    public static final String NAME = "webcrawl";

    private final String seedUrl;
    private final int maxPages;
    private final int maxDepth;
    private final boolean stayOnDomain;

    public WebCrawlAction(String seedUrl) {
        this(seedUrl, 10, 2, true);
    }

    public WebCrawlAction(String seedUrl, int maxPages, int maxDepth, boolean stayOnDomain) {
        this.seedUrl = seedUrl;
        this.maxPages = Math.min(maxPages, 50);
        this.maxDepth = Math.min(maxDepth, 5);
        this.stayOnDomain = stayOnDomain;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        var now = Instant.now();
        var crawler = WebCrawlerService.getInstance();

        LOG.info(() -> "WebCrawl: " + seedUrl + " (max=" + maxPages + " depth=" + maxDepth + ")");

        var result = crawler.crawl(seedUrl, maxPages, maxDepth, 1000, stayOnDomain);

        if (!result.success()) {
            return ActionResult.fail(NAME, result.error(), now);
        }

        LOG.info(() -> "WebCrawl OK: " + result.pages().size() + " pages from " + seedUrl);
        return ActionResult.ok(NAME, result.summary(), now);
    }
}
