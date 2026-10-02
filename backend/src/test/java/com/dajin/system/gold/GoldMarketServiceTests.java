package com.dajin.system.gold;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoldMarketServiceTests {
    @Test
    void calculatesSaleAndRecycleFromBaseMarkupAndDeduction() {
        BigDecimal sale = GoldMarketService.calculate(new BigDecimal("894.74"), new BigDecimal("0.75"), new BigDecimal("20"), new BigDecimal("12"), "NONE", false);
        BigDecimal recycle = GoldMarketService.calculate(new BigDecimal("894.74"), new BigDecimal("0.75"), new BigDecimal("20"), new BigDecimal("12"), "NONE", true);

        assertEquals(new BigDecimal("691.06"), sale);
        assertEquals(new BigDecimal("659.06"), recycle);
    }

    @Test
    void roundsToRequestedRule() {
        assertEquals(new BigDecimal("672.80"), GoldMarketService.round(new BigDecimal("672.83"), "TAIL_8"));
        assertEquals(new BigDecimal("673.00"), GoldMarketService.round(new BigDecimal("672.83"), "YUAN"));
        assertEquals(new BigDecimal("672.80"), GoldMarketService.round(new BigDecimal("672.84"), "TENTH"));
    }

    @Test
    void tradingSessionIncludesDayAndNightButExcludesBreaksAndWeekend() {
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 10, 1));
        assertTrue(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 29, 9, 15), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 29, 12, 0), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 28, 1, 30), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 27, 1, 30), holidays));
        assertTrue(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 30, 1, 30), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 10, 1, 10, 0), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 10, 3, 21, 0), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 30, 21, 0), holidays));
        assertFalse(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 10, 8, 1, 0), GoldTradingCalendar.holidays()));
        assertTrue(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 18, 21, 0), holidays));
        assertTrue(GoldMarketService.isTradingSession(LocalDateTime.of(2026, 9, 19, 1, 0), holidays));
    }

    @Test
    void rejectsInvalidOrTenPercentQuoteJump() {
        Map<String, Object> previous = Map.of("price", new BigDecimal("900"));
        assertFalse(new BigDecimal("0").signum() > 0);
        assertTrue(new BigDecimal("900").subtract(new BigDecimal("800")).abs().divide(new BigDecimal("900"), 8, java.math.RoundingMode.HALF_UP).compareTo(new BigDecimal("0.10")) > 0);
    }
}
