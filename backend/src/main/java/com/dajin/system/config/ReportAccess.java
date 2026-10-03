package com.dajin.system.config;

import com.dajin.system.common.BusinessException;
import io.jsonwebtoken.Claims;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;

/** Independent manager reporting gates; endpoint annotations still protect other roles. */
public final class ReportAccess {
    private ReportAccess() { }

    public static boolean enabled(HttpServletRequest request, String permission) {
        Object claims = request.getAttribute("claims");
        if (!(claims instanceof Claims c) || !"MANAGER".equalsIgnoreCase(String.valueOf(c.get("role")))) return true;
        Object raw = request.getAttribute("permissions");
        return raw instanceof Set<?> granted && (granted.contains("*") || granted.contains(permission));
    }

    public static void require(HttpServletRequest request, String permission) {
        if (!enabled(request, permission)) throw new BusinessException(403431, "当前店长未开启该报表权限");
    }

    /** Mixed reports must not expose disabled modules through nested summaries or employee rows. */
    public static Object filter(HttpServletRequest request, Object value) {
        if (value instanceof List<?> rows) return rows.stream().map(row -> filter(request, row)).toList();
        if (!(value instanceof Map<?,?> source)) return value;
        Map<String,Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), filter(request, item)));
        if (!enabled(request, "report:processing")) {
            if (result.containsKey("processing_amount")) {
                if (result.containsKey("turnover")) result.put("turnover", decimal(result.get("turnover")).subtract(decimal(result.get("processing_amount"))));
                if (result.containsKey("sales_amount") && result.containsKey("amount")) result.put("amount", result.get("sales_amount"));
            }
            if (result.containsKey("processingRevenue") && result.containsKey("salesRevenue")) result.put("revenue", result.get("salesRevenue"));
            if (result.containsKey("processing_orders") && result.containsKey("sales_order_count")) result.put("order_count", result.get("sales_order_count"));
            Set.of("processing_amount", "processingRevenue", "processing_base", "processing_orders").forEach(result::remove);
        }
        if (!enabled(request, "report:commission")) {
            Set.of("commission", "commission_amount", "sales_commission", "processing_commission", "commission_rate", "commission_rate_snapshot", "sales_commission_rate_snapshot").forEach(result::remove);
        }
        if (!enabled(request, "report:recycle")) Set.of("recycle_amount", "recycle_weight").forEach(result::remove);
        return result;
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
