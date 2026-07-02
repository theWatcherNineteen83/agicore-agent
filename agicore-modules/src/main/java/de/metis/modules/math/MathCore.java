package de.metis.modules.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Rechenkern ohne Rundungsfehler — beliebige Präzision mit {@link BigDecimal}.
 *
 * <p>Motivation: double/float verlieren bei wiederholten Operationen Genauigkeit
 * (0.1 + 0.2 != 0.3). MathCore nutzt BigDecimal mit konfigurierbarem MathContext,
 * sodass Rundungsfehler explizit kontrolliert werden.
 *
 * <p>Lernquelle: Java in 21 Tagen, Kapitel 3 (Datentypen) + 19 (Datenstreams).
 */
public final class MathCore {

    private final MathContext mc;

    /** Standard: 34 Dezimalstellen (DECIMAL128), HALF_EVEN-Rundung. */
    public MathCore() {
        this(MathContext.DECIMAL128);
    }

    public MathCore(MathContext mc) {
        this.mc = mc;
    }

    public MathContext context() { return mc; }

    // ── Grundrechenarten ────────────────────────────────────────

    public BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b, mc);
    }

    public BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return a.subtract(b, mc);
    }

    public BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b, mc);
    }

    public BigDecimal divide(BigDecimal a, BigDecimal b) {
        return a.divide(b, mc);
    }

    // ── Erweiterte Operationen ───────────────────────────────────

    /** Potenz: a^exponent (nur ganzzahlige Exponenten). */
    public BigDecimal pow(BigDecimal a, int exponent) {
        return a.pow(exponent, mc);
    }

    /**
     * Quadratwurzel nach Newton-Raphson.
     * Startwert = a/2, iteriere bis Konvergenz (max 50 Iterationen).
     */
    public BigDecimal sqrt(BigDecimal a) {
        if (a.signum() < 0) {
            throw new ArithmeticException("sqrt of negative: " + a);
        }
        if (a.signum() == 0) return BigDecimal.ZERO;

        BigDecimal x = a.divide(BigDecimal.valueOf(2), mc);
        BigDecimal two = BigDecimal.valueOf(2);
        for (int i = 0; i < 50; i++) {
            BigDecimal next = x.add(a.divide(x, mc), mc).divide(two, mc);
            if (next.subtract(x).abs().compareTo(BigDecimal.ONE.scaleByPowerOfTen(-mc.getPrecision() + 2)) < 0) {
                return next.round(mc);
            }
            x = next;
        }
        return x.round(mc);
    }

    /** Kehrwert: 1/a. */
    public BigDecimal reciprocal(BigDecimal a) {
        return BigDecimal.ONE.divide(a, mc);
    }

    /** Absolutbetrag. */
    public BigDecimal abs(BigDecimal a) {
        return a.abs();
    }

    /** Negation. */
    public BigDecimal negate(BigDecimal a) {
        return a.negate();
    }

    // ── Trigonometrie (Taylor-Reihen) ────────────────────────────

    /**
     * Sinus via Taylor-Reihe: sin(x) = x - x³/3! + x⁵/5! - ...
     * Eingabe in Radiant.
     */
    public BigDecimal sin(BigDecimal radians) {
        BigDecimal result = BigDecimal.ZERO;
        BigDecimal term = radians;
        int n = 1;
        for (int i = 0; i < 15; i++) {
            result = result.add(term, mc);
            n += 2;
            term = term.multiply(radians, mc).multiply(radians, mc)
                    .negate().divide(BigDecimal.valueOf(n * (n - 1)), mc);
            if (term.abs().compareTo(BigDecimal.ONE.scaleByPowerOfTen(-mc.getPrecision())) < 0) break;
        }
        return result.round(mc);
    }

    /** Cosinus: cos(x) = sin(x + π/2). */
    public BigDecimal cos(BigDecimal radians) {
        BigDecimal halfPi = BigDecimal.valueOf(Math.PI / 2);
        return sin(radians.add(new BigDecimal(halfPi.toString()), mc));
    }

    // ── Vergleiche ───────────────────────────────────────────────

    public boolean isZero(BigDecimal a) {
        return a.compareTo(BigDecimal.ZERO) == 0;
    }

    public boolean isPositive(BigDecimal a) {
        return a.signum() > 0;
    }

    public BigDecimal max(BigDecimal a, BigDecimal b) {
        return a.max(b);
    }

    public BigDecimal min(BigDecimal a, BigDecimal b) {
        return a.min(b);
    }

    // ── Konvertierung ────────────────────────────────────────────

    public static BigDecimal from(String s) {
        return new BigDecimal(s);
    }

    public static BigDecimal from(long n) {
        return BigDecimal.valueOf(n);
    }

    public static BigDecimal from(double d) {
        return BigDecimal.valueOf(d);
    }
}
