package com.dajin.system.stock;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GoodsInventoryUnitTests {
    @Test
    void gramPricedGoodsUseWeightAsInventoryQuantity() {
        assertEquals(new BigDecimal("5.000"), GoodsInventoryUnit.quantity(1, new BigDecimal("5.000"), 1));
        assertEquals(new BigDecimal("10.000"), GoodsInventoryUnit.quantity(1, new BigDecimal("5.000"), 2));
    }

    @Test
    void piecePricedGoodsKeepQuantityAsInventoryQuantity() {
        assertEquals(new BigDecimal("2"), GoodsInventoryUnit.quantity(2, new BigDecimal("5.000"), 2));
    }
}
