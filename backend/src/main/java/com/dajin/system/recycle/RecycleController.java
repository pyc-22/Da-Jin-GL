package com.dajin.system.recycle;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.pay.PaymentChannelPolicy;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import java.math.*;
import java.util.*;

@RestController
@RequestMapping("/api/recycle")
public class RecycleController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final ShiftService shifts;
    private final OldMaterialLedgerService oldMaterials;
    public RecycleController(DbSupport db, SyncWebSocketHandler ws, ShiftService shifts, OldMaterialLedgerService oldMaterials) { this.db = db; this.ws = ws; this.shifts = shifts; this.oldMaterials = oldMaterials; }
    @PostMapping("/create") @Transactional
    public ApiResponse<?> create(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        String clientRequestId = q.get("clientRequestId") == null ? "" : String.valueOf(q.get("clientRequestId")).trim();
        if (!clientRequestId.isBlank()) {
            Integer previous = db.jdbc().queryForObject("select count(*) from operation_log where store_id=:s and module='RECYCLE' and action='CREATE' and client_request_id=:client", Map.of("s", db.store(r), "client", clientRequestId), Integer.class);
            if (previous != null && previous > 0) return ApiResponse.ok(Map.of("idempotentReplay", true));
        }
        BigDecimal weight = decimal(q, "weight"), purity = decimal(q, "purity");
        BigDecimal price = q.get("recyclePrice") == null ? currentPrice(r) : decimal(q, "recyclePrice");
        if (weight.signum() <= 0 || purity.signum() <= 0 || purity.compareTo(BigDecimal.ONE) > 0) throw new BusinessException(400401, "克重和成色参数不合法");
        BigDecimal lossRate = q.get("deductLossRate") == null ? BigDecimal.ZERO : decimal(q, "deductLossRate");
        if (lossRate.compareTo(BigDecimal.ONE) > 0) lossRate = lossRate.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        if (lossRate.signum() < 0 || lossRate.compareTo(BigDecimal.ONE) >= 0) throw new BusinessException(400402, "扣损比例应在0到1之间");
        BigDecimal base = weight.multiply(purity).multiply(price), lossAmount = base.multiply(lossRate).setScale(2, RoundingMode.HALF_UP), amount = base.subtract(lossAmount).setScale(2, RoundingMode.HALF_UP);
        String payMethod = PaymentChannelPolicy.requireActiveExternal(db, db.store(r), q.getOrDefault("payMethod", "CASH"));
        int status = amount.compareTo(config(r, "recycle_approval_limit")) > 0 ? 3 : 1;
        String billNo = "HS" + System.currentTimeMillis();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r)).addValue("no", billNo).addValue("m", q.get("memberId")).addValue("type", q.getOrDefault("materialType", "足金旧料")).addValue("w", weight).addValue("purity", purity).addValue("loss", lossAmount).addValue("amount", amount).addValue("pay", payMethod).addValue("status", status);
        db.jdbc().update("insert into recycle_order(store_id,bill_no,member_id,material_type,weight,purity,deduct_loss,total_amount,pay_method,status,create_time,update_time) values(:s,:no,:m,:type,:w,:purity,:loss,:amount,:pay,:status,now(),now())", p);
        long recycleId = db.jdbc().queryForObject("select recycle_order_id from recycle_order where store_id=:s and bill_no=:no", p, Long.class);
        BigDecimal effectivePurity = purity.multiply(BigDecimal.ONE.subtract(lossRate)).setScale(4, RoundingMode.HALF_UP);
        db.jdbc().update("insert into old_material(store_id,material_type,weight,purity,source,value,status,create_time,update_time) values(:s,:type,:w,:p,:source,:value,:st,now(),now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("type", q.getOrDefault("materialType", "足金旧料")).addValue("w", weight).addValue("p", effectivePurity).addValue("source", "RECYCLE:" + recycleId).addValue("value", amount).addValue("st", status == 1 ? 1 : 0));
        Long approvalId = null;
        if (status == 3) {
            db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) values(:s,'RECYCLE',:id,:uid,:amount,'大额回收超过配置限额',1,now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("id", recycleId).addValue("uid", userId(r)).addValue("amount", amount));
            approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type='RECYCLE' and biz_id=:id order by approval_id desc limit 1", Map.of("s", db.store(r), "id", recycleId), Long.class);
            db.jdbc().update("update recycle_order set approver_id=:a where recycle_order_id=:id and store_id=:s", Map.of("a", approvalId, "id", recycleId, "s", db.store(r)));
            ws.broadcast("APPROVAL_CREATED", Map.of("storeId", db.store(r), "id", approvalId, "approvalId", approvalId, "type", "RECYCLE", "bizId", recycleId));
        } else finalizeFinance(recycleId, r);
        if (!clientRequestId.isBlank()) db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'RECYCLE','CREATE',:content,:client,'',now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("uid", userId(r)).addValue("content", billNo).addValue("client", clientRequestId));
        Map<String,Object> result = new LinkedHashMap<>(); result.put("recycleOrderId", recycleId); result.put("billNo", billNo); result.put("amount", amount); result.put("status", status); result.put("approvalRequired", status == 3); if (approvalId != null) result.put("approvalId", approvalId); return ApiResponse.ok(result);
    }
    @GetMapping("/list") public ApiResponse<?> list(HttpServletRequest r) { return ApiResponse.ok(db.list("select * from recycle_order where store_id=:s order by recycle_order_id desc limit 500", Map.of("s", db.store(r)))); }
    public void finalizeFinance(long recycleId, HttpServletRequest r) {
        Map<String,Object> row = db.one("select * from recycle_order where recycle_order_id=:id and store_id=:s for update", Map.of("id", recycleId, "s", db.store(r)));
        if (((Number)row.get("status")).intValue() == 1 && !db.list("select finance_id from finance_record where related_bill_no=:no and category='RECYCLE'", Map.of("no", row.get("bill_no"))).isEmpty()) return;
        db.jdbc().update("update recycle_order set status=1,update_time=now() where recycle_order_id=:id and store_id=:s", Map.of("id", recycleId, "s", db.store(r)));
        oldMaterials.activateAndRecord(db.store(r), "RECYCLE:" + recycleId, userId(r));
        db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'EXPENSE','RECYCLE',:amount,:pay,:no,:uid,'旧料回收付款',:shift,now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("amount", row.get("total_amount")).addValue("pay", row.get("pay_method")).addValue("no", row.get("bill_no")).addValue("uid", userId(r)).addValue("shift", shifts.current(db.store(r))));
        ws.broadcast("RECYCLE_COMPLETED", Map.of("storeId", db.store(r), "recycleOrderId", recycleId, "amount", row.get("total_amount")));
        ws.broadcast("OLD_MATERIAL_UPDATED", Map.of("storeId", db.store(r), "source", "RECYCLE:" + recycleId, "action", "ACTIVATE"));
    }
    private BigDecimal decimal(Map<String,Object> q,String key){Object value=q.get(key);if(value==null)throw new BusinessException(400403,key+"不能为空");return new BigDecimal(String.valueOf(value));}
    private BigDecimal currentPrice(HttpServletRequest r){return db.jdbc().queryForObject("select price from gold_price where store_id=:s and price_type='回收金价' order by date desc,price_id desc limit 1",Map.of("s",db.store(r)),BigDecimal.class);}
    private BigDecimal config(HttpServletRequest r,String key){try{return new BigDecimal(db.jdbc().queryForObject("select config_value from sys_config where store_id=:s and config_key=:k and enabled=1",Map.of("s",db.store(r),"k",key),String.class));}catch(Exception e){if("recycle_approval_limit".equals(key))return new BigDecimal("99999999");throw new BusinessException(400404,"缺少系统配置: "+key);}}
    private long userId(HttpServletRequest r){io.jsonwebtoken.Claims c=(io.jsonwebtoken.Claims)r.getAttribute("claims");return c==null?0L:Long.parseLong(c.getSubject());}
}
