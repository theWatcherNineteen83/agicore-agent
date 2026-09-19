package de.metis.modules.util;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Geteilter HttpClient fuer alle Metis-Komponenten.
 *
 * Hintergrund (Incident 2026-09-18 10:05): Viele Stellen erzeugten pro Aufruf
 * ein eigenes {@code HttpClient.newHttpClient()}. Jeder Client bringt eigene
 * Selector-/Worker-Threads mit; bei haeufiger Erzeugung (AutoTuner, HealthProbe,
 * Action-Ausfuehrungen) lief der JVM-Threadpool voll und die HTTP-API bekam
 * keine Worker mehr (stillstand, kein Crash). Ein geteilter Client mit
 * connectTimeout behebt beides.
 */
public final class SharedHttp {

    /** Ein Client fuer alle kurzlebigen HTTP-Aufrufe. */
    public static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private SharedHttp() {
    }

    public static HttpClient client() {
        return SHARED;
    }
}
