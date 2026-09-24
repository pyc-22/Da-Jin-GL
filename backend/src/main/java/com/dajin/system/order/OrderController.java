package com.dajin.system.order;

import com.dajin.system.common.*;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.math.*;
import java.time.*;
import java.util.*;

@RestController
@RequestMapping("/api/order")
public class OrderController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final ObjectMapper objectMapper;
    public OrderController(DbSupport db, SyncWebSocketHandler ws, ObjectMapper objectMapper) { this.db = db; this.ws = ws; this.objectMapper = objectMapper; }
    public record Item(Long goodsId, String itemName, BigDecimal weight, BigDecimal unitPrice, BigDecimal laborFee, Integer qty, BigDecimal subtotal, List<String> pieceNos) {}
    public record OldMaterialItem(String materialType, BigDecimal weight, BigDecimal purity, String priceType, BigDecimal price, String note) {}
    public record Req(Long memberId, BigDecimal discount, BigDecimal oldMaterialDeduct, BigDecimal laborFee,
                      BigDecimal payAmount, String payMethod, String oldMaterialPayoutMethod, Long salesId,
                      String remark, List<Item> items, List<OldMaterialItem> oldMaterials,
                      @NotBlank String clientRequestId, Integer version, Boolean handover) {}

    @PostMapping("/create")
    @RequirePermission("order:create")
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ApiResponse<?> create(@Valid @RequestBody Req q, HttpServletRequest r) {
        if (q.items() == null || q.items().isEmpty()) throw new BusinessException("订单明细不能为空");
        if (q.discount() != null && (q.discount().signum() <= 0 || q.discount().compareTo(BigDecimal.ONE) > 0))
            throw new BusinessException(400104, "折扣必须大于0且不超过10折");
        if (q.clientRequestId() != null && !q.clientRequestId().isBlank()) {
            if ("CANCELLED".equals(lockClientRequest(q.clientRequestId(), r)))
                throw new BusinessException(409109, "订单已取消，不能重新同步开单");
            List<Map<String, Object>> existing = db.list("select order_id,order_no,status,approval_id,old_material_deduct,old_material_excess,old_material_payout_method from sales_order where store_id=:s and client_request_id=:c", Map.of("s", db.store(r), "c", q.clientRequestId()));
            if (!existing.isEmpty()) {
                Map<String,Object> e = existing.get(0);
                Map<String,Object> replay = new LinkedHashMap<>();
                replay.put("orderId", e.get("order_id"));
                replay.put("orderNo", e.get("order_no"));
                replay.put("status", e.get("status"));
                replay.put("approvalId", e.get("approval_id"));
                replay.put("oldMaterialDeduct", e.get("old_material_deduct"));
                replay.put("oldMaterialExcess", e.get("old_material_excess"));
                replay.put("oldMaterialPayoutMethod", e.get("old_material_payout_method"));
                replay.put("idempotentReplay", true);
                return ApiResponse.ok(replay);
            }
        }
        for (Item item : q.items()) {
            if (item.qty() == null || item.qty() <= 0 || (item.weight() != null && item.weight().signum() <= 0)
                    || item.subtotal() == null || item.subtotal().signum() < 0)
                throw new BusinessException(400105, "商品数量、克重和金额参数不合法");
        }
        validateStockAvailability(q.items(), r);
        Set<String> requestedPieceNos = new HashSet<>();
        for (Item item : q.items()) {
            validatePieces(item, requestedPieceNos, r);
        }
        BigDecimal total = q.items().stream().map(Item::subtotal).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = q.discount() == null ? BigDecimal.ONE : q.discount();
        BigDecimal laborFee = q.laborFee() == null ? BigDecimal.ZERO : q.laborFee();
        if (laborFee.signum() < 0) throw new BusinessException(400106, "工费不能小于0");
        BigDecimal oldMaterialValue = oldMaterialDeduct(q.oldMaterials(), q.oldMaterialDeduct(), r);
        OrderSettlement settlement = OrderSettlement.calculate(total, discount, laborFee, oldMaterialValue);
        String payoutMethod = validatePayoutMethod(q.oldMaterialPayoutMethod(), settlement.excessPayout(), r);
        int status = discount.compareTo(configDecimal(r, "discount_threshold")) < 0 ? 3 : 0;
        // A timestamp suffix alone collides under burst traffic; keep the human-readable date
        // while adding a short random component for the store/order unique key.
        String no = "XS" + LocalDate.now().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r)).addValue("no", no).addValue("m", q.memberId()).addValue("total", total).addValue("discount", discount).addValue("old", settlement.appliedDeduction()).addValue("excess", settlement.excessPayout()).addValue("payoutMethod", payoutMethod).addValue("labor", laborFee).addValue("pay", BigDecimal.ZERO).addValue("method", null).addValue("cashier", userId(r)).addValue("sales", q.salesId()).addValue("approval", null).addValue("status", status).addValue("remark", q.remark()).addValue("client", q.clientRequestId()).addValue("handover", Boolean.TRUE.equals(q.handover()) ? 1 : 0);
        db.jdbc().update("insert into sales_order(store_id,order_no,member_id,total_amount,discount,old_material_deduct,old_material_excess,old_material_payout_method,labor_fee,pay_amount,pay_method,cashier_id,sales_id,approval_id,status,remark,client_request_id,handover,create_time,update_time) values(:s,:no,:m,:total,:discount,:old,:excess,:payoutMethod,:labor,:pay,:method,:cashier,:sales,:approval,:status,:remark,:client,:handover,now(),now())", p);
        long id = db.jdbc().queryForObject("select order_id from sales_order where store_id=:s and order_no=:no", p, Long.class);
        for (Item i : q.items()) {
            BigDecimal costSnapshot = costSnapshot(i, r);
            db.jdbc().update("insert into sales_order_item(store_id,order_id,goods_id,item_name,weight,unit_price,labor_fee,qty,subtotal,cost_snapshot,piece_nos,create_time) values(:s,:id,:g,:name,:w,:u,coalesce(:fee,0),:qty,coalesce(:sub,0),:cost,:pieceNos,now())",
                    new MapSqlParameterSource().addValue("s", db.store(r)).addValue("id", id).addValue("g", i.goodsId()).addValue("name", i.itemName()).addValue("w", i.weight()).addValue("u", i.unitPrice()).addValue("fee", i.laborFee()).addValue("qty", i.qty()).addValue("sub", i.subtotal()).addValue("cost", costSnapshot).addValue("pieceNos", pieceNosJson(i.pieceNos())));
        }
        reservePieces(id, q.items(), r);
        insertPendingOldMaterials(id, q.oldMaterials(), r);
        Long approvalId = null;
        if (status == 3) {
            db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) values(:s,'DISCOUNT',:id,0,:amount,'折扣低于配置阈值',1,now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("id", id).addValue("amount", total));
            approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type='DISCOUNT' and biz_id=:id order by approval_id desc limit 1", Map.of("s", db.store(r), "id", id), Long.class);
            db.jdbc().update("update sales_order set approval_id=:a where order_id=:id and store_id=:s", Map.of("a", approvalId, "id", id, "s", db.store(r)));
            ws.broadcast("APPROVAL_CREATED", Map.of("storeId", db.store(r), "id", approvalId, "type", "DISCOUNT", "bizId", id));
        }
        if (q.salesId() != null) ws.broadcast("MOBILE_ORDER_CREATED", Map.of("storeId", db.store(r), "orderId", id, "orderNo", no, "salesId", q.salesId()));
        if (Boolean.TRUE.equals(q.handover())) ws.broadcast("ORDER_HANDOVER", Map.of("storeId", db.store(r), "orderId", id, "orderNo", no));
        ws.broadcast("ORDER_CREATED", Map.of("storeId", db.store(r), "orderId", id, "orderNo", no, "status", status));
        ws.broadcast("STOCK_UPDATED", Map.of("storeId", db.store(r), "orderId", id, "action", "RESERVE"));
        Map<String, Object> result = new HashMap<>(); result.put("orderId", id); result.put("orderNo", no); result.put("status", status); result.put("oldMaterialDeduct", settlement.appliedDeduction()); result.put("oldMaterialExcess", settlement.excessPayout()); result.put("oldMaterialPayoutMethod", payoutMethod); result.put("approvalRequired", status == 3); if (approvalId != null) result.put("approvalId", approvalId);
        return ApiResponse.ok(result);
    }
    private String validatePayoutMethod(String requested, BigDecimal excess, HttpServletRequest r) {
        if (excess.signum() <= 0) return null;
        String method = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (method.isBlank() || "BALANCE".equals(method) || "COMBINATION".equals(method) || com.dajin.system.pay.PaymentChannelPolicy.isGroupChannel(method))
            throw new BusinessException(400107, "请选择现金、微信、支付宝或银行卡作为超额旧金返款方式");
        Integer count = db.jdbc().queryForObject("select count(*) from pay_channel where store_id=:s and channel_code=:code and status=1",
                Map.of("s", db.store(r), "code", method), Integer.class);
        if (count == null || count == 0) throw new BusinessException(400107, "超额旧金返款方式未启用");
        return method;
    }
    private BigDecimal configDecimal(HttpServletRequest r, String k) { String v = db.jdbc().queryForObject("select config_value from sys_config where store_id=:s and config_key=:k and enabled=1", Map.of("s", db.store(r), "k", k), String.class); if (v == null) throw new BusinessException(500101, "缺少系统配置: " + k); return new BigDecimal(v); }
    void validateStockAvailability(Item item, HttpServletRequest request) {
        if (item.goodsId() == null) return;
        validateStockAvailability(item.goodsId(), item.qty(), request, false);
    }
    void validateStockAvailability(List<Item> items, HttpServletRequest request) {
        Map<Long,Integer> requested = new TreeMap<>();
        for (Item item : items) {
            if (item.goodsId() != null) requested.merge(item.goodsId(), item.qty(), Integer::sum);
        }
        for (Map.Entry<Long,Integer> entry : requested.entrySet()) {
            validateStockAvailability(entry.getKey(), entry.getValue(), request, true);
        }
    }
    private void validateStockAvailability(Long goodsId, Integer qty, HttpServletRequest request, boolean lock) {
        Map<String,Object> goods;
        try {
            goods = db.one("select stock,status from goods where goods_id=:g and store_id=:s" + (lock ? " for update" : ""),
                    Map.of("g", goodsId, "s", db.store(request)));
        } catch (Exception e) {
            throw new BusinessException(404002, "商品不存在或不属于当前门店");
        }
        if (((Number) goods.getOrDefault("status", 0)).intValue() != 1)
            throw new BusinessException(409104, "商品未上架销售，请先在商品管理中上架");
        BigDecimal stock = new BigDecimal(String.valueOf(goods.getOrDefault("stock", 0)));
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(request)).addValue("g", goodsId);
        BigDecimal reserved = db.jdbc().queryForObject(
                "select coalesce(sum(soi.qty),0) from sales_order_item soi join sales_order so on so.order_id=soi.order_id and so.store_id=soi.store_id where soi.store_id=:s and soi.goods_id=:g and so.status in (0,3)",
                p, BigDecimal.class);
        BigDecimal available = stock.subtract(reserved == null ? BigDecimal.ZERO : reserved);
        if (available.compareTo(BigDecimal.valueOf(qty)) < 0)
            throw new BusinessException(409103, "商品可售库存不足，已有待收款订单占用，请先收款或取消原订单: " + goodsId);
    }
    private void validatePieces(Item item, Set<String> requested, HttpServletRequest request) {
        if (item.pieceNos() == null || item.pieceNos().isEmpty()) return;
        if (item.goodsId() == null || item.qty() != item.pieceNos().size()) throw new BusinessException(400112, "销售数量必须与单件码数量一致");
        for (String raw : item.pieceNos()) {
            String pieceNo = raw == null ? "" : raw.trim();
            if (pieceNo.isBlank() || pieceNo.length() > 80) throw new BusinessException(400112, "单件码不正确");
            if (!requested.add(pieceNo)) throw new BusinessException(409112, "订单中单件码重复: " + pieceNo);
            Integer count = db.jdbc().queryForObject("select count(*) from goods_piece where store_id=:s and goods_id=:g and piece_no=:pieceNo and status=1",
                    new MapSqlParameterSource().addValue("s", db.store(request)).addValue("g", item.goodsId()).addValue("pieceNo", pieceNo), Integer.class);
            if (count == null || count == 0) throw new BusinessException(409113, "单件码不在库或不属于该商品: " + pieceNo);
        }
    }
    private String pieceNosJson(List<String> pieceNos) {
        try { return objectMapper.writeValueAsString(pieceNos == null ? List.of() : pieceNos.stream().map(String::trim).toList()); }
        catch (Exception e) { throw new BusinessException(400112, "单件码格式不正确"); }
    }
    void reservePieces(long orderId, List<Item> items, HttpServletRequest request) {
        long storeId = db.store(request);
        for (Item item : items) {
            if (item.goodsId() == null) continue;
            List<String> pieceNos = item.pieceNos() == null ? List.of() : item.pieceNos().stream().map(String::trim).toList();
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("g", item.goodsId()).addValue("o", orderId);
            if (!pieceNos.isEmpty()) {
                int changed = db.jdbc().update("update goods_piece set status=2,sales_order_id=:o,update_time=now() where store_id=:s and goods_id=:g and status=1 and piece_no in (:pieceNos)", p.addValue("pieceNos", pieceNos));
                if (changed != pieceNos.size()) throw new BusinessException(409115, "所选单件码已被其他待收款订单占用，请重新扫码");
            } else if (item.qty() > 0) {
                Integer trackedPieces = db.jdbc().queryForObject(
                        "select count(*) from goods_piece where store_id=:s and goods_id=:g", p, Integer.class);
                int changed = db.jdbc().update("update goods_piece set status=2,sales_order_id=:o,update_time=now() where piece_id in (select piece_id from (select piece_id from goods_piece where store_id=:s and goods_id=:g and status=1 order by piece_id limit :pieceLimit) t)", p.addValue("pieceLimit", item.qty()));
                if (trackedPieces != null && trackedPieces > 0 && changed != item.qty())
                    throw new BusinessException(409115, "单件库存记录与商品库存不一致，请先核对库存后再开单");
            }
        }
    }
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable long id, HttpServletRequest r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("s", db.store(r));
        Map<String,Object> order = db.one("select o.*, cashier.real_name cashier_name, sales.real_name sales_name "
                + "from sales_order o "
                + "left join sys_user cashier on cashier.user_id=o.cashier_id and cashier.store_id=o.store_id "
                + "left join sys_user sales on sales.user_id=o.sales_id and sales.store_id=o.store_id "
                + "where o.order_id=:id and o.store_id=:s", p);
        List<Map<String,Object>> payments = order == null ? List.of() : db.list(
                "select pay_method,case when type='EXPENSE' then -amount else amount end as amount,type,category,create_time from finance_record where store_id=:s and related_bill_no=:no order by finance_id",
                new MapSqlParameterSource().addValue("s", db.store(r)).addValue("no", order.get("order_no")));
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("order", order);
        result.put("items", db.list("select * from sales_order_item where order_id=:id and store_id=:s", p));
        result.put("oldMaterials", db.list("select * from old_material where store_id=:s and source=concat('ORDER:',:id) order by material_id", p));
        result.put("payments", payments);
        return ApiResponse.ok(result);
    }
    @GetMapping("/list") public ApiResponse<?> list(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size, @RequestParam(required = false) Integer status, @RequestParam(required = false) Integer handover, HttpServletRequest r) { MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r)).addValue("st", status).addValue("ho", handover).addValue("limit", size).addValue("off", (page - 1) * size); return ApiResponse.ok(db.list("select * from sales_order where store_id=:s and (:st is null or status=:st) and (:ho is null or handover=:ho) order by order_id desc limit :limit offset :off", p)); }
    @PostMapping("/{id}/cancel")
    @RequirePermission(value = {"order:create", "order:checkout"}, anyOf = true)
    @Transactional
    public ApiResponse<?> cancel(@PathVariable long id, @RequestBody(required = false) Map<String,Object> body, HttpServletRequest r) {
        long storeId = db.store(r);
        List<Map<String,Object>> rows = db.list("select order_id,order_no,status from sales_order where order_id=:id and store_id=:s for update", Map.of("id", id, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(404108, "销售订单不存在");
        Map<String,Object> order = rows.get(0);
        int status = ((Number) order.get("status")).intValue();
        if (status == 4) return ApiResponse.ok(Map.of("orderId", id, "status", 4, "idempotentReplay", true));
        if (status != 0 && status != 3) throw new BusinessException(409109, "只有待收款或待审批订单可以取消");
        db.jdbc().update("update sales_order set status=4,remark=case when :reason='' then remark else concat(coalesce(remark,''),case when remark is null or remark='' then '' else '；' end,'取消原因：',:reason) end,update_time=now(),version=version+1 where order_id=:id and store_id=:s and status in (0,3)",
                new MapSqlParameterSource().addValue("id", id).addValue("s", storeId).addValue("reason", body == null ? "" : String.valueOf(body.getOrDefault("reason", "")).trim()));
        db.jdbc().update("update goods_piece set status=1,sales_order_id=null,update_time=now() where store_id=:s and sales_order_id=:id and status=2", Map.of("s", storeId, "id", id));
        db.jdbc().update("delete from old_material where store_id=:s and source=concat('ORDER:',:id) and status=0", Map.of("s", storeId, "id", id));
        db.jdbc().update("update approval set status=4,approver_id=:uid,approve_remark='订单已取消',approve_time=now() where store_id=:s and type='DISCOUNT' and biz_id=:id and status=1",
                new MapSqlParameterSource().addValue("s", storeId).addValue("id", id).addValue("uid", userId(r)));
        Map<String,Object> event = Map.of("storeId", storeId, "orderId", id, "orderNo", order.get("order_no"), "action", "CANCEL");
        ws.broadcast("ORDER_UPDATED", event);
        ws.broadcast("STOCK_UPDATED", event);
        return ApiResponse.ok(Map.of("orderId", id, "status", 4));
    }

    @PostMapping("/cancel-by-client")
    @RequirePermission(value = {"order:create", "order:checkout"}, anyOf = true)
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ApiResponse<?> cancelByClient(@RequestBody Map<String,Object> body, HttpServletRequest r) {
        String client = String.valueOf(body.getOrDefault("orderClientRequestId", "")).trim();
        if (client.isBlank() || client.length() > 64) throw new BusinessException(400101, "本地订单编号不合法");
        lockClientRequest(client, r);
        List<Map<String,Object>> orders = db.list("select order_id from sales_order where store_id=:s and client_request_id=:c for update", Map.of("s", db.store(r), "c", client));
        if (!orders.isEmpty()) cancel(((Number) orders.get(0).get("order_id")).longValue(), body, r);
        db.jdbc().update("update operation_log set action='CANCELLED' where store_id=:s and module='ORDER_REQUEST' and client_request_id=:c", Map.of("s", db.store(r), "c", client));
        return ApiResponse.ok(Map.of("cancelled", true));
    }

    private String lockClientRequest(String client, HttpServletRequest r) {
        Map<String,Object> p = Map.of("s", db.store(r), "c", client, "uid", userId(r));
        db.jdbc().update("insert ignore into operation_log(store_id,user_id,module,action,content,client_request_id,create_time) values(:s,:uid,'ORDER_REQUEST','OPEN','',:c,now())", p);
        return db.jdbc().queryForObject("select action from operation_log where store_id=:s and module='ORDER_REQUEST' and client_request_id=:c for update", p, String.class);
    }
    @PostMapping("/refund") @RequirePermission("order:refund")
    public ApiResponse<?> refund(@RequestBody Map<String, Object> body, HttpServletRequest r) {
        long id = ((Number) body.get("orderId")).longValue();
        long storeId = db.store(r);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", id).addValue("s", storeId)
                .addValue("uid", userId(r)).addValue("reason", body.getOrDefault("reason", "退单申请"));
        int changed = db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) select store_id,'REFUND',order_id,:uid,pay_amount,:reason,1,now() from sales_order where order_id=:id and store_id=:s", p);
        if (changed == 0) throw new BusinessException(404108, "销售订单不存在");
        long approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type='REFUND' and biz_id=:id order by approval_id desc limit 1", p, Long.class);
        ws.broadcast("APPROVAL_CREATED", Map.of("storeId", storeId, "id", approvalId, "approvalId", approvalId, "type", "REFUND", "bizId", id));
        return ApiResponse.ok(Map.of("approvalId", approvalId));
    }
    private BigDecimal oldMaterialDeduct(List<OldMaterialItem> materials, BigDecimal requested, HttpServletRequest r) {
        if (materials == null || materials.isEmpty()) {
            if (requested != null && requested.signum() != 0) throw new BusinessException(400113, "旧料抵扣必须提供实收旧料明细");
            return BigDecimal.ZERO;
        }
        BigDecimal recyclePrice = db.jdbc().queryForObject("select price from gold_price where store_id=:s and price_type='回收金价' order by date desc,price_id desc limit 1", Map.of("s", db.store(r)), BigDecimal.class);
        return materials.stream().map(item -> {
            if (item.weight() == null || item.purity() == null || item.weight().signum() <= 0
                    || item.purity().signum() <= 0 || item.purity().compareTo(BigDecimal.ONE) > 0)
                throw new BusinessException(400103, "旧料克重和成色必须大于0且成色不超过100%");
            return item.weight().multiply(item.purity()).multiply(recyclePrice);
        }).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** Returns the total cost for one order line, independent of later goods edits. */
    private BigDecimal costSnapshot(Item item, HttpServletRequest r) {
        if (item.goodsId() == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> goods;
        try {
            goods = db.one("select status,price_type,cost_price,weight from goods where goods_id=:g and store_id=:s", Map.of("g", item.goodsId(), "s", db.store(r)));
        } catch (Exception e) {
            throw new BusinessException(404002, "商品不存在或不属于当前门店");
        }
        if (((Number) goods.getOrDefault("status", 0)).intValue() != 1)
            throw new BusinessException(409104, "商品未上架销售，请先在商品管理中上架");
        BigDecimal unitCost = goods.get("cost_price") == null ? BigDecimal.ZERO : new BigDecimal(goods.get("cost_price").toString());
        BigDecimal qty = BigDecimal.valueOf(item.qty() == null ? 1 : item.qty());
        int priceType = ((Number) goods.getOrDefault("price_type", 2)).intValue();
        if (priceType == 1) {
            BigDecimal weight = item.weight() != null ? item.weight() : (goods.get("weight") == null ? BigDecimal.ZERO : new BigDecimal(goods.get("weight").toString()));
            return unitCost.multiply(weight).multiply(qty).setScale(2, RoundingMode.HALF_UP);
        }
        return unitCost.multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }
    private void insertPendingOldMaterials(long orderId, List<OldMaterialItem> materials, HttpServletRequest r) {
        if (materials == null || materials.isEmpty()) return;
        BigDecimal recyclePrice = db.jdbc().queryForObject("select price from gold_price where store_id=:s and price_type='回收金价' order by date desc,price_id desc limit 1", Map.of("s", db.store(r)), BigDecimal.class);
        for (OldMaterialItem item : materials) {
            BigDecimal value = item.weight().multiply(item.purity()).multiply(recyclePrice).setScale(2, RoundingMode.HALF_UP);
            db.jdbc().update("insert into old_material(store_id,material_type,weight,purity,source,value,status,create_time,update_time) values(:s,:type,:weight,:purity,:source,:value,0,now(),now())", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("type", item.materialType() == null ? "旧金抵扣" : item.materialType()).addValue("weight", item.weight()).addValue("purity", item.purity()).addValue("source", "ORDER:" + orderId).addValue("value", value));
        }
    }
    private long userId(HttpServletRequest r) { io.jsonwebtoken.Claims c=(io.jsonwebtoken.Claims)r.getAttribute("claims"); return c == null ? 0L : Long.parseLong(c.getSubject()); }

    /** 销售单据（热敏小票版式）：移动端预览 + 收银端待打印共用同一 HTML。 */
    @GetMapping(value = "/{id}/receipt", produces = "text/html;charset=UTF-8")
    public String receipt(@PathVariable long id, @RequestParam(required = false) Boolean preview, HttpServletRequest r) {
        boolean previewMode = Boolean.TRUE.equals(preview);
        long storeId = db.store(r);
        Map<String, Object> detail;
        try {
            @SuppressWarnings("rawtypes") ApiResponse raw = detail(id, r);
            detail = (Map<String, Object>) raw.data();
        } catch (Exception e) { throw new BusinessException(404801, "订单不存在"); }
        @SuppressWarnings("unchecked") Map<String, Object> o = (Map<String, Object>) detail.get("order");
        @SuppressWarnings("unchecked") List<Map<String, Object>> items = (List<Map<String, Object>>) detail.get("items");
        @SuppressWarnings("unchecked") List<Map<String, Object>> payments = (List<Map<String, Object>>) detail.get("payments");
        String storeName = String.valueOf(db.one("select store_name from sys_store where store_id=:s", Map.of("s", storeId)).getOrDefault("store_name", "-"));
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>销售单据 ").append(escHtml(String.valueOf(o.get("order_no")))).append("</title><style>body{font:13px/1.55 'Microsoft YaHei',monospace;color:#111;margin:0;padding:14px}.ticket{max-width:320px;margin:0 auto;background:#fff;padding:14px 12px}.ticket h1{font-size:16px;text-align:center;margin:0 0 2px}.ticket .sub{text-align:center;font-size:12px;margin:0 0 8px}.ticket .meta{font-size:12px;margin-bottom:6px}.ticket .meta div{display:flex;justify-content:space-between}.hr{border-top:1px dashed #666;margin:8px 0}.it{margin:4px 0;font-size:12px}.it .l1{display:flex;justify-content:space-between}.it .l2{color:#555}.sum{font-size:12px}.sum div{display:flex;justify-content:space-between;margin:2px 0}.sum .big{font-size:15px;font-weight:700}.foot{text-align:center;font-size:11px;color:#555;margin-top:10px}.print-btn{display:block;width:min(100%,280px);margin:0 auto 12px;padding:13px;border:0;border-radius:10px;background:#b8812f;color:#fff;font-size:16px;font-weight:700;cursor:pointer}@media print{.noprint{display:none}.ticket{max-width:none}}</style></head><body>")
          .append(previewMode ? "<div class=\"noprint\" style=\"max-width:320px;margin:0 auto 12px;padding:12px;border-radius:10px;background:#fff4df;border:1px solid #efd39d;font-size:14px;font-weight:700;text-align:center\">📱 预览模式 · 确认无误后点下方按钮送收银端打印</div><button id=\"confirmPrintBtn\" class=\"print-btn noprint\" onclick=\"confirmPrint()\">✅ 确认送打印</button><p id=\"printStatus\" class=\"noprint\" style=\"text-align:center;color:#555;font-size:12px;margin:6px 0 0\"></p><script>function confirmPrint(){var b=document.getElementById('confirmPrintBtn');b.disabled=true;b.textContent='正在发送…';try{window.opener.postMessage({type:'DAJIN_CONFIRM_PRINT_SALE',orderId:" + id + "},'*')}catch(e){b.disabled=false;b.textContent='✅ 确认送打印';document.getElementById('printStatus').textContent='发送失败，请回到订单页重试'}}window.addEventListener('message',function(ev){var d=ev.data||{};if(d.type==='DAJIN_PRINT_SENT'){document.getElementById('confirmPrintBtn').textContent='✓ 已发送到收银端待打印';document.getElementById('printStatus').textContent='请到电脑收银端「待打印」确认打印';setTimeout(function(){try{window.close()}catch(e){}},2500)}else if(d.type==='DAJIN_PRINT_FAIL'){var b=document.getElementById('confirmPrintBtn');b.disabled=false;b.textContent='✅ 确认送打印';document.getElementById('printStatus').textContent=d.message||'发送失败，请重试'}});</script>" : "<button class=\"print-btn noprint\" onclick=\"window.print()\">🖨 打印本单据</button>");
        h.append("<div class=\"ticket\"><h1>").append(escHtml(storeName)).append("</h1><p class=\"sub\">销售单据</p><div class=\"meta\">");
        h.append("<div><span>单号</span><span>").append(escHtml(String.valueOf(o.get("order_no")))).append("</span></div>");
        h.append("<div><span>时间</span><span>").append(escHtml(String.valueOf(o.getOrDefault("create_time", "-")).replace('T', ' '))).append("</span></div>");
        Object memberIdObj = o.get("member_id");
        if (memberIdObj instanceof Number) {
            List<Map<String, Object>> mem = db.list("select name,phone from member where member_id=:m and store_id=:s", Map.of("m", ((Number) memberIdObj).longValue(), "s", storeId));
            if (!mem.isEmpty()) h.append("<div><span>会员</span><span>").append(escHtml(String.valueOf(mem.get(0).get("name")))).append(" ").append(escHtml(String.valueOf(mem.get(0).getOrDefault("phone", "")))).append("</span></div>");
        }
        h.append("</div><div class=\"hr\"></div>");
        BigDecimal goodsTotal = BigDecimal.ZERO;
        for (Map<String, Object> it : items) {
            BigDecimal qty = it.get("qty") == null ? BigDecimal.ONE : new BigDecimal(String.valueOf(it.get("qty")));
            BigDecimal sub = it.get("subtotal") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(it.get("subtotal")));
            BigDecimal labor = it.get("labor_fee") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(it.get("labor_fee")));
            goodsTotal = goodsTotal.add(sub).add(labor.multiply(qty));
            String weightLine = it.get("weight") == null ? "按件计价" : new BigDecimal(String.valueOf(it.get("weight"))).stripTrailingZeros().toPlainString() + "g × ¥" + new BigDecimal(String.valueOf(it.getOrDefault("unit_price", "0"))).stripTrailingZeros().toPlainString();
            h.append("<div class=\"it\"><div class=\"l1\"><span>").append(escHtml(String.valueOf(it.get("item_name")))).append(" ×").append(qty.stripTrailingZeros().toPlainString()).append("</span><span>¥").append(sub.add(labor.multiply(qty))).append("</span></div><div class=\"l2\">").append(escHtml(weightLine)).append(labor.signum() > 0 ? " + 工费¥" + labor : "").append("</div></div>");
        }
        h.append("<div class=\"hr\"></div><div class=\"sum\">");
        h.append("<div><span>商品合计</span><span>¥").append(goodsTotal).append("</span></div>");
        BigDecimal deduct = o.get("old_material_deduct") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(o.get("old_material_deduct")));
        BigDecimal excess = o.get("old_material_excess") == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(o.get("old_material_excess")));
        if (deduct.signum() > 0 || excess.signum() > 0) h.append("<div><span>旧金估值</span><span>¥").append(deduct.add(excess)).append("</span></div>");
        if (deduct.signum() > 0) h.append("<div><span>本单抵扣</span><span>-¥").append(deduct).append("</span></div>");
        if (excess.signum() > 0) h.append("<div><span>回收返款</span><span>¥").append(excess).append(" · ").append(payMethodCn(String.valueOf(o.get("old_material_payout_method")))).append("</span></div>");
        BigDecimal discount = o.get("discount") == null ? BigDecimal.ONE : new BigDecimal(String.valueOf(o.get("discount")));
        if (discount.compareTo(BigDecimal.ONE) != 0) h.append("<div><span>折扣</span><span>").append(discount.multiply(new BigDecimal("10")).stripTrailingZeros().toPlainString()).append("折</span></div>");
        h.append("<div class=\"big\"><span>应收</span><span>¥").append(o.getOrDefault("pay_amount", "0")).append("</span></div>");
        if (payments != null && !payments.isEmpty()) { StringBuilder pm = new StringBuilder(); for (Map<String, Object> pay : payments) { if (!"INCOME".equals(String.valueOf(pay.get("type")))) continue; if (pm.length() > 0) pm.append(" + "); pm.append(payMethodCn(String.valueOf(pay.get("pay_method")))).append(" ¥").append(pay.get("amount")); } if (pm.length() > 0) h.append("<div><span>支付</span><span>").append(escHtml(pm.toString())).append("</span></div>"); }
        h.append("</div><div class=\"hr\"></div><p class=\"foot\">金饰享门店质保 · 免费清洗抛光<br>谢谢惠顾</p></div></body></html>");
        return h.toString();
    }

    private String payMethodCn(String code) { return switch (code == null ? "" : code) { case "CASH" -> "现金"; case "WECHAT" -> "微信"; case "ALIPAY" -> "支付宝"; case "BANK" -> "银行卡"; case "BALANCE" -> "储值"; default -> code; }; }

    private String escHtml(String s) { return s == null ? "-" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** 移动端送销售单据打印：进入收银端「待打印」队列。 */
    @PostMapping("/{id}/print-request")
    @Transactional
    public ApiResponse<?> salesPrintRequest(@PathVariable long id, HttpServletRequest r) {
        long storeId = db.store(r);
        Map<String, Object> o = db.one("select o.order_no,m.name member_name,m.phone member_phone from sales_order o left join member m on m.member_id=o.member_id and m.store_id=o.store_id where o.order_id=:id and o.store_id=:s", Map.of("id", id, "s", storeId));
        List<Map<String, Object>> existing = db.list("select job_id from print_job where store_id=:s and order_id=:oid and job_type='SALES' and status='PENDING' limit 1", Map.of("s", storeId, "oid", id));
        if (!existing.isEmpty()) return ApiResponse.ok(Map.of("sent", true, "jobId", existing.get(0).get("job_id"), "duplicate", true));
        db.jdbc().update("insert into print_job(store_id,order_id,order_no,customer_name,customer_phone,job_type,status,created_by,create_time) values(:s,:oid,:no,:n,:p,'SALES','PENDING',:u,now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("oid", id).addValue("no", String.valueOf(o.get("order_no")))
                        .addValue("n", o.get("member_name") == null ? "" : String.valueOf(o.get("member_name")))
                        .addValue("p", o.get("member_phone") == null ? "" : String.valueOf(o.get("member_phone")))
                        .addValue("u", userId(r)));
        ws.broadcast("PRINT_JOB_NEW", Map.of("storeId", storeId, "orderNo", String.valueOf(o.get("order_no"))));
        return ApiResponse.ok(Map.of("sent", true));
    }
}
