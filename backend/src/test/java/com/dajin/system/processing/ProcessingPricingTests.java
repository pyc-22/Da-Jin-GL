package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingPricingTests {
    private static BigDecimal d(String value) { return new BigDecimal(value); }

    @Test
    void wholeTierUsesTheLowerTierAtAnExactBoundary() {
        List<ProcessingPricing.Tier> tiers = List.of(
                new ProcessingPricing.Tier(d("0"), d("10"), ProcessingPricing.PER_GRAM, d("30"), 0, true),
                new ProcessingPricing.Tier(d("10"), null, ProcessingPricing.PER_GRAM, d("25"), 1, true));

        ProcessingPricing.Result atBoundary = ProcessingPricing.calculate(ProcessingPricing.WHOLE_TIER, tiers, d("10.000"), d("8"));
        ProcessingPricing.Result aboveBoundary = ProcessingPricing.calculate(ProcessingPricing.WHOLE_TIER, tiers, d("12.345"), d("8"));

        assertEquals(d("300.00"), atBoundary.fee());
        assertEquals(d("308.63"), aboveBoundary.fee());
        assertNotNull(atBoundary.matchedTier());
        assertEquals(d("30"), atBoundary.matchedTier().price());
    }

    @Test
    void progressivePricingAccumulatesSegmentsAndChargesFlatTierOnce() {
        List<ProcessingPricing.Tier> tiers = List.of(
                new ProcessingPricing.Tier(d("0"), d("5"), ProcessingPricing.FLAT, d("150"), 0, true),
                new ProcessingPricing.Tier(d("5"), null, ProcessingPricing.PER_GRAM, d("25"), 1, true));

        ProcessingPricing.Result result = ProcessingPricing.calculate(ProcessingPricing.PROGRESSIVE, tiers, d("8"), d("8"));

        assertEquals(d("225.00"), result.fee());
        assertTrue(result.description().contains("一口价"));
        assertTrue(result.description().contains("25.00元/克"));
    }

    @Test
    void disabledOrUnmatchedTiersFallBackToTheBasePrice() {
        List<ProcessingPricing.Tier> disabled = List.of(
                new ProcessingPricing.Tier(d("0"), d("10"), ProcessingPricing.PER_GRAM, d("30"), 0, false));
        ProcessingPricing.Result disabledResult = ProcessingPricing.calculate(ProcessingPricing.WHOLE_TIER, disabled, d("2"), d("8"));
        assertEquals(d("16.00"), disabledResult.fee());

        List<ProcessingPricing.Tier> gapped = List.of(
                new ProcessingPricing.Tier(d("0"), d("5"), ProcessingPricing.PER_GRAM, d("30"), 0, true),
                new ProcessingPricing.Tier(d("10"), null, ProcessingPricing.PER_GRAM, d("25"), 1, true));
        ProcessingPricing.Result gapResult = ProcessingPricing.calculate(ProcessingPricing.WHOLE_TIER, gapped, d("7"), d("8"));
        assertEquals(d("56.00"), gapResult.fee());
    }

    @Test
    void progressiveConfigurationRejectsOverlapGapsAndBoundedFinalTier() {
        assertThrows(BusinessException.class, () -> ProcessingPricing.validate(ProcessingPricing.PROGRESSIVE, List.of(
                new ProcessingPricing.Tier(d("0"), d("10"), ProcessingPricing.PER_GRAM, d("30"), 0, true),
                new ProcessingPricing.Tier(d("5"), null, ProcessingPricing.PER_GRAM, d("25"), 1, true))));
        assertThrows(BusinessException.class, () -> ProcessingPricing.validate(ProcessingPricing.PROGRESSIVE, List.of(
                new ProcessingPricing.Tier(d("0"), d("5"), ProcessingPricing.PER_GRAM, d("30"), 0, true),
                new ProcessingPricing.Tier(d("6"), null, ProcessingPricing.PER_GRAM, d("25"), 1, true))));
        assertThrows(BusinessException.class, () -> ProcessingPricing.validate(ProcessingPricing.PROGRESSIVE, List.of(
                new ProcessingPricing.Tier(d("0"), d("5"), ProcessingPricing.PER_GRAM, d("30"), 0, true))));
    }
}
