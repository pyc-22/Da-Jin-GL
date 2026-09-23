package com.dajin.system.order;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record OrderSettlement(BigDecimal grossAmount, BigDecimal appliedDeduction,
                              BigDecimal excessPayout, BigDecimal payable) {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    public static OrderSettlement calculate(BigDecimal total, BigDecimal discount,
                                            BigDecimal laborFee, BigDecimal oldMaterialValue) {
        BigDecimal safeTotal = nonNegative(total);
        BigDecimal safeDiscount = discount == null ? BigDecimal.ONE : discount;
        BigDecimal safeLabor = nonNegative(laborFee);
        BigDecimal safeOldMaterial = nonNegative(oldMaterialValue);
        BigDecimal gross = safeTotal.multiply(safeDiscount).add(safeLabor).setScale(2, RoundingMode.HALF_UP);
        BigDecimal applied = safeOldMaterial.min(gross).setScale(2, RoundingMode.HALF_UP);
        BigDecimal excess = safeOldMaterial.subtract(applied).setScale(2, RoundingMode.HALF_UP);
        BigDecimal payable = gross.subtract(applied).setScale(2, RoundingMode.HALF_UP);
        return new OrderSettlement(gross, applied, excess, payable);
    }

    private static BigDecimal nonNegative(BigDecimal value) {
        if (value == null || value.signum() <= 0) return ZERO;
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
