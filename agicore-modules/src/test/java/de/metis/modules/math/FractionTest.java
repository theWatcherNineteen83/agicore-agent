package de.metis.modules.math;

import org.junit.jupiter.api.Test;
import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class FractionTest {

    @Test
    void creationAndReduction() {
        Fraction f = new Fraction(6, 8);  // 6/8 → 3/4
        assertEquals(BigInteger.valueOf(3), f.numerator());
        assertEquals(BigInteger.valueOf(4), f.denominator());
    }

    @Test
    void negativeDenominator() {
        Fraction f = new Fraction(1, -2);
        assertEquals(BigInteger.valueOf(-1), f.numerator());
        assertEquals(BigInteger.valueOf(2), f.denominator());
    }

    @Test
    void addition() {
        Fraction a = new Fraction(1, 3);
        Fraction b = new Fraction(1, 6);
        Fraction sum = a.add(b);
        assertEquals(new Fraction(1, 2), sum);  // 1/3 + 1/6 = 1/2
    }

    @Test
    void multiplication() {
        Fraction a = new Fraction(2, 3);
        Fraction b = new Fraction(3, 4);
        assertEquals(new Fraction(1, 2), a.multiply(b));  // 6/12 = 1/2
    }

    @Test
    void division() {
        Fraction a = new Fraction(3, 4);
        Fraction b = new Fraction(2, 1);
        assertEquals(new Fraction(3, 8), a.divide(b));  // 3/4 ÷ 2 = 3/8
    }

    @Test
    void reciprocal() {
        assertEquals(new Fraction(3, 2), new Fraction(2, 3).reciprocal());
    }

    @Test
    void divisionByZero() {
        assertThrows(ArithmeticException.class, () -> new Fraction(1, 0));
        assertThrows(ArithmeticException.class, () -> Fraction.ONE.divide(Fraction.ZERO));
    }

    @Test
    void compareTo() {
        assertTrue(new Fraction(2, 3).compareTo(new Fraction(1, 2)) > 0);
        assertEquals(0, new Fraction(2, 4).compareTo(new Fraction(1, 2)));
    }

    @Test
    void toString_() {
        assertEquals("3/4", new Fraction(3, 4).toString());
        assertEquals("5", new Fraction(5, 1).toString());
    }
}
