package com.dajin.system.processing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 下料独立记录，不进入损耗率分母；分母为融后金重（缺失时用来料折重）+ 店供补金。 */
class ProcessingDownMaterialTests {
    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void lossBaseIsMeltedGoldPlusStoreGold() {
        // 融后金重 15 + 店供补金 1 = 16；下料另行记录，不参与此计算。
        eq("16", ProcessingController.lossBase(new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("0.9"), new BigDecimal("1")));
    }

    @Test
    void lossBaseFallsBackToIncomingWeightWhenMeltWeightIsMissing() {
        // 没登记融后金重：来料 20g @0.9 → 18，+ 店供补金 0.5 = 18.5
        eq("18.500", ProcessingController.lossBase(null, new BigDecimal("20"), new BigDecimal("0.9"), new BigDecimal("0.5")));
        eq("20.000", ProcessingController.lossBase(null, new BigDecimal("20"), new BigDecimal("0.999"), null));
    }

    @Test
    void lossBaseTreatsMissingValuesAsZero() {
        eq("0", ProcessingController.lossBase(null, null, null, null));
        eq("0.300", ProcessingController.lossBase(null, null, null, new BigDecimal("0.300")));
    }

    @Test
    void lossPermilleIncludesStoreGoldButNotDownMaterial() {
        BigDecimal withSupplement = ProcessingController.dustPermille(new BigDecimal("0.2"), ProcessingController.lossBase(new BigDecimal("20"), null, null, new BigDecimal("1")));
        BigDecimal withoutSupplement = ProcessingController.dustPermille(new BigDecimal("0.2"), ProcessingController.lossBase(new BigDecimal("20"), null, null, BigDecimal.ZERO));
        eq("9.52", withSupplement);
        eq("10.00", withoutSupplement);
    }

    @Test
    void denominatorUsesTheUpdatedSupplementWeightWhenCorrected() {
        // 已称重订单补金更正后，应使用更正后的补金克重重新计算，而不是旧快照。
        BigDecimal updated = ProcessingController.lossBase(new BigDecimal("20"), null, null, new BigDecimal("2"));
        eq("22", updated);
        eq("9.09", ProcessingController.dustPermille(new BigDecimal("0.2"), updated));
    }

    @Test
    void topUpIsOnlyNeededWhenFinishedWeightExceedsMeltedWeight() {
        eq("1.000", ProcessingController.requiredStoreGoldWeight(new BigDecimal("39"), new BigDecimal("40")));
        eq("0.000", ProcessingController.requiredStoreGoldWeight(new BigDecimal("40"), new BigDecimal("40")));
        eq("0.000", ProcessingController.requiredStoreGoldWeight(new BigDecimal("41"), new BigDecimal("40")));
        // 0g 是有效的融后金重：没有融后金而成品有重量时必须补足差额。
        eq("2.000", ProcessingController.requiredStoreGoldWeight(BigDecimal.ZERO, new BigDecimal("2")));
    }

    @Test
    void topUpReferenceStaysUnknownUntilBothWeightsAreKnown() {
        assertEquals(null, ProcessingController.requiredStoreGoldWeight(null, new BigDecimal("40")));
        assertEquals(null, ProcessingController.requiredStoreGoldWeight(new BigDecimal("40"), null));
    }
}
