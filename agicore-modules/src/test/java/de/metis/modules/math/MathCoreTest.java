package de.metis.modules.math;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.*;

class MathCoreTest {

    private final MathCore mc = new MathCore(new MathContext(30, RoundingMode.HALF_UP));

    @Test
    void basicArithmetic() {
        assertEquals(0, BigDecimal.valueOf(30).compareTo(mc.add(BigDecimal.valueOf(12), BigDecimal.valueOf(18))));
        assertEquals(0, BigDecimal.ZERO.compareTo(mc.subtract(BigDecimal.ONE, BigDecimal.ONE)));
        assertEquals(0, BigDecimal.valueOf(6).compareTo(mc.multiply(BigDecimal.valueOf(2), BigDecimal.valueOf(3))));
        assertEquals(0, BigDecimal.valueOf(3).compareTo(mc.divide(BigDecimal.valueOf(9), BigDecimal.valueOf(3))));
    }

    @Test
    void sqrt() {
        BigDecimal sqrt9 = mc.sqrt(BigDecimal.valueOf(9));
        assertTrue(sqrt9.subtract(BigDecimal.valueOf(3)).abs().compareTo(BigDecimal.valueOf(1e-10)) < 0);

        BigDecimal sqrt2 = mc.sqrt(BigDecimal.valueOf(2));
        assertEquals(0, mc.multiply(sqrt2, sqrt2).subtract(BigDecimal.valueOf(2)).abs()
                .compareTo(BigDecimal.valueOf(1e-10)) < 0 ? 0 : 1, 0);
    }

    @Test
    void sqrtZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(mc.sqrt(BigDecimal.ZERO)));
    }

    @Test
    void sqrtNegative() {
        assertThrows(ArithmeticException.class, () -> mc.sqrt(BigDecimal.valueOf(-1)));
    }

    @Test
    void pow() {
        assertEquals(0, BigDecimal.valueOf(8).compareTo(mc.pow(BigDecimal.valueOf(2), 3)));
        assertEquals(0, BigDecimal.ONE.compareTo(mc.pow(BigDecimal.valueOf(5), 0)));
    }
}
