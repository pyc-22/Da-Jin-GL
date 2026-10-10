package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * The single pricing implementation used by processing orders and all clients.
 * Tier lower bounds are exclusive; upper bounds are inclusive, so a weight
 * exactly on a boundary stays in the lower tier.
 */
public final class ProcessingPricing {
    public static final String WHOLE_TIER = "WHOLE_TIER";
    public static final String PROGRESSIVE = "PROGRESSIVE";
    public static final String PER_GRAM = "PER_GRAM";
    public static final String FLAT = "FLAT";

    private ProcessingPricing() { }

    public record Tier(BigDecimal minWeight, BigDecimal maxWeight, String mode,
                       BigDecimal price, int sort, boolean enabled) {
        public Tier {
            minWeight = minWeight == null ? BigDecimal.ZERO : minWeight;
            mode = mode == null ? PER_GRAM : mode.toUpperCase(Locale.ROOT);
            price = price == null ? BigDecimal.ZERO : price;
        }
    }

    public record Result(BigDecimal fee, String description, Tier matchedTier) { }

    public static Result calculate(String mode, List<Tier> input, BigDecimal weight, BigDecimal fallback) {
        BigDecimal grams = weight == null ? BigDecimal.ZERO : weight.max(BigDecimal.ZERO);
        BigDecimal base = fallback == null ? BigDecimal.ZERO : fallback;
        List<Tier> tiers = enabledSorted(input);
        if (grams.signum() <= 0) {
            return new Result(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), "基础工费", null);
        }
        if (tiers.isEmpty()) {
            BigDecimal fee = base.multiply(grams).setScale(2, RoundingMode.HALF_UP);
            return new Result(fee, "基础工费：" + base.setScale(2, RoundingMode.HALF_UP).toPlainString() + "元/克 × " + grams.setScale(3, RoundingMode.HALF_UP).toPlainString() + "g = " + fee.toPlainString(), null);
        }
        String algorithm = normalizeMode(mode);
        if (WHOLE_TIER.equals(algorithm)) {
            Tier tier = matchingWhole(tiers, grams);
            if (tier == null) {
                BigDecimal fee = base.multiply(grams).setScale(2, RoundingMode.HALF_UP);
                return new Result(fee, "基础工费（无匹配阶梯）：" + base.setScale(2, RoundingMode.HALF_UP).toPlainString() + "元/克 × " + grams.setScale(3, RoundingMode.HALF_UP).toPlainString() + "g = " + fee.toPlainString(), null);
            }
            BigDecimal fee = FLAT.equals(tier.mode()) ? tier.price() : grams.multiply(tier.price());
            fee = fee.setScale(2, RoundingMode.HALF_UP);
            return new Result(fee, describe(tier, grams, fee, false), tier);
        }
        BigDecimal fee = BigDecimal.ZERO;
        List<String> parts = new ArrayList<>();
        Tier last = null;
        BigDecimal remaining = grams;
        for (Tier tier : tiers) {
            if (remaining.signum() <= 0) break;
            BigDecimal lower = tier.minWeight();
            BigDecimal upper = tier.maxWeight();
            BigDecimal segment = upper == null ? remaining : remaining.min(upper.subtract(lower).max(BigDecimal.ZERO));
            if (segment.signum() <= 0) continue;
            BigDecimal part = FLAT.equals(tier.mode()) ? tier.price() : segment.multiply(tier.price());
            fee = fee.add(part);
            parts.add(describe(tier, segment, part, true));
            remaining = remaining.subtract(segment);
            last = tier;
        }
        if (remaining.signum() > 0) {
            // A malformed/partial configuration must not silently undercharge.
            BigDecimal fallbackFee = base.multiply(grams).setScale(2, RoundingMode.HALF_UP);
            return new Result(fallbackFee, "基础工费（阶梯未覆盖）", null);
        }
        return new Result(fee.setScale(2, RoundingMode.HALF_UP), String.join(" + ", parts), last);
    }

    public static void validate(String mode, List<Tier> input) {
        String algorithm = normalizeMode(mode);
        if (!WHOLE_TIER.equals(algorithm) && !PROGRESSIVE.equals(algorithm))
            throw new BusinessException(400741, "计费算法只能是全额按档或累进分段");
        List<Tier> tiers = enabledSorted(input);
        BigDecimal previousMax = null;
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            if (tier.minWeight().signum() < 0 || tier.price().signum() < 0)
                throw new BusinessException(400742, "阶梯重量和价格不能为负数");
            if (!PER_GRAM.equals(tier.mode()) && !FLAT.equals(tier.mode()))
                throw new BusinessException(400743, "阶梯计价方式只能是每克价或一口价");
            if (tier.maxWeight() != null && tier.maxWeight().compareTo(tier.minWeight()) <= 0)
                throw new BusinessException(400744, "阶梯上限必须大于下限");
            if (i > 0 && (previousMax == null || tier.minWeight().compareTo(previousMax) < 0))
                throw new BusinessException(400745, "阶梯区间重叠，请检查第" + i + "档与第" + (i + 1) + "档");
            previousMax = tier.maxWeight();
        }
        if (PROGRESSIVE.equals(normalizeMode(mode)) && !tiers.isEmpty()) {
            if (tiers.get(0).minWeight().signum() != 0)
                throw new BusinessException(400746, "累进阶梯必须从0克开始");
            for (int i = 1; i < tiers.size(); i++) {
                BigDecimal expected = tiers.get(i - 1).maxWeight();
                if (expected == null || tiers.get(i).minWeight().compareTo(expected) != 0)
                    throw new BusinessException(400747, "累进阶梯存在空洞或无界档位，请检查第" + (i + 1) + "档");
            }
            if (tiers.get(tiers.size() - 1).maxWeight() != null)
                throw new BusinessException(400748, "累进阶梯最高档必须是不设上限");
        }
    }

    public static String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "" : mode.toUpperCase(Locale.ROOT);
    }

    private static List<Tier> enabledSorted(List<Tier> input) {
        if (input == null) return List.of();
        return input.stream().filter(Objects::nonNull).filter(Tier::enabled)
                .sorted(Comparator.comparing(Tier::minWeight).thenComparing(Tier::maxWeight, Comparator.nullsLast(Comparator.naturalOrder())).thenComparingInt(Tier::sort)).toList();
    }

    private static Tier matchingWhole(List<Tier> tiers, BigDecimal weight) {
        for (int i = 0; i < tiers.size(); i++) {
            Tier tier = tiers.get(i);
            boolean above = i == 0
                    ? weight.compareTo(tier.minWeight()) >= 0
                    : weight.compareTo(tier.minWeight()) > 0;
            boolean below = tier.maxWeight() == null || weight.compareTo(tier.maxWeight()) <= 0;
            if (above && below) return tier;
        }
        return null;
    }

    private static String describe(Tier tier, BigDecimal grams, BigDecimal fee, boolean part) {
        String value = FLAT.equals(tier.mode())
                ? "一口价 " + tier.price().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : tier.price().setScale(2, RoundingMode.HALF_UP).toPlainString() + "元/克 × " + grams.setScale(3, RoundingMode.HALF_UP).toPlainString() + "g";
        return (part ? "阶梯" : "按克") + "：" + value + " = " + fee.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
