package de.metis.modules.quantum;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimaler REST-Client für die Quantum-Bridge (Qiskit-Service, Port 11740).
 * <p>
 * Endpunkte: GET /health · GET /api/quantum/backends ·
 * POST /api/quantum/bell {"shots":N,"backend":"aer_simulator|ibm"} ·
 * POST /api/quantum/run {"qasm":"OPENQASM 2.0...","shots":N,"backend":"..."}
 * <p>
 * JSON wird bewusst als Rohstring zurückgegeben — Weiterverarbeitung mit dem
 * in Metis vorhandenen JSON-Stack. Keine externen Dependencies (nur java.net.http).
 * Für Action-Integration siehe {@link QuantumAction}.
 */
public class QuantumBridgeClient {

    private final String baseUrl;

    public QuantumBridgeClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** GET /health — true wenn die Bridge antwortet und "ok" meldet. */
    public boolean isHealthy() {
        try {
            return get("/health", Duration.ofSeconds(5)).contains("\"ok\"");
        } catch (Exception e) {
            return false;
        }
    }

    /** GET /api/quantum/backends — rohes JSON (lokale Simulatoren + IBM-QPUs falls Token). */
    public String listBackends() throws Exception {
        return get("/api/quantum/backends", Duration.ofSeconds(30));
    }

    /** POST /api/quantum/bell — Smoke-Test: Bell-Zustand (H+CNOT, 2 Qubits). */
    public String runBell(int shots, String backend) throws Exception {
        return post("/api/quantum/bell",
                "{\"shots\":" + shots + ",\"backend\":\"" + backend + "\"}");
    }

    /** POST /api/quantum/run — beliebigen OpenQASM-2.0-Schaltkreis ausführen. */
    public String runQasm(String qasm, int shots, String backend) throws Exception {
        return post("/api/quantum/run",
                "{\"qasm\":\"" + escape(qasm) + "\",\"shots\":" + shots
                        + ",\"backend\":\"" + backend + "\"}");
    }

    // --- intern ---

    private String get(String path, Duration timeout) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout).GET().build();
        return send(req);
    }

    private String post(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMinutes(10)) // IBM-Queue kann dauern
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();
        return send(req);
    }

    private String send(HttpRequest req) throws Exception {
        HttpResponse<String> resp = de.metis.modules.util.SharedHttp.client()
                .send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("Quantum-Bridge HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
