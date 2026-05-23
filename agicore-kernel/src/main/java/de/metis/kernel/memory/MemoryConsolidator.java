package de.metis.kernel.memory;

import java.util.logging.Logger;

/**
 * Bridge between {@link ShortTermMemory} and {@link LongTermMemory}.
 * <p>
 * The consolidator periodically moves high-salience experiences from
 * STM to LTM and triggers LTM maintenance (decay, eviction, boosting).
 * <p>
 * Consolidation is triggered when:
 * <ol>
 *   <li>The STM exceeds a configured fill threshold (e.g. 80%)</li>
 *   <li>The agent's prediction error spikes (extrinsic trigger)</li>
 * </ol>
 * <p>
 * This is the memory "sleep phase" — in Phase 3 it could be extended
 * to run asynchronously during idle periods.
 */
public class MemoryConsolidator {

    private static final Logger LOG = Logger.getLogger(MemoryConsolidator.class.getName());

    /** Consolidate when STM exceeds this fraction of capacity. */
    private static final double STM_FILL_THRESHOLD = 0.8;
    /** Only promote experiences in the top fraction by salience. */
    private static final double PROMOTION_QUANTILE = 0.75;
    /** Hard cap: evict lowest-salience entries when exceeded. */
    private static final int MAX_LTM_ENTRIES = 1000;

    private final ShortTermMemory stm;
    private final LongTermMemory ltm;

    public MemoryConsolidator(ShortTermMemory stm, LongTermMemory ltm) {
        this.stm = stm;
        this.ltm = ltm;
    }

    /**
     * Check whether consolidation is needed and, if so, execute it.
     * <p>
     * Two triggers:
     * <ul>
     *   <li>STM fill ratio &gt; threshold → move high-salience entries to LTM</li>
     *   <li>Always: run LTM decay/eviction</li>
     * </ul>
     *
     * @param force consolidate regardless of STM fill level
     * @return number of experiences moved to LTM
     */
    public int maybeConsolidate(boolean force) {
        double fillRatio = (double) stm.size() / stm.capacity();
        boolean shouldConsolidate = force || fillRatio >= STM_FILL_THRESHOLD;

        if (shouldConsolidate) {
            return consolidate();
        }
        // Light maintenance: still run LTM decay
        ltm.consolidate();
        return 0;
    }

    private int consolidate() {
        var allStm = stm.all();
        if (allStm.isEmpty()) return 0;

        // Promote only top-quantile experiences (was median)
        double threshold = allStm.stream()
                .mapToDouble(Experience::salience)
                .sorted()
                .skip((long) (allStm.size() * PROMOTION_QUANTILE))
                .findFirst()
                .orElse(1.0);

        var toPromote = allStm.stream()
                .filter(exp -> exp.salience() >= threshold)
                .toList();

        ltm.storeAll(toPromote);

        // Cap LTM size: evict lowest-salience if over limit
        if (ltm.size() > MAX_LTM_ENTRIES) {
            ltm.trimToSize(MAX_LTM_ENTRIES);
        }
        ltm.consolidate();

        LOG.info(() -> "Consolidated: promoted " + toPromote.size()
                + " of " + allStm.size() + " STM → LTM (LTM size=" + ltm.size()
                + ", threshold=" + String.format("%.2f", threshold) + ")");
        return toPromote.size();
    }

    public ShortTermMemory stm() { return stm; }
    public LongTermMemory ltm() { return ltm; }
}
