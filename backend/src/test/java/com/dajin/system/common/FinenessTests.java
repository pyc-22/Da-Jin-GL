package com.dajin.system.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 甲方口径：成色 0.995 及以上视作足金（按整克不折），低于才按含金量折算。 */
class FinenessTests {
    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void treatsTheFullGoldLineAndAboveAsWholeGrams() {
        eq("1", Fineness.factor(new BigDecimal("0.995")));
        eq("1", Fineness.factor(new BigDecimal("0.9950")));
        eq("1", Fineness.factor(new BigDecimal("0.999")));
        eq("1", Fineness.factor(new BigDecimal("1")));
        eq("1", Fineness.factor(null));
    }

    @Test
    void foldsOnlyBelowTheFullGoldLine() {
        eq("0.9940", Fineness.factor(new BigDecimal("0.9940")));
        eq("0.99", Fineness.factor(new BigDecimal("0.99")));
        eq("0.75", Fineness.factor(new BigDecimal("0.75")));
    }

    @Test
    void computesEffectiveWeight() {
        eq("20.000", Fineness.weight(new BigDecimal("20"), new BigDecimal("0.999")));
        eq("20.000", Fineness.weight(new BigDecimal("20"), new BigDecimal("0.995")));
        eq("18.000", Fineness.weight(new BigDecimal("20"), new BigDecimal("0.9")));
        eq("0.000", Fineness.weight(null, new BigDecimal("0.9")));
        eq("7.500", Fineness.weight(new BigDecimal("10"), new BigDecimal("0.75")));
    }
}
