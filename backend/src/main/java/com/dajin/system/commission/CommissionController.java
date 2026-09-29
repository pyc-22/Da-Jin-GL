package com.dajin.system.commission;

import com.dajin.system.common.*;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.SyncWebSocketHandler;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/commission")
@RequireRoles({"ADMIN","MANAGER"})
public class CommissionController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    public CommissionController(DbSupport db,SyncWebSocketHandler ws) { this.db=db; this.ws=ws; }

    @GetMapping("/settings")
    @RequirePermission("report:view:all")
    public ApiResponse<?> settings(HttpServletRequest request) {
        long store = db.store(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("defaultCommissionRate", rate(store, "default_commission_rate", "0.02"));
        result.put("processingSalesCommissionRate", rate(store, "processing_sales_commission_rate", "0.01"));
        return ApiResponse.ok(result);
    }

    @PutMapping("/settings")
    @RequirePermission("commission:manage")
    public ApiResponse<?> updateSettings(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long store = db.store(request);
        BigDecimal salesRate = percent(body.get("defaultCommissionRate"), "成品销售提成率");
        BigDecimal processingRate = percent(body.get("processingSalesCommissionRate"), "加工导购提成率");
        upsertRate(store, "default_commission_rate", salesRate, "默认成品销售提成比例（按实收总额）", 3);
        upsertRate(store, "processing_sales_commission_rate", processingRate, "默认加工导购提成比例（按工费）", 6);
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:uid,'COMMISSION','CONFIG_UPDATE',:content,'',now())",
                Map.of("s", store, "uid", userId(request), "content", "成品销售提成率=" + salesRate + ",加工导购提成率=" + processingRate));
        Map<String, Object> event = Map.of("storeId", store, "action", "CONFIG_UPDATE");
        ws.broadcast("COMMISSION_UPDATED", event);
        ws.broadcast("REPORT_UPDATED", event);
        return ApiResponse.ok(Map.of("defaultCommissionRate", salesRate, "processingSalesCommissionRate", processingRate));
    }

    @PostMapping("/calculate")
    @RequirePermission("commission:manage")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ApiResponse<?> calculate(@RequestBody(required=false) Map<String,Object> body,HttpServletRequest request) {
        String month=body==null || body.get("month")==null?java.time.LocalDate.now().toString().substring(0,7):String.valueOf(body.get("month"));
        long store=db.store(request); var ledger=new CommissionLedger(db); int users=ledger.rebuild(store,month);
        var event=Map.of("storeId",store,"action","RECALCULATE","month",month,"users",users);
        ws.broadcast("COMMISSION_UPDATED",event); ws.broadcast("REPORT_UPDATED",event);
        return ApiResponse.ok(Map.of("month",month,"rate",ledger.rate(store),"users",users));
    }

    private BigDecimal rate(long store, String key, String fallback) {
        var rows = db.list("select config_value from sys_config where store_id=:s and config_key=:k and enabled=1", Map.of("s", store, "k", key));
        if (rows.isEmpty() || rows.get(0).get("config_value") == null) return new BigDecimal(fallback);
        try { return new BigDecimal(String.valueOf(rows.get(0).get("config_value"))); }
        catch (NumberFormatException ignored) { return new BigDecimal(fallback); }
    }

    private void upsertRate(long store, String key, BigDecimal value, String description, int sort) {
        db.jdbc().update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) values(:s,'SYSTEM',:k,:v,:d,:sort,1) "
                        + "on duplicate key update config_value=values(config_value),description=values(description),config_sort=values(config_sort),enabled=1,update_time=now()",
                Map.of("s", store, "k", key, "v", value.toPlainString(), "d", description, "sort", sort));
    }

    private BigDecimal percent(Object value, String label) {
        try {
            BigDecimal rate = new BigDecimal(String.valueOf(value));
            if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) throw new NumberFormatException();
            return rate.setScale(6, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            throw new BusinessException(400503, label + "必须是0到100%之间的小数（例如2%填写0.02）");
        }
    }

    private long userId(HttpServletRequest request) {
        io.jsonwebtoken.Claims claims = (io.jsonwebtoken.Claims) request.getAttribute("claims");
        return claims == null ? 0L : Long.parseLong(claims.getSubject());
    }
}
