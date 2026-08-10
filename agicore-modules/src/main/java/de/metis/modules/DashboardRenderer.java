package de.metis.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Serves the live Metis dashboard HTML.
 * The HTML file is loaded from classpath resource or generated inline.
 */
public final class DashboardRenderer {

    private static final String FALLBACK_HTML = "<html><body><h1>Metis Dashboard</h1><p>Loading…</p></body></html>";

    public static String render() {
        // Try class-relative resource first (works in shaded JARs)
        try (var in = DashboardRenderer.class.getResourceAsStream("/dashboard.html")) {
            if (in != null) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        // Fallback: try classLoader
        try {
            var url = DashboardRenderer.class.getClassLoader().getResource("dashboard.html");
            if (url != null) {
                return Files.readString(Path.of(url.toURI()));
            }
        } catch (Exception ignored) {}
        return FALLBACK_HTML;
    }
}
