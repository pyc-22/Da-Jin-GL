package com.dajin.system.processing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 下料：店里为保证做工额外加的金料；不计费、不扣库存，但计入损耗率分母。 */
class ProcessingDownMaterialTests {
    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void lossBaseIsMeltedIncomingPlusDownMaterial() {
        // 融后金重 15 + 下料 0.2 = 15.2（店供补金不进分母）
        eq("15.200", ProcessingController.lossBase(new BigDecimal("15"), new BigDecimal("20"), new BigDecimal("0.9"), new BigDecimal("0.2")));
    }

    @Test
    void lossBaseFallsBackToIncomingWeightWhenMeltWeightIsMissing() {
        // 没登记融后金重：来料 20g @0.9 → 18，+ 下料 0.5 = 18.5
        eq("18.500", ProcessingController.lossBase(null, new BigDecimal("20"), new BigDecimal("0.9"), new BigDecimal("0.5")));
        eq("20.000", ProcessingController.lossBase(null, new BigDecimal("20"), new BigDecimal("0.999"), null));
    }

    @Test
    void lossBaseTreatsMissingValuesAsZero() {
        eq("0", ProcessingController.lossBase(null, null, null, null));
        eq("0.300", ProcessingController.lossBase(null, null, null, new BigDecimal("0.300")));
    }

    @Test
    void downMaterialLowersTheLossPermille() {
        // 损耗 0.3g：不含下料 0.3/15 = 20.00‰；含下料 0.2g → 0.3/15.2 = 19.70‰
        BigDecimal withoutDown = ProcessingController.dustPermille(new BigDecimal("0.3"), ProcessingController.lossBase(new BigDecimal("15"), null, null, null));
        BigDecimal withDown = ProcessingController.dustPermille(new BigDecimal("0.3"), ProcessingController.lossBase(new BigDecimal("15"), null, null, new BigDecimal("0.2")));
        eq("20.00", withoutDown);
        eq("19.70", withDown);
    }
}
