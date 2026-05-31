package de.metis.kernel.world;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Phase 10.7 — Sicherheits-Constraints für aktive kausale Hypothesen.
 *
 * <p>Bevor der {@link InterventionRunner} eine Hypothese in {@code TESTING}
 * überführt und einen do-Operator-Eingriff ausführt, fragt er diesen Gate.
 * Drei harte Schranken sind verdrahtet:
 *
 * <ol>
 *   <li><b>do-Operator-Whitelist</b> — wenn nicht leer gesetzt, dürfen nur
 *       Hypothesen mit {@code cause ∈ whitelist} getestet werden. Default
 *       (leere Whitelist) bedeutet: „nichts explizit erlaubt"; bei
 *       {@code strict=true} blockiert die leere Whitelist alles. Im
 *       Foundation-Modus ({@code strict=false}) ist eine leere Whitelist
 *       ein „pass-through", damit bestehende Foundation-Tests weiterlaufen.</li>
 *   <li><b>max 1 Intervention pro Tick</b> — verhindert dass mehrere
 *       Eingriffe pro CoreLoop-Tick zu unklarem Posterior führen. Der
 *       Tick-Counter wird via {@link #onTick()} vom CoreLoop zurückgesetzt.</li>
 *   <li><b>max 10 TESTING-Hypothesen gleichzeitig</b> — schützt vor
 *       Ressourcen-Runaway, wenn der Generator viele Hypothesen erzeugt
 *       und keine schließt.</li>
 * </ol>
 *
 * <p>Gate ist bewusst <em>klein und additiv</em>: ohne Wiring im Runner
 * (siehe {@link InterventionRunner#setSafetyGate(CausalSafetyGate)}) hat
 * er keinen Effekt. Mit Wiring wird er bei jedem
 * {@link InterventionRunner#startTesting(CausalHypothesis)} befragt.
 *
 * <p>Thread-Safety: alle Felder atomic oder synchronized. Mehrere
 * CoreLoop-Threads dürfen den Gate parallel befragen.
 *
 * <p>Observability: {@link #decisions()} liefert kumulierte Counter pro
 * {@link Outcome} für Health-Checks und Watchdog-Alerts.
 */
public final class CausalSafetyGate {

    private static final Logger LOG = Logger.getLogger(CausalSafetyGate.class.getName());

    /** Decision outcome for a {@link #tryStart(CausalHypothesis, HypothesisStore)} call. */
    public enum Outcome {
        ALLOWED,
        REJECTED_WHITELIST,
        REJECTED_TICK_BUDGET,
        REJECTED_TESTING_CAPACITY
    }

    public record Decision(Outcome outcome, String reason) {
        public boolean allowed() { return outcome == Outcome.ALLOWED; }
        public static Decision allow() { return new Decision(Outcome.ALLOWED, ""); }
    }

    // ── Configuration ─────────────────────────────────────────────
    private final Set<String> causeWhitelist = ConcurrentHashMap.newKeySet();
    private volatile boolean strict = false;
    private volatile int maxInterventionsPerTick = 1;
    private volatile int maxConcurrentTesting = 10;

    // ── Runtime state ─────────────────────────────────────────────
    private final AtomicInteger interventionsThisTick = new AtomicInteger();
    private final ConcurrentHashMap<Outcome, AtomicInteger> counters = new ConcurrentHashMap<>();

    // ── Configuration mutators ────────────────────────────────────

    /**
     * Add a cause to the do-operator whitelist. Causes are compared
     * case-sensitive against {@link CausalHypothesis#cause()}.
     */
    public CausalSafetyGate allow(String cause) {
        if (cause != null && !cause.isBlank()) causeWhitelist.add(cause);
        return this;
    }

    /** Replace the current whitelist atomically. */
    public CausalSafetyGate allowAll(Set<String> causes) {
        causeWhitelist.clear();
        if (causes != null) for (String c : causes) allow(c);
        return this;
    }

    /**
     * Strict mode: when {@code true}, an <em>empty</em> whitelist rejects
     * every hypothesis (deny-by-default). When {@code false} (default), an
     * empty whitelist is a pass-through and only non-empty whitelists are
     * enforced. Production deployments should set this to {@code true}.
     */
    public CausalSafetyGate setStrict(boolean strict) { this.strict = strict; return this; }

    public CausalSafetyGate setMaxInterventionsPerTick(int n) {
        if (n >= 0) this.maxInterventionsPerTick = n;
        return this;
    }

    public CausalSafetyGate setMaxConcurrentTesting(int n) {
        if (n >= 0) this.maxConcurrentTesting = n;
        return this;
    }

    // ── Decision API ──────────────────────────────────────────────

    /**
     * Decide whether the given hypothesis may be moved to TESTING right now.
     * On {@code ALLOWED}, the tick budget is decremented atomically — callers
     * must NOT call {@code tryStart} twice for the same intervention.
     */
    public Decision tryStart(CausalHypothesis h, HypothesisStore store) {
        if (h == null) {
            return reject(Outcome.REJECTED_WHITELIST, "null hypothesis");
        }

        // 1. Whitelist check
        if (!whitelistAllows(h.cause())) {
            return reject(Outcome.REJECTED_WHITELIST,
                    "cause not whitelisted: " + h.cause());
        }

        // 2. Capacity check (current TESTING population)
        if (store != null) {
            long testingNow = store.open().stream()
                    .filter(o -> o.status() == CausalHypothesis.Status.TESTING)
                    .count();
            if (testingNow >= maxConcurrentTesting) {
                return reject(Outcome.REJECTED_TESTING_CAPACITY,
                        "TESTING population at limit (" + testingNow + "/" + maxConcurrentTesting + ")");
            }
        }

        // 3. Per-tick budget (atomic claim)
        while (true) {
            int current = interventionsThisTick.get();
            if (current >= maxInterventionsPerTick) {
                return reject(Outcome.REJECTED_TICK_BUDGET,
                        "tick budget exhausted (" + current + "/" + maxInterventionsPerTick + ")");
            }
            if (interventionsThisTick.compareAndSet(current, current + 1)) break;
        }

        counters.computeIfAbsent(Outcome.ALLOWED, k -> new AtomicInteger()).incrementAndGet();
        return Decision.allow();
    }

    private boolean whitelistAllows(String cause) {
        if (causeWhitelist.isEmpty()) return !strict;     // strict ⇒ empty == deny-all
        return causeWhitelist.contains(cause);
    }

    private Decision reject(Outcome o, String reason) {
        counters.computeIfAbsent(o, k -> new AtomicInteger()).incrementAndGet();
        LOG.fine(() -> "CausalSafetyGate: " + o + " — " + reason);
        return new Decision(o, reason);
    }

    // ── Tick lifecycle ────────────────────────────────────────────

    /**
     * Reset the per-tick budget. CoreLoop calls this once per tick.
     */
    public void onTick() {
        interventionsThisTick.set(0);
    }

    // ── Observability ─────────────────────────────────────────────

    public int countOf(Outcome o) {
        AtomicInteger c = counters.get(o);
        return c == null ? 0 : c.get();
    }

    /** Snapshot of all outcome counters; useful for {@code /api/status}. */
    public java.util.Map<Outcome, Integer> decisions() {
        java.util.EnumMap<Outcome, Integer> m = new java.util.EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) m.put(o, countOf(o));
        return m;
    }

    public int interventionsThisTick() { return interventionsThisTick.get(); }
    public int maxInterventionsPerTick() { return maxInterventionsPerTick; }
    public int maxConcurrentTesting() { return maxConcurrentTesting; }
    public boolean strict() { return strict; }
    public Set<String> whitelistSnapshot() { return Set.copyOf(causeWhitelist); }
}
