package com.dajin.system.pay;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;

import java.util.Locale;
import java.util.Map;

public final class PaymentChannelPolicy {
    private PaymentChannelPolicy() { }

    public static String requireActiveCollection(DbSupport db, long storeId, Object rawCode) {
        return requireActive(db, storeId, rawCode, true);
    }

    public static String requireActiveExternal(DbSupport db, long storeId, Object rawCode) {
        return requireActive(db, storeId, rawCode, false);
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
