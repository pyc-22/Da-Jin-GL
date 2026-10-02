package com.dajin.system.stock;

import java.math.BigDecimal;

/**
 * Inventory quantities use grams for gram-priced goods and pieces for all
 * other goods. Historical order rows may still carry a unit weight with a
 * quantity greater than one, so the gram conversion intentionally multiplies
 * both fields for those rows.
 */
public final class GoodsInventoryUnit {
    private GoodsInventoryUnit() {}

    public static boolean isGramPriced(Object priceType) {
        if (priceType == null) return false;
        try { return Integer.parseInt(String.valueOf(priceType)) == 1; }
        catch (NumberFormatException ignored) { return false; }
    }

    public static BigDecimal quantity(Object priceType, Object weight, Object qty) {
        BigDecimal quantity = decimal(qty, BigDecimal.ZERO);
        if (!isGramPriced(priceType)) return quantity;
        return decimal(weight, BigDecimal.ZERO).multiply(quantity);
    }

    public static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }
}
