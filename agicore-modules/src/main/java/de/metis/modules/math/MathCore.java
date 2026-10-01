package de.metis.modules.math;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.logging.Logger;

/**
 * MathCore provides a robust arithmetic core for the Metis AGI system.
 * It ensures precision and avoids floating-point rounding errors by relying
 * on immutable types (BigDecimal) and exact rational arithmetic (Fraction).
 *
 * @author Metis AGI Core
 */
public class MathCore {

    private static final Logger LOG = Logger.getLogger(MathCore.class.getName());

    // Default precision for BigDecimal operations
    private static final int DEFAULT_PRECISION = 34;
    private static final int DEFAULT_FRACTION_DENOMINATOR_LIMIT = 1000000;

    private final MathContext mathContext;

    public MathCore() {
        this(DEFAULT_PRECISION);
    }

    public MathCore(int precision) {
        this.mathContext = new MathContext(precision, RoundingMode.HALF_EVEN);
        LOG.fine("MathCore initialized with precision: " + precision);
    }

    /**
     * Represents an exact rational number as numerator/denominator.
     * Used for operations where exactness is required (e.g., ratios).
     */
    public static class Fraction {
        private final long numerator;
        private final long denominator;

        public Fraction(long numerator, long denominator) {
            if (denominator == 0) {
                throw new ArithmeticException("Denominator cannot be zero");
            }
            if (denominator < 0) {
                numerator = -numerator;
                denominator = -denominator;
            }
            long gcd = gcd(Math.abs(numerator), Math.abs(denominator));
            this.numerator = numerator / gcd;
            this.denominator = denominator / gcd;
        }

        public long getNumerator() {
            return numerator;
        }

        public long getDenominator() {
            return denominator;
        }

        public BigDecimal toBigDecimal() {
            return new BigDecimal(numerator).divide(new BigDecimal(denominator), mathContextSafe());
        }

        private MathContext mathContextSafe() {
            return new MathContext(34, RoundingMode.HALF_EVEN);
        }

        @Override
        public String toString() {
            return denominator == 1 ? String.valueOf(numerator) : numerator + "/" + denominator;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Fraction other = (Fraction) obj;
            return this.numerator == other.numerator && this.denominator == other.denominator;
        }

        @Override
        public int hashCode() {
            return 31 * (int) (numerator ^ (numerator >>> 32)) + (int) (denominator ^ (denominator >>> 32));
        }

        private static long gcd(long a, long b) {
            while (b != 0) {
                long t = b;
                b = a % b;
                a = t;
            }
            return a;
        }
    }

    /**
     * Performs division with specified precision, returning a BigDecimal.
     *
     * @param dividend   The number to divide
     * @param divisor    The number to divide by
     * @return The result as a BigDecimal
     */
    public BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        return dividend.divide(divisor, mathContext);
    }

    /**
     * Calculates the square root of a BigDecimal using Newton's method.
     * This avoids the loss of precision inherent in Math.sqrt(double).
     *
     * @param value The non-negative value to find the square root of
     * @return The square root as a BigDecimal
     */
    public BigDecimal sqrt(BigDecimal value) {
        if (value.signum() < 0) {
            throw new ArithmeticException("Cannot calculate square root of a negative number");
        }
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }

        // Initial guess: value / 2 + 1 (simple heuristic)
        BigDecimal two = new BigDecimal(2);
        BigDecimal guess = value.divide(two, mathContext).add(BigDecimal.ONE);
        BigDecimal newGuess;
        int maxIterations = 100; // Safety limit

        for (int i = 0; i < maxIterations; i++) {
            newGuess = value.divide(guess, mathContext).add(guess).divide(two, mathContext);
            if (newGuess.subtract(guess).abs().compareTo(new BigDecimal("1e-" + mathContext.getPrecision())) <= 0) {
                break;
            }
            guess = newGuess;
        }

        return guess;
    }

    /**
     * Creates a Fraction from two integers, reducing it to lowest terms.
     *
     * @param num Numerator
     * @param den Denominator
     * @return A simplified Fraction
     */
    public Fraction createFraction(long num, long den) {
        return new Fraction(num, den);
    }

    /**
     * Converts a string representation of a number to a BigDecimal.
     *
     * @param value The string value
     * @return The corresponding BigDecimal
     */
    public BigDecimal parse(String value) {
        return new BigDecimal(value, mathContext);
    }

    /**
     * Adds two BigDecimal values.
     */
    public BigDecimal add(BigDecimal a, BigDecimal b) {
        return a.add(b, mathContext);
    }

    /**
     * Multiplies two BigDecimal values.
     */
    public BigDecimal multiply(BigDecimal a, BigDecimal b) {
        return a.multiply(b, mathContext);
    }

    /**
     * Subtracts b from a.
     */
    public BigDecimal subtract(BigDecimal a, BigDecimal b) {
        return a.subtract(b, mathContext);
    }
}