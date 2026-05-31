package de.metis.kernel.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.trace.SdkTracerProvider;

import java.util.logging.Logger;

/**
 * OpenTelemetry integration for Metis observability.
 * <p>
 * Provides structured tracing and metrics via OTel SDK (no external exporter).
 * For Prometheus/Grafana, attach the OTel Java agent at JVM startup.
 * <p>
 * Configuration: {@code metis.telemetry.enabled} (default: true).
 */
public class TelemetryService {

    private static final Logger LOG = Logger.getLogger(TelemetryService.class.getName());
    private static volatile TelemetryService INSTANCE;

    private final boolean enabled;
    private volatile boolean initialized;

    private Tracer tracer;
    private Meter meter;
    private LongCounter plannerCalls;
    private LongCounter actionExecutions;
    private LongCounter actionFailures;
    private LongCounter goalsCompleted;
    private LongCounter ticksTotal;

    private TelemetryService() {
        this.enabled = Boolean.parseBoolean(
                System.getProperty("metis.telemetry.enabled", "true"));
    }

    public static TelemetryService getInstance() {
        if (INSTANCE == null) {
            synchronized (TelemetryService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new TelemetryService();
                }
            }
        }
        return INSTANCE;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Initialize OpenTelemetry SDK (no external exporter by default).
     */
    public synchronized void init() {
        if (!enabled || initialized) return;

        try {
            var sdkBuilder = SdkTracerProvider.builder();
            var meterBuilder = SdkMeterProvider.builder();

            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(sdkBuilder.build())
                    .setMeterProvider(meterBuilder.build())
                    .buildAndRegisterGlobal();

            tracer = sdk.getTracer("metis-agi", "0.2.0");
            meter = sdk.getMeter("metis-agi");

            plannerCalls = meter.counterBuilder("metis.planner.calls")
                    .setDescription("Number of planner invocations")
                    .build();
            actionExecutions = meter.counterBuilder("metis.action.executions")
                    .setDescription("Number of action executions")
                    .build();
            actionFailures = meter.counterBuilder("metis.action.failures")
                    .setDescription("Number of failed action executions")
                    .build();
            goalsCompleted = meter.counterBuilder("metis.goals.completed")
                    .setDescription("Number of completed goals")
                    .build();
            ticksTotal = meter.counterBuilder("metis.ticks.total")
                    .setDescription("Total agent ticks")
                    .build();

            initialized = true;
            LOG.info("TelemetryService initialized (OTel SDK 1.51)");
        } catch (Exception e) {
            LOG.warning("Telemetry init failed: " + e.getMessage());
        }
    }

    // ── Tracing ──

    public Span startPlannerSpan(String goalDescription) {
        if (!ensureReady()) return Span.getInvalid();
        return tracer.spanBuilder("planner.plan")
                .setAttribute("goal", goalDescription)
                .startSpan();
    }

    public Span startActionSpan(String actionName) {
        if (!ensureReady()) return Span.getInvalid();
        return tracer.spanBuilder("action.execute")
                .setAttribute("action", actionName)
                .startSpan();
    }

    public Span startTickSpan(long tickNumber) {
        if (!ensureReady()) return Span.getInvalid();
        return tracer.spanBuilder("agent.tick")
                .setAttribute("tick", tickNumber)
                .startSpan();
    }

    // ── Metrics Recording ──

    public void recordPlannerCall(String model) {
        if (!ensureReady()) return;
        plannerCalls.add(1, Attributes.builder()
                .put("model", model != null ? model : "unknown").build());
    }

    public void recordActionExecution(String action, boolean success) {
        if (!ensureReady()) return;
        var attrs = Attributes.builder().put("action", action).build();
        actionExecutions.add(1, attrs);
        if (!success) actionFailures.add(1, attrs);
    }

    public void recordGoalCompleted(String category) {
        if (!ensureReady()) return;
        goalsCompleted.add(1, Attributes.builder()
                .put("category", category != null ? category : "unknown").build());
    }

    public void recordTick() {
        if (!ensureReady()) return;
        ticksTotal.add(1);
    }

    // ── Status shortcut for /api/metrics ──

    /**
     * Return current metrics as JSON (no Prometheus format without exporter).
     */
    public String metricsJson() {
        if (!initialized) return "{\"status\":\"not-initialized\"}";
        return "{\"status\":\"ok\",\"otel\":\"1.51\",\"note\":\"attach -javaagent:opentelemetry-javaagent.jar for Prometheus export\"}";
    }

    public synchronized void shutdown() {
        if (!initialized) return;
        try {
            GlobalOpenTelemetry.resetForTest();
            initialized = false;
            LOG.info("TelemetryService shutdown");
        } catch (Exception e) {
            LOG.warning("Telemetry shutdown: " + e.getMessage());
        }
    }

    private boolean ensureReady() {
        return enabled && initialized && tracer != null;
    }
}
