package com.dajin.system.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OrderSettlementTests {
    @Test
    void capsSaleDeductionAndReturnsExcessRecyclePayout() {
        OrderSettlement result = OrderSettlement.calculate(
                new BigDecimal("268.00"), BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("299.70"));

        assertEquals(new BigDecimal("268.00"), result.grossAmount());
        assertEquals(new BigDecimal("268.00"), result.appliedDeduction());
        assertEquals(new BigDecimal("31.70"), result.excessPayout());
        assertEquals(new BigDecimal("0.00"), result.payable());
    }

    @Test
    void appliesDiscountBeforeAddingLaborFee() {
        OrderSettlement result = OrderSettlement.calculate(
                new BigDecimal("200.00"), new BigDecimal("0.90"), new BigDecimal("20.00"), new BigDecimal("250.00"));

        assertEquals(new BigDecimal("200.00"), result.grossAmount());
        assertEquals(new BigDecimal("200.00"), result.appliedDeduction());
        assertEquals(new BigDecimal("50.00"), result.excessPayout());
        assertEquals(new BigDecimal("0.00"), result.payable());
    }
}
