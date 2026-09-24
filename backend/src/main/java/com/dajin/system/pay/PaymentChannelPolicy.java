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
