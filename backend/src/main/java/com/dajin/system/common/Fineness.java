package com.dajin.system.common;

import java.math.BigDecimal;

/**
 * 成色口径（甲方规则）：成色 ≥ 0.995 视作足金，按整克计，不折算；低于 0.995 才按含金量折算。
 * 所有"有效克重 = 克重 × 成色"的地方都必须走这里，避免各处口径不一致。
 */
public final class Fineness {
    /** 足金线：0.995 及以上按整克。 */
    public static final BigDecimal FULL_GOLD_LINE = new BigDecimal("0.995");

    private Fineness() { }

    /** 折算系数：0.995 及以上（或未填）返回 1，否则返回成色本身。 */
    public static BigDecimal factor(BigDecimal fineness) {
        if (fineness == null) return BigDecimal.ONE;
        return fineness.compareTo(FULL_GOLD_LINE) >= 0 ? BigDecimal.ONE : fineness;
    }

    /** 有效克重 = 克重 × 折算系数（足金线以上按整克）。 */
    public static BigDecimal weight(BigDecimal weight, BigDecimal fineness) {
        if (weight == null) return BigDecimal.ZERO;
        return weight.multiply(factor(fineness));
    }
}
