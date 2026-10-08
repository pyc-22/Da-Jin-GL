package com.dajin.system.processing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.gold.GoldMarketService;
import com.dajin.system.order.SalesOrderWithdrawalService;
import com.dajin.system.pay.PaymentChannelPolicy;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Processing-project, order, payment and craftsman-commission endpoints. */
@RestController
@RequestMapping("/api/processing")
@RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
public class ProcessingController {
    private static final Set<String> ORDER_STATUSES = Set.of("PENDING", "PROCESSING", "COMPLETED", "PICKED_UP");
    private static final Set<String> PAYMENT_TYPES = Set.of("DEPOSIT", "BALANCE");
    private static final Set<String> HANDLINGS = Set.of("TAKE_AWAY", "STORE_DEDUCT");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final ShiftService shifts;
    private final OldMaterialLedgerService oldMaterialLedger;
    private final GoldMarketService market;

    public ProcessingController(DbSupport db, SyncWebSocketHandler ws, ShiftService shifts,
                                OldMaterialLedgerService oldMaterialLedger) {
        this(db, ws, shifts, oldMaterialLedger, null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public ProcessingController(DbSupport db, SyncWebSocketHandler ws, ShiftService shifts,
                                OldMaterialLedgerService oldMaterialLedger, GoldMarketService market) {
        this.db = db;
        this.ws = ws;
        this.shifts = shifts;
        this.oldMaterialLedger = oldMaterialLedger;
        this.market = market;
    }

    @GetMapping("/categories")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> categories(@RequestParam(required = false) Integer status, HttpServletRequest request) {
        return ApiResponse.ok(db.list("select * from processing_category where store_id=:s and (:status is null or status=:status) order by sort,category_id",
                new MapSqlParameterSource().addValue("s", store(request)).addValue("status", status)));
    }

    @PostMapping("/categories")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> createCategory(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        String name = text(body, "name", "分类名称");
        String code = text(body, "categoryCode", "分类编码").toUpperCase(Locale.ROOT);
        ensureUnique("processing_category", "category_code", code, 0, storeId, "分类编码已存在");
        db.jdbc().update("insert into processing_category(store_id,name,category_code,sort,status,created_by,updated_by,create_time,update_time) values(:s,:n,:c,:sort,:status,:uid,:uid,now(),now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("n", name).addValue("c", code)
                        .addValue("sort", integer(body.get("sort"), 0, "排序")).addValue("status", status(body.get("status")))
                        .addValue("uid", userId(request)));
        log(storeId, userId(request), "CATEGORY_CREATE", "加工分类=" + name);
        processingCatalogUpdated(storeId, "CATEGORY_CREATE", null);
        return ApiResponse.ok();
    }

    @PutMapping("/categories/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> updateCategory(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        requireCategory(id, storeId);
        String name = optionalText(body, "name");
        String code = optionalText(body, "categoryCode");
        if (name != null && name.isBlank()) throw new BusinessException(400701, "分类名称不能为空");
        if (code != null) {
            if (code.isBlank()) throw new BusinessException(400702, "分类编码不能为空");
            code = code.toUpperCase(Locale.ROOT);
            ensureUnique("processing_category", "category_code", code, id, storeId, "分类编码已存在");
        }
        db.jdbc().update("update processing_category set name=coalesce(:n,name),category_code=coalesce(:c,category_code),sort=coalesce(:sort,sort),status=coalesce(:status,status),updated_by=:uid,update_time=now() where category_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("s", storeId).addValue("id", id).addValue("n", name).addValue("c", code)
                        .addValue("sort", body.get("sort")).addValue("status", body.get("status")).addValue("uid", userId(request)));
        log(storeId, userId(request), "CATEGORY_UPDATE", "加工分类ID=" + id);
        processingCatalogUpdated(storeId, "CATEGORY_UPDATE", id);
        return ApiResponse.ok();
    }

    @PatchMapping("/categories/{id}/status")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> categoryStatus(@PathVariable long id, @RequestParam int status, HttpServletRequest request) {
        if (status != 0 && status != 1) throw new BusinessException(400703, "状态参数不合法");
        long storeId = store(request);
        requireCategory(id, storeId);
        db.jdbc().update("update processing_category set status=:status,updated_by=:uid,update_time=now() where category_id=:id and store_id=:s",
                Map.of("status", status, "uid", userId(request), "id", id, "s", storeId));
        log(storeId, userId(request), status == 1 ? "CATEGORY_ENABLE" : "CATEGORY_DISABLE", "加工分类ID=" + id);
        processingCatalogUpdated(storeId, status == 1 ? "CATEGORY_ENABLE" : "CATEGORY_DISABLE", id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/categories/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> deleteCategory(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        requireCategory(id, storeId);
        int items = count("select count(*) from processing_item where store_id=:s and category_id=:id", storeId, id);
        if (items > 0) throw new BusinessException(409701, "该分类有关联加工项目，只能禁用");
        db.jdbc().update("delete from processing_category where category_id=:id and store_id=:s", Map.of("id", id, "s", storeId));
        log(storeId, userId(request), "CATEGORY_DELETE", "加工分类ID=" + id);
        processingCatalogUpdated(storeId, "CATEGORY_DELETE", id);
        return ApiResponse.ok();
    }

    @GetMapping("/items")
    public ApiResponse<?> items(@RequestParam(required = false) Integer status,
                                @RequestParam(required = false) Long categoryId,
                                HttpServletRequest request) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", store(request)).addValue("status", status).addValue("categoryId", categoryId);
        String sql = "select i.*,c.name category_name,c.category_code from processing_item i join processing_category c on c.category_id=i.category_id and c.store_id=i.store_id "
                + "where i.store_id=:s and (:status is null or i.status=:status) and (:categoryId is null or i.category_id=:categoryId) order by c.sort,i.processing_days,i.item_id";
        List<Map<String, Object>> rows = db.list(sql, p);
        rows.forEach(this::enrichSettlementFields);
        return ApiResponse.ok(rows);
    }

    @PostMapping("/items")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> createItem(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        long categoryId = requiredId(body.get("categoryId"), "加工分类");
        requireActiveCategory(categoryId, storeId);
        String name = text(body, "name", "项目名称");
        String code = text(body, "itemCode", "项目编码").toUpperCase(Locale.ROOT);
        ensureUnique("processing_item", "item_code", code, 0, storeId, "项目编码已存在");
        BigDecimal fee = nonNegative(body.get("laborFee"), "工费");
        int days = integer(body.get("processingDays"), 0, "加工周期");
        if (days < 0) throw new BusinessException(400704, "加工周期不能小于0");
        BigDecimal rate = percent(body.get("commissionRate"), "提成比例");
        String pricingUnit = pricingUnit(body.get("pricingUnit"));
        String durationText = trimToEmpty(body.get("durationText"));
        String processSteps = trimToEmpty(body.get("processSteps"));
        db.jdbc().update("insert into processing_item(store_id,category_id,name,item_code,labor_fee,pricing_unit,processing_days,duration_text,process_steps,commission_rate,status,remark,created_by,updated_by,create_time,update_time) values(:s,:category,:n,:code,:fee,:unit,:days,:dur,:steps,:rate,:status,:remark,:uid,:uid,now(),now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("category", categoryId).addValue("n", name).addValue("code", code)
                        .addValue("fee", fee).addValue("unit", pricingUnit).addValue("days", days).addValue("dur", durationText).addValue("steps", processSteps)
                        .addValue("rate", rate).addValue("status", status(body.get("status")))
                        .addValue("remark", optionalText(body, "remark")).addValue("uid", userId(request)));
        log(storeId, userId(request), "ITEM_CREATE", "加工项目=" + name);
        processingCatalogUpdated(storeId, "ITEM_CREATE", null);
        return ApiResponse.ok();
    }

    @PutMapping("/items/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> updateItem(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        requireItem(id, storeId);
        Object categoryValue = body.get("categoryId");
        if (categoryValue != null) requireActiveCategory(requiredId(categoryValue, "加工分类"), storeId);
        String name = optionalText(body, "name");
        if (name != null && name.isBlank()) throw new BusinessException(400705, "项目名称不能为空");
        String code = optionalText(body, "itemCode");
        if (code != null) {
            if (code.isBlank()) throw new BusinessException(400706, "项目编码不能为空");
            code = code.toUpperCase(Locale.ROOT);
            ensureUnique("processing_item", "item_code", code, id, storeId, "项目编码已存在");
        }
        BigDecimal fee = body.containsKey("laborFee") ? nonNegative(body.get("laborFee"), "工费") : null;
        Integer days = body.containsKey("processingDays") ? integer(body.get("processingDays"), 0, "加工周期") : null;
        if (days != null && days < 0) throw new BusinessException(400704, "加工周期不能小于0");
        BigDecimal rate = body.containsKey("commissionRate") ? percent(body.get("commissionRate"), "提成比例") : null;
        String pricingUnit = body.containsKey("pricingUnit") ? pricingUnit(body.get("pricingUnit")) : null;
        String durationText = body.containsKey("durationText") ? trimToEmpty(body.get("durationText")) : null;
        String processSteps = body.containsKey("processSteps") ? trimToEmpty(body.get("processSteps")) : null;
        db.jdbc().update("update processing_item set category_id=coalesce(:category,category_id),name=coalesce(:n,name),item_code=coalesce(:code,item_code),labor_fee=coalesce(:fee,labor_fee),pricing_unit=coalesce(:unit,pricing_unit),processing_days=coalesce(:days,processing_days),duration_text=coalesce(:dur,duration_text),process_steps=coalesce(:steps,process_steps),commission_rate=coalesce(:rate,commission_rate),status=coalesce(:status,status),remark=coalesce(:remark,remark),updated_by=:uid,update_time=now() where item_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("s", storeId).addValue("id", id).addValue("category", categoryValue)
                        .addValue("n", name).addValue("code", code).addValue("fee", fee).addValue("unit", pricingUnit).addValue("days", days)
                        .addValue("dur", durationText).addValue("steps", processSteps).addValue("rate", rate)
                        .addValue("status", body.get("status")).addValue("remark", optionalText(body, "remark")).addValue("uid", userId(request)));
        log(storeId, userId(request), "ITEM_UPDATE", "加工项目ID=" + id);
        processingCatalogUpdated(storeId, "ITEM_UPDATE", id);
        return ApiResponse.ok();
    }

    @PatchMapping("/items/{id}/status")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> itemStatus(@PathVariable long id, @RequestParam int status, HttpServletRequest request) {
        if (status != 0 && status != 1) throw new BusinessException(400703, "状态参数不合法");
        long storeId = store(request);
        requireItem(id, storeId);
        db.jdbc().update("update processing_item set status=:status,updated_by=:uid,update_time=now() where item_id=:id and store_id=:s",
                Map.of("status", status, "uid", userId(request), "id", id, "s", storeId));
        log(storeId, userId(request), status == 1 ? "ITEM_ENABLE" : "ITEM_DISABLE", "加工项目ID=" + id);
        processingCatalogUpdated(storeId, status == 1 ? "ITEM_ENABLE" : "ITEM_DISABLE", id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/items/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> deleteItem(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        requireItem(id, storeId);
        int orders = count("select count(*) from processing_order where store_id=:s and processing_item_id=:id", storeId, id);
        if (orders > 0) throw new BusinessException(409702, "该加工项目已有订单，只能禁用");
        db.jdbc().update("delete from processing_item where item_id=:id and store_id=:s", Map.of("id", id, "s", storeId));
        log(storeId, userId(request), "ITEM_DELETE", "加工项目ID=" + id);
        processingCatalogUpdated(storeId, "ITEM_DELETE", id);
        return ApiResponse.ok();
    }

    @GetMapping("/craftsmen")
    public ApiResponse<?> craftsmen(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select u.user_id,u.username,u.real_name,u.phone,r.role_code,r.role_name from sys_user u "
                + "join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                + "where u.store_id=:s and u.status=1 and r.status=1 and r.role_code='CRAFTSMAN' order by u.real_name,u.user_id", Map.of("s", store(request))));
    }

    /** Lightweight selector data for sales staff; never expose credentials or other staff fields. */
    @GetMapping("/salespeople")
    public ApiResponse<?> salespeople(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select u.user_id,u.real_name,r.role_code from sys_user u "
                + "join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                + "where u.store_id=:s and u.status=1 and r.status=1 and r.role_code='SALES' "
                + "order by u.real_name,u.user_id", Map.of("s", store(request))));
    }

    @PostMapping("/orders")
    @Transactional
    public ApiResponse<?> createOrder(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        // 幂等：收银端离线队列与手机端待同步草稿都会带同一个 clientRequestId 重传，重复请求直接返回原单
        String clientRequestId = body.get("clientRequestId") == null ? "" : String.valueOf(body.get("clientRequestId")).trim();
        if (!clientRequestId.isBlank()) {
            List<Map<String, Object>> replay = db.list("select processing_order_id from processing_order where store_id=:s and client_request_id=:client",
                    Map.of("s", storeId, "client", clientRequestId));
            if (!replay.isEmpty()) return ApiResponse.ok(orderDetail(((Number) replay.get(0).get("processing_order_id")).longValue(), storeId, request));
        }
        long itemId = requiredId(body.get("processingItemId"), "加工项目");
        Map<String, Object> item = activeItem(itemId, storeId);
        String customerName = text(body, "customerName", "客户姓名");
        String customerPhone = text(body, "customerPhone", "客户电话");
        int quantity = integer(body.get("quantity"), 1, "数量");
        if (quantity <= 0) throw new BusinessException(400707, "数量必须大于0");
        BigDecimal unitFee = decimal(item.get("labor_fee"));
        String pricingUnit = String.valueOf(item.getOrDefault("pricing_unit", "按件"));
        // 按克项目：计费总克重等加工完成、知道成品实际克重后柜面再填，开单时允许留空（工费先记 0）
        BigDecimal billingWeight = "按克".equals(pricingUnit) ? optionalDecimal(body.get("billingWeight"), 3) : null;
        if (billingWeight != null && billingWeight.signum() <= 0) billingWeight = null;
        BigDecimal laborFee = unitFee.multiply(billingWeight == null
                ? ("按克".equals(pricingUnit) ? BigDecimal.ZERO : BigDecimal.valueOf(quantity))
                : billingWeight).setScale(2, RoundingMode.HALF_UP);
        BigDecimal storeGoldWeight = optionalDecimal(body.get("storeGoldWeight"), 3);
        if (storeGoldWeight != null && storeGoldWeight.signum() <= 0) storeGoldWeight = null;
        BigDecimal storeGoldFineness = optionalDecimal(body.get("storeGoldFineness"), 4);
        if (storeGoldFineness != null && (storeGoldFineness.signum() < 0 || storeGoldFineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400710, "店供金料成色范围为0到1");
        BigDecimal storeGoldPrice = optionalDecimal(body.get("storeGoldPrice"), 2);
        if (storeGoldPrice != null && storeGoldPrice.signum() < 0) throw new BusinessException(400721, "店供金料金价不能小于0");
        BigDecimal storeGoldAmount = BigDecimal.ZERO;
        Map<String, Object> goldSnapshot = market == null ? Map.of() : market.snapshot(storeId, "足金", storeGoldPrice);
        BigDecimal configuredRetailPrice = decimalValue(goldSnapshot.get("salePrice"));
        if (configuredRetailPrice == null || configuredRetailPrice.signum() <= 0) configuredRetailPrice = retailGoldPrice(storeId);
        if (storeGoldWeight != null) {
            if (storeGoldPrice == null || storeGoldPrice.signum() == 0) storeGoldPrice = configuredRetailPrice;
            if (storeGoldPrice == null || storeGoldPrice.signum() <= 0) throw new BusinessException(400722, "未配置足金零售价，无法计价店供金料，请先在金价管理维护");
            storeGoldAmount = storeGoldWeight.multiply(storeGoldPrice).setScale(2, RoundingMode.HALF_UP);
        }
        // 剩余旧料在前台完成加工时才确认；开单阶段一律不抵扣、不入旧料库存。
        String handling = "TAKE_AWAY";
        BigDecimal residualWeight = null;
        BigDecimal residualFineness = null;
        String materialType = null;
        BigDecimal deduction = BigDecimal.ZERO;
        BigDecimal due = laborFee.add(storeGoldAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        Long craftsman = nullableId(body.get("craftsmanId"));
        requireActiveCraftsman(craftsman, storeId);
        Long memberId = nullableId(body.get("memberId"));
        Long sales = nullableId(body.get("salesId"));
        if (sales == null && memberId != null) sales = optionalActiveSales(memberSales(memberId, storeId), storeId);
        requireActiveSales(sales, storeId);
        Long sourceSalesOrder = nullableId(body.get("sourceSalesOrderId"));
        if (sales == null && sourceSalesOrder != null) sales = optionalActiveSales(sourceOrderSales(sourceSalesOrder, storeId), storeId);
        requireActiveSales(sales, storeId);
        String orderNo = orderNo();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("no", orderNo)
                .addValue("member", memberId).addValue("name", customerName).addValue("phone", customerPhone)
                .addValue("itemId", itemId).addValue("itemName", item.get("name")).addValue("unitFee", unitFee)
                .addValue("commissionRate", decimal(item.get("commission_rate"))).addValue("qty", quantity)
                .addValue("pricingUnit", pricingUnit).addValue("billingWeight", billingWeight)
                .addValue("laborFee", laborFee).addValue("oldWeight", optionalDecimal(body.get("oldGoldWeight"), 3)).addValue("oldFineness", optionalDecimal(body.get("oldGoldFineness"), 4))
                .addValue("materialType", materialType).addValue("residualWeight", residualWeight).addValue("residualFineness", residualFineness)
                .addValue("handling", handling).addValue("deduction", deduction).addValue("due", due).addValue("pickup", body.get("pickupDate"))
                .addValue("sgWeight", storeGoldWeight != null ? storeGoldWeight : BigDecimal.ZERO).addValue("sgFineness", storeGoldFineness).addValue("sgPrice", storeGoldWeight != null && storeGoldPrice != null ? storeGoldPrice : BigDecimal.ZERO).addValue("sgAmount", storeGoldAmount)
                .addValue("goldInstrument", goldSnapshot.get("baseInstrument")).addValue("goldBase", goldSnapshot.get("basePrice")).addValue("goldPurity", goldSnapshot.get("purityCoefficient")).addValue("goldMarkup", goldSnapshot.get("markup")).addValue("goldDeduction", goldSnapshot.get("recycleDeduction")).addValue("goldSnapshot", configuredRetailPrice).addValue("goldQuoteTime", goldSnapshot.get("quoteTime")).addValue("goldSource", goldSnapshot.get("source")).addValue("goldMarketStatus", goldSnapshot.get("marketStatus"))
                .addValue("craftsman", craftsman).addValue("sales", sales).addValue("sourceSalesOrder", sourceSalesOrder).addValue("remark", optionalText(body, "remark")).addValue("uid", userId(request))
                .addValue("clientRef", clientRequestId.isBlank() ? null : clientRequestId);
        db.jdbc().update("insert into processing_order(store_id,order_no,member_id,customer_name,customer_phone,processing_item_id,item_name_snapshot,unit_labor_fee,commission_rate_snapshot,pricing_unit,billing_weight,quantity,labor_fee,old_gold_weight,old_gold_fineness,store_gold_weight,store_gold_fineness,store_gold_price,store_gold_amount,gold_base_instrument,gold_base_price,gold_purity_coefficient,gold_markup,gold_recycle_deduction,gold_price_snapshot,gold_quote_time,gold_quote_source,gold_market_status,residual_material_type,residual_gold_weight,residual_gold_fineness,residual_gold_handling,residual_gold_deduction,due_amount,paid_amount,pickup_date,craftsman_id,sales_id,status,remark,source_sales_order_id,created_by,client_request_id,create_time,update_time) values(:s,:no,:member,:name,:phone,:itemId,:itemName,:unitFee,:commissionRate,:pricingUnit,:billingWeight,:qty,:laborFee,:oldWeight,:oldFineness,:sgWeight,:sgFineness,:sgPrice,:sgAmount,:goldInstrument,:goldBase,:goldPurity,:goldMarkup,:goldDeduction,:goldSnapshot,:goldQuoteTime,:goldSource,:goldMarketStatus,:materialType,:residualWeight,:residualFineness,:handling,:deduction,:due,0,:pickup,:craftsman,:sales,'PENDING',:remark,:sourceSalesOrder,:uid,:clientRef,now(),now())", p);
        long orderId = db.jdbc().queryForObject("select processing_order_id from processing_order where store_id=:s and order_no=:no", p, Long.class);
        if (storeGoldWeight != null) deductGoldMaterial(storeId, orderId, orderNo, storeGoldWeight, userId(request));
        log(storeId, userId(request), "ORDER_CREATE", "加工单=" + orderNo + ",应收=" + due);
        Map<String, Object> result = orderDetail(orderId, storeId, request);
        broadcast("PROCESSING_ORDER_CREATED", processingOrderEvent(result, "CREATE"));
        if (storeGoldWeight != null) broadcast("STOCK_UPDATED", Map.of("storeId", storeId, "processingOrderId", orderId, "action", "PROCESSING_OUT"));
        return ApiResponse.ok(result);
    }

    public ApiResponse<?> orders(String keyword, String status, Long craftsmanId,
                                 Long salesId, Long memberId, String start,
                                 HttpServletRequest request) {
        return orders(keyword, status, craftsmanId, salesId, memberId, start, null, request);
    }

    @GetMapping("/orders")
    @RequirePermission(value = {"processing:view", "order:checkout"}, anyOf = true)
    public ApiResponse<?> orders(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Long craftsmanId,
                                 @RequestParam(required = false) Long salesId,
                                 @RequestParam(required = false) Long memberId,
                                 @RequestParam(required = false) String start,
                                 @RequestParam(required = false) String end,
                                 HttpServletRequest request) {
        boolean sales = isSales(request);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", store(request)).addValue("keyword", keyword == null ? null : "%" + keyword.trim() + "%")
                .addValue("status", blankToNull(status)).addValue("craftsman", craftsmanId).addValue("sales", salesId).addValue("memberId", memberId).addValue("start", blankToNull(start)).addValue("end", blankToNull(end)).addValue("uid", userId(request));
        String sql = "select o.*,m.name member_name,u.real_name craftsman_name,sales.real_name sales_name,creator.real_name creator_name from processing_order o "
                + "left join member m on m.member_id=o.member_id and m.store_id=o.store_id "
                + "left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id "
                + "left join sys_user sales on sales.user_id=o.sales_id and sales.store_id=o.store_id "
                + "left join sys_user creator on creator.user_id=o.created_by and creator.store_id=o.store_id "
                + "where o.store_id=:s and o.status<>'WITHDRAWN' and (:keyword is null or o.order_no like :keyword or o.customer_name like :keyword or o.customer_phone like :keyword) "
                + "and (:status is null or o.status=:status) and (:craftsman is null or o.craftsman_id=:craftsman) and (:sales is null or o.sales_id=:sales) "
                + "and (:memberId is null or o.member_id=:memberId) "
                + (sales ? "and (o.created_by=:uid or o.sales_id=:uid) " : "")
                + "and (:start is null or date(o.create_time)>=:start) and (:end is null or date(o.create_time)<=:end) order by o.processing_order_id desc limit 500";
        return ApiResponse.ok(db.list(sql, p));
    }

    @GetMapping("/orders/{id}")
    @RequirePermission(value = {"processing:view", "order:checkout"}, anyOf = true)
    public ApiResponse<?> detail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> order = orderDetail(id, store(request), request);
        if (isSales(request) && !salesCanView(order, userId(request))) {
            throw new BusinessException(403403, "无权查看该加工订单");
        }
        return ApiResponse.ok(order);
    }

    @PostMapping("/orders/{id}/notify")
    @RequirePermission(value = {"processing:view", "order:checkout"}, anyOf = true)
    public ApiResponse<?> notifyPickup(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = orderDetail(id, storeId, request);
        if (isSales(request) && !salesCanView(order, userId(request))) {
            throw new BusinessException(403403, "无权通知该加工订单");
        }
        Object salesValue = order.get("sales_id");
        Object creatorValue = order.get("created_by");
        long recipient = salesValue instanceof Number ? ((Number) salesValue).longValue()
                : creatorValue instanceof Number ? ((Number) creatorValue).longValue() : userId(request);
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:uid,'NOTIFICATION','PROCESSING_READY',:content,'',now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("uid", recipient)
                        .addValue("content", "加工单 " + order.get("order_no") + " 已完成，可通知客户取货"));
        broadcast("PROCESSING_PICKUP_NOTIFY", processingOrderEvent(order, "NOTIFY_PICKUP"));
        return ApiResponse.ok(Map.of("notified", true));
    }

    /**
     * 手机端把「已取货」的加工单从手机列表里隐藏。
     * 只置 mobile_archived 标记：不删数据、不影响管理端列表与账务/库存/提成记录。
     */
    @PostMapping("/orders/{id}/archive")
    @RequireRoles({"ADMIN", "MANAGER"})
    @Transactional
    public ApiResponse<?> archiveOnMobile(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = lockedOrder(id, storeId);
        if (!"PICKED_UP".equals(String.valueOf(order.get("status")))) {
            throw new BusinessException(409716, "只有已取货的加工单可以从手机端删除");
        }
        db.jdbc().update("update processing_order set mobile_archived=1,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s and status='PICKED_UP'",
                Map.of("id", id, "s", storeId));
        log(storeId, userId(request), "ORDER_MOBILE_ARCHIVE", "加工单=" + order.get("order_no") + ",手机端隐藏（管理端保留记录）");
        return ApiResponse.ok(Map.of("processingOrderId", id, "mobileArchived", true));
    }

    private boolean salesCanView(Map<String, Object> order, long userId) {
        return matchesUser(order.get("created_by"), userId) || matchesUser(order.get("sales_id"), userId);
    }

    private boolean matchesUser(Object value, long userId) {
        return value instanceof Number && ((Number) value).longValue() == userId;
    }

    /** 补金登记（成品反推）：加工中/待取货可登记或修正，按差额退补金料库存，金额并入应收。 */
    @PostMapping("/orders/{id}/store-gold")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> registerStoreGold(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        BigDecimal weight = optionalDecimal(body.get("weight"), 3);
        if (weight == null || weight.signum() <= 0) throw new BusinessException(400724, "补金克重必须大于0");
        BigDecimal fineness = optionalDecimal(body.get("fineness"), 4);
        if (fineness != null && (fineness.signum() < 0 || fineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400710, "店供金料成色范围为0到1");
        Map<String, Object> order = lockedOrder(id, storeId);
        String status = String.valueOf(order.get("status"));
        if ("PICKED_UP".equals(status)) throw new BusinessException(409711, "已取货订单不能再登记补金");
        if ("PENDING".equals(status)) throw new BusinessException(409712, "订单尚未开始加工，请先确认加工再登记补金");
        BigDecimal oldWeight = decimal(order.get("store_gold_weight"));
        BigDecimal oldAmount = decimal(order.get("store_gold_amount"));
        // 下料：店里为保证做工额外加的金料，不计费、不扣库存，只计入损耗率分母
        BigDecimal downMaterial = optionalDecimal(body.get("downMaterialWeight"), 3);
        if (downMaterial != null && downMaterial.signum() < 0) throw new BusinessException(400736, "下料克重不能为负数");
        if (downMaterial == null) downMaterial = decimal(order.get("down_material_weight"));
        BigDecimal price = optionalDecimal(body.get("price"), 2);
        if (price == null || price.signum() <= 0) {
            price = oldWeight.signum() > 0 && decimal(order.get("store_gold_price")).signum() > 0 ? decimal(order.get("store_gold_price")) : retailGoldPrice(storeId);
        }
        if (price == null || price.signum() <= 0) throw new BusinessException(400722, "未配置足金零售价，无法计价补金，请先在金价管理维护");
        Map<String, Object> goldSnapshot = market == null ? Map.of() : market.snapshot(storeId, "足金", price);
        BigDecimal amount = weight.multiply(price).setScale(2, RoundingMode.HALF_UP);
        long operator = userId(request);
        adjustGoldMaterial(storeId, String.valueOf(order.get("order_no")), weight.subtract(oldWeight), operator);
        BigDecimal residualDeduction = decimal(order.get("residual_gold_deduction"));
        BigDecimal grossDue = decimal(order.get("labor_fee")).add(amount).subtract(residualDeduction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = decimal(order.get("paid_amount"));
        BigDecimal promotionDiscount = decimal(order.get("promotion_discount"));
        BigDecimal due = grossDue.subtract(promotionDiscount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal refund = paid.subtract(grossDue).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        db.jdbc().update("update processing_order set store_gold_weight=:w,down_material_weight=:dm,store_gold_fineness=:f,store_gold_price=:p,store_gold_amount=:a,gold_base_instrument=:goldInstrument,gold_base_price=:goldBase,gold_purity_coefficient=:goldPurity,gold_markup=:goldMarkup,gold_recycle_deduction=:goldDeduction,gold_price_snapshot=:goldSnapshot,gold_quote_time=:goldQuoteTime,gold_quote_source=:goldSource,gold_market_status=:goldMarketStatus,due_amount=:due,refund_amount=:refund,refund_paid_amount=least(refund_paid_amount,:refund),original_due_amount=case when promotion_discount>0 then coalesce(original_due_amount,:grossDue) else original_due_amount end,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("w", weight).addValue("dm", downMaterial).addValue("f", fineness).addValue("p", price).addValue("a", amount).addValue("goldInstrument", goldSnapshot.get("baseInstrument")).addValue("goldBase", goldSnapshot.get("basePrice")).addValue("goldPurity", goldSnapshot.get("purityCoefficient")).addValue("goldMarkup", goldSnapshot.get("markup")).addValue("goldDeduction", goldSnapshot.get("recycleDeduction")).addValue("goldSnapshot", price).addValue("goldQuoteTime", goldSnapshot.get("quoteTime")).addValue("goldSource", goldSnapshot.get("source")).addValue("goldMarketStatus", goldSnapshot.get("marketStatus")).addValue("due", due)
                        .addValue("refund", refund).addValue("grossDue", grossDue).addValue("id", id).addValue("s", storeId));
        // 补金或下料变动后，已称重的单按新分母（融后金重 + 下料）重算损耗率，避免考核口径过期
        if (order.get("finished_weight") != null) {
            BigDecimal weightedBase = lossBase(decimal(order.get("melt_weight")), decimal(order.get("old_gold_weight")), decimalValue(order.get("old_gold_fineness")), downMaterial);
            BigDecimal recomputed = dustPermille(decimal(order.get("recovered_weight")), weightedBase);
            boolean recomputedOver = recomputed != null && recomputed.compareTo(lossConfig(storeId)) > 0;
            db.jdbc().update("update processing_order set loss_permille=:lp,loss_over=:lo where processing_order_id=:id and store_id=:s",
                    new MapSqlParameterSource().addValue("lp", recomputed).addValue("lo", recomputedOver ? 1 : 0).addValue("id", id).addValue("s", storeId));
        }
        ensureProcessingRefundApproval(storeId, id, String.valueOf(order.get("order_no")), refund, grossDue, paid, request);
        log(storeId, operator, "ORDER_STORE_GOLD", "加工单=" + order.get("order_no") + ",补金=" + weight + "g,金额=" + amount + ",应收=" + due + ",返款=" + refund);
        Map<String, Object> result = orderDetail(id, storeId, request);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "STORE_GOLD"));
        broadcast("STOCK_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "PROCESSING_ADJUST"));
        return ApiResponse.ok(result);
    }

    /** 成品称重与损耗登记：损耗（打磨屑）计入师傅考核，损耗率 = 损耗 ÷ (融后金重 + 下料)‰，超约定值标预警。 */
    @PostMapping("/orders/{id}/weighing")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> weighing(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        java.math.BigDecimal finishedWeight = optionalDecimal(body.get("finishedWeight"), 3);
        if (finishedWeight == null || finishedWeight.signum() <= 0) throw new BusinessException(400725, "成品实重必须大于0");
        java.math.BigDecimal finishedFineness = optionalDecimal(body.get("finishedFineness"), 4);
        if (finishedFineness != null && (finishedFineness.signum() < 0 || finishedFineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400710, "成品成色范围为0到1");
        java.math.BigDecimal recovered = optionalDecimal(body.get("recoveredWeight"), 3);
        if (recovered != null && recovered.signum() < 0) throw new BusinessException(400726, "损耗克重不能小于0");
        java.math.BigDecimal melted = optionalDecimal(body.get("meltedWeight"), 3);
        if (melted != null && melted.signum() < 0) throw new BusinessException(400727, "融后金重不能小于0");
        java.math.BigDecimal incomingWeight = optionalDecimal(body.get("oldGoldWeight"), 3);
        if (incomingWeight != null && incomingWeight.signum() < 0) throw new BusinessException(400711, "来料克重不能小于0");
        java.math.BigDecimal incomingFineness = optionalDecimal(body.get("oldGoldFineness"), 4);
        if (incomingFineness != null && (incomingFineness.signum() < 0 || incomingFineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400711, "来料成色范围为0到1");
        // 完工计费：按克项目在成品称重时填计费总克重（成品实际克重这时才知道）
        java.math.BigDecimal billingWeight = optionalDecimal(body.get("billingWeight"), 3);
        if (billingWeight != null && billingWeight.signum() <= 0) throw new BusinessException(400724, "计费总克重必须大于0");
        Map<String, Object> order = lockedOrder(id, storeId);
        String status = String.valueOf(order.get("status"));
        if ("PENDING".equals(status)) throw new BusinessException(409715, "加工开始前不能登记称重，请先确认加工");
        if ("PICKED_UP".equals(status)) throw new BusinessException(409711, "已取货订单不能再登记称重");
        // 现场补录来料：必须先写库，再据此核算损耗（否则会拿旧的来料算账）
        if (incomingWeight != null || incomingFineness != null) {
            db.jdbc().update("update processing_order set old_gold_weight=coalesce(:w,old_gold_weight),old_gold_fineness=coalesce(:f,old_gold_fineness),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                    new MapSqlParameterSource().addValue("w", incomingWeight).addValue("f", incomingFineness).addValue("id", id).addValue("s", storeId));
            if (incomingWeight != null) order.put("old_gold_weight", incomingWeight);
            if (incomingFineness != null) order.put("old_gold_fineness", incomingFineness);
        }
        // 损耗率分母：融后金重 + 下料（本次没填融后金重就用库里已有的，再没有才退回「来料克重 × 足金线折算」）
        java.math.BigDecimal meltForBase = melted != null && melted.signum() > 0 ? melted : decimal(order.get("melt_weight"));
        java.math.BigDecimal base = lossBase(meltForBase, decimal(order.get("old_gold_weight")), decimalValue(order.get("old_gold_fineness")), decimal(order.get("down_material_weight")));
        java.math.BigDecimal finishedNet = finishedWeight.multiply(finenessFactor(finishedFineness));
        java.math.BigDecimal recoveredWeight = recovered == null ? BigDecimal.ZERO : recovered;
        // 只考核损耗（打磨屑）；成品比来料+补金重（含称重误差）不再拦截，仅提示核对补金登记
        boolean refillMissing = base.compareTo(finishedNet) < 0;
        // 考核口径：损耗(打磨屑) ÷ (融后金重 + 下料)，见上方 base
        java.math.BigDecimal permille = dustPermille(recoveredWeight, base);
        java.math.BigDecimal config = lossConfig(storeId);
        boolean over = permille != null && permille.compareTo(config) > 0;
        // 按克项目的计费总克重只在成品称重这一步确定；按件单不接受该字段
        if (billingWeight != null && !"按克".equals(String.valueOf(order.getOrDefault("pricing_unit", "按件")))) billingWeight = null;
        java.math.BigDecimal unitLaborFee = decimal(order.get("unit_labor_fee"));
        java.math.BigDecimal billingLaborFee = billingWeight == null ? decimal(order.get("labor_fee")) : unitLaborFee.multiply(billingWeight).setScale(2, RoundingMode.HALF_UP);
        java.math.BigDecimal billingGrossDue = billingLaborFee.add(decimal(order.get("store_gold_amount"))).subtract(decimal(order.get("residual_gold_deduction"))).setScale(2, RoundingMode.HALF_UP);
        java.math.BigDecimal billingPaid = decimal(order.get("paid_amount"));
        java.math.BigDecimal billingPromotion = decimal(order.get("promotion_discount"));
        java.math.BigDecimal billingDue = billingGrossDue.subtract(billingPromotion).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        java.math.BigDecimal billingRefund = billingPaid.subtract(billingGrossDue).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        db.jdbc().update("update processing_order set finished_weight=:w,finished_fineness=:f,melt_weight=:m,recovered_weight=:r,loss_permille=:p,loss_over=:o,loss_note=:n,billing_weight=coalesce(:bw,billing_weight),labor_fee=:lf,due_amount=:due,refund_amount=:refund,refund_paid_amount=least(refund_paid_amount,:refund),original_due_amount=case when promotion_discount>0 then coalesce(original_due_amount,:grossDue) else original_due_amount end,loss_time=now(),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("w", finishedWeight).addValue("f", finishedFineness).addValue("m", melted).addValue("r", recovered)
                        .addValue("p", permille).addValue("o", over ? 1 : 0).addValue("n", optionalText(body, "note"))
                        .addValue("bw", billingWeight).addValue("lf", billingLaborFee).addValue("due", billingDue).addValue("refund", billingRefund).addValue("grossDue", billingGrossDue)
                        .addValue("id", id).addValue("s", storeId));
        if (billingWeight != null) ensureProcessingRefundApproval(storeId, id, String.valueOf(order.get("order_no")), billingRefund, billingGrossDue, billingPaid, request);
        log(storeId, userId(request), "ORDER_WEIGHING", "加工单=" + order.get("order_no") + ",来料=" + order.get("old_gold_weight") + "g,成品=" + finishedWeight + "g,损耗=" + recoveredWeight + "g,损耗率=" + permille + "‰"
                + (billingWeight == null ? "" : ",计费总克重=" + billingWeight + "g,工费=" + billingLaborFee + ",应收=" + billingDue) + (over ? ",超标" : "") + (refillMissing ? ",成品重于来料+补金待核对" : ""));
        Map<String, Object> result = orderDetail(id, storeId, request);
        if (refillMissing) result.put("refillMissing", true);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "WEIGHING"));
        if (over) broadcast("PROCESSING_LOSS_OVER", processingOrderEvent(result, "LOSS_OVER"));
        return ApiResponse.ok(result);
    }

    /** 成色折算系数：≥0.995（足金线）按整克不折；统一走 Fineness，避免各处口径不一致。 */
    private static BigDecimal finenessFactor(BigDecimal fineness) {
        return com.dajin.system.common.Fineness.factor(fineness);
    }

    /**
     * 损耗率分母 = 融后金重 + 下料（甲方口径）。
     * 融后金重是客户来料熔化后的实际金重；没登记时退回按「来料克重 × 足金线折算」兜底。
     * 店供补金不算进分母：补金是卖给客户的，不是考核师傅的投料。
     */
    static BigDecimal lossBase(BigDecimal meltWeight, BigDecimal oldWeight, BigDecimal oldFineness, BigDecimal downMaterialWeight) {
        BigDecimal incoming = meltWeight != null && meltWeight.signum() > 0
                ? meltWeight
                : (oldWeight == null ? BigDecimal.ZERO : oldWeight).multiply(finenessFactor(oldFineness));
        return incoming.add(downMaterialWeight == null ? BigDecimal.ZERO : downMaterialWeight);
    }

    /**
     * 回收屑 = 融后金重 − 成品实重，不足按 0（成品比融后金重还重时不存在回收屑，只有补金或不补金）。
     * 未登记融后金重时按来料折重兜底；店里的补金不参与回收屑。
     */
    static BigDecimal residualDustWeight(BigDecimal meltWeight, BigDecimal oldWeight, BigDecimal oldFineness, BigDecimal finishedWeight) {
        BigDecimal base = meltWeight != null && meltWeight.signum() > 0
                ? meltWeight
                : (oldWeight == null ? BigDecimal.ZERO : oldWeight).multiply(finenessFactor(oldFineness));
        BigDecimal finished = finishedWeight == null ? BigDecimal.ZERO : finishedWeight;
        return base.subtract(finished).max(BigDecimal.ZERO).setScale(3, RoundingMode.HALF_UP);
    }

    /** 回收屑抵扣 = 回收屑 × 足金回收金价。 */
    static BigDecimal residualDustDeduction(BigDecimal dustWeight, BigDecimal recyclePrice) {
        if (dustWeight == null || recyclePrice == null) return BigDecimal.ZERO;
        return dustWeight.multiply(recyclePrice).setScale(2, RoundingMode.HALF_UP);
    }

    /** 损耗率（千分比）= 损耗(打磨屑) ÷ (融后金重 + 下料)。 */
    static BigDecimal dustPermille(BigDecimal recoveredWeight, BigDecimal base) {
        if (base == null || base.signum() <= 0) return null;
        BigDecimal recovered = recoveredWeight == null ? BigDecimal.ZERO : recoveredWeight;
        return recovered.divide(base, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(1000)).setScale(2, RoundingMode.HALF_UP);
    }

    /** 来料、称重、取货照片：URL 合并保存（每类上限 6 张）。 */
    @PostMapping("/orders/{id}/photos")
    @Transactional
    public ApiResponse<?> photos(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) throws com.fasterxml.jackson.core.JsonProcessingException {
        String type = String.valueOf(body.getOrDefault("type", "incoming"));
        if (!Set.of("incoming", "weigh", "pickup").contains(type)) throw new BusinessException(400727, "照片类型不合法");
        Object urlsObj = body.get("urls");
        if (!(urlsObj instanceof List)) throw new BusinessException(400728, "照片列表不合法");
        java.util.function.Predicate<String> validPhoto = u -> u.startsWith("http") || u.startsWith("/api/file/");
        List<String> urls = new ArrayList<>();
        for (Object o : (List<?>) urlsObj) { String u = String.valueOf(o).trim(); if (validPhoto.test(u)) urls.add(u); }
        if (urls.isEmpty()) throw new BusinessException(400728, "没有可保存的照片");
        long storeId = store(request);
        Map<String, Object> order = lockedOrder(id, storeId);
        if ("pickup".equals(type) && !"COMPLETED".equals(String.valueOf(order.get("status"))))
            throw new BusinessException(409715, "只有待取货加工单才能上传取货照片");
        String column = "incoming".equals(type) ? "incoming_photos" : "weigh".equals(type) ? "weigh_photos" : "pickup_photos";
        LinkedHashSet<String> merged = new LinkedHashSet<>(parsePhotoList(order.get(column)));
        merged.addAll(urls);
        if (merged.size() > 6) throw new BusinessException(400729, "每类照片最多 6 张");
        String json = JSON.writeValueAsString(merged);
        db.jdbc().update("update processing_order set " + column + "=:p,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("p", json).addValue("id", id).addValue("s", storeId));
        Map<String,Object> result = orderDetail(id, storeId, request);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "PHOTOS"));
        return ApiResponse.ok(result);
    }

    /** 加工工单/质保凭证 HTML（移动端仅预览；打印统一在收银端确认）。 */
    @GetMapping(value = "/orders/{id}/print", produces = "text/html;charset=UTF-8")
    public String print(@PathVariable long id, @RequestParam(required = false) Boolean preview, HttpServletRequest request) {
        boolean previewMode = Boolean.TRUE.equals(preview);
        long storeId = store(request);
        Map<String, Object> o = orderDetail(id, storeId, request);
        String storeName = String.valueOf(db.one("select store_name from sys_store where store_id=:s", Map.of("s", storeId)).getOrDefault("store_name", "-"));
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>加工工单 ").append(escHtml(String.valueOf(o.get("order_no")))).append("</title><style>body{font:13px/1.6 'Microsoft YaHei',sans-serif;color:#222;margin:0;padding:16px}.sheet{max-width:680px;margin:0 auto;border:1px solid #555;padding:16px}h1{font-size:18px;margin:0 0 4px}.sub{color:#555;font-size:11px;margin-bottom:10px}.meta{display:grid;grid-template-columns:1fr 1fr;gap:6px 16px;border-bottom:1px solid #333;padding-bottom:10px}.meta span{display:block}.meta b{font-weight:600;margin-left:5px}h2{font-size:14px;border-left:4px solid #a66b2c;padding-left:7px;margin:14px 0 8px}.rows{border:1px solid #555}.row{display:grid;grid-template-columns:1fr 1fr;border-bottom:1px solid #bbb;min-height:28px}.row:last-child{border-bottom:0}.row span{background:#f7f4ef;padding:6px 8px}.row b{padding:6px 8px;text-align:right;font-weight:600}.calc{display:grid;grid-template-columns:1fr 1fr;border:1px solid #555}.calc div{padding:8px;border-bottom:1px solid #bbb}.calc .v{text-align:right;font-weight:700}.hand{margin-top:13px;border:1px solid #555}.hand div{display:grid;grid-template-columns:34mm 1fr;min-height:34px;border-bottom:1px solid #bbb}.hand div:last-child{border-bottom:0}.hand span{padding:9px 8px;background:#f7f4ef;font-weight:600}.hand b{border-bottom:1px dashed #777;margin:0 8px}.remark{margin-top:13px;border:1px solid #555;min-height:38px;padding:8px}.sign{margin-top:18px;display:grid;grid-template-columns:1fr 1fr;gap:20px}.sign div{border-bottom:1px solid #333;padding-bottom:4px}.footer{margin-top:14px;display:flex;justify-content:space-between;color:#555;font-size:11px}.print-btn{display:block;width:min(100%,320px);margin:0 auto 12px;padding:13px;border:0;border-radius:10px;background:#b8812f;color:#fff;font-size:16px;font-weight:700;cursor:pointer}.tip{margin-top:10px;color:#555;font-size:11px}@media print{.noprint{display:none}.sheet{border:0;padding:0}}</style></head><body>" + (previewMode ? "<div class=\"noprint\" style=\"max-width:680px;margin:0 auto 12px;padding:12px;border-radius:10px;background:#fff4df;border:1px solid #efd39d;font-size:14px;font-weight:700;text-align:center\">📱 预览模式 · 正式打印请在电脑收银端操作</div>" : "<button class=\"print-btn noprint\" onclick=\"window.print()\">🖨 打印本工单</button>") + "<main class=\"sheet\">");
        h.append("<h1>加工工单 / 质保凭证</h1><div class=\"sub\">请按工单要求完成加工并做好称重记录；取货时请出示本凭证</div><div class=\"meta\">");
        h.append("<span>门店：<b>").append(escHtml(storeName)).append("</b></span><span>工单号：<b>").append(escHtml(String.valueOf(o.get("order_no")))).append("</b></span>");
        h.append("<span>创建时间：<b>").append(escHtml(String.valueOf(o.getOrDefault("create_time", "-")).replace('T', ' '))).append("</b></span><span>预计取货：<b>").append(escHtml(String.valueOf(o.getOrDefault("pickup_date", "-")))).append("</b></span>");
        h.append("<span>客户姓名：<b>").append(escHtml(String.valueOf(o.getOrDefault("customer_name", "-")))).append("</b></span><span>联系电话：<b>").append(escHtml(String.valueOf(o.getOrDefault("customer_phone", "-")))).append("</b></span>");
        String statusCn = switch (String.valueOf(o.get("status"))) { case "PENDING" -> "待加工"; case "PROCESSING" -> "加工中"; case "COMPLETED" -> "待取货"; case "PICKED_UP" -> "已取货"; default -> "-"; };
        h.append("<span>订单状态：<b>").append(statusCn).append("</b></span></div>");
        h.append("<h2>加工信息</h2><div class=\"rows\">");
        h.append("<div class=\"row\"><span>加工项目</span><b>").append(escHtml(String.valueOf(o.getOrDefault("item_name_snapshot", "-")))).append("</b></div>");
        h.append("<div class=\"row\"><span>加工数量</span><b>").append(String.valueOf(o.getOrDefault("quantity", 1))).append(" 件</b></div>");
        h.append("<div class=\"row\"><span>加工师傅</span><b>").append(escHtml(o.get("craftsman_name") == null ? "暂未分配" : String.valueOf(o.get("craftsman_name")))).append("</b></div>");
        h.append("<div class=\"row\"><span>导购（销售）</span><b>").append(escHtml(o.get("sales_name") == null ? "无导购（散客）" : String.valueOf(o.get("sales_name")))).append("</b></div>");
        h.append("<div class=\"row\"><span>回收屑处理</span><b>").append("STORE_DEDUCT".equals(String.valueOf(o.get("residual_gold_handling"))) ? "留店抵扣工费" : "客户带走").append("</b></div></div>");
        BigDecimal printDue = decimal(o.get("due_amount"));
        BigDecimal printPaid = decimal(o.get("paid_amount"));
        BigDecimal printTail = printDue.subtract(printPaid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal printRefund = decimal(o.get("refund_amount"));
        BigDecimal printRefundPaid = decimal(o.get("refund_paid_amount"));
        BigDecimal printRefundOutstanding = printRefund.subtract(printRefundPaid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        h.append("<h2>费用</h2><div class=\"calc\"><div>加工工费</div><div class=\"v\">").append(decimal(o.get("labor_fee")).toPlainString()).append("</div><div>补金金额</div><div class=\"v\">").append(decimal(o.get("store_gold_amount")).signum() > 0 ? decimal(o.get("store_gold_amount")).toPlainString() : "").append("</div><div>回收屑抵扣</div><div class=\"v\">").append(decimal(o.get("residual_gold_deduction")).signum() > 0 ? "-" + decimal(o.get("residual_gold_deduction")).toPlainString() + (decimal(o.get("residual_gold_weight")).signum() > 0 ? "（" + decimal(o.get("residual_gold_weight")).toPlainString() + "g）" : "") : "").append("</div><div>整单应收</div><div class=\"v\">").append(printDue.toPlainString()).append("</div><div>已收定金/收款</div><div class=\"v\">").append(printPaid.toPlainString()).append("</div><div>尾款待收</div><div class=\"v\">").append(printTail.toPlainString()).append("</div><div>客户返款</div><div class=\"v\">").append(printRefund.toPlainString()).append("</div><div>待返款</div><div class=\"v\">").append(printRefundOutstanding.toPlainString()).append("</div></div><p class=\"tip\">完工金额以完成加工登记为准</p>");
        String printIncomingNet = "";
        if (o.get("old_gold_weight") != null) {
            printIncomingNet = decimal(o.get("old_gold_weight")).toPlainString() + "g"
                    + (o.get("old_gold_fineness") == null ? "" : " · " + decimal(o.get("old_gold_fineness")).multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        }
        String printFinished = "";
        if (o.get("finished_weight") != null) {
            printFinished = decimal(o.get("finished_weight")).toPlainString() + "g"
                    + (o.get("finished_fineness") == null ? "" : " · " + decimal(o.get("finished_fineness")).multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        }
        h.append("<h2>称重记录</h2><div class=\"hand\">")
                .append("<div><span>来料</span><b>").append(escHtml(printIncomingNet)).append("</b></div>")
                .append("<div><span>店供补金</span><b>").append(decimal(o.get("store_gold_weight")).signum() > 0 ? escHtml(decimal(o.get("store_gold_weight")).toPlainString() + "g") : "").append("</b></div>")
                .append("<div><span>下料</span><b>").append(decimal(o.get("down_material_weight")).signum() > 0 ? escHtml(decimal(o.get("down_material_weight")).toPlainString() + "g") : "").append("</b></div>")
                .append("<div><span>成品实重</span><b>").append(escHtml(printFinished)).append("</b></div>")
                .append("<div><span>融后金重</span><b>").append(o.get("melt_weight") == null ? "" : escHtml(decimal(o.get("melt_weight")).toPlainString() + "g")).append("</b></div>")
                .append("<div><span>回收屑</span><b>").append(o.get("residual_gold_weight") == null ? "" : escHtml(decimal(o.get("residual_gold_weight")).toPlainString() + "g")).append("</b></div>")
                .append("</div>");
        h.append("<div class=\"remark\"><b>备注：</b>").append(escHtml(String.valueOf(o.getOrDefault("remark", "")))).append("</div>");
        h.append("<div class=\"sign\"><div>加工师傅签字：</div><div>客户取货签字：</div></div>");
        h.append("<div class=\"footer\"><span>打印格式：A4 纵向</span><span>打印时间：").append(escHtml(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))).append("</span></div>");
        h.append("<p class=\"tip noprint\">提示：在浏览器菜单选择「打印」可输出纸质单或保存 PDF。</p>");
        h.append("</main></body></html>");
        // Keep the server-rendered work order on one readable A4 page. The base
        // styles above are shared with the browser preview; these print overrides
        // also apply to the browser print dialog and the cashier Electron window.
        return h.toString().replace("</head>", "<style>" +
                "@page{size:A4 portrait;margin:7mm}" +
                "html,body{margin:0;padding:0}" +
                ".sheet{max-width:196mm;margin:0 auto;padding:7mm;break-inside:avoid;page-break-inside:avoid}" +
                "h2{margin:9px 0 5px}.row{min-height:24px}.row span,.row b{padding:4px 7px}" +
                ".calc div{padding:5px 7px}.hand{margin-top:8px}.hand div{min-height:27px}" +
                ".hand span,.hand b{padding:6px 7px}.remark{margin-top:8px;min-height:30px;padding:6px}" +
                ".sign{margin-top:10px}.footer{margin-top:8px}" +
                "@media print{.sheet{border:0;padding:0;max-width:none;width:100%;break-inside:avoid;page-break-inside:avoid}}" +
                "</style></head>");
    }

    private String escHtml(String s) { return s == null ? "-" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }

    /** 加工质保单（A4 横版双联，与成品质保单同款文案）：取货时打给客户。 */
    @GetMapping(value = "/orders/{id}/warranty", produces = "text/html;charset=UTF-8")
    public String warranty(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> o = orderDetail(id, storeId, request);
        String storeName = String.valueOf(db.one("select store_name from sys_store where store_id=:s", Map.of("s", storeId)).getOrDefault("store_name", "-"));
        return ProcessingWarrantyHtml.build(o, storeName);
    }

    /** 移动端送打印：生成待打印任务，收银端「待打印」确认。 */
    @PostMapping("/orders/{id}/print-request")
    @Transactional
    public ApiResponse<?> printRequest(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> o = lockedOrder(id, storeId);
        Map<String, Object> existing = db.list("select job_id,status from print_job where store_id=:s and order_id=:oid and job_type='PROCESSING' and status='PENDING' order by job_id desc limit 1",
                new MapSqlParameterSource().addValue("s", storeId).addValue("oid", id)).stream().findFirst().orElse(null);
        if (existing != null && !existing.isEmpty()) return ApiResponse.ok(Map.of("sent", true, "jobId", existing.get("job_id"), "duplicate", true));
        db.jdbc().update("insert into print_job(store_id,order_id,order_no,customer_name,customer_phone,job_type,status,created_by,create_time) values(:s,:oid,:no,:n,:p,'PROCESSING','PENDING',:u,now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("oid", id).addValue("no", String.valueOf(o.get("order_no")))
                        .addValue("n", String.valueOf(o.getOrDefault("customer_name", ""))).addValue("p", String.valueOf(o.getOrDefault("customer_phone", ""))).addValue("u", userId(request)));
        log(storeId, userId(request), "PRINT_REQUEST", "加工单=" + o.get("order_no") + " 送收银端打印");
        broadcast("PRINT_JOB_NEW", Map.of("storeId", storeId, "orderNo", String.valueOf(o.get("order_no"))));
        return ApiResponse.ok(Map.of("sent", true));
    }

    /** 移动端把待加工单转交前台：收银端「前台待办」确认后开始加工。 */
    @PostMapping("/orders/{id}/handover")
    @RequireRoles({"ADMIN", "MANAGER", "SALES"})
    @Transactional
    public ApiResponse<?> handover(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = lockedOrder(id, storeId);
        if (!"PENDING".equals(String.valueOf(order.get("status")))) throw new BusinessException(409713, "只有待加工的单子可以转交前台");
        db.jdbc().update("update processing_order set handover=1,handover_time=now(),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s", Map.of("id", id, "s", storeId));
        log(storeId, userId(request), "PROCESSING_HANDOVER", "加工单=" + order.get("order_no") + " 转交前台");
        Map<String, Object> result = orderDetail(id, storeId, request);
        broadcast("PROCESSING_HANDOVER", Map.of("storeId", storeId, "orderId", id, "orderNo", String.valueOf(order.get("order_no"))));
        return ApiResponse.ok(result);
    }

    /** 收银端「前台待办」：所有待加工的加工单（手机转交 + 收银端自开）。 */
    @GetMapping("/handovers")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    public ApiResponse<?> handovers(HttpServletRequest request) {
        long storeId = store(request);
        List<Map<String, Object>> rows = db.list("select o.processing_order_id,o.order_no,o.customer_name,o.customer_phone,o.item_name_snapshot,o.quantity,o.labor_fee,o.store_gold_amount,o.residual_gold_deduction,o.due_amount,o.paid_amount,o.refund_amount,o.refund_paid_amount,o.refund_pay_method,o.refund_approval_id,o.pickup_date,o.handover_time,u.real_name craftsman_name from processing_order o left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id where o.store_id=:s and o.status='PENDING' order by coalesce(o.handover_time,o.create_time) desc limit 50", Map.of("s", storeId));
        rows.forEach(this::enrichSettlementFields);
        return ApiResponse.ok(rows);
    }

    @GetMapping("/print-jobs") @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    public ApiResponse<?> printJobs(@RequestParam(defaultValue = "PENDING") String status, HttpServletRequest r) {
        if (!Set.of("PENDING", "PRINTED", "IGNORED").contains(status)) throw new BusinessException(400731, "打印任务状态不合法");
        return ApiResponse.ok(db.list("select j.*,o.item_name_snapshot,o.quantity from print_job j left join processing_order o on j.job_type='PROCESSING' and o.processing_order_id=j.order_id and o.store_id=j.store_id where j.store_id=:s and j.status=:st order by j.job_id desc limit 50", new MapSqlParameterSource().addValue("s", db.store(r)).addValue("st", status)));
    }

    @PostMapping("/print-jobs/{jobId}/done") @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    public ApiResponse<?> printJobDone(@PathVariable long jobId, HttpServletRequest r) {
        long storeId = store(r);
        int updated = db.jdbc().update("update print_job set status='PRINTED',printed_time=now(),printed_by=:u where job_id=:id and store_id=:s and status='PENDING'", new MapSqlParameterSource().addValue("u", userId(r)).addValue("id", jobId).addValue("s", storeId));
        if (updated == 0) throw new BusinessException(409716, "打印任务不存在或已处理");
        log(storeId, userId(r), "PRINT_JOB_DONE", "打印任务ID=" + jobId);
        broadcast("PRINT_JOB_UPDATED", Map.of("storeId", storeId, "jobId", jobId, "action", "PRINTED"));
        return ApiResponse.ok(Map.of("done", true));
    }

    @PostMapping("/print-jobs/{jobId}/ignore") @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    public ApiResponse<?> printJobIgnore(@PathVariable long jobId, HttpServletRequest r) {
        long storeId = store(r);
        int updated = db.jdbc().update("update print_job set status='IGNORED',ignored_time=now(),ignored_by=:u where job_id=:id and store_id=:s and status='PENDING'", new MapSqlParameterSource().addValue("u", userId(r)).addValue("id", jobId).addValue("s", storeId));
        if (updated == 0) throw new BusinessException(409716, "打印任务不存在或已处理");
        log(storeId, userId(r), "PRINT_JOB_IGNORE", "打印任务ID=" + jobId);
        broadcast("PRINT_JOB_UPDATED", Map.of("storeId", storeId, "jobId", jobId, "action", "IGNORED"));
        return ApiResponse.ok(Map.of("ignored", true));
    }

    /** 约定损耗千分比配置（默认 3‰）。 */
    private java.math.BigDecimal lossConfig(long storeId) {
        try { Map<String, Object> row = db.one("select config_value from sys_config where store_id=:s and config_key='processing_loss_permille' and enabled=1", Map.of("s", storeId)); return new java.math.BigDecimal(String.valueOf(row.get("config_value"))); } catch (Exception e) { return java.math.BigDecimal.valueOf(3); }
    }

    @GetMapping("/loss-config") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> lossConfigGet(HttpServletRequest r) { return ApiResponse.ok(Map.of("permille", lossConfig(db.store(r)))); }

    @PutMapping("/loss-config") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> lossConfigPut(@RequestBody Map<String, Object> body, HttpServletRequest r) {
        java.math.BigDecimal permille = optionalDecimal(body.get("permille"), 2);
        if (permille == null || permille.signum() < 0 || permille.doubleValue() > 100) throw new BusinessException(400730, "约定损耗千分比须在 0 到 100 之间");
        long store = db.store(r);
        db.jdbc().update("update sys_config set config_value=:v,update_time=now() where store_id=:s and config_key='processing_loss_permille'", new MapSqlParameterSource().addValue("v", permille.toPlainString()).addValue("s", store));
        db.jdbc().update("insert ignore into sys_config(store_id,config_key,config_value,enabled) values(:s,'processing_loss_permille',:v,1)", new MapSqlParameterSource().addValue("s", store).addValue("v", permille.toPlainString()));
        broadcast("PROCESSING_LOSS_CONFIG_UPDATED", Map.of("storeId", store, "permille", permille));
        return ApiResponse.ok(Map.of("permille", permille));
    }

    /** 损耗考核：按师傅汇总损耗与超标次数。 */
    @GetMapping("/loss-summary") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> lossSummary(@RequestParam(required = false) String from, @RequestParam(required = false) String to, HttpServletRequest r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r)).addValue("from", from == null || from.isBlank() ? null : from).addValue("to", to == null || to.isBlank() ? null : to);
        String range = " and (:from is null or o.create_time>=:from) and (:to is null or o.create_time<date_add(:to,interval 1 day)) ";
        List<Map<String, Object>> rows = db.list("select coalesce(o.craftsman_id,0) craftsman_id,coalesce(u.real_name,'未指派') name,count(*) order_count," +
                "coalesce(sum(coalesce(o.recovered_weight,0)),0) recovered_weight," +
                "coalesce(sum(o.loss_over),0) over_count," +
                "count(o.finished_weight) weighed_count " +
                "from processing_order o left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id where o.store_id=:s and o.status<>'WITHDRAWN' and o.finished_weight is not null" + range +
                " group by o.craftsman_id,u.real_name order by over_count desc,recovered_weight desc", p);
        return ApiResponse.ok(Map.of("permille", lossConfig(db.store(r)), "rows", rows));
    }

    /** 损耗逐单明细：只统计已称重的单，指标为损耗（打磨屑）与损耗率。 */
    @GetMapping("/loss-orders") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> lossOrders(@RequestParam(required = false) Long craftsmanId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(defaultValue = "false") boolean onlyOver,
                                     HttpServletRequest r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r))
                .addValue("from", from == null || from.isBlank() ? null : from)
                .addValue("to", to == null || to.isBlank() ? null : to);
        StringBuilder where = new StringBuilder(" where o.store_id=:s and o.status<>'WITHDRAWN' and o.finished_weight is not null")
                .append(" and (:from is null or o.create_time>=:from) and (:to is null or o.create_time<date_add(:to,interval 1 day))");
        if (craftsmanId != null) {
            if (craftsmanId == 0) where.append(" and o.craftsman_id is null");
            else { where.append(" and o.craftsman_id=:craftsman"); p.addValue("craftsman", craftsmanId); }
        }
        if (onlyOver) where.append(" and o.loss_over=1");
        List<Map<String, Object>> rows = db.list("select o.processing_order_id,o.order_no,o.item_name_snapshot,o.customer_name," +
                "coalesce(u.real_name,'未指派') craftsman_name,o.old_gold_weight,o.old_gold_fineness," +
                "round(coalesce(o.old_gold_weight,0)*coalesce(o.old_gold_fineness,1),3) incoming_net_weight," +
                "o.store_gold_weight,o.down_material_weight,o.melt_weight,o.finished_weight,o.finished_fineness,o.recovered_weight,o.loss_permille,o.loss_over,o.loss_note,o.loss_time,o.create_time " +
                "from processing_order o left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id" + where + " order by o.loss_time desc,o.processing_order_id desc limit 500", p);
        return ApiResponse.ok(rows);
    }

    @PutMapping("/orders/{id}")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> updateOrder(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = lockedOrder(id, storeId);
        String currentStatus = String.valueOf(order.get("status"));
        boolean pickedUp = "PICKED_UP".equals(currentStatus);
        if (pickedUp && !isAdminOrManager(request)) throw new BusinessException(403705, "已取货订单仅管理员或店长可以改派导购");
        if (!pickedUp && !"PENDING".equals(currentStatus)) throw new BusinessException(409703, "仅待加工订单可编辑");
        String name = optionalText(body, "customerName");
        String phone = optionalText(body, "customerPhone");
        if (name != null && name.isBlank()) throw new BusinessException(400712, "客户姓名不能为空");
        if (phone != null && phone.isBlank()) throw new BusinessException(400713, "客户电话不能为空");
        Long craftsman = nullableId(body.get("craftsmanId"));
        requireActiveCraftsman(craftsman, storeId);
        boolean salesProvided = body.containsKey("salesId");
        Long sales = salesProvided ? nullableId(body.get("salesId")) : null;
        if (sales == null && body.containsKey("memberId")) sales = optionalActiveSales(memberSales(nullableId(body.get("memberId")), storeId), storeId);
        requireActiveSales(sales, storeId);
        Long oldSales = order.get("sales_id") instanceof Number ? ((Number) order.get("sales_id")).longValue() : null;
        boolean salesChanged = salesProvided && !Objects.equals(oldSales, sales);
        if (salesChanged && pickedUp) {
            String reason = optionalText(body, "reason");
            if (reason == null || reason.isBlank()) throw new BusinessException(400733, "取货后改派导购必须填写原因");
        }
        db.jdbc().update("update processing_order set customer_name=coalesce(:name,customer_name),customer_phone=coalesce(:phone,customer_phone),member_id=coalesce(:member,member_id),pickup_date=coalesce(:pickup,pickup_date),craftsman_id=coalesce(:craftsman,craftsman_id),sales_id=case when :salesProvided=1 then :sales else sales_id end,remark=coalesce(:remark,remark),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("s", storeId).addValue("id", id).addValue("name", name).addValue("phone", phone)
                        .addValue("member", nullableId(body.get("memberId"))).addValue("pickup", body.get("pickupDate")).addValue("craftsman", craftsman)
                        .addValue("salesProvided", salesProvided ? 1 : 0).addValue("sales", sales).addValue("remark", optionalText(body, "remark")));
        if (salesChanged) {
            log(storeId, userId(request), "SALES_REASSIGN", "加工单ID=" + id + ",原导购=" + oldSales + ",新导购=" + sales + (pickedUp ? ",原因=" + optionalText(body, "reason") : ""));
            new com.dajin.system.commission.CommissionLedger(db).rebuildForProcessingOrder(storeId, id);
            broadcast("COMMISSION_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "SALES_REASSIGN"));
            broadcast("REPORT_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "SALES_REASSIGN"));
        }
        log(storeId, userId(request), "ORDER_UPDATE", "加工单ID=" + id);
        Map<String, Object> result = orderDetail(id, storeId, request); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "UPDATE")); return ApiResponse.ok(result);
    }

    @PostMapping("/orders/{id}/assign")
    @RequireRoles({"ADMIN", "MANAGER"})
    @Transactional
    public ApiResponse<?> assign(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request); Map<String,Object> order = lockedOrder(id, storeId);
        Long craftsman = body.containsKey("craftsmanId") ? nullableId(body.get("craftsmanId")) : (order.get("craftsman_id") instanceof Number ? ((Number) order.get("craftsman_id")).longValue() : null);
        Long sales = body.containsKey("salesId") ? nullableId(body.get("salesId")) : (order.get("sales_id") instanceof Number ? ((Number) order.get("sales_id")).longValue() : null);
        requireActiveCraftsman(craftsman, storeId);
        requireActiveSales(sales, storeId);
        boolean craftChanged = body.containsKey("craftsmanId") && !Objects.equals(craftsman, numberId(order.get("craftsman_id")));
        boolean salesChanged = body.containsKey("salesId") && !Objects.equals(sales, numberId(order.get("sales_id")));
        String reason = optionalText(body, "reason");
        if (salesChanged && "PICKED_UP".equals(String.valueOf(order.get("status"))) && (reason == null || reason.isBlank()))
            throw new BusinessException(400733, "取货后改派导购必须填写原因");
        if (craftChanged && orderHasPaidCommission(storeId, id)) throw new BusinessException(409718, "提成已发放，不能改派");
        if (craftChanged) db.jdbc().update("update processing_commission set status='CANCELLED',update_time=now() where store_id=:s and processing_order_id=:id and status='PENDING'",
                Map.of("s", storeId, "id", id));
        db.jdbc().update("update processing_order set craftsman_id=:craftsman,sales_id=:sales,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("craftsman", craftsman).addValue("sales", sales).addValue("id", id).addValue("s", storeId));
        if (craftChanged && ("COMPLETED".equals(order.get("status")) || "PICKED_UP".equals(order.get("status")))) {
            Map<String,Object> refreshed = lockedOrder(id, storeId); createCommission(refreshed, storeId);
        }
        if (craftChanged) log(storeId, userId(request), "COMMISSION_CANCEL", "加工单ID=" + id + ",原师傅=" + order.get("craftsman_id") + ",新师傅=" + craftsman);
        if (salesChanged) log(storeId, userId(request), "SALES_REASSIGN", "加工单ID=" + id + ",原导购=" + order.get("sales_id") + ",新导购=" + sales + (reason == null ? "" : ",原因=" + reason));
        if (salesChanged && "PICKED_UP".equals(order.get("status"))) new com.dajin.system.commission.CommissionLedger(db).rebuildForProcessingOrder(storeId, id);
        if (craftChanged || salesChanged) {
            broadcast("COMMISSION_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "ASSIGN"));
            broadcast("REPORT_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "ASSIGN"));
        }
        log(storeId, userId(request), "ORDER_ASSIGN", "加工单ID=" + id + ",师傅=" + craftsman + ",导购=" + sales);
        Map<String, Object> result = orderDetail(id, storeId, request); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "ASSIGN")); return ApiResponse.ok(result);
    }

    @PatchMapping("/orders/{id}/status")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> changeStatus(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String next = text(body, "status", "订单状态").toUpperCase(Locale.ROOT);
        if (!ORDER_STATUSES.contains(next)) throw new BusinessException(400715, "订单状态不合法");
        long storeId = store(request); Map<String, Object> order = lockedOrder(id, storeId); String current = String.valueOf(order.get("status"));
        if (!canTransition(current, next)) throw new BusinessException(409704, "状态只能按待加工、加工中、已完成、已取货顺序流转");
        if ("PICKED_UP".equals(next) && processingTailDue(order).signum() > 0) throw new BusinessException(409705, "加工单尚有尾款未收，不能取货");
        if ("PICKED_UP".equals(next)) ensureProcessingRefundSettled(order, storeId);
        if ("PICKED_UP".equals(next) && parsePhotoList(order.get("pickup_photos")).isEmpty()) throw new BusinessException(409715, "请先上传取货照片，上传成功后才能确认取货");
        if ("COMPLETED".equals(next)) order = applyResidualMaterialOnCompletion(order, body, storeId, request);
        if ("COMPLETED".equals(next)) createCommission(order, storeId);
        if ("PICKED_UP".equals(next) && order.get("sales_id") != null && order.get("sales_commission_rate_snapshot") == null) {
            db.jdbc().update("update processing_order set sales_commission_rate_snapshot=:rate where processing_order_id=:id and store_id=:s and sales_commission_rate_snapshot is null",
                    new MapSqlParameterSource().addValue("rate", new com.dajin.system.commission.CommissionLedger(db).processingRate(storeId)).addValue("id", id).addValue("s", storeId));
        }
        String completedSql = "COMPLETED".equals(next) ? ",completed_time=now()" : "";
        String pickupSql = "PICKED_UP".equals(next) ? ",picked_up_time=now()" : "";
        db.jdbc().update("update processing_order set status=:status,version=version+1,update_time=now()" + completedSql + pickupSql + " where processing_order_id=:id and store_id=:s",
                Map.of("status", next, "id", id, "s", storeId));
        log(storeId, userId(request), "ORDER_STATUS", "加工单ID=" + id + "," + current + "->" + next);
        Map<String, Object> result = orderDetail(id, storeId, request); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "STATUS"));
        Map<String,Object> reportEvent = Map.of("storeId", storeId, "processingOrderId", id, "action", next);
        broadcast("REPORT_UPDATED", reportEvent);
        if (("COMPLETED".equals(next) || "PICKED_UP".equals(next)) && order.get("sales_id") != null) {
            new com.dajin.system.commission.CommissionLedger(db).rebuildForProcessingOrder(storeId, id);
            broadcast("COMMISSION_UPDATED", reportEvent);
        }
        return ApiResponse.ok(result);
    }

    /** Terminates a completed processing order and compensates every related ledger. */
    @PostMapping("/orders/{id}/withdraw")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @RequirePermission("processing:withdraw")
    @Transactional
    public ApiResponse<?> withdraw(@PathVariable long id, @RequestBody(required = false) Map<String,Object> body, HttpServletRequest request) {
        long storeId = store(request);
        Long linkedSalesId = linkedSalesOrderId(id, storeId);
        Map<String,Object> linkedSales = linkedSalesId == null ? null : lockLinkedSalesOrder(linkedSalesId, storeId);
        Map<String,Object> order = lockedOrderForWithdrawal(id, storeId);
        String status = String.valueOf(order.get("status"));
        String orderNo = String.valueOf(order.get("order_no"));
        String withdrawRequestId = "WITHDRAW-PROCESSING-" + id;
        if ("WITHDRAWN".equals(status) || !db.list("select log_id from operation_log where store_id=:s and module='PROCESSING' and action='WITHDRAW' and client_request_id=:client limit 1", Map.of("s",storeId,"client",withdrawRequestId)).isEmpty())
            return ApiResponse.ok(Map.of("processingOrderId", id, "status", "WITHDRAWN", "withdrawn", true, "idempotentReplay", true, "message", "加工单已撤回"));
        Integer expectedVersion = body == null || body.get("version") == null ? null : Integer.valueOf(String.valueOf(body.get("version")));
        int version = number(order.get("version"));
        if (expectedVersion != null && expectedVersion != version) throw new BusinessException(409704, "加工单版本已变化，请刷新后重试");
        if ("PICKED_UP".equals(status)) throw new BusinessException(409706, "已取货加工单不能撤回");
        if (!"COMPLETED".equals(status)) throw new BusinessException(409704, "仅待取货加工单可以撤回");
        if (linkedSales != null) {
            int salesStatus = number(linkedSales.get("status"));
            if (salesStatus != SalesOrderWithdrawalService.WITHDRAWN_STATUS
                    && !new SalesOrderWithdrawalService(db).canWithdraw(linkedSales))
                throw new BusinessException(409109, "关联成品单已处于终态，整组撤回未执行");
        }
        long operator = userId(request);
        List<Map<String,Object>> payments = db.list("select payment_id,payment_type,amount,pay_method,client_request_id from processing_payment where store_id=:s and processing_order_id=:id and payment_type<>'WITHDRAW' order by payment_id", Map.of("s",storeId,"id",id));
        for (Map<String,Object> payment : payments) {
            BigDecimal amount = decimal(payment.get("amount"));
            if (amount.signum() <= 0) continue;
            String method = String.valueOf(payment.get("pay_method"));
            String client = "WITHDRAW-PROCESSING-" + id + "-PAY-" + payment.get("payment_id");
            db.jdbc().update("insert into processing_payment(store_id,processing_order_id,payment_type,amount,pay_method,client_request_id,operator_id,remark,create_time) values(:s,:id,'WITHDRAW',:amount,:method,:client,:uid,'加工单撤回反向流水',now()) on duplicate key update amount=values(amount)",
                    new MapSqlParameterSource().addValue("s",storeId).addValue("id",id).addValue("amount",amount).addValue("method",method).addValue("client",client).addValue("uid",operator));
            if ("BALANCE".equalsIgnoreCase(method) && order.get("member_id") != null) {
                int restored = db.jdbc().update("update member set balance=balance+:amount,update_time=now() where member_id=:m and store_id=:s", new MapSqlParameterSource().addValue("amount",amount).addValue("m",order.get("member_id")).addValue("s",storeId));
                if (restored != 1) throw new BusinessException(409106, "储值退款会员不存在");
                new com.dajin.system.member.MemberBalanceLedger(db).record(storeId, order.get("member_id"), amount, "PROCESSING_WITHDRAW", client, operator);
            }
        }
        List<Map<String,Object>> feeIncome = db.list("select finance_id,amount,pay_method,shift_no from finance_record where store_id=:s and related_bill_no=:bill and type='INCOME' and category='PROCESSING_FEE' order by finance_id", Map.of("s",storeId,"bill",orderNo));
        for (Map<String,Object> line : feeIncome) {
            String client = "WITHDRAW-PROCESSING-" + id + "-FIN-" + line.get("finance_id");
            db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,client_request_id,create_time) values(:s,'EXPENSE','PROCESSING_WITHDRAW',:amount,:method,:bill,:uid,'加工收款撤回冲销',:shift,:client,now()) on duplicate key update amount=values(amount)",
                    new MapSqlParameterSource().addValue("s",storeId).addValue("amount",line.get("amount")).addValue("method",line.get("pay_method")).addValue("bill",orderNo).addValue("uid",operator).addValue("shift",line.get("shift_no")).addValue("client",client));
        }
        List<Map<String,Object>> refunds = db.list("select finance_id,amount,pay_method,shift_no from finance_record where store_id=:s and related_bill_no=:bill and type='EXPENSE' and category='RECYCLE' and remark='加工旧金抵扣返款' order by finance_id", Map.of("s",storeId,"bill",orderNo));
        for (Map<String,Object> refund : refunds) {
            BigDecimal amount = decimal(refund.get("amount")); if (amount.signum() <= 0) continue;
            String client = "WITHDRAW-PROCESSING-" + id + "-REFUND-" + refund.get("finance_id");
            db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,client_request_id,create_time) values(:s,'INCOME','PROCESSING_WITHDRAW',:amount,:method,:bill,:uid,'加工返款撤回冲销',:shift,:client,now()) on duplicate key update amount=values(amount)", new MapSqlParameterSource().addValue("s",storeId).addValue("amount",amount).addValue("method",refund.get("pay_method")).addValue("bill",orderNo).addValue("uid",operator).addValue("shift",refund.get("shift_no")).addValue("client",client));
        }
        reverseProcessingGoldStock(order, storeId, operator);
        if (number(order.get("residual_material_recorded")) == 1) oldMaterialLedger.returnAndRecord(storeId, "PROCESSING:" + id, operator);
        db.jdbc().update("update processing_commission set status='CANCELLED',update_time=now() where store_id=:s and processing_order_id=:id and status<>'CANCELLED'", Map.of("s",storeId,"id",id));
        db.jdbc().update("update approval set status=4,approver_id=:uid,approve_remark='加工单已撤回',approve_time=now() where store_id=:s and biz_id=:id and type in ('PROCESSING_REFUND','PROCESSING_PAYMENT_DISCOUNT') and status in (1,3)", new MapSqlParameterSource().addValue("s",storeId).addValue("id",id).addValue("uid",operator));
        db.jdbc().update("update print_job set status='IGNORED',ignored_time=now(),ignored_by=:uid where store_id=:s and order_id=:id and job_type='PROCESSING' and status='PENDING'", new MapSqlParameterSource().addValue("uid",operator).addValue("s",storeId).addValue("id",id));
        int changed = db.jdbc().update("update processing_order set status='WITHDRAWN',version=version+1,update_time=now() where processing_order_id=:id and store_id=:s and status='COMPLETED' and version=:version", Map.of("id",id,"s",storeId,"version",version));
        if (changed != 1) throw new BusinessException(409704, "加工单版本已变化，请刷新后重试");
        boolean salesWasWithdrawn = false;
        BigDecimal linkedSalesPaid = BigDecimal.ZERO;
        if (linkedSales != null && number(linkedSales.get("status")) != SalesOrderWithdrawalService.WITHDRAWN_STATUS) {
            linkedSalesPaid = new SalesOrderWithdrawalService(db).withdraw(linkedSales, storeId, operator);
            salesWasWithdrawn = true;
        }
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'PROCESSING','WITHDRAW',:content,:client,'',now())", new MapSqlParameterSource().addValue("s", storeId).addValue("uid", operator).addValue("content", "加工单=" + orderNo + ",原状态=COMPLETED").addValue("client", withdrawRequestId));
        Map<String,Object> event = Map.of("storeId",storeId,"processingOrderId",id,"orderNo",orderNo,"action","WITHDRAW");
        broadcast("PROCESSING_ORDER_UPDATED", event); broadcast("STOCK_UPDATED", event); broadcast("REPORT_UPDATED", event); broadcast("COMMISSION_UPDATED", event); broadcast("APPROVAL_UPDATED", event);
        new com.dajin.system.commission.CommissionLedger(db).rebuildForProcessingOrder(storeId, id);
        if (salesWasWithdrawn) {
            Map<String,Object> salesEvent = Map.of("storeId",storeId,"orderId",linkedSalesId,"action","WITHDRAW");
            broadcast("ORDER_UPDATED", salesEvent); broadcast("MEMBER_UPDATED", salesEvent);
        }
        boolean clientRefundRequired = decimal(order.get("paid_amount")).signum() > 0 || linkedSalesPaid.signum() > 0;
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("processingOrderId", id); result.put("status", "WITHDRAWN"); result.put("withdrawn", true);
        result.put("sourceSalesOrderId", linkedSalesId); result.put("sourceSalesOrderWithdrawn", linkedSalesId != null);
        result.put("clientRefundRequired", clientRefundRequired);
        result.put("message", clientRefundRequired ? "加工单及关联单据已撤回，账务已冲销，客户款项需门店线下退还" : "加工单及关联单据已撤回");
        return ApiResponse.ok(result);
    }

    private Long linkedSalesOrderId(long processingOrderId, long storeId) {
        List<Map<String,Object>> rows = db.list("select source_sales_order_id from processing_order where processing_order_id=:id and store_id=:s", Map.of("id",processingOrderId,"s",storeId));
        if (rows.isEmpty()) throw new BusinessException(404701, "加工订单不存在");
        Object value = rows.get(0).get("source_sales_order_id");
        return value instanceof Number ? ((Number)value).longValue() : null;
    }

    private Map<String,Object> lockLinkedSalesOrder(long salesOrderId, long storeId) {
        Map<String,Object> order = db.one("select o.*," + com.dajin.system.order.SalesAmounts.actualPaid("o") + " actual_paid from sales_order o where o.order_id=:id and o.store_id=:s for update", Map.of("id",salesOrderId,"s",storeId));
        if (order == null) throw new BusinessException(409109, "关联成品单不存在，整组撤回未执行");
        return order;
    }

    private void reverseProcessingGoldStock(Map<String,Object> order, long storeId, long operator) {
        long id = ((Number)order.get("processing_order_id")).longValue();
        String orderNo = String.valueOf(order.get("order_no"));
        String issuePrefix = "JL" + orderNo;
        String returnPrefix = "JI" + orderNo;
        List<Map<String,Object>> issued = db.list("select stock_out_id,goods_id,qty from stock_out where store_id=:s and type='OUT' and reason=:reason order by stock_out_id", Map.of("s",storeId,"reason","加工领料:" + orderNo));
        for (Map<String,Object> row : issued) {
            BigDecimal qty = decimal(row.get("qty"));
            if (qty.signum() <= 0 || row.get("goods_id") == null) continue;
            long goodsId = ((Number)row.get("goods_id")).longValue();
            db.jdbc().update("update goods set stock=stock+:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s", new MapSqlParameterSource().addValue("qty",qty).addValue("g",goodsId).addValue("s",storeId));
            db.jdbc().update("insert into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) values(:s,:bill,'PROCESSING_WITHDRAW',:g,:qty,0,:uid,now()) on duplicate key update qty=values(qty)", new MapSqlParameterSource().addValue("s",storeId).addValue("bill","PW"+id+"O"+row.get("stock_out_id")).addValue("g",goodsId).addValue("qty",qty).addValue("uid",operator));
        }
        List<Map<String,Object>> returned = db.list("select stock_in_id,goods_id,qty from stock_in where store_id=:s and type='IN' and bill_no like :prefix order by stock_in_id", new MapSqlParameterSource().addValue("s",storeId).addValue("prefix",returnPrefix + "%"));
        for (Map<String,Object> row : returned) {
            BigDecimal qty = decimal(row.get("qty"));
            if (qty.signum() <= 0 || row.get("goods_id") == null) continue;
            long goodsId = ((Number)row.get("goods_id")).longValue();
            int changed = db.jdbc().update("update goods set stock=stock-:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s and stock>=:qty", new MapSqlParameterSource().addValue("qty",qty).addValue("g",goodsId).addValue("s",storeId));
            if (changed != 1) throw new BusinessException(409710, "金料库存已变化，无法完整撤回补金领料");
            db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:bill,'PROCESSING_WITHDRAW',:g,:qty,:reason,:uid,now()) on duplicate key update qty=values(qty)", new MapSqlParameterSource().addValue("s",storeId).addValue("bill","PW"+id+"I"+row.get("stock_in_id")).addValue("g",goodsId).addValue("qty",qty).addValue("reason","撤回加工补金退料:"+orderNo).addValue("uid",operator));
        }
    }

    /**
     * Pays the customer when old-gold credit (and any deposit already taken)
     * exceeds the processing settlement.  The endpoint is intentionally
     * separate from customer collection so the income and refund ledgers do
     * not get mixed together.
     */
    @PostMapping("/orders/{id}/refund")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> refund(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = lockedOrder(id, storeId);
        BigDecimal total = decimal(order.get("refund_amount"));
        BigDecimal paid = decimal(order.get("refund_paid_amount"));
        BigDecimal outstanding = total.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (outstanding.signum() == 0) throw new BusinessException(409719, "该加工单没有待返款");
        ensureProcessingRefundApproval(storeId, id, String.valueOf(order.get("order_no")), outstanding, grossSettlement(order), decimal(order.get("paid_amount")), request);
        Map<String, Object> approval = latestPaymentApproval(storeId, id, "PROCESSING_REFUND");
        BigDecimal limit = processingRefundApprovalLimit(storeId);
        if (outstanding.compareTo(limit) > 0 && (approval == null || number(approval.get("status")) != 3)) {
            long approvalId = approval == null ? 0L : ((Number) approval.get("approval_id")).longValue();
            return ApiResponse.ok(Map.of("processingOrderId", id, "approvalRequired", true, "approvalId", approvalId, "refundAmount", outstanding, "status", "PENDING"));
        }
        String payMethod = PaymentChannelPolicy.requireActiveProcessingCollection(db, storeId, text(body, "payMethod", "返款方式"));
        BigDecimal amount = body.containsKey("amount") ? positive(body.get("amount"), "返款金额") : outstanding;
        if (amount.compareTo(outstanding) > 0) throw new BusinessException(409720, "返款金额不能超过待返金额");
        if (amount.compareTo(outstanding) < 0) throw new BusinessException(409721, "加工返款必须一次结清");
        String requestId = text(body, "clientRequestId", "clientRequestId");
        int duplicate = db.jdbc().queryForObject("select count(*) from finance_record where store_id=:s and related_bill_no=:bill and category='RECYCLE' and remark='加工旧金抵扣返款' and client_request_id=:requestId", Map.of("s", storeId, "bill", order.get("order_no"), "requestId", requestId), Integer.class);
        if (duplicate > 0) return ApiResponse.ok(orderDetail(id, storeId, request));
        db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,client_request_id,create_time) values(:s,'EXPENSE','RECYCLE',:amount,:method,:bill,:uid,'加工旧金抵扣返款',:shift,:requestId,now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("amount", amount).addValue("method", payMethod).addValue("bill", order.get("order_no"))
                        .addValue("uid", userId(request)).addValue("shift", shifts.current(storeId)).addValue("requestId", requestId));
        db.jdbc().update("update processing_order set refund_paid_amount=refund_amount,refund_pay_method=:method,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                Map.of("method", payMethod, "id", id, "s", storeId));
        log(storeId, userId(request), "PROCESSING_REFUND", "加工单=" + order.get("order_no") + ",返款=" + amount);
        Map<String, Object> result = orderDetail(id, storeId, request);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "REFUND"));
        broadcast("REPORT_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "REFUND"));
        return ApiResponse.ok(result);
    }

    /**
     * 完成加工时确认余料。开单阶段只记录工费，避免尚未称重的余料提前入库或抵扣。
     * residual_material_recorded 是幂等保护；历史订单已入账时不重复写入库存。
     */
    private Map<String, Object> applyResidualMaterialOnCompletion(Map<String, Object> order,
                                                                    Map<String, Object> body,
                                                                    long storeId,
                                                                    HttpServletRequest request) {
        if (number(order.get("residual_material_recorded")) == 1) return order;
        String existing = String.valueOf(order.getOrDefault("residual_gold_handling", "TAKE_AWAY"));
        String handling = String.valueOf(body.getOrDefault("residualGoldHandling", existing)).toUpperCase(Locale.ROOT);
        if (!HANDLINGS.contains(handling)) throw new BusinessException(400708, "剩余旧料处理方式不合法");
        long orderId = ((Number) order.get("processing_order_id")).longValue();
        String orderNo = String.valueOf(order.get("order_no"));
        long operatorId = userId(request);
        if ("TAKE_AWAY".equals(handling)) {
            db.jdbc().update("update processing_order set residual_material_type=null,residual_gold_weight=null,residual_gold_fineness=null,residual_gold_handling='TAKE_AWAY',residual_gold_deduction=0,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                    Map.of("id", orderId, "s", storeId));
            order.put("residual_material_type", null);
            order.put("residual_gold_weight", null);
            order.put("residual_gold_fineness", null);
            order.put("residual_gold_handling", "TAKE_AWAY");
            order.put("residual_gold_deduction", BigDecimal.ZERO);
            return order;
        }

        // 允许完成加工时现场补录来料：必须先写库，再据此计算回收屑。
        BigDecimal incomingWeight = optionalDecimal(body.get("oldGoldWeight"), 3);
        BigDecimal incomingFineness = optionalDecimal(body.get("oldGoldFineness"), 4);
        if (incomingWeight != null && incomingWeight.signum() < 0) throw new BusinessException(400711, "来料克重不能小于0");
        if (incomingFineness != null && (incomingFineness.signum() < 0 || incomingFineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400711, "来料成色范围为0到1");
        if (incomingWeight != null || incomingFineness != null) {
            db.jdbc().update("update processing_order set old_gold_weight=coalesce(:w,old_gold_weight),old_gold_fineness=coalesce(:f,old_gold_fineness),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                    new MapSqlParameterSource().addValue("w", incomingWeight).addValue("f", incomingFineness).addValue("id", orderId).addValue("s", storeId));
            if (incomingWeight != null) order.put("old_gold_weight", incomingWeight);
            if (incomingFineness != null) order.put("old_gold_fineness", incomingFineness);
        }
        BigDecimal oldWeight = decimalValue(order.get("old_gold_weight"));
        BigDecimal oldFineness = decimalValue(order.get("old_gold_fineness"));
        if (oldWeight == null || oldFineness == null) throw new BusinessException(400711, "请先补录来料克重与成色");
        BigDecimal finishedWeight = decimalValue(order.get("finished_weight"));
        if (finishedWeight == null || finishedWeight.signum() <= 0) throw new BusinessException(400725, "请先登记成品实重后再完成加工");
        String materialType = body.containsKey("residualMaterialType")
                ? optionalText(body, "residualMaterialType") : optionalText(order, "residual_material_type");
        if (materialType == null || materialType.isBlank()) materialType = "足金999";
        // 回收价：收银端可手工填，留 0/不填则取系统设置的足金回收价
        BigDecimal customRecycle = optionalDecimal(body.get("residualRecyclePrice"), 2);
        if (customRecycle != null && customRecycle.signum() < 0) throw new BusinessException(400735, "回收价格不能小于0");
        BigDecimal recycle = customRecycle != null && customRecycle.signum() > 0 ? customRecycle : currentRecyclePrice(storeId);
        if (recycle == null || recycle.signum() <= 0) throw new BusinessException(400722, "未配置回收金价，无法计算回收屑抵扣");
        // 回收屑 = 融后金重 − 成品实重（不足按 0，补金不参与）；抵扣 = 回收屑 × 足金回收金价
        BigDecimal residualWeight = residualDustWeight(decimalValue(order.get("melt_weight")), oldWeight, oldFineness,
                finishedWeight);
        BigDecimal deduction = residualDustDeduction(residualWeight, recycle);
        BigDecimal laborFee = decimal(order.get("labor_fee"));
        // The full old-gold value can exceed the labour fee.  Keep the signed
        // settlement in the derived refund fields instead of capping it at
        // zero: labour + top-up - 回收屑 deduction - deposits is the amount
        // still owed by the customer (negative means the shop owes a refund).
        BigDecimal grossDue = laborFee.add(decimal(order.get("store_gold_amount"))).subtract(deduction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal paid = decimal(order.get("paid_amount"));
        BigDecimal refund = grossDue.subtract(paid).negate().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal promotionDiscount = decimal(order.get("promotion_discount"));
        BigDecimal due = grossDue.subtract(promotionDiscount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        // 回收屑按折重口径入库：克重已折算，成色固定记 1，避免二次折算。
        db.jdbc().update("update processing_order set residual_material_type=:type,residual_gold_weight=:weight,residual_gold_fineness=1,residual_gold_handling='STORE_DEDUCT',residual_gold_deduction=:deduction,due_amount=:due,refund_amount=:refund,refund_paid_amount=least(refund_paid_amount,:refund),original_due_amount=case when promotion_discount>0 then coalesce(original_due_amount,:grossDue) else original_due_amount end,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("type", materialType).addValue("weight", residualWeight).addValue("deduction", deduction).addValue("due", due).addValue("refund", refund).addValue("grossDue", grossDue).addValue("id", orderId).addValue("s", storeId));
        ensureProcessingRefundApproval(storeId, orderId, orderNo, refund, grossDue, paid, request);
        if (residualWeight.signum() > 0) recordResidualMaterial(storeId, orderId, orderNo, materialType, residualWeight, BigDecimal.ONE, deduction, operatorId);
        order.put("residual_material_type", materialType);
        order.put("residual_gold_weight", residualWeight);
        order.put("residual_gold_fineness", BigDecimal.ONE);
        order.put("residual_gold_handling", "STORE_DEDUCT");
        order.put("residual_gold_deduction", deduction);
        order.put("due_amount", due);
        order.put("refund_amount", refund);
        order.put("residual_material_recorded", 1);
        return order;
    }

    @PostMapping("/orders/{id}/payments")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> pay(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        String requestId = text(body, "clientRequestId", "clientRequestId");
        Map<String, Object> order = lockedOrder(id, storeId);
        List<Map<String, Object>> replay = db.list("select payment_id,processing_order_id,payment_type,amount,pay_method,client_request_id,remark,create_time from processing_payment where store_id=:s and client_request_id=:requestId for update", Map.of("s", storeId, "requestId", requestId));
        if (!replay.isEmpty()) {
            if (((Number) replay.get(0).get("processing_order_id")).longValue() != id)
                throw new BusinessException(409708, "支付请求编号已用于其他加工单");
            Map<String, Object> result = new LinkedHashMap<>(orderDetail(id, storeId, request));
            result.put("processingOrderId", id);
            result.put("idempotentReplay", true);
            result.put("replayedPayment", replay.get(0));
            return ApiResponse.ok(result);
        }
        if ("PICKED_UP".equals(order.get("status"))) throw new BusinessException(409706, "已取货订单不能继续收款");
        String paymentType = text(body, "paymentType", "收款类型").toUpperCase(Locale.ROOT);
        if (!PAYMENT_TYPES.contains(paymentType)) throw new BusinessException(400716, "收款类型只能是定金或尾款");
        String payMethod = PaymentChannelPolicy.requireActiveProcessingCollection(db, storeId, text(body, "payMethod", "支付方式"));
        BigDecimal amount = positive(body.get("amount"), "收款金额");
        BigDecimal due = decimal(order.get("due_amount")); BigDecimal paid = decimal(order.get("paid_amount"));
        BigDecimal remaining = due.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        boolean groupPayment = PaymentChannelPolicy.isGroupChannel(payMethod);
        String settlementMode = String.valueOf(body.getOrDefault("settlementMode", "FULL")).trim().toUpperCase(Locale.ROOT);
        boolean depositPayment = "DEPOSIT".equals(paymentType);
        if (!Set.of("FULL", "DISCOUNT").contains(settlementMode) && !(depositPayment && "PARTIAL".equals(settlementMode)))
            throw new BusinessException(400718, "结算方式不合法，加工尾款只能一次结清");
        String voucherNo = null;
        BigDecimal discount = BigDecimal.ZERO;
        if (groupPayment) {
            if (!"BALANCE".equals(paymentType) || !"COMPLETED".equals(order.get("status")) || order.get("promotion_channel") != null)
                throw new BusinessException(400724, "团购核销仅用于已完成加工单的首次尾款收取");
            voucherNo = text(body, "voucherNo", "团购核销单号");
            if (voucherNo.length() > 100) throw new BusinessException(400725, "团购核销单号不能超过100字");
            Integer used = db.jdbc().queryForObject("select count(*) from processing_order where store_id=:s and promotion_channel=:channel and voucher_no=:voucher",
                    Map.of("s", storeId, "channel", payMethod, "voucher", voucherNo), Integer.class);
            if (used != null && used > 0) throw new BusinessException(409717, "团购核销单号已用于其他加工单");
            if (amount.compareTo(remaining) > 0) throw new BusinessException(409707, "收款金额超过加工单未收金额");
            discount = remaining.subtract(amount).setScale(2, RoundingMode.HALF_UP);
        } else if (body.containsKey("voucherNo") && !trimToEmpty(body.get("voucherNo")).isEmpty()) {
            throw new BusinessException(400724, "普通收款不能填写团购核销单号");
        }
        if (amount.compareTo(remaining) > 0) throw new BusinessException(409707, "收款金额超过加工单未收金额");
        if ("BALANCE".equals(paymentType) && !groupPayment && amount.compareTo(remaining) < 0)
            discount = remaining.subtract(amount).setScale(2, RoundingMode.HALF_UP);
        if ("DISCOUNT".equals(settlementMode) && !groupPayment && !"BALANCE".equals(paymentType))
            throw new BusinessException(400717, "只有尾款可以使用优惠结清");
        String promotionReason = discount.signum() > 0 ? optionalText(body, "settlementDiscountReason") : null;
        if (discount.signum() > 0 && (promotionReason == null || promotionReason.isBlank())) promotionReason = "顾客优惠";
        if (promotionReason != null && promotionReason.length() > 200) throw new BusinessException(400108, "优惠原因不能超过200字");
        BigDecimal originalDue = order.get("original_due_amount") == null ? due : decimal(order.get("original_due_amount"));
        BigDecimal existingDiscount = decimal(order.get("promotion_discount"));
        if (discount.signum() > 0 && existingDiscount.signum() > 0)
            throw new BusinessException(400724, "该加工单已经存在优惠记录，不能重复优惠");
        BigDecimal finalPaid = paid.add(amount).setScale(2, RoundingMode.HALF_UP);
        BigDecimal threshold = paymentApprovalThreshold(storeId);
        boolean belowThreshold = "BALANCE".equals(paymentType) && !groupPayment && originalDue.signum() > 0
                && finalPaid.compareTo(originalDue.multiply(threshold).setScale(2, RoundingMode.HALF_UP)) < 0;
        if (belowThreshold) {
            Map<String,Object> previous = latestPaymentApproval(storeId, id, "PROCESSING_PAYMENT_DISCOUNT");
            if (previous != null && number(previous.get("status")) == 1)
                return ApiResponse.ok(paymentApprovalResult(id, originalDue, finalPaid, discount, ((Number) previous.get("approval_id")).longValue()));
            boolean approved = previous != null && number(previous.get("status")) == 3 && approvedPaymentMatches(previous, finalPaid);
            if (!approved) {
                long approvalId = createPaymentApproval(storeId, id, "PROCESSING_PAYMENT_DISCOUNT", amount, originalDue, finalPaid, discount, promotionReason, request);
                return ApiResponse.ok(paymentApprovalResult(id, originalDue, finalPaid, discount, approvalId));
            }
        }
        if ("BALANCE".equals(payMethod)) {
            if (order.get("member_id") == null) throw new BusinessException(400106, "储值支付必须关联会员");
            int debited = db.jdbc().update("update member set balance=balance-:amount,update_time=now() where member_id=:member and store_id=:s and balance>=:amount",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("member", order.get("member_id")).addValue("amount", amount));
            if (debited != 1) throw new BusinessException(409106, "会员储值余额不足");
            new com.dajin.system.member.MemberBalanceLedger(db).record(storeId,order.get("member_id"),amount.negate(),"PROCESSING",requestId,userId(request));
        }
        long operator = userId(request); String shiftNo = shifts.current(storeId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("order", id).addValue("type", paymentType)
                .addValue("amount", amount).addValue("method", payMethod).addValue("requestId", requestId).addValue("operator", operator)
                .addValue("remark", optionalText(body, "remark"));
        db.jdbc().update("insert into processing_payment(store_id,processing_order_id,payment_type,amount,pay_method,client_request_id,operator_id,remark,create_time) values(:s,:order,:type,:amount,:method,:requestId,:operator,:remark,now())", p);
        db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'INCOME','PROCESSING_FEE',:amount,:method,:orderNo,:operator,:financeRemark,:shift,now())",
                p.addValue("orderNo", order.get("order_no")).addValue("financeRemark", discount.signum() > 0 ? "加工优惠结清：" + promotionReason : ("DEPOSIT".equals(paymentType) ? "加工定金" : "加工尾款")).addValue("shift", shiftNo));
        p.addValue("discount", discount).addValue("promotionReason", promotionReason).addValue("methodOrNull", groupPayment ? payMethod : null)
                .addValue("voucher", voucherNo).addValue("originalDue", originalDue);
        try {
            if (groupPayment || discount.signum() > 0) {
                db.jdbc().update("update processing_order set original_due_amount=:originalDue,promotion_discount=:discount,promotion_channel=:methodOrNull,voucher_no=:voucher,promotion_reason=:promotionReason,due_amount=due_amount-:discount,paid_amount=paid_amount+:amount,version=version+1,update_time=now() where processing_order_id=:order and store_id=:s", p);
            } else {
                db.jdbc().update("update processing_order set paid_amount=paid_amount+:amount,version=version+1,update_time=now() where processing_order_id=:order and store_id=:s", p);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409717, "团购核销单号已用于其他加工单");
        }
        if (discount.signum() > 0 && !groupPayment)
            notifyNegotiatedPayment(storeId, id, String.valueOf(order.get("order_no")), originalDue, paid.add(amount), discount, promotionReason);
        log(storeId, operator, "ORDER_PAYMENT", "加工单=" + order.get("order_no") + "," + paymentType + "=" + amount + (discount.signum() > 0 ? ",优惠=" + discount + ",原因=" + promotionReason : ""));
        Map<String, Object> result = orderDetail(id, storeId, request); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "PAYMENT"));
        Map<String,Object> financeEvent = Map.of("storeId", storeId, "processingOrderId", id, "action", "PAYMENT");
        broadcast("REPORT_UPDATED", financeEvent); broadcast("SHIFT_UPDATED", financeEvent);
        return ApiResponse.ok(result);
    }

    @GetMapping("/commissions")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> commissions(@RequestParam(required = false) String status, @RequestParam(required = false) Long employeeId, HttpServletRequest request) {
        String sql = "select c.*,o.order_no,o.item_name_snapshot,u.real_name employee_name from processing_commission c join processing_order o on o.processing_order_id=c.processing_order_id and o.store_id=c.store_id left join sys_user u on u.user_id=c.employee_id and u.store_id=c.store_id where c.store_id=:s and c.status<>'CANCELLED' and (:status is null or c.status=:status) and (:employee is null or c.employee_id=:employee) order by c.commission_id desc";
        return ApiResponse.ok(db.list(sql, new MapSqlParameterSource().addValue("s", store(request)).addValue("status", blankToNull(status)).addValue("employee", employeeId)));
    }

    @PostMapping("/commissions/generate")
    @RequireRoles({"ADMIN", "MANAGER"})
    @Transactional
    public ApiResponse<?> generateCommissions(HttpServletRequest request) {
        long storeId = store(request);
        List<Map<String, Object>> eligible = db.list("select * from processing_order where store_id=:s and status in ('COMPLETED','PICKED_UP') and craftsman_id is not null", Map.of("s", storeId));
        int generated = 0; for (Map<String, Object> order : eligible) generated += createCommission(order, storeId) ? 1 : 0;
        Map<String,Object> event = Map.of("storeId", storeId, "action", "PROCESSING_GENERATE", "generated", generated);
        broadcast("COMMISSION_UPDATED", event); broadcast("REPORT_UPDATED", event);
        return ApiResponse.ok(Map.of("generated", generated));
    }

    @PatchMapping("/commissions/{id}/pay")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> payCommission(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        int changed = db.jdbc().update("update processing_commission set status='PAID',paid_at=now(),paid_by=:uid,update_time=now() where commission_id=:id and store_id=:s and status='PENDING'",
                Map.of("uid", userId(request), "id", id, "s", storeId));
        if (changed == 0) throw new BusinessException(409708, "提成不存在或已发放");
        log(storeId, userId(request), "COMMISSION_PAY", "加工提成ID=" + id);
        Map<String,Object> event = Map.of("storeId", storeId, "commissionId", id, "action", "PROCESSING_PAID");
        broadcast("COMMISSION_UPDATED", event); broadcast("REPORT_UPDATED", event); return ApiResponse.ok();
    }

    @GetMapping("/commissions/summary")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> commissionSummary(HttpServletRequest request) {
        return ApiResponse.ok(db.one("select coalesce(sum(commission_amount),0) total,coalesce(sum(case when status='PENDING' then commission_amount else 0 end),0) pending,coalesce(sum(case when status='PAID' then commission_amount else 0 end),0) paid,count(*) count from processing_commission where store_id=:s and status<>'CANCELLED'", Map.of("s", store(request))));
    }

    @GetMapping("/statistics")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("report:processing")
    public ApiResponse<?> statistics(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Long craftsmanId, HttpServletRequest request) {
        long storeId = store(request);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("from", blankToNull(from)).addValue("to", blankToNull(to)).addValue("craftsman", craftsmanId);
        String filters = " where o.store_id=:s and o.status<>'WITHDRAWN' and (:from is null or date(o.create_time)>=:from) and (:to is null or date(o.create_time)<=:to) and (:craftsman is null or o.craftsman_id=:craftsman)";
        String orderFrom = " from processing_order o" + filters;
        Map<String, Object> summary = db.one("select count(*) order_count,coalesce(sum(case when status='PENDING' then 1 else 0 end),0) pending_count,coalesce(sum(case when status='PROCESSING' then 1 else 0 end),0) processing_count,coalesce(sum(case when status in ('COMPLETED','PICKED_UP') then 1 else 0 end),0) completed_count,coalesce(sum(labor_fee),0) labor_fee,coalesce(sum(due_amount),0) due_amount,coalesce(sum(paid_amount),0) paid_amount,coalesce(sum(greatest(due_amount-paid_amount,0)),0) outstanding,coalesce(sum(refund_amount),0) refund_amount,coalesce(sum(refund_paid_amount),0) refund_paid_amount,coalesce(sum(greatest(refund_amount-refund_paid_amount,0)),0) refund_outstanding" + orderFrom, p);
        if (hasPermission(request, "processing:commissions")) {
            BigDecimal commission = db.jdbc().queryForObject("select coalesce(sum(c.commission_amount),0) from processing_commission c join processing_order o on o.processing_order_id=c.processing_order_id and o.store_id=c.store_id" + filters + " and c.status<>'CANCELLED'", p, BigDecimal.class);
            summary.put("commission_expense", commission == null ? BigDecimal.ZERO : commission);
        }
        summary.put("item_ranking", db.list("select o.item_name_snapshot item_name,count(*) order_count,coalesce(sum(o.labor_fee),0) labor_fee,coalesce(sum(o.paid_amount),0) paid_amount" + orderFrom + " group by o.item_name_snapshot order by paid_amount desc limit 10", p));
        return ApiResponse.ok(summary);
    }

    private Map<String, Object> orderDetail(long id, long storeId, HttpServletRequest request) {
        Map<String, Object> order;
        try {
            order = db.one("select o.*,m.name member_name,u.real_name craftsman_name,sales.real_name sales_name,creator.real_name creator_name from processing_order o left join member m on m.member_id=o.member_id and m.store_id=o.store_id left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id left join sys_user sales on sales.user_id=o.sales_id and sales.store_id=o.store_id left join sys_user creator on creator.user_id=o.created_by and creator.store_id=o.store_id where o.processing_order_id=:id and o.store_id=:s", Map.of("id", id, "s", storeId));
        } catch (Exception e) { throw new BusinessException(404701, "加工订单不存在"); }
        if ("WITHDRAWN".equals(String.valueOf(order.get("status"))))
            throw new BusinessException(409704, "加工单已撤回，不能继续查看或操作");
        enrichSettlementFields(order);
        order.put("payments", db.list("select p.*,u.real_name operator_name from processing_payment p left join sys_user u on u.user_id=p.operator_id and u.store_id=p.store_id where p.store_id=:s and p.processing_order_id=:id order by p.payment_id", Map.of("s", storeId, "id", id)));
        if (hasPermission(request, "processing:commissions")) {
            order.put("commissions", db.list("select c.*,u.real_name employee_name from processing_commission c left join sys_user u on u.user_id=c.employee_id and u.store_id=c.store_id where c.store_id=:s and c.processing_order_id=:id and c.status<>'CANCELLED' order by c.commission_id", Map.of("s", storeId, "id", id)));
        }
        return order;
    }

    /** Adds stable derived names so all clients can render the same settlement vocabulary. */
    private void enrichSettlementFields(Map<String, Object> order) {
        BigDecimal gross = grossSettlement(order);
        BigDecimal paid = decimal(order.get("paid_amount"));
        BigDecimal due = decimal(order.get("due_amount"));
        BigDecimal refund = decimal(order.get("refund_amount"));
        refund = refund.max(paid.subtract(gross).max(BigDecimal.ZERO));
        BigDecimal refundPaid = decimal(order.get("refund_paid_amount"));
        order.put("settlement_due_amount", gross.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        order.put("actual_paid_amount", paid.setScale(2, RoundingMode.HALF_UP));
        order.put("tail_due_amount", due.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        order.put("refund_amount", refund.setScale(2, RoundingMode.HALF_UP));
        order.put("refund_paid_amount", refundPaid.setScale(2, RoundingMode.HALF_UP));
        order.put("refund_outstanding", refund.subtract(refundPaid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
    }

    private boolean hasPermission(HttpServletRequest request, String permission) {
        if (request == null || permission == null) return false;
        Object raw = request.getAttribute("permissions");
        if (!(raw instanceof Collection<?> values)) return false;
        for (Object value : values) {
            if ("*".equals(String.valueOf(value)) || permission.equals(String.valueOf(value))) return true;
        }
        return false;
    }

    private Map<String, Object> lockedOrder(long id, long storeId) {
        Map<String, Object> order = lockedOrderForWithdrawal(id, storeId);
        if ("WITHDRAWN".equals(String.valueOf(order.get("status")))) throw new BusinessException(409704, "加工单已撤回，不能继续操作");
        return order;
    }

    private Map<String, Object> lockedOrderForWithdrawal(long id, long storeId) {
        List<Map<String, Object>> rows = db.list("select * from processing_order where processing_order_id=:id and store_id=:s for update", Map.of("id", id, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(404701, "加工订单不存在");
        return rows.get(0);
    }

    private List<String> parsePhotoList(Object raw) {
        if (raw == null) return new ArrayList<>();
        try {
            List<?> values = raw instanceof Collection<?> collection
                    ? new ArrayList<>(collection)
                    : JSON.readValue(String.valueOf(raw), new TypeReference<List<?>>() {});
            List<String> photos = new ArrayList<>();
            for (Object value : values) {
                String photo = String.valueOf(value).trim();
                if (photo.startsWith("http") || photo.startsWith("/api/file/")) photos.add(photo);
            }
            return photos;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private void recordResidualMaterial(long storeId, long orderId, String orderNo, String type, BigDecimal weight,
                                        BigDecimal purity, BigDecimal value, long operatorId) {
        String source = "PROCESSING:" + orderId;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("type", type).addValue("weight", weight)
                .addValue("purity", purity).addValue("source", source).addValue("value", value);
        db.jdbc().update("insert into old_material(store_id,material_type,weight,purity,source,value,status,direction,create_time,update_time) values(:s,:type,:weight,:purity,:source,:value,1,1,now(),now())", p);
        long materialId = db.jdbc().queryForObject("select material_id from old_material where store_id=:s and source=:source order by material_id desc limit 1", p, Long.class);
        oldMaterialLedger.recordMaterial(storeId, materialId, source, weight, purity, value, operatorId);
        db.jdbc().update("update processing_order set residual_material_recorded=1 where processing_order_id=:id and store_id=:s", Map.of("id", orderId, "s", storeId));
        log(storeId, operatorId, "RESIDUAL_MATERIAL_IN", "加工单=" + orderNo + ",旧料=" + type + ",克重=" + weight);
    }

    private boolean createCommission(Map<String, Object> order, long storeId) {
        Object craftsman = order.get("craftsman_id");
        if (!(craftsman instanceof Number)) return false;
        long orderId = ((Number) order.get("processing_order_id")).longValue();
        int exists = db.jdbc().queryForObject("select count(*) from processing_commission where processing_order_id=:id and employee_id=:employee and status<>'CANCELLED'",
                Map.of("id", orderId, "employee", ((Number) craftsman).longValue()), Integer.class);
        if (exists > 0) return false;
        BigDecimal rate = decimal(order.get("commission_rate_snapshot"));
        BigDecimal base = decimal(order.get("labor_fee")); BigDecimal amount = base.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        db.jdbc().update("insert into processing_commission(store_id,processing_order_id,employee_id,commission_base,commission_rate,commission_amount,status,create_time,update_time) values(:s,:order,:employee,:base,:rate,:amount,'PENDING',now(),now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("order", orderId).addValue("employee", craftsman).addValue("base", base).addValue("rate", rate).addValue("amount", amount));
        return true;
    }

    private Map<String, Object> activeItem(long id, long storeId) {
        List<Map<String, Object>> rows = db.list("select i.* from processing_item i join processing_category c on c.category_id=i.category_id and c.store_id=i.store_id where i.item_id=:id and i.store_id=:s and i.status=1 and c.status=1", Map.of("id", id, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(400718, "加工项目不存在或已禁用");
        return rows.get(0);
    }

    private BigDecimal recyclePrice(long storeId) {
        List<Map<String, Object>> rows = db.list("select price from gold_price where store_id=:s and price_type='回收金价' order by date desc,price_id desc limit 1", Map.of("s", storeId));
        return rows.isEmpty() ? BigDecimal.ZERO : decimal(rows.get(0).get("price"));
    }

    private BigDecimal currentRecyclePrice(long storeId) {
        BigDecimal quoted = null;
        if (market != null) quoted = decimalValue(market.snapshot(storeId, "足金", null).get("recyclePrice"));
        return quoted != null && quoted.signum() > 0 ? quoted : recyclePrice(storeId);
    }

    private BigDecimal retailGoldPrice(long storeId) {
        List<Map<String, Object>> rows = db.list("select price from gold_price where store_id=:s and price_type='足金' order by date desc,price_id desc limit 1", Map.of("s", storeId));
        return rows.isEmpty() ? null : decimal(rows.get(0).get("price"));
    }

    /**
     * Finds the internal store-material record without requiring it to be a sellable product.
     *
     * "足金用料" is consumed by processing, not sold from the cashier catalog. A manually
     * created inventory item therefore commonly remains status=0 (库存待上架). Older data can
     * also contain the zero-stock row that was auto-created on the first failed attempt, so a
     * caller that needs stock must select a row with enough stock explicitly.
     */
    private long goldMaterialId(long storeId) {
        List<Map<String, Object>> goodsRows = db.list("select goods_id from goods where store_id=:s and name='足金用料' order by case when status=1 then 0 else 1 end, case when stock>0 then 0 else 1 end, stock desc, goods_id limit 1", Map.of("s", storeId));
        if (!goodsRows.isEmpty()) return ((Number) goodsRows.get(0).get("goods_id")).longValue();
        List<Map<String, Object>> roots = db.list("select category_id from goods_category where store_id=:s and level=1 and status=1 order by sort,category_id limit 1", Map.of("s", storeId));
        if (roots.isEmpty()) throw new BusinessException(400723, "缺少商品分类，无法建档金料");
        db.jdbc().update("insert into goods(store_id,barcode,name,category_id,weight,cost_price,sale_price,price_type,gold_type,stock,status,images,version) values(:s,:barcode,'足金用料',:category,null,0,0,2,'足金',0,1,'[]',0)",
                new MapSqlParameterSource().addValue("s", storeId).addValue("barcode", "GOLD-MAT-" + storeId).addValue("category", roots.get(0).get("category_id")));
        return db.jdbc().queryForObject("select goods_id from goods where store_id=:s and barcode=:barcode", new MapSqlParameterSource().addValue("s", storeId).addValue("barcode", "GOLD-MAT-" + storeId), Long.class);
    }

    private long goldMaterialIdWithStock(long storeId, BigDecimal weight) {
        List<Map<String, Object>> goodsRows = db.list("select goods_id from goods where store_id=:s and name='足金用料' and stock>=:w order by case when status=1 then 0 else 1 end, stock desc, goods_id limit 1",
                new MapSqlParameterSource().addValue("s", storeId).addValue("w", weight));
        if (!goodsRows.isEmpty()) return ((Number) goodsRows.get(0).get("goods_id")).longValue();
        throw new BusinessException(409710, "金料库存不足，请先对「足金用料」入库 " + weight + " 克后再开单");
    }

    /** 店供金料：扣减"足金用料"商品库存并生成出库流水，保证金料账实一致。 */
    private void deductGoldMaterial(long storeId, long orderId, String orderNo, BigDecimal weight, long operatorId) {
        long goodsId = goldMaterialIdWithStock(storeId, weight);
        int changed = db.jdbc().update("update goods set stock=stock-:w,version=version+1,update_time=now() where goods_id=:g and store_id=:s and stock>=:w",
                new MapSqlParameterSource().addValue("w", weight).addValue("g", goodsId).addValue("s", storeId));
        if (changed == 0) throw new BusinessException(409710, "金料库存不足，请先对「足金用料」入库 " + weight + " 克后再开单");
        String billNo = "JL" + orderNo;
        db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:bill,'OUT',:g,:w,:reason,:uid,now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("bill", billNo).addValue("g", goodsId).addValue("w", weight)
                        .addValue("reason", "加工领料:" + orderNo).addValue("uid", operatorId));
        db.jdbc().update("update processing_order set store_gold_goods_id=:g,store_gold_deducted=1 where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("g", goodsId).addValue("id", orderId).addValue("s", storeId));
        log(storeId, operatorId, "STOCK_OUT", "金料领用=" + weight + "g,加工单=" + orderNo);
    }

    /** 按差额调整金料库存：正数领用出库，负数退料入库；流水单号带序号防重。 */
    private void adjustGoldMaterial(long storeId, String orderNo, BigDecimal delta, long operatorId) {
        if (delta.signum() == 0) return;
        long goodsId = delta.signum() > 0 ? goldMaterialIdWithStock(storeId, delta) : goldMaterialId(storeId);
        int seq = db.jdbc().queryForObject(delta.signum() > 0
                ? "select count(*) from stock_out where store_id=:s and bill_no like :prefix"
                : "select count(*) from stock_in where store_id=:s and bill_no like :prefix",
                new MapSqlParameterSource().addValue("s", storeId).addValue("prefix", (delta.signum() > 0 ? "JL" : "JI") + orderNo + "%"), Integer.class);
        if (delta.signum() > 0) {
            int changed = db.jdbc().update("update goods set stock=stock-:w,version=version+1,update_time=now() where goods_id=:g and store_id=:s and stock>=:w",
                    new MapSqlParameterSource().addValue("w", delta).addValue("g", goodsId).addValue("s", storeId));
            if (changed == 0) throw new BusinessException(409710, "金料库存不足，请先对「足金用料」入库 " + delta + " 克");
            String billNo = "JL" + orderNo + (seq == 0 ? "" : "-A" + seq);
            db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:bill,'OUT',:g,:w,:reason,:uid,now())",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("bill", billNo).addValue("g", goodsId).addValue("w", delta)
                            .addValue("reason", "加工领料:" + orderNo).addValue("uid", operatorId));
            log(storeId, operatorId, "STOCK_OUT", "金料领用=" + delta + "g,加工单=" + orderNo);
        } else {
            db.jdbc().update("update goods set stock=stock+:w,version=version+1,update_time=now() where goods_id=:g and store_id=:s",
                    new MapSqlParameterSource().addValue("w", delta.negate()).addValue("g", goodsId).addValue("s", storeId));
            String billNo = "JI" + orderNo + (seq == 0 ? "" : "-A" + seq);
            db.jdbc().update("insert into stock_in(store_id,bill_no,type,goods_id,qty,operator_id,create_time) values(:s,:bill,'IN',:g,:w,:uid,now())",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("bill", billNo).addValue("g", goodsId).addValue("w", delta.negate()).addValue("uid", operatorId));
            log(storeId, operatorId, "STOCK_IN", "金料退料=" + delta.negate() + "g,加工单=" + orderNo);
        }
    }

    private void requireCategory(long id, long storeId) { if (count("select count(*) from processing_category where store_id=:s and category_id=:id", storeId, id) == 0) throw new BusinessException(404702, "加工分类不存在"); }
    private void requireActiveCategory(long id, long storeId) { if (count("select count(*) from processing_category where store_id=:s and category_id=:id and status=1", storeId, id) == 0) throw new BusinessException(400719, "加工分类不存在或已禁用"); }
    private void requireItem(long id, long storeId) { if (count("select count(*) from processing_item where store_id=:s and item_id=:id", storeId, id) == 0) throw new BusinessException(404703, "加工项目不存在"); }
    private void requireActiveCraftsman(Long id, long storeId) {
        if (id == null) return;
        int found = count("select count(*) from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                + "where u.store_id=:s and u.user_id=:id and u.status=1 and r.status=1 and r.role_code='CRAFTSMAN'", storeId, id);
        if (found == 0) throw new BusinessException(400714, "加工师傅不存在、已禁用或角色不是打金师傅");
    }
    private void requireActiveSales(Long id, long storeId) {
        if (id == null) return;
        int found = count("select count(*) from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                + "where u.store_id=:s and u.user_id=:id and u.status=1 and r.status=1 and r.role_code='SALES'", storeId, id);
        if (found == 0) throw new BusinessException(400732, "导购不存在、已禁用或角色不是销售");
    }
    /**
     * 会员/来源销售单带来的默认导购：已停用或不是销售账号时按「无导购（散客）」处理，不阻断开单。
     * 前端显式传入的导购仍走 {@link #requireActiveSales}，无效照样报错。
     */
    private Long optionalActiveSales(Long id, long storeId) {
        if (id == null) return null;
        int found = count("select count(*) from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                + "where u.store_id=:s and u.user_id=:id and u.status=1 and r.status=1 and r.role_code='SALES'", storeId, id);
        return found == 0 ? null : id;
    }
    private Long memberSales(Long memberId, long storeId) {
        if (memberId == null) return null;
        List<Map<String,Object>> rows = db.list("select sales_id from member where member_id=:id and store_id=:s", Map.of("id", memberId, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(404704, "会员不存在或不属于当前门店");
        Object value = rows.get(0).get("sales_id");
        return value instanceof Number ? ((Number) value).longValue() : null;
    }
    private Long sourceOrderSales(Long orderId, long storeId) {
        if (orderId == null) return null;
        List<Map<String,Object>> rows = db.list("select sales_id from sales_order where order_id=:id and store_id=:s", Map.of("id", orderId, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(404705, "来源销售单不存在或不属于当前门店");
        Object value = rows.get(0).get("sales_id");
        return value instanceof Number ? ((Number) value).longValue() : null;
    }
    private String pricingUnit(Object value) {
        String unit = value == null ? null : String.valueOf(value).trim();
        if (unit == null || unit.isBlank()) return "按件";
        if (!"按件".equals(unit) && !"按克".equals(unit)) throw new BusinessException(400720, "计价方式只能是按件或按克");
        return unit;
    }
    private String trimToEmpty(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private BigDecimal paymentApprovalThreshold(long storeId) {
        try {
            String value = db.jdbc().queryForObject("select config_value from sys_config where store_id=:s and config_key='discount_threshold' and enabled=1", Map.of("s", storeId), String.class);
            BigDecimal threshold = new BigDecimal(value);
            if (threshold.signum() > 0 && threshold.compareTo(BigDecimal.ONE) <= 0) return threshold;
        } catch (Exception ignored) { }
        return new BigDecimal("0.85");
    }
    private List<Map<String,Object>> paymentApprovals(long storeId, long orderId, String type) {
        return db.list("select approval_id,status,reason from approval where store_id=:s and type=:type and biz_id=:biz order by approval_id desc limit 1 for update", Map.of("s", storeId, "type", type, "biz", orderId));
    }
    private Map<String,Object> latestPaymentApproval(long storeId, long orderId, String type) {
        List<Map<String,Object>> rows = paymentApprovals(storeId, orderId, type);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The signed settlement before deposits and negotiated collection adjustments. */
    private BigDecimal grossSettlement(Map<String, Object> order) {
        return decimal(order.get("labor_fee"))
                .add(decimal(order.get("store_gold_amount")))
                .subtract(decimal(order.get("residual_gold_deduction")))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal processingTailDue(Map<String, Object> order) {
        return decimal(order.get("due_amount"))
                .subtract(decimal(order.get("paid_amount")))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** Configurable customer-refund approval threshold; 200 is the local default. */
    private BigDecimal processingRefundApprovalLimit(long storeId) {
        try {
            String value = db.jdbc().queryForObject(
                    "select config_value from sys_config where store_id=:s and config_key='processing_refund_approval_limit' and enabled=1",
                    Map.of("s", storeId), String.class);
            BigDecimal limit = new BigDecimal(value);
            if (limit.signum() >= 0) return limit.setScale(2, RoundingMode.HALF_UP);
        } catch (Exception ignored) { }
        return new BigDecimal("200.00");
    }

    /** Create or validate the single approval used for an oversized old-gold refund. */
    private void ensureProcessingRefundApproval(long storeId, long orderId, String orderNo,
                                                BigDecimal refund, BigDecimal grossDue,
                                                BigDecimal paid, HttpServletRequest request) {
        BigDecimal amount = refund == null ? BigDecimal.ZERO : refund.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() == 0 || amount.compareTo(processingRefundApprovalLimit(storeId)) <= 0) return;
        Map<String,Object> existing = latestPaymentApproval(storeId, orderId, "PROCESSING_REFUND");
        if (existing != null) {
            int status = number(existing.get("status"));
            if (status == 3) return;
            if (status == 4) throw new BusinessException(409723, "客户返款审批已驳回，不能继续返款");
            return;
        }
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("kind", "PROCESSING_REFUND");
        data.put("processingOrderId", orderId);
        data.put("orderNo", orderNo);
        data.put("refundAmount", amount);
        data.put("grossSettlement", grossDue);
        data.put("paidAmount", paid);
        final String reason;
        try { reason = JSON.writeValueAsString(data); }
        catch (Exception e) { throw new BusinessException(500103, "返款审批信息生成失败"); }
        db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) values(:s,'PROCESSING_REFUND',:biz,:uid,:amount,:reason,1,now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("biz", orderId)
                        .addValue("uid", userId(request)).addValue("amount", amount).addValue("reason", reason));
        Long approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type='PROCESSING_REFUND' and biz_id=:biz order by approval_id desc limit 1",
                Map.of("s", storeId, "biz", orderId), Long.class);
        db.jdbc().update("update processing_order set refund_approval_id=:approval,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                Map.of("approval", approvalId, "id", orderId, "s", storeId));
        broadcast("APPROVAL_CREATED", Map.of("storeId", storeId, "id", approvalId, "approvalId", approvalId, "type", "PROCESSING_REFUND", "bizId", orderId));
    }

    /** A pickup is blocked until every computed customer refund is paid. */
    private void ensureProcessingRefundSettled(Map<String,Object> order, long storeId) {
        BigDecimal total = decimal(order.get("refund_amount"));
        BigDecimal paid = decimal(order.get("refund_paid_amount"));
        if (total.signum() <= 0 || paid.compareTo(total) >= 0) return;
        Map<String,Object> approval = latestPaymentApproval(storeId,
                ((Number) order.get("processing_order_id")).longValue(), "PROCESSING_REFUND");
        if (total.compareTo(processingRefundApprovalLimit(storeId)) > 0 && (approval == null || number(approval.get("status")) != 3))
            throw new BusinessException(409724, "客户返款尚未审批通过");
        throw new BusinessException(409725, "客户返款尚未完成，不能取货");
    }
    private boolean approvedPaymentMatches(Map<String,Object> approval, BigDecimal actualPaid) {
        try {
            Map<?,?> data = JSON.readValue(String.valueOf(approval.get("reason")), Map.class);
            return actualPaid.compareTo(new BigDecimal(String.valueOf(data.get("actualPaid")))) == 0;
        } catch (Exception ignored) { return false; }
    }
    private long createPaymentApproval(long storeId, long orderId, String type, BigDecimal amount, BigDecimal originalDue,
                                       BigDecimal actualPaid, BigDecimal discount, String reason, HttpServletRequest request) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("kind", "PAYMENT_DISCOUNT"); data.put("orderId", orderId); data.put("originalDue", originalDue);
        data.put("actualPaid", actualPaid); data.put("discount", discount);
        data.put("discountRate", originalDue.signum() == 0 ? BigDecimal.ONE : actualPaid.divide(originalDue, 6, RoundingMode.HALF_UP));
        data.put("reason", reason == null || reason.isBlank() ? "顾客优惠" : reason);
        final String serialized;
        try { serialized = JSON.writeValueAsString(data); } catch (Exception e) { throw new BusinessException(500102, "优惠审批信息生成失败"); }
        db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) values(:s,:type,:biz,:uid,:amount,:reason,1,now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("type", type).addValue("biz", orderId)
                        .addValue("uid", userId(request)).addValue("amount", amount).addValue("reason", serialized));
        long approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type=:type and biz_id=:biz order by approval_id desc limit 1",
                Map.of("s", storeId, "type", type, "biz", orderId), Long.class);
        broadcast("APPROVAL_CREATED", Map.of("storeId", storeId, "id", approvalId, "approvalId", approvalId, "type", type, "bizId", orderId));
        return approvalId;
    }
    private Map<String,Object> paymentApprovalResult(long orderId, BigDecimal originalDue, BigDecimal actualPaid, BigDecimal discount, long approvalId) {
        Map<String,Object> result = new LinkedHashMap<>(); result.put("processingOrderId", orderId); result.put("recorded", false);
        result.put("approvalRequired", true); result.put("approvalId", approvalId); result.put("status", "PENDING");
        result.put("actualPaid", actualPaid); result.put("originalDue", originalDue); result.put("settlementDiscount", discount); result.put("remaining", BigDecimal.ZERO);
        return result;
    }
    private int number(Object value) { try { return value == null ? 0 : new BigDecimal(String.valueOf(value)).intValue(); } catch (Exception ignored) { return 0; } }
    private void ensureUnique(String table, String field, String value, long currentId, long storeId, String message) {
        String idField = "processing_category".equals(table) ? "category_id" : "item_id";
        int found = db.jdbc().queryForObject("select count(*) from " + table + " where store_id=:s and " + field + "=:value and " + idField + "<>:id", Map.of("s", storeId, "value", value, "id", currentId), Integer.class);
        if (found > 0) throw new BusinessException(409709, message);
    }
    private boolean canTransition(String current, String next) { return ("PENDING".equals(current) && "PROCESSING".equals(next)) || ("PROCESSING".equals(current) && "COMPLETED".equals(next)) || ("COMPLETED".equals(current) && "PICKED_UP".equals(next)); }
    private String orderNo() { return "JG" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT); }
    private long store(HttpServletRequest request) { return db.store(request); }
    private long userId(HttpServletRequest request) { Claims claims = (Claims) request.getAttribute("claims"); return claims == null ? 0L : Long.parseLong(claims.getSubject()); }
    private void notifyNegotiatedPayment(long storeId, long orderId, String orderNo, BigDecimal originalDue,
                                         BigDecimal actualPaid, BigDecimal discount, String reason) {
        List<Map<String,Object>> receivers = db.list("select u.user_id from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.store_id=:s and u.status=1 and r.status=1 and r.role_code in ('ADMIN','MANAGER')", Map.of("s", storeId));
        String content = "加工议价成交：订单" + orderNo + "，原应收 ¥" + originalDue + "，实收 ¥" + actualPaid
                + "，议价优惠 ¥" + discount + "；原因：" + reason;
        for (Map<String,Object> receiver : receivers) {
            long uid = ((Number) receiver.get("user_id")).longValue();
            db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'NOTIFICATION','BARGAIN',:content,:client,'',now())",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("uid", uid).addValue("content", content)
                            .addValue("client", "PROC-BARGAIN-" + orderId + "-" + uid));
        }
        broadcast("BARGAIN_RECORDED", Map.of("storeId", storeId, "processingOrderId", orderId));
    }
    private boolean isSales(HttpServletRequest request) { Claims claims = (Claims) request.getAttribute("claims"); return claims != null && "SALES".equalsIgnoreCase(String.valueOf(claims.get("role"))); }
    private boolean isAdminOrManager(HttpServletRequest request) {
        Claims claims = (Claims) request.getAttribute("claims");
        if (claims == null) return false;
        String role = String.valueOf(claims.get("role"));
        return "ADMIN".equalsIgnoreCase(role) || "MANAGER".equalsIgnoreCase(role);
    }
    private Long numberId(Object value) { return value instanceof Number ? ((Number) value).longValue() : null; }
    private boolean orderHasPaidCommission(long storeId, long orderId) {
        Integer count = db.jdbc().queryForObject("select count(*) from processing_commission where store_id=:s and processing_order_id=:id and status='PAID'",
                Map.of("s", storeId, "id", orderId), Integer.class);
        return count != null && count > 0;
    }
    private void broadcast(String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>(data == null ? Map.of() : data);
        if (!event.containsKey("storeId") && event.containsKey("store_id")) event.put("storeId", event.get("store_id"));
        ws.broadcast(type, event);
    }
    private Map<String,Object> processingOrderEvent(Map<String,Object> order, String action) {
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("storeId", order.get("store_id"));
        event.put("processingOrderId", order.get("processing_order_id"));
        event.put("orderNo", order.get("order_no"));
        event.put("status", order.get("status"));
        event.put("action", action);
        return event;
    }
    private void processingCatalogUpdated(long storeId, String action, Long id) {
        Map<String,Object> event = new LinkedHashMap<>(); event.put("storeId", storeId); event.put("action", action); if (id != null) event.put("id", id);
        broadcast("PROCESSING_CATALOG_UPDATED", event);
    }
    private void log(long storeId, long userId, String action, String content) { db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:uid,'PROCESSING',:action,:content,'',now())", Map.of("s", storeId, "uid", userId, "action", action, "content", content)); }
    private int count(String sql, long storeId, long id) { return db.jdbc().queryForObject(sql, Map.of("s", storeId, "id", id), Integer.class); }
    private int count(String sql, long orderId, long employee, boolean ignored) { return db.jdbc().queryForObject(sql, Map.of("id", orderId, "employee", employee), Integer.class); }
    private String text(Map<String, Object> body, String key, String label) { String value = optionalText(body, key); if (value == null || value.isBlank()) throw new BusinessException(400700, label + "不能为空"); return value; }
    private String optionalText(Map<String, Object> body, String key) { Object value = body.get(key); return value == null ? null : String.valueOf(value).trim(); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private long requiredId(Object value, String label) { Long id = nullableId(value); if (id == null || id <= 0) throw new BusinessException(400700, label + "不能为空"); return id; }
    private Long nullableId(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; try { return Long.parseLong(String.valueOf(value)); } catch (NumberFormatException e) { throw new BusinessException(400720, "编号格式不正确"); } }
    private int status(Object value) { int status = integer(value, 1, "状态"); if (status != 0 && status != 1) throw new BusinessException(400703, "状态参数不合法"); return status; }
    private int integer(Object value, int fallback, String label) { if (value == null || String.valueOf(value).isBlank()) return fallback; try { return Integer.parseInt(String.valueOf(value)); } catch (NumberFormatException e) { throw new BusinessException(400720, label + "格式不正确"); } }
    private BigDecimal positive(Object value, String label) { BigDecimal amount = nonNegative(value, label); if (amount.signum() <= 0) throw new BusinessException(400721, label + "必须大于0"); return amount; }
    private BigDecimal nonNegative(Object value, String label) { BigDecimal amount = optionalDecimal(value, 2); if (amount == null || amount.signum() < 0) throw new BusinessException(400722, label + "必须为非负金额"); return amount; }
    private BigDecimal percent(Object value, String label) { BigDecimal rate = nonNegative(value, label); if (rate.compareTo(BigDecimal.valueOf(100)) > 0) throw new BusinessException(400723, label + "不能超过100%"); return rate; }
    private BigDecimal optionalDecimal(Object value, int scale) { if (value == null || String.valueOf(value).isBlank()) return null; try { return new BigDecimal(String.valueOf(value)).setScale(scale, RoundingMode.HALF_UP); } catch (NumberFormatException e) { throw new BusinessException(400720, "数值格式不正确"); } }
    private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); }
    private BigDecimal decimalValue(Object value) { if (value == null || "null".equalsIgnoreCase(String.valueOf(value))) return null; try { return new BigDecimal(String.valueOf(value)); } catch (Exception ignored) { return null; } }
}
