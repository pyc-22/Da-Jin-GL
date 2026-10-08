package com.dajin.system.pay;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PaymentChannelPolicy {
    private static final Set<String> GROUP_CHANNELS = Set.of("DOUYIN_GROUP", "MEITUAN_GROUP");
    private PaymentChannelPolicy() { }

    public static boolean isGroupChannel(String code) { return GROUP_CHANNELS.contains(code); }

    public static String requireActiveCollection(DbSupport db, long storeId, Object rawCode) {
        String code = requireActive(db, storeId, rawCode, true);
        if (isGroupChannel(code)) throw new BusinessException(400310, "团购方式仅用于加工尾款");
        return code;
    }

    public static String requireActiveProcessingCollection(DbSupport db, long storeId, Object rawCode) {
        return requireActive(db, storeId, rawCode, true);
    }

    /**
     * 储值渠道：各门店的渠道 code 未必都是 BALANCE（生产在用 CHUZHI），所以按 code + 渠道名一起认。
     * 储值付款要扣会员余额、撤回要退回余额，认错了不是扣错就是漏扣。
     */
    public static boolean isBalanceChannel(DbSupport db, long storeId, Object rawCode) {
        String code = rawCode == null ? "" : String.valueOf(rawCode).trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) return false;
        if (Set.of("BALANCE", "CHUZHI", "STORED_VALUE", "PREPAID").contains(code)) return true;
        if (db == null) return false;
        try {
            java.util.List<String> names = db.jdbc().queryForList(
                    "select channel_name from pay_channel where store_id=:s and channel_code=:code",
                    Map.of("s", storeId, "code", code), String.class);
            for (String name : names) {
                String text = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
                if (text.contains("储值") || text.contains("余额") || text.contains("balance")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    /** 组合支付允许的渠道：启用中的收款渠道，且不能是组合本身，也不能是储值（撤回按方式冲销只认外部渠道）。 */
    public static String requireCombinationChannel(DbSupport db, long storeId, Object rawCode) {
        String code = requireActiveProcessingCollection(db, storeId, rawCode);
        if ("COMBINATION".equals(code)) throw new BusinessException(400726, "组合支付里不能再嵌套组合支付");
        if (isBalanceChannel(db, storeId, code)) throw new BusinessException(400726, "储值不参与组合支付，请单独收取");
        return code;
    }

    public static String requireActiveExternal(DbSupport db, long storeId, Object rawCode) {
        String code = requireActive(db, storeId, rawCode, false);
        if (isGroupChannel(code)) throw new BusinessException(400310, "团购方式仅用于加工尾款");
        return code;
    }

    private static String requireActive(DbSupport db, long storeId, Object rawCode, boolean allowBalance) {
        String code = rawCode == null ? "" : String.valueOf(rawCode).trim().toUpperCase(Locale.ROOT);
        if (code.isBlank() || "COMBINATION".equals(code) || (!allowBalance && "BALANCE".equals(code))) {
            throw new BusinessException(400310, allowBalance ? "支付方式不合法" : "请选择已启用的外部支付方式");
        }
        Integer count = db.jdbc().queryForObject(
                "select count(*) from pay_channel where store_id=:s and channel_code=:code and status=1",
                Map.of("s", storeId, "code", code), Integer.class);
        if (count == null || count == 0) throw new BusinessException(400310, "支付方式未启用");
        return code;
    }
}
