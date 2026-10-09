package com.dajin.system.processing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProcessingPricingTests {
    private static BigDecimal d(String v) { return new BigDecimal(v); }
    private static ProcessingPricing.Tier tier(String min, String max, String mode, String price, int sort) {
        return new ProcessingPricing.Tier(d(min), max == null ? null : d(max), mode, d(price), sort, true);
    }

    @Test void wholeTierUsesLowerBoundaryAndHalfUp() {
        var tiers = List.of(tier("0", "10", ProcessingPricing.FLAT, "400", 1), tier("10", null, ProcessingPricing.PER_GRAM, "20", 2));
        assertEquals(d("400.00"), ProcessingPricing.calculate(ProcessingPricing.WHOLE_TIER, tiers, d("10"), d("99")).fee());
        assertEquals(d("280.00"), ProcessingPricing.calculate(ProcessingPricing.WHOLE_TIER, tiers, d("14"), d("99")).fee());
    }

    @Test void progressiveAccumulatesFlatOnceAndFallsBackWhenNotCovered() {
        var tiers = List.of(tier("0", "10", ProcessingPricing.FLAT, "400", 1), tier("10", null, ProcessingPricing.PER_GRAM, "20", 2));
        assertEquals(d("480.00"), ProcessingPricing.calculate(ProcessingPricing.PROGRESSIVE, tiers, d("14"), d("99")).fee());
        assertEquals(d("400.00"), ProcessingPricing.calculate(ProcessingPricing.PROGRESSIVE, tiers, d("10"), d("99")).fee());
        assertDoesNotThrow(() -> ProcessingPricing.validate(ProcessingPricing.PROGRESSIVE, tiers));
    }

    @Test void validatesHolesAndOverlap() {
        var overlap = List.of(tier("0", "10", ProcessingPricing.PER_GRAM, "1", 1), tier("9", null, ProcessingPricing.PER_GRAM, "2", 2));
        assertThrows(RuntimeException.class, () -> ProcessingPricing.validate(ProcessingPricing.WHOLE_TIER, overlap));
        var hole = List.of(tier("0", "10", ProcessingPricing.PER_GRAM, "1", 1), tier("11", null, ProcessingPricing.PER_GRAM, "2", 2));
        assertThrows(RuntimeException.class, () -> ProcessingPricing.validate(ProcessingPricing.PROGRESSIVE, hole));
    }
}
