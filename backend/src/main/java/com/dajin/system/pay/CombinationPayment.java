package com.dajin.system.pay;

import com.dajin.system.common.BusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 组合支付明细（一笔收款里现金 + 微信 + …）。
 * 合计必须等于本次收款金额；每个方式都要通过渠道校验（不存在/停用/团购/组合本身都会被拒）。
 */
public final class CombinationPayment {
    private CombinationPayment() { }

    public record Part(String method, BigDecimal amount, String voucherNo) { }

    /** 解析并校验明细；resolve 用于逐个方式做渠道校验（返回归一化后的方式代码）。 */
    public static List<Part> parse(Object raw, BigDecimal total, Function<String, String> resolve) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) throw new BusinessException(400726, "组合支付明细不能为空");
        BigDecimal expected = total == null ? BigDecimal.ZERO : total.setScale(2, RoundingMode.HALF_UP);
        List<Part> parts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> row)) throw new BusinessException(400726, "组合支付明细格式不正确");
            String code = row.get("payMethod") == null ? "" : String.valueOf(row.get("payMethod")).trim().toUpperCase(Locale.ROOT);
            if (code.isBlank()) throw new BusinessException(400726, "组合支付明细缺少收款方式");
            if ("COMBINATION".equals(code)) throw new BusinessException(400726, "组合支付里不能再嵌套组合支付");
            if ("BALANCE".equals(code)) throw new BusinessException(400726, "储值不参与组合支付，请单独收取");
            String method = resolve.apply(code);
            if (!seen.add(method)) throw new BusinessException(400726, "组合支付里同一收款方式只能出现一次");
            BigDecimal amount;
            try {
                amount = new BigDecimal(String.valueOf(row.get("amount"))).setScale(2, RoundingMode.UNNECESSARY);
            } catch (Exception e) {
                throw new BusinessException(400726, "组合支付每笔金额最多保留两位小数");
            }
            if (amount.signum() <= 0) throw new BusinessException(400726, "组合支付每笔金额必须大于0");
            String voucher = row.get("voucherNo") == null ? null : String.valueOf(row.get("voucherNo")).trim();
            if (voucher != null && voucher.isEmpty()) voucher = null;
            if (voucher != null && voucher.length() > 100) throw new BusinessException(400725, "团购核销单号不能超过100字");
            parts.add(new Part(method, amount, voucher));
            sum = sum.add(amount);
        }
        if (sum.compareTo(expected) != 0) throw new BusinessException(400726, "组合支付明细合计必须等于本次收款金额");
        return parts;
    }

    /** 团购核销那一笔（最多一笔）；没有团购参与时返回 null。 */
    public static Part groupPart(List<Part> parts) {
        Part found = null;
        for (Part part : parts) {
            if (!PaymentChannelPolicy.isGroupChannel(part.method())) continue;
            if (found != null) throw new BusinessException(400726, "组合支付里团购核销只能有一笔");
            found = part;
        }
        return found;
    }

    /** 明细的可读文本，例如「现金 500.00 + 抖音团购 500.00」，用于收款备注与打印。 */
    public static String describe(List<Part> parts, Function<String, String> label) {
        StringBuilder text = new StringBuilder();
        for (Part part : parts) {
            if (text.length() > 0) text.append(" + ");
            String name = label == null ? null : label.apply(part.method());
            text.append(name == null || name.isBlank() ? part.method() : name).append(' ').append(part.amount().toPlainString());
        }
        return text.toString();
    }
}
