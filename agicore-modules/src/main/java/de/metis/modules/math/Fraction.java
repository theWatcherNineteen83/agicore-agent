package de.metis.modules.math;

import java.math.BigInteger;
import java.util.Objects;

/**
 * Bruchrechnung ohne Rundungsfehler — {@link BigInteger} Zähler/Nenner.
 *
 * <p>Jede Operation liefert einen vollständig gekürzten Bruch (ggT).
 * Division durch Null wird als {@link ArithmeticException} geworfen.
 * Der Bruch ist immutable (Record-ähnlich).
 *
 * <p>Lernquelle: Java in 21 Tagen, Kapitel 3 (Datentypen) + 16 (Schnittstellen).
 */
public final class Fraction implements Comparable<Fraction> {

    private final BigInteger numerator;
    private final BigInteger denominator;

    public static final Fraction ZERO = new Fraction(BigInteger.ZERO, BigInteger.ONE);
    public static final Fraction ONE  = new Fraction(BigInteger.ONE, BigInteger.ONE);

    public Fraction(long numerator, long denominator) {
        this(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    public Fraction(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() == 0) {
            throw new ArithmeticException("Division by zero: denominator is 0");
        }
        // Normalisiere: Nenner positiv, kürze via ggT
        if (denominator.signum() < 0) {
            numerator = numerator.negate();
            denominator = denominator.negate();
        }
        BigInteger gcd = numerator.gcd(denominator);
        this.numerator = numerator.divide(gcd);
        this.denominator = denominator.divide(gcd);
    }

    public BigInteger numerator() { return numerator; }
    public BigInteger denominator() { return denominator; }

    // ── Arithmetik ───────────────────────────────────────────────

    public Fraction add(Fraction other) {
        BigInteger num = numerator.multiply(other.denominator)
                .add(other.numerator.multiply(denominator));
        BigInteger den = denominator.multiply(other.denominator);
        return new Fraction(num, den);
    }

    public Fraction subtract(Fraction other) {
        BigInteger num = numerator.multiply(other.denominator)
                .subtract(other.numerator.multiply(denominator));
        BigInteger den = denominator.multiply(other.denominator);
        return new Fraction(num, den);
    }

    public Fraction multiply(Fraction other) {
        return new Fraction(
                numerator.multiply(other.numerator),
                denominator.multiply(other.denominator));
    }

    public Fraction divide(Fraction other) {
        if (other.numerator.signum() == 0) {
            throw new ArithmeticException("Division by zero fraction");
        }
        return new Fraction(
                numerator.multiply(other.denominator),
                denominator.multiply(other.numerator));
    }

    public Fraction negate() {
        return new Fraction(numerator.negate(), denominator);
    }

    public Fraction reciprocal() {
        if (numerator.signum() == 0) {
            throw new ArithmeticException("Reciprocal of zero");
        }
        return new Fraction(denominator, numerator);
    }

    // ── Konvertierung ────────────────────────────────────────────

    public double toDouble() {
        return numerator.doubleValue() / denominator.doubleValue();
    }

    public String toDecimalString(int scale) {
        return new java.math.BigDecimal(numerator)
                .divide(new java.math.BigDecimal(denominator), scale,
                        java.math.RoundingMode.HALF_UP)
                .toString();
    }

    public boolean isInteger() {
        return denominator.equals(BigInteger.ONE);
    }

    // ── Vergleich ────────────────────────────────────────────────

    @Override
    public int compareTo(Fraction other) {
        return numerator.multiply(other.denominator)
                .compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Fraction f)) return false;
        return numerator.equals(f.numerator) && denominator.equals(f.denominator);
    }

    @Override
    public int hashCode() {
        return Objects.hash(numerator, denominator);
    }

    @Override
    public String toString() {
        return denominator.equals(BigInteger.ONE)
                ? numerator.toString()
                : numerator + "/" + denominator;
    }
}
