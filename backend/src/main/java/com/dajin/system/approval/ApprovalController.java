package com.dajin.system.approval;

import com.dajin.system.common.*;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.recycle.RecycleController;
import com.dajin.system.stock.OldMaterialLedgerService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/approval")
@RequireRoles({"ADMIN", "MANAGER"})
public class ApprovalController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final RecycleController recycle;
    private final OldMaterialLedgerService oldMaterials;
    private final ObjectMapper objectMapper;

    public ApprovalController(DbSupport db, SyncWebSocketHandler ws, RecycleController recycle,
                             OldMaterialLedgerService oldMaterials, ObjectMapper objectMapper) {
        this.db = db;
        this.ws = ws;
        this.recycle = recycle;
        this.oldMaterials = oldMaterials;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/pending")
    public ApiResponse<?> pending(HttpServletRequest r) {
        return ApiResponse.ok(db.list("select * from approval where store_id=:s and status=1 order by approval_id desc", Map.of("s", db.store(r))));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable long id, HttpServletRequest r) {
        Map<String,Object> approval = db.one("select * from approval where approval_id=:id and store_id=:s", Map.of("id", id, "s", db.store(r)));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("approval", approval);
        if ("REFUND".equals(approval.get("type"))) {
            result.put("order",db.one("select * from sales_order where order_id=:id and store_id=:s",Map.of("id",approval.get("biz_id"),"s",db.store(r))));
        }
        if ("STOCK_CHECK".equals(approval.get("type"))) {
            Map<String,Object> check = db.one("select sc.*,u.real_name operator_name,case when sc.scope_type='STORE' then st.store_name "
                    + "when sc.scope_type in ('CATEGORY_L1','CATEGORY_L2') then c.name when sc.scope_type='GOODS' then g.name else '自定义商品' end scope_name "
                    + "from stock_check sc left join sys_user u on u.user_id=sc.operator_id and u.store_id=sc.store_id "
                    + "left join sys_store st on st.store_id=sc.store_id left join goods_category c on c.category_id=sc.scope_id and c.store_id=sc.store_id "
                    + "left join goods g on g.goods_id=sc.scope_id and g.store_id=sc.store_id where sc.check_id=:id and sc.store_id=:s",
                    Map.of("id", approval.get("biz_id"), "s", db.store(r)));
            result.put("stockCheck", check);
        } else if ("TRADE_IN".equals(approval.get("type"))) {
            Map<String,Object> tradeIn = db.one("select * from trade_in where trade_in_id=:id and store_id=:s", Map.of("id", approval.get("biz_id"), "s", db.store(r)));
            result.put("tradeIn", tradeIn);
            result.put("oldMaterials", db.list("select * from old_material where store_id=:s and source=concat('TRADEIN:',:id) order by material_id", Map.of("s", db.store(r), "id", approval.get("biz_id"))));
        } else if ("STOCK_OUT".equals(approval.get("type"))) {
            try {
                result.put("stockOut", objectMapper.readValue(String.valueOf(approval.get("reason")), new TypeReference<Map<String,Object>>() {}));
            } catch (Exception ignored) {
                result.put("stockOut", Map.of("reason", approval.get("reason")));
            }
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/{id}/approve")
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ApiResponse<?> approve(@PathVariable long id, @RequestBody(required=false) Map<String,Object> q, HttpServletRequest r) { return decide(id, 3, q, r); }

    @PostMapping("/{id}/reject")
    @Transactional
    public ApiResponse<?> reject(@PathVariable long id, @RequestBody(required=false) Map<String,Object> q, HttpServletRequest r) { return decide(id, 4, q, r); }

    private ApiResponse<?> decide(long id, int status, Map<String,Object> q, HttpServletRequest r) {
        String decisionRemark = q == null || q.get("remark") == null ? "" : String.valueOf(q.get("remark")).trim();
        if (status == 4 && decisionRemark.isBlank()) throw new BusinessException(400211, "驳回原因不能为空");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("st", status).addValue("id", id).addValue("s", db.store(r)).addValue("remark", q == null ? null : q.get("remark")).addValue("uid", userId(r));
        int changed = db.jdbc().update("update approval set status=:st,approver_id=:uid,approve_remark=:remark,approve_time=now() where approval_id=:id and store_id=:s and status=1", p);
        if (changed == 0) throw new BusinessException(409001, "审批单状态已变化");
        Map<String,Object> approval = db.one("select approval_id,type,biz_id,reason from approval where approval_id=:id and store_id=:s", p);
        String type = String.valueOf(approval.get("type"));
        long bizId = ((Number) approval.get("biz_id")).longValue();

        if ("DISCOUNT".equals(type)) {
            db.jdbc().update("update sales_order set status=:orderStatus,update_time=now(),version=version+1 where order_id=:biz and store_id=:s", Map.of("orderStatus", status == 3 ? 0 : 4, "biz", bizId, "s", db.store(r)));
            if (status != 3) {
                db.jdbc().update("delete from old_material where store_id=:s and source=concat('ORDER:',:biz) and status=0", Map.of("s", db.store(r), "biz", bizId));
                db.jdbc().update("update goods_piece set status=1,sales_order_id=null,update_time=now() where store_id=:s and sales_order_id=:biz and status=2", Map.of("s", db.store(r), "biz", bizId));
            }
        } else if ("RECYCLE".equals(type) && status == 3) {
            recycle.finalizeFinance(bizId, r);
        } else if ("RECYCLE".equals(type)) {
            db.jdbc().update("update recycle_order set status=4,update_time=now() where recycle_order_id=:biz and store_id=:s", Map.of("biz", bizId, "s", db.store(r)));
            db.jdbc().update("update old_material set status=2,update_time=now() where store_id=:s and source=concat('RECYCLE:',:biz)", Map.of("s", db.store(r), "biz", bizId));
        } else if ("STOCK_CHECK".equals(type)) {
            decideStockCheck(bizId, status, q == null ? null : String.valueOf(q.get("remark")), r);
        } else if ("TRADE_IN".equals(type)) {
            decideTradeIn(bizId, status, q == null ? null : String.valueOf(q.get("remark")), r);
        } else if ("STOCK_OUT".equals(type)) {
            decideStockOut(approval, status, q == null ? null : String.valueOf(q.get("remark")), r);
        } else if ("REFUND".equals(type) && status == 3) {
            refundOrder(bizId, q, r);
        }

        Map<String,Object> decidedEvent = Map.of("storeId", db.store(r), "approvalId", id, "id", id, "status", status, "type", type, "bizId", bizId);
        ws.broadcast("APPROVAL_DECIDED", decidedEvent);
        if ("STOCK_CHECK".equals(type)) ws.broadcast(status == 3 ? "STOCK_CHECK_APPROVED" : "STOCK_CHECK_REJECTED", decidedEvent);
        if ("DISCOUNT".equals(type)) {
            ws.broadcast("ORDER_UPDATED", decidedEvent);
            if (status != 3) ws.broadcast("STOCK_UPDATED", decidedEvent);
        }
        if ("RECYCLE".equals(type)) {
            ws.broadcast("RECYCLE_UPDATED", decidedEvent);
            ws.broadcast("OLD_MATERIAL_UPDATED", decidedEvent);
        }
        if ("TRADE_IN".equals(type)) {
            ws.broadcast("TRADE_IN_UPDATED", decidedEvent);
            ws.broadcast("OLD_MATERIAL_UPDATED", decidedEvent);
        }
        if (status == 3 && Set.of("STOCK_CHECK", "STOCK_OUT", "REFUND").contains(type)) {
            ws.broadcast("STOCK_UPDATED", decidedEvent);
        }
        if (status == 3 && "REFUND".equals(type)) {
            ws.broadcast("ORDER_UPDATED", decidedEvent);
            ws.broadcast("MEMBER_UPDATED", decidedEvent);
            ws.broadcast("VISIT_TASK_UPDATED", decidedEvent);
            ws.broadcast("REPORT_UPDATED", decidedEvent);
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("approvalId", id);
        result.put("status", status);
        result.put("remark", q == null ? null : q.get("remark"));
        return ApiResponse.ok(result);
    }

    private void decideStockCheck(long checkId, int status, String remark, HttpServletRequest r) {
        Map<String,Object> check = db.one("select * from stock_check where check_id=:id and store_id=:s for update", Map.of("id", checkId, "s", db.store(r)));
        if (status == 3) {
            for (Map<String,Object> row : detailRows(check.get("detail"))) {
                Object rawGoodsId = row.getOrDefault("goodsId", row.get("id"));
                long goodsId = Long.parseLong(String.valueOf(rawGoodsId));
                BigDecimal actual = new BigDecimal(String.valueOf(row.get("actual")));
                if (actual.signum() < 0) throw new BusinessException(400203, "实盘数量不能小于0");
                Map<String,Object> goods = db.one("select stock from goods where goods_id=:g and store_id=:s for update", Map.of("g", goodsId, "s", db.store(r)));
                BigDecimal current = new BigDecimal(String.valueOf(goods.get("stock")));
                BigDecimal diff;
                if (row.get("difference") != null || row.get("stockSnapshot") != null) {
                    Object snapshot = row.getOrDefault("stockSnapshot", row.getOrDefault("stock", 0));
                    diff = row.get("difference") == null
                            ? actual.subtract(new BigDecimal(String.valueOf(snapshot)))
                            : new BigDecimal(String.valueOf(row.get("difference")));
                } else {
                    // Legacy management checks did not persist a trusted difference snapshot.
                    diff = actual.subtract(current);
                }
                BigDecimal adjusted = current.add(diff);
                if (adjusted.signum() < 0) throw new BusinessException(409212, "盘点调整后库存将小于0，请驳回后重新盘点");
                var availability = new com.dajin.system.stock.InventoryAvailability(db);
                if(diff.signum()<0) availability.requireAvailable(db.store(r),goodsId,diff.negate());
                availability.adjustCountPieces(db.store(r),goodsId,diff);
                db.jdbc().update("update goods set stock=:adjusted,version=version+1,update_time=now() where goods_id=:g and store_id=:s", new MapSqlParameterSource().addValue("adjusted", adjusted).addValue("g", goodsId).addValue("s", db.store(r)));
                if (diff.signum() > 0) {
                    db.jdbc().update("insert ignore into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) values(:s,:no,'STOCK_CHECK',:g,:qty,0,:uid,now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("no", check.get("bill_no") + "-IN-" + goodsId).addValue("g", goodsId).addValue("qty", diff).addValue("uid", userId(r)));
                } else if (diff.signum() < 0) {
                    db.jdbc().update("insert ignore into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:no,'STOCK_CHECK',:g,:qty,'盘点审批调整',:uid,now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("no", check.get("bill_no") + "-OUT-" + goodsId).addValue("g", goodsId).addValue("qty", diff.abs()).addValue("uid", userId(r)));
                }
            }
        }
        db.jdbc().update("update stock_check set status=:st,approver_id=:uid,approve_remark=:remark,update_time=now() where check_id=:id and store_id=:s", new MapSqlParameterSource().addValue("st", status).addValue("uid", userId(r)).addValue("remark", remark).addValue("id", checkId).addValue("s", db.store(r)));
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,'STOCK',:action,:content,'',now())",
                new MapSqlParameterSource().addValue("s", db.store(r)).addValue("u", userId(r))
                        .addValue("action", status == 3 ? "STOCK_CHECK_APPROVE" : "STOCK_CHECK_REJECT")
                        .addValue("content", "盘点单=" + check.get("bill_no") + (remark == null || remark.isBlank() ? "" : ",备注=" + remark)));
    }

    private void decideTradeIn(long tradeInId, int status, String remark, HttpServletRequest r) {
        if (status == 3) throw new BusinessException(409109, "旧版换新单缺少商品和收付款记录，请核对实物及资金后驳回，通过商品开单重新办理");
        Map<String,Object> tradeIn = db.one("select * from trade_in where trade_in_id=:id and store_id=:s for update", Map.of("id", tradeInId, "s", db.store(r)));
        if (status == 3) {
            db.jdbc().update("update trade_in set status=1,update_time=now() where trade_in_id=:id and store_id=:s",
                    Map.of("id", tradeInId, "s", db.store(r)));
            oldMaterials.activateAndRecord(db.store(r), "TRADEIN:" + tradeInId, userId(r));
        } else {
            db.jdbc().update("update trade_in set status=4,update_time=now() where trade_in_id=:id and store_id=:s",
                    Map.of("id", tradeInId, "s", db.store(r)));
            db.jdbc().update("update old_material set status=2,update_time=now() where store_id=:s and source=concat('TRADEIN:',:id) and status=0",
                    Map.of("s", db.store(r), "id", tradeInId));
        }
    }

    /** Applies an approved manual goods out request exactly once while holding the goods row lock. */
    private void decideStockOut(Map<String,Object> approval, int status, String remark, HttpServletRequest r) {
        if (status != 3) return;
        Map<String,Object> request;
        try { request = objectMapper.readValue(String.valueOf(approval.get("reason")), new TypeReference<Map<String,Object>>() {}); }
        catch (Exception e) { throw new BusinessException(400205, "出库审批数据格式错误"); }
        long storeId = db.store(r); long goodsId = Long.parseLong(String.valueOf(request.get("goodsId")));
        BigDecimal qty = new BigDecimal(String.valueOf(request.get("qty")));
        if (qty.signum() <= 0) throw new BusinessException(400206, "出库数量必须大于0");
        Map<String,Object> goods = db.one("select stock,version from goods where goods_id=:g and store_id=:s for update", Map.of("g", goodsId, "s", storeId));
        BigDecimal stock = new BigDecimal(String.valueOf(goods.get("stock")));
        if (stock.compareTo(qty) < 0) throw new BusinessException(409204, "商品库存不足，审批无法通过");
        new com.dajin.system.stock.InventoryAvailability(db).requireAvailable(storeId,goodsId,qty);
        int expectedVersion = request.get("expectedVersion") == null ? ((Number) goods.get("version")).intValue() : Integer.parseInt(String.valueOf(request.get("expectedVersion")));
        int changed = db.jdbc().update("update goods set stock=stock-:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s and stock>=:qty and version=:v", new MapSqlParameterSource().addValue("qty", qty).addValue("g", goodsId).addValue("s", storeId).addValue("v", expectedVersion));
        if (changed == 0) throw new BusinessException(409205, "库存版本已变化，请重新盘点");
        String pieceNo = request.get("pieceNo") == null ? "" : String.valueOf(request.get("pieceNo")).trim();
        new com.dajin.system.stock.InventoryAvailability(db).removePieces(storeId,goodsId,qty,pieceNo);
        String billNo = String.valueOf(request.getOrDefault("billNo", "MO-APPROVAL-" + approval.get("approval_id")));
        db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:no,'MANUAL_APPROVED',:g,:qty,:reason,:uid,now()) on duplicate key update qty=values(qty),reason=values(reason)",
                new MapSqlParameterSource().addValue("s", storeId).addValue("no", billNo).addValue("g", goodsId).addValue("qty", qty).addValue("reason", request.getOrDefault("reason", remark == null ? "审批出库" : remark)).addValue("uid", userId(r)));
    }

    /** 退款审批通过：回滚库存与件、冲销财务流水、退回储值、标记订单已退款。 */
    private void refundOrder(long orderId, Map<String,Object> confirmation, HttpServletRequest r) {
        long storeId = db.store(r);
        Map<String,Object> order = db.one("select * from sales_order where order_id=:o and store_id=:s for update", Map.of("o", orderId, "s", storeId));
        int status = ((Number) order.get("status")).intValue();
        if (status != 1) throw new BusinessException(409108, "只有已结算订单可以退款");
        BigDecimal excess = new BigDecimal(String.valueOf(order.getOrDefault("old_material_excess",0)));
        if (excess.signum()>0 && (confirmation==null || !Boolean.TRUE.equals(confirmation.get("oldMaterialExcessRecovered"))))
            throw new BusinessException(409112,"请确认已按原渠道收回超额旧金返款后再审批退款");
        BigDecimal refund = new BigDecimal(String.valueOf(order.get("pay_amount")));
        if (refund.signum() < 0) throw new BusinessException(400213, "订单实付金额不合法");
        for (Map<String,Object> item : db.list("select order_item_id,goods_id,qty from sales_order_item where order_id=:o and store_id=:s and goods_id is not null order by goods_id,order_item_id", Map.of("o", orderId, "s", storeId))) {
            db.jdbc().update("update goods set stock=stock+:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s",
                    new MapSqlParameterSource().addValue("qty", item.get("qty")).addValue("g", item.get("goods_id")).addValue("s", storeId));
            int pieceQty = (int) Math.round(new BigDecimal(String.valueOf(item.get("qty"))).doubleValue());
            if (pieceQty > 0) db.jdbc().update("update goods_piece set status=1,sales_order_id=null,update_time=now() where piece_id in (select piece_id from (select piece_id from goods_piece where store_id=:s and goods_id=:g and sales_order_id=:o and status=0 order by piece_id desc limit :qty) t)",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("g", item.get("goods_id")).addValue("o", orderId).addValue("qty", pieceQty));
            db.jdbc().update("insert into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) values(:s,:no,'SALE_REFUND',:g,:qty,0,:uid,now())",
                    new MapSqlParameterSource().addValue("s",storeId).addValue("no","REFUND-"+item.get("order_item_id")).addValue("g",item.get("goods_id")).addValue("qty",item.get("qty")).addValue("uid",userId(r)));
        }
        String shift = new com.dajin.system.shift.ShiftService(db).current(storeId);
        List<Map<String,Object>> paymentLines = db.list("select pay_method,sum(amount) amount from finance_record where store_id=:s and related_bill_no=:no and type='INCOME' and category='SALE' group by pay_method",
                Map.of("s",storeId,"no",order.get("order_no")));
        if (paymentLines.isEmpty() && refund.signum()>0) {
            String method = String.valueOf(order.getOrDefault("pay_method", "CASH"));
            if (method.contains("+")) throw new BusinessException(409110,"历史组合支付缺少渠道明细，请先核对原收款流水");
            paymentLines = List.of(Map.of("pay_method",method,"amount",refund));
        }
        BigDecimal paymentTotal = paymentLines.stream().map(line -> new BigDecimal(line.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
        if (paymentTotal.compareTo(refund)!=0) throw new BusinessException(409110,"原收款流水与订单实付不一致，请先核对");
        for (Map<String,Object> line : paymentLines) {
            BigDecimal amount = new BigDecimal(line.get("amount").toString());
            if(amount.signum()==0) continue;
            String method = String.valueOf(line.get("pay_method"));
            db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'EXPENSE','SALE_REFUND',:a,:m,:no,:uid,'销售退款冲销',:shift,now())",
                    new MapSqlParameterSource().addValue("s",storeId).addValue("a",amount).addValue("m",method).addValue("no",order.get("order_no")).addValue("uid",userId(r)).addValue("shift",shift));
            if ("BALANCE".equalsIgnoreCase(method)) {
                if(order.get("member_id")==null) throw new BusinessException(409110,"储值退款缺少会员信息");
                int restored=db.jdbc().update("update member set balance=balance+:a,update_time=now() where member_id=:m and store_id=:s",
                        new MapSqlParameterSource().addValue("a",amount).addValue("m",order.get("member_id")).addValue("s",storeId));
                if(restored!=1) throw new BusinessException(409110,"储值退款会员不存在");
                new com.dajin.system.member.MemberBalanceLedger(db).record(storeId,order.get("member_id"),amount,"SALE_REFUND",String.valueOf(orderId),userId(r));
            }
        }
        if (order.get("member_id") != null) {
            db.jdbc().update("update member set total_consume=total_consume-:a where member_id=:m and store_id=:s and total_consume>=:a",
                    new MapSqlParameterSource().addValue("a", refund).addValue("m", order.get("member_id")).addValue("s", storeId));
            db.jdbc().update("delete from member_consume where store_id=:s and order_id=:o", Map.of("s", storeId, "o", orderId));
        }
        oldMaterials.returnAndRecord(storeId,"ORDER:"+orderId,userId(r));
        if (excess.signum()>0) {
            var payouts=db.list("select pay_method,sum(amount) amount from finance_record where store_id=:s and related_bill_no=:no and type='EXPENSE' and category='RECYCLE' group by pay_method",Map.of("s",storeId,"no",order.get("order_no")));
            BigDecimal paid=payouts.stream().map(row->new BigDecimal(row.get("amount").toString())).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(paid.compareTo(excess)!=0) throw new BusinessException(409112,"原超额返款流水不完整，请先核对");
            for(var payout:payouts) db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'INCOME','RECYCLE_REFUND',:amount,:method,:no,:uid,'销售退款收回超额旧金返款',:shift,now())",
                    new MapSqlParameterSource().addValue("s",storeId).addValue("amount",payout.get("amount")).addValue("method",payout.get("pay_method")).addValue("no",order.get("order_no")).addValue("uid",userId(r)).addValue("shift",shift));
        }
        db.jdbc().update("update sales_order set status=5,update_time=now(),version=version+1 where order_id=:o and store_id=:s and status=1", Map.of("o", orderId, "s", storeId));
        if (order.get("sales_id") != null) new com.dajin.system.commission.CommissionLedger(db).rebuildForOrder(storeId, orderId);
        db.jdbc().update("update visit_task set status=2,call_result='ORDER_REFUNDED',record=case when record is null or trim(record)='' then '订单已退款，回访任务自动关闭' else record end,update_time=now() where store_id=:s and order_id=:orderId and status=1",
                Map.of("s", storeId, "orderId", orderId));
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,'ORDER','REFUND',:content,'',now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("u", userId(r)).addValue("content", "退款单=" + order.get("order_no") + ",金额=" + refund));
    }

    private List<Map<String,Object>> detailRows(Object raw) {
        if (raw == null) return List.of();
        try {
            return objectMapper.readValue(String.valueOf(raw), new TypeReference<List<Map<String,Object>>>() {});
        } catch (Exception e) {
            throw new BusinessException(400204, "盘点明细格式错误");
        }
    }

    private long userId(HttpServletRequest r) { io.jsonwebtoken.Claims c = (io.jsonwebtoken.Claims) r.getAttribute("claims"); return c == null ? 0L : Long.parseLong(c.getSubject()); }
}
