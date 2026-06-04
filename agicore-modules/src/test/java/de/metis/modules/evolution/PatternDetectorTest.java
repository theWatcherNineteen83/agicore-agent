package de.metis.modules.evolution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PatternDetectorTest {

    @Test
    void testNoPatternWithFewSamples() {
        var pd = new PatternDetector();
        var snapshots = java.util.List.<MetricTimeSeries.Snapshot>of();
        assertTrue(pd.analyze(snapshots).isEmpty());
    }

    @Test
    void testDetectsOscillation() {
        var pd = new PatternDetector();
        var list = new java.util.ArrayList<MetricTimeSeries.Snapshot>();
        // Create oscillating data
        for (int i = 0; i < 20; i++) {
            double val = (i % 4 < 2) ? 0.8 : 0.3;
            list.add(new MetricTimeSeries.Snapshot(i, 0.7, val, 0.6, 100, 5, 0.9));
        }
        var patterns = pd.analyze(list);
        assertTrue(patterns.stream().anyMatch(p -> p.id().contains("oscillation")));
    }

    @Test
    void testDetectsCorrelation() {
        var pd = new PatternDetector();
        var list = new java.util.ArrayList<MetricTimeSeries.Snapshot>();
        // Create correlated data: planning and confidence move together
        for (int i = 0; i < 20; i++) {
            double base = 0.5 + (i % 10) * 0.04;
            list.add(new MetricTimeSeries.Snapshot(i, base, base + 0.1, base, 100, 5, 0.9));
        }
        var patterns = pd.analyze(list);
        assertTrue(patterns.stream().anyMatch(p -> p.id().contains("correlation")));
    }

    @Test
    void testDetectsDegradation() {
        var pd = new PatternDetector();
        var list = new java.util.ArrayList<MetricTimeSeries.Snapshot>();
        // Create degrading data
        for (int i = 0; i < 15; i++) {
            double val = 0.9 - i * 0.05;
            list.add(new MetricTimeSeries.Snapshot(i, 0.7, Math.max(0.1, val), 0.6, 100, 5, 0.9));
        }
        var patterns = pd.analyze(list);
        assertTrue(patterns.stream().anyMatch(p -> p.id().contains("degradation")));
    }
}
