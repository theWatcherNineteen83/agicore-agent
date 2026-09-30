package de.metis.modules.quantum;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Quantum-Computing-Zugriff über die lokale Quantum-Bridge (Qiskit-REST, Port 11740).
 * <p>
 * Führt einen Bell-Zustand-Smoke-Lauf (H + CNOT, 2 Qubits) gegen die Bridge aus und
 * liefert die Mess-Counts als ActionResult-Body (JSON). Backends:
 * "aer_simulator" (lokaler Simulator, Default) oder "ibm" (echte IBM-QPU,
 * benötigt Token auf der Bridge; ohne Token meldet die Bridge HTTP 503 → ActionResult.fail).
 * <p>
 * Konfiguration via System-Properties:
 * <ul>
 *   <li>{@code metis.quantum.url} (Default http://127.0.0.1:11740)</li>
 *   <li>{@code metis.quantum.shots} (Default 1024)</li>
 *   <li>{@code metis.quantum.backend} (Default aer_simulator)</li>
 * </ul>
 * Nutzt {@code SharedHttp.client()} — keine eigenen HttpClient-Instanzen
 * (Lehre aus der Pool-Erschöpfung, Fix 3b4da64).
 */
public class QuantumAction implements Action {

    public static final String NAME = "quantum-bridge";
    private static final Logger LOG = Logger.getLogger(QuantumAction.class.getName());

    private final String baseUrl;
    private final int shots;
    private final String backend;

    public QuantumAction() {
        this(System.getProperty("metis.quantum.url", "http://127.0.0.1:11740"),
                Integer.getInteger("metis.quantum.shots", 1024),
                System.getProperty("metis.quantum.backend", "aer_simulator"));
    }

    public QuantumAction(String baseUrl) {
        this(baseUrl, 1024, "aer_simulator");
    }

    public QuantumAction(String baseUrl, int shots, String backend) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.shots = shots;
        this.backend = backend;
    }

    @Override public String name() { return NAME; }
    @Override public String category() { return "read"; }
    @Override public ApprovalLevel approvalLevel() { return ApprovalLevel.AUTO; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            var client = de.metis.modules.util.SharedHttp.client();

            // Pre-flight: Bridge gesund? (schneller Fail, wie bei AudioBridgeAction)
            HttpRequest healthReq = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> health = client.send(healthReq, HttpResponse.BodyHandlers.ofString());
            if (health.statusCode() != 200 || !health.body().contains("\"ok\"")) {
                return ActionResult.fail(NAME,
                        "Quantum bridge not healthy (HTTP " + health.statusCode() + "): " + health.body(), start);
            }

            // Bell-Lauf (H+CNOT) — deterministischer Smoke-Test für Verschränkung
            String json = "{\"shots\":" + shots + ",\"backend\":\"" + backend + "\"}";
            HttpRequest bellReq = HttpRequest.newBuilder(URI.create(baseUrl + "/api/quantum/bell"))
                    .timeout(Duration.ofMinutes(10)) // IBM-Queue kann dauern
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            HttpResponse<String> resp = client.send(bellReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                return ActionResult.fail(NAME,
                        "Quantum run failed (HTTP " + resp.statusCode() + "): " + resp.body(), start);
            }
            LOG.info("Quantum bridge result: " + resp.body());
            return ActionResult.ok(NAME, resp.body(), start);
        } catch (Exception e) {
            return ActionResult.fail(NAME, "Quantum bridge error: " + e.getMessage(), start);
        }
    }
}
