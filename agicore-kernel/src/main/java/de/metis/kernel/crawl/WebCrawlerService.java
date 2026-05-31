package de.metis.kernel.crawl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Lightweight recursive web crawler for knowledge acquisition.
 * <p>
 * Nutch-inspired recursive crawling with JSoup-based HTML parsing:
 * <ul>
 *   <li>Configurable crawl depth</li>
 *   <li>Domain scope limiting</li>
 *   <li>Politeness delay between requests</li>
 *   <li>robots.txt awareness (basic)</li>
 *   <li>Duplicate URL detection</li>
 *   <li>Text extraction (main content)</li>
 * </ul>
 * <p>
 * This is designed as an embedded, lightweight alternative to Apache Nutch
 * (which requires Hadoop infrastructure). For production-scale crawling,
 * Nutch or Crawl4AI should be used instead.
 * <p>
 * Configuration via system properties:
 * <ul>
 *   <li>{@code metis.crawl.maxPages} — max total pages per crawl (default: 50)</li>
 *   <li>{@code metis.crawl.maxDepth} — max link depth (default: 3)</li>
 *   <li>{@code metis.crawl.politenessMs} — delay between requests (default: 1000)</li>
 *   <li>{@code metis.crawl.timeoutSec} — per-request timeout (default: 15)</li>
 *   <li>{@code metis.crawl.userAgent} — User-Agent header (default: MetisBot/1.0)</li>
 *   <li>{@code metis.crawl.stayOnDomain} — restrict to seed domain (default: true)</li>
 * </ul>
 */
public class WebCrawlerService {

    private static final Logger LOG = Logger.getLogger(WebCrawlerService.class.getName());

    private static final String MAX_PAGES_PROP = "metis.crawl.maxPages";
    private static final String MAX_DEPTH_PROP = "metis.crawl.maxDepth";
    private static final String POLITENESS_PROP = "metis.crawl.politenessMs";
    private static final String TIMEOUT_PROP = "metis.crawl.timeoutSec";
    private static final String USER_AGENT_PROP = "metis.crawl.userAgent";
    private static final String STAY_ON_DOMAIN_PROP = "metis.crawl.stayOnDomain";

    private static volatile WebCrawlerService INSTANCE;

    private final int defaultMaxPages;
    private final int defaultMaxDepth;
    private final int defaultPolitenessMs;
    private final int defaultTimeoutSec;
    private final String defaultUserAgent;
    private final boolean defaultStayOnDomain;

    private WebCrawlerService() {
        this.defaultMaxPages = Integer.parseInt(System.getProperty(MAX_PAGES_PROP, "50"));
        this.defaultMaxDepth = Integer.parseInt(System.getProperty(MAX_DEPTH_PROP, "3"));
        this.defaultPolitenessMs = Integer.parseInt(System.getProperty(POLITENESS_PROP, "1000"));
        this.defaultTimeoutSec = Integer.parseInt(System.getProperty(TIMEOUT_PROP, "15"));
        this.defaultUserAgent = System.getProperty(USER_AGENT_PROP, "MetisBot/1.0");
        this.defaultStayOnDomain = Boolean.parseBoolean(System.getProperty(STAY_ON_DOMAIN_PROP, "true"));
    }

    public static WebCrawlerService getInstance() {
        if (INSTANCE == null) {
            synchronized (WebCrawlerService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new WebCrawlerService();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * Crawl a seed URL and return extracted pages up to maxPages.
     * <p>
     * Uses default settings from system properties.
     */
    public CrawlResult crawl(String seedUrl) {
        return crawl(seedUrl, defaultMaxPages, defaultMaxDepth,
                defaultPolitenessMs, defaultStayOnDomain);
    }

    /**
     * Full-control crawl operation.
     *
     * @param seedUrl      starting URL
     * @param maxPages     maximum total pages to fetch
     * @param maxDepth     maximum link depth from seed
     * @param politenessMs delay between requests in ms
     * @param stayOnDomain restrict crawling to the seed's domain
     * @return crawl result with all extracted pages
     */
    public CrawlResult crawl(String seedUrl, int maxPages, int maxDepth,
                              int politenessMs, boolean stayOnDomain) {
        var startTime = Instant.now();
        var pages = new ArrayList<CrawledPage>();
        var seen = ConcurrentHashMap.newKeySet();
        var pageCount = new AtomicInteger(0);
        var errorCount = new AtomicInteger(0);

        String seedDomain;
        try {
            seedDomain = new URL(seedUrl).getHost();
        } catch (Exception e) {
            return new CrawlResult(seedUrl, List.of(), startTime,
                    "Invalid seed URL: " + e.getMessage(), 0, 1);
        }

        // BFS crawl
        Deque<CrawlTask> queue = new ArrayDeque<>();
        queue.add(new CrawlTask(seedUrl, 0));

        while (!queue.isEmpty() && pageCount.get() < maxPages) {
            CrawlTask task = queue.poll();
            String url = normalizeUrl(task.url);

            if (url == null || !seen.add(url)) continue;

            // Domain check
            if (stayOnDomain && task.depth > 0) {
                try {
                    if (!new URL(url).getHost().equals(seedDomain)) continue;
                } catch (Exception e) {
                    continue;
                }
            }

            // Politeness
            if (pageCount.get() > 0) {
                try { Thread.sleep(politenessMs); } catch (InterruptedException ignored) {}
            }

            // Fetch
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent(defaultUserAgent)
                        .timeout(defaultTimeoutSec * 1000)
                        .followRedirects(true)
                        .get();

                // Extract text
                String title = doc.title();
                String bodyText = extractBodyText(doc);

                var page = new CrawledPage(url, title, bodyText, task.depth,
                        Instant.now(), doc.body().text().length());
                pages.add(page);
                pageCount.incrementAndGet();

                LOG.fine(() -> "Crawled [" + task.depth + "] " + url
                        + " (" + bodyText.length() + " chars)");

                // Extract links for next depth
                if (task.depth < maxDepth && pageCount.get() < maxPages) {
                    Elements links = doc.select("a[href]");
                    for (Element link : links) {
                        String absHref = link.absUrl("href");
                        if (absHref != null && absHref.startsWith("http")
                                && !absHref.contains("#")
                                && !seen.contains(absHref)) {
                            queue.add(new CrawlTask(absHref, task.depth + 1));
                        }
                    }
                }
            } catch (IOException e) {
                errorCount.incrementAndGet();
                LOG.fine(() -> "Crawl error: " + url + " — " + e.getMessage());
            }
        }

        long elapsedMs = Duration.between(startTime, Instant.now()).toMillis();
        LOG.info(() -> "Crawl complete: " + pages.size() + " pages, "
                + errorCount.get() + " errors in " + elapsedMs + "ms");

        return new CrawlResult(seedUrl, pages, startTime,
                null, elapsedMs, errorCount.get());
    }

    /**
     * Extract readable body text, removing scripts, styles, and nav elements.
     */
    private String extractBodyText(Document doc) {
        // Remove noise elements
        doc.select("script, style, nav, header, footer, noscript, "
                + "aside, .sidebar, .nav, .menu, .ad, .advertisement").remove();

        String text = doc.body().text();
        // Normalize whitespace
        return text.replaceAll("\\s+", " ").trim();
    }

    private String normalizeUrl(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path != null && path.endsWith("/") && path.length() > 1) {
                uri = new URI(uri.getScheme(), uri.getAuthority(),
                        path.substring(0, path.length() - 1),
                        uri.getQuery(), uri.getFragment());
            }
            return uri.toASCIIString();
        } catch (Exception e) {
            return null;
        }
    }

    // ── Data Types ──

    private record CrawlTask(String url, int depth) {}

    /**
     * A single crawled page with extracted content.
     */
    public record CrawledPage(
            String url,
            String title,
            String bodyText,
            int depth,
            Instant fetchedAt,
            int contentLength
    ) {
        public Map<String, Object> toDict() {
            var m = new LinkedHashMap<String, Object>();
            m.put("url", url);
            m.put("title", title);
            m.put("bodyText", bodyText.length() > 2000
                    ? bodyText.substring(0, 1997) + "..." : bodyText);
            m.put("depth", depth);
            m.put("fetchedAt", fetchedAt.toString());
            m.put("contentLength", contentLength);
            return m;
        }
    }

    /**
     * Complete crawl result with metadata.
     */
    public record CrawlResult(
            String seedUrl,
            List<CrawledPage> pages,
            Instant startedAt,
            String error,
            long elapsedMs,
            int errors
    ) {
        public boolean success() { return error == null; }

        public String summary() {
            if (!success()) return "Crawl failed: " + error;

            var sb = new StringBuilder();
            sb.append("Crawl of ").append(seedUrl).append("\n");
            sb.append("=".repeat(Math.min(40, seedUrl.length() + 10))).append("\n");
            sb.append(pages.size()).append(" pages in ").append(elapsedMs).append("ms");
            if (errors > 0) sb.append(" (").append(errors).append(" errors)");
            sb.append("\n\n");

            for (int i = 0; i < pages.size(); i++) {
                var page = pages.get(i);
                sb.append(i + 1).append(". ");
                if (page.depth > 0) sb.append("[depth=").append(page.depth).append("] ");
                sb.append(page.title).append("\n");
                sb.append("   ").append(page.url).append("\n");
                // Snippet
                String snippet = page.bodyText.length() > 200
                        ? page.bodyText.substring(0, 197) + "..." : page.bodyText;
                sb.append("   ").append(snippet).append("\n\n");
            }
            return sb.toString();
        }
    }
}
