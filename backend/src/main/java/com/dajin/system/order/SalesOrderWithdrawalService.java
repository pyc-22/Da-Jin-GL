package com.dajin.system.order;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.commission.CommissionLedger;
import com.dajin.system.stock.GoodsInventoryUnit;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Applies the transactional compensations for a terminal sales-order withdrawal. */
public final class SalesOrderWithdrawalService {
    public static final int WITHDRAWN_STATUS = 6;

    private final DbSupport db;
    private final OldMaterialLedgerService oldMaterials;

    public SalesOrderWithdrawalService(DbSupport db) {
        this.db = db;
        this.oldMaterials = new OldMaterialLedgerService(db);
    }

    public boolean canWithdraw(Map<String, Object> order) {
        int status = number(order.get("status"));
        return status == 0 || status == 1 || status == 3;
    }

    public BigDecimal withdraw(Map<String, Object> order, long storeId, long operatorId) {
        long id = ((Number) order.get("order_id")).longValue();
        int status = number(order.get("status"));
        if (!canWithdraw(order)) throw new BusinessException(409109, "关联成品单状态已变化，整组撤回已取消");

        BigDecimal paid = decimal(order.get("actual_paid"));
        String orderNo = String.valueOf(order.get("order_no"));
        String requestId = "WITHDRAW-SALE-" + id;
        if (status == 1) {
            restoreGoods(id, storeId, operatorId);
            oldMaterials.returnAndRecord(storeId, "ORDER:" + id, operatorId);
        } else {
            db.jdbc().update("update goods_piece set status=1,sales_order_id=null,update_time=now() where store_id=:s and sales_order_id=:o and status=2", Map.of("s", storeId, "o", id));
        }
        reverseSalePayments(order, id, storeId, operatorId, requestId);
        reverseOldMaterialPayout(orderNo, id, storeId, operatorId);
        if (status == 1) reverseMemberAndVisit(order, id, storeId, paid);
        db.jdbc().update("update old_material set status=2,version=version+1,update_time=now() where store_id=:s and source=concat('ORDER:',:o) and status=0", Map.of("s", storeId, "o", id));

        db.jdbc().update("update approval set status=4,approver_id=:uid,approve_remark='订单已撤回',approve_time=now() where store_id=:s and ((type='DISCOUNT' and biz_id=:id) or (type='REFUND' and biz_id=:id) or (type='SALE_PAYMENT_DISCOUNT' and biz_id=:id)) and status=1",
                new MapSqlParameterSource().addValue("s", storeId).addValue("id", id).addValue("uid", operatorId));
        int changed = db.jdbc().update("update sales_order set status=:withdrawn,version=version+1,update_time=now() where order_id=:id and store_id=:s and status=:status and version=:version",
                new MapSqlParameterSource().addValue("withdrawn", WITHDRAWN_STATUS).addValue("id", id).addValue("s", storeId)
                        .addValue("status", status).addValue("version", number(order.get("version"))));
        if (changed != 1) throw new BusinessException(409107, "销售单版本已变化，整组撤回已取消");
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'ORDER','WITHDRAW',:content,:client,'',now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("uid", operatorId)
                        .addValue("content", "订单=" + orderNo + ",原状态=" + status + ",原实收=" + paid)
                        .addValue("client", requestId));
        if (status == 1) new CommissionLedger(db).rebuildForOrder(storeId, id);
        return paid;
    }

    private void restoreGoods(long orderId, long storeId, long operatorId) {
        for (Map<String, Object> item : db.list("select i.order_item_id,i.goods_id,i.qty,i.weight,i.piece_nos,g.price_type from sales_order_item i join goods g on g.goods_id=i.goods_id and g.store_id=i.store_id where i.order_id=:o and i.store_id=:s and i.goods_id is not null order by i.goods_id,i.order_item_id", Map.of("o", orderId, "s", storeId))) {
            BigDecimal qty = GoodsInventoryUnit.quantity(item.get("price_type"), item.get("weight"), item.get("qty"));
            db.jdbc().update("update goods set stock=stock+:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s",
                    new MapSqlParameterSource().addValue("qty", qty).addValue("g", item.get("goods_id")).addValue("s", storeId));
            if (!GoodsInventoryUnit.isGramPriced(item.get("price_type")) && qty.signum() > 0) {
                List<String> pieces = pieceNos(item.get("piece_nos"));
                MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("g", item.get("goods_id")).addValue("o", orderId).addValue("qty", qty.intValue());
                if (!pieces.isEmpty()) db.jdbc().update("update goods_piece set status=1,sales_order_id=null,update_time=now() where store_id=:s and goods_id=:g and sales_order_id=:o and status=0 and piece_no in (:pieces)", p.addValue("pieces", pieces));
                else db.jdbc().update("update goods_piece set status=1,sales_order_id=null,update_time=now() where piece_id in (select piece_id from (select piece_id from goods_piece where store_id=:s and goods_id=:g and sales_order_id=:o and status=0 order by piece_id desc limit :qty) t)", p);
            }
            db.jdbc().update("insert into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) values(:s,:no,'SALE_WITHDRAW',:g,:qty,0,:uid,now()) on duplicate key update qty=values(qty)",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("no", "WITHDRAW-SALE-" + item.get("order_item_id"))
                            .addValue("g", item.get("goods_id")).addValue("qty", qty).addValue("uid", operatorId));
        }
    }

    private void reverseSalePayments(Map<String, Object> order, long id, long storeId, long operatorId, String requestId) {
        List<Map<String, Object>> income = db.list("select pay_method,sum(amount) amount from finance_record where store_id=:s and related_bill_no=:no and type='INCOME' and category='SALE' group by pay_method", Map.of("s", storeId, "no", order.get("order_no")));
        for (Map<String, Object> line : income) {
            BigDecimal amount = decimal(line.get("amount"));
            if (amount.signum() == 0) continue;
            String method = String.valueOf(line.get("pay_method"));
            db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,client_request_id,create_time) values(:s,'EXPENSE','SALE_WITHDRAW',:a,:m,:no,:uid,'销售单撤回冲销',:shift,:client,now()) on duplicate key update amount=values(amount)",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("a", amount).addValue("m", method).addValue("no", order.get("order_no"))
                            .addValue("uid", operatorId).addValue("shift", order.get("shift_no")).addValue("client", requestId + "-" + method));
            if (com.dajin.system.pay.PaymentChannelPolicy.isBalanceChannel(db, storeId, method) && order.get("member_id") != null) {
                db.jdbc().update("update member set balance=balance+:a,update_time=now() where member_id=:m and store_id=:s",
                        new MapSqlParameterSource().addValue("a", amount).addValue("m", order.get("member_id")).addValue("s", storeId));
                new com.dajin.system.member.MemberBalanceLedger(db).record(storeId, order.get("member_id"), amount, "SALE_WITHDRAW", requestId + "-" + method, operatorId);
            }
        }
    }

    private void reverseOldMaterialPayout(String orderNo, long orderId, long storeId, long operatorId) {
        List<Map<String, Object>> payouts = db.list("select finance_id,amount,pay_method,shift_no from finance_record where store_id=:s and related_bill_no=:no and type='EXPENSE' and category='RECYCLE' order by finance_id", Map.of("s", storeId, "no", orderNo));
        for (Map<String, Object> payout : payouts) {
            String client = "WITHDRAW-SALE-" + orderId + "-RECYCLE-" + payout.get("finance_id");
            db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,client_request_id,create_time) values(:s,'INCOME','SALE_WITHDRAW',:a,:m,:no,:uid,'销售旧料返款撤回冲销',:shift,:client,now()) on duplicate key update amount=values(amount)",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("a", payout.get("amount")).addValue("m", payout.get("pay_method"))
                            .addValue("no", orderNo).addValue("uid", operatorId).addValue("shift", payout.get("shift_no")).addValue("client", client));
        }
    }

    private void reverseMemberAndVisit(Map<String, Object> order, long id, long storeId, BigDecimal paid) {
        if (order.get("member_id") != null) {
            db.jdbc().update("update member set total_consume=greatest(total_consume-:a,0),update_time=now() where member_id=:m and store_id=:s",
                    new MapSqlParameterSource().addValue("a", paid).addValue("m", order.get("member_id")).addValue("s", storeId));
            db.jdbc().update("insert into member_consume(store_id,member_id,order_id,amount,consume_time) values(:s,:m,:o,:amount,now())",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("m", order.get("member_id")).addValue("o", id).addValue("amount", paid.negate()));
        }
        db.jdbc().update("update visit_task set status=2,call_result='ORDER_WITHDRAWN',update_time=now() where store_id=:s and order_id=:o and status=1", Map.of("s", storeId, "o", id));
    }

    private List<String> pieceNos(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(String.valueOf(value), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            throw new BusinessException(409114, "订单单件码格式不正确");
        }
    }

    private int number(Object value) { return value == null ? 0 : Integer.parseInt(String.valueOf(value)); }
    private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); }
}
