package com.dajin.system.processing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 甲方口径：回收屑 = 融后金重 − 成品实重，不足按 0（成品比融后金重还重时只有补金）；
 * 店里的补金不参与回收屑；抵扣 = 回收屑 × 足金回收价。未填融后金重时按来料折重兜底。
 */
class ProcessingRecycleDustTests {
    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void fullGoldJobCreditsTheWholeDifference() {
        // 来料 20g 成色 0.999、无补金、成品 18g → 回收屑 2g × 900 = 1800
        BigDecimal dust = ProcessingController.residualDustWeight(
                null, new BigDecimal("20"), new BigDecimal("0.999"), new BigDecimal("18"));
        eq("2.000", dust);
        eq("1800.00", ProcessingController.residualDustDeduction(dust, new BigDecimal("900")));
    }

    @Test
    void meltedWeightDrivesTheLeftover() {
        // 融后金重 9.9、成品实重 11 → 负数按 0（客户的金不够，只剩补金）
        eq("0.000", ProcessingController.residualDustWeight(
                new BigDecimal("9.9"), new BigDecimal("10"), new BigDecimal("0.999"), new BigDecimal("11")));
        // 融后金重 12、成品实重 11 → 回收屑 1g
        eq("1.000", ProcessingController.residualDustWeight(
                new BigDecimal("12"), new BigDecimal("10"), new BigDecimal("0.999"), new BigDecimal("11")));
    }

    @Test
    void storeSuppliedGoldNeverBecomesCustomerCredit() {
        // 来料 20 + 补金 5（不参与）+ 成品 18 → 客户只抵自己剩下的 2g，而不是 7g
        eq("2.000", ProcessingController.residualDustWeight(
                null, new BigDecimal("20"), new BigDecimal("0.999"), new BigDecimal("18")));
    }

    @Test
    void fallbackIncomingWeightStillHonoursThe995Line() {
        eq("2.000", ProcessingController.residualDustWeight(
                null, new BigDecimal("20"), new BigDecimal("0.995"), new BigDecimal("18")));
        eq("1.880", ProcessingController.residualDustWeight(
                null, new BigDecimal("20"), new BigDecimal("0.994"), new BigDecimal("18")));
    }

    @Test
    void nonPureOldGoldNeverProducesANegativeCredit() {
        // 18K 来料 10g（折 7.5g）做 8g 成品：差额为负，按 0，不产生抵扣
        BigDecimal dust = ProcessingController.residualDustWeight(
                null, new BigDecimal("10"), new BigDecimal("0.75"), new BigDecimal("8"));
        eq("0.000", dust);
        eq("0.00", ProcessingController.residualDustDeduction(dust, new BigDecimal("900")));
    }

    @Test
    void missingWeightsFallBackToZeroInsteadOfFailing() {
        eq("0.000", ProcessingController.residualDustWeight(null, null, null, new BigDecimal("18")));
        eq("0.00", ProcessingController.residualDustDeduction(null, new BigDecimal("900")));
    }

    @Test
    void dustRatioIsRecoveredWeightOverIncomingPlusStoreGold() {
        eq("15.00", ProcessingController.dustPermille(new BigDecimal("0.3"), new BigDecimal("20")));
        eq("0.00", ProcessingController.dustPermille(null, new BigDecimal("20")));
        assertNull(ProcessingController.dustPermille(new BigDecimal("0.3"), BigDecimal.ZERO));
    }
}
