package com.dajin.system.processing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.SyncWebSocketHandler;
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

    public ProcessingController(DbSupport db, SyncWebSocketHandler ws, ShiftService shifts,
                                OldMaterialLedgerService oldMaterialLedger) {
        this.db = db;
        this.ws = ws;
        this.shifts = shifts;
        this.oldMaterialLedger = oldMaterialLedger;
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
        return ApiResponse.ok(db.list(sql, p));
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

    @PostMapping("/orders")
    @Transactional
    public ApiResponse<?> createOrder(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        long itemId = requiredId(body.get("processingItemId"), "加工项目");
        Map<String, Object> item = activeItem(itemId, storeId);
        String customerName = text(body, "customerName", "客户姓名");
        String customerPhone = text(body, "customerPhone", "客户电话");
        int quantity = integer(body.get("quantity"), 1, "数量");
        if (quantity <= 0) throw new BusinessException(400707, "数量必须大于0");
        String handling = String.valueOf(body.getOrDefault("residualGoldHandling", "TAKE_AWAY")).toUpperCase(Locale.ROOT);
        if (!HANDLINGS.contains(handling)) throw new BusinessException(400708, "剩余旧料处理方式不合法");
        BigDecimal unitFee = decimal(item.get("labor_fee"));
        String pricingUnit = String.valueOf(item.getOrDefault("pricing_unit", "按件"));
        BigDecimal billingWeight = "按克".equals(pricingUnit) ? optionalDecimal(body.get("billingWeight"), 3) : null;
        if ("按克".equals(pricingUnit) && (billingWeight == null || billingWeight.signum() <= 0))
            throw new BusinessException(400724, "按克加工需填写大于0的计费总克重");
        BigDecimal laborFee = unitFee.multiply(billingWeight == null ? BigDecimal.valueOf(quantity) : billingWeight).setScale(2, RoundingMode.HALF_UP);
        BigDecimal residualWeight = optionalDecimal(body.get("residualGoldWeight"), 3);
        BigDecimal residualFineness = optionalDecimal(body.get("residualGoldFineness"), 4);
        if (residualWeight != null && residualWeight.signum() < 0) throw new BusinessException(400709, "剩余旧料克重不能小于0");
        if (residualFineness != null && (residualFineness.signum() < 0 || residualFineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400710, "剩余旧料成色范围为0到1");
        BigDecimal storeGoldWeight = optionalDecimal(body.get("storeGoldWeight"), 3);
        if (storeGoldWeight != null && storeGoldWeight.signum() <= 0) storeGoldWeight = null;
        BigDecimal storeGoldFineness = optionalDecimal(body.get("storeGoldFineness"), 4);
        if (storeGoldFineness != null && (storeGoldFineness.signum() < 0 || storeGoldFineness.compareTo(BigDecimal.ONE) > 0)) throw new BusinessException(400710, "店供金料成色范围为0到1");
        BigDecimal storeGoldPrice = optionalDecimal(body.get("storeGoldPrice"), 2);
        if (storeGoldPrice != null && storeGoldPrice.signum() < 0) throw new BusinessException(400721, "店供金料金价不能小于0");
        BigDecimal storeGoldAmount = BigDecimal.ZERO;
        if (storeGoldWeight != null) {
            if (storeGoldPrice == null || storeGoldPrice.signum() == 0) storeGoldPrice = retailGoldPrice(storeId);
            if (storeGoldPrice == null || storeGoldPrice.signum() <= 0) throw new BusinessException(400722, "未配置足金零售价，无法计价店供金料，请先在金价管理维护");
            storeGoldAmount = storeGoldWeight.multiply(storeGoldPrice).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal deduction = BigDecimal.ZERO;
        String materialType = optionalText(body, "residualMaterialType");
        if ("STORE_DEDUCT".equals(handling)) {
            if (materialType == null || materialType.isBlank() || residualWeight == null || residualWeight.signum() <= 0 || residualFineness == null || residualFineness.signum() <= 0) {
                throw new BusinessException(400711, "留店抵扣需填写旧料类型、克重和成色");
            }
            deduction = residualWeight.multiply(residualFineness).multiply(recyclePrice(storeId)).setScale(2, RoundingMode.HALF_UP).min(laborFee);
        }
        BigDecimal due = laborFee.subtract(deduction).add(storeGoldAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        Long craftsman = nullableId(body.get("craftsmanId"));
        requireActiveCraftsman(craftsman, storeId);
        String orderNo = orderNo();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("no", orderNo)
                .addValue("member", nullableId(body.get("memberId"))).addValue("name", customerName).addValue("phone", customerPhone)
                .addValue("itemId", itemId).addValue("itemName", item.get("name")).addValue("unitFee", unitFee)
                .addValue("commissionRate", decimal(item.get("commission_rate"))).addValue("qty", quantity)
                .addValue("pricingUnit", pricingUnit).addValue("billingWeight", billingWeight)
                .addValue("laborFee", laborFee).addValue("oldWeight", optionalDecimal(body.get("oldGoldWeight"), 3)).addValue("oldFineness", optionalDecimal(body.get("oldGoldFineness"), 4))
                .addValue("materialType", materialType).addValue("residualWeight", residualWeight).addValue("residualFineness", residualFineness)
                .addValue("handling", handling).addValue("deduction", deduction).addValue("due", due).addValue("pickup", body.get("pickupDate"))
                .addValue("sgWeight", storeGoldWeight != null ? storeGoldWeight : BigDecimal.ZERO).addValue("sgFineness", storeGoldFineness).addValue("sgPrice", storeGoldWeight != null && storeGoldPrice != null ? storeGoldPrice : BigDecimal.ZERO).addValue("sgAmount", storeGoldAmount)
                .addValue("craftsman", craftsman).addValue("remark", optionalText(body, "remark")).addValue("uid", userId(request));
        db.jdbc().update("insert into processing_order(store_id,order_no,member_id,customer_name,customer_phone,processing_item_id,item_name_snapshot,unit_labor_fee,commission_rate_snapshot,pricing_unit,billing_weight,quantity,labor_fee,old_gold_weight,old_gold_fineness,store_gold_weight,store_gold_fineness,store_gold_price,store_gold_amount,residual_material_type,residual_gold_weight,residual_gold_fineness,residual_gold_handling,residual_gold_deduction,due_amount,paid_amount,pickup_date,craftsman_id,status,remark,created_by,create_time,update_time) values(:s,:no,:member,:name,:phone,:itemId,:itemName,:unitFee,:commissionRate,:pricingUnit,:billingWeight,:qty,:laborFee,:oldWeight,:oldFineness,:sgWeight,:sgFineness,:sgPrice,:sgAmount,:materialType,:residualWeight,:residualFineness,:handling,:deduction,:due,0,:pickup,:craftsman,'PENDING',:remark,:uid,now(),now())", p);
        long orderId = db.jdbc().queryForObject("select processing_order_id from processing_order where store_id=:s and order_no=:no", p, Long.class);
        if ("STORE_DEDUCT".equals(handling)) recordResidualMaterial(storeId, orderId, orderNo, materialType, residualWeight, residualFineness, deduction, userId(request));
        if (storeGoldWeight != null) deductGoldMaterial(storeId, orderId, orderNo, storeGoldWeight, userId(request));
        log(storeId, userId(request), "ORDER_CREATE", "加工单=" + orderNo + ",应收=" + due);
        Map<String, Object> result = orderDetail(orderId, storeId);
        broadcast("PROCESSING_ORDER_CREATED", processingOrderEvent(result, "CREATE"));
        if ("STORE_DEDUCT".equals(handling)) broadcast("OLD_MATERIAL_UPDATED", Map.of("storeId", storeId, "processingOrderId", orderId, "action", "PROCESSING_IN"));
        if (storeGoldWeight != null) broadcast("STOCK_UPDATED", Map.of("storeId", storeId, "processingOrderId", orderId, "action", "PROCESSING_OUT"));
        return ApiResponse.ok(result);
    }

    @GetMapping("/orders")
    @RequirePermission(value = {"processing:view", "order:checkout"}, anyOf = true)
    public ApiResponse<?> orders(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(required = false) Long craftsmanId,
                                 @RequestParam(required = false) Long memberId,
                                 @RequestParam(required = false) String start,
                                 @RequestParam(required = false) String end,
                                 HttpServletRequest request) {
        boolean sales = isSales(request);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", store(request)).addValue("keyword", keyword == null ? null : "%" + keyword.trim() + "%")
                .addValue("status", blankToNull(status)).addValue("craftsman", craftsmanId).addValue("memberId", memberId).addValue("start", blankToNull(start)).addValue("end", blankToNull(end)).addValue("uid", userId(request));
        String sql = "select o.*,m.name member_name,u.real_name craftsman_name,creator.real_name creator_name from processing_order o "
                + "left join member m on m.member_id=o.member_id and m.store_id=o.store_id "
                + "left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id "
                + "left join sys_user creator on creator.user_id=o.created_by and creator.store_id=o.store_id "
                + "where o.store_id=:s and (:keyword is null or o.order_no like :keyword or o.customer_name like :keyword or o.customer_phone like :keyword) "
                + "and (:status is null or o.status=:status) and (:craftsman is null or o.craftsman_id=:craftsman) "
                + "and (:memberId is null or o.member_id=:memberId) "
                + (sales ? "and o.created_by=:uid " : "")
                + "and (:start is null or date(o.create_time)>=:start) and (:end is null or date(o.create_time)<=:end) order by o.processing_order_id desc limit 500";
        return ApiResponse.ok(db.list(sql, p));
    }

    @GetMapping("/orders/{id}")
    @RequirePermission(value = {"processing:view", "order:checkout"}, anyOf = true)
    public ApiResponse<?> detail(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> order = orderDetail(id, store(request));
        Object ownerValue = order.get("created_by");
        long ownerId = ownerValue instanceof Number ? ((Number) ownerValue).longValue() : 0L;
        if (isSales(request) && userId(request) != ownerId) {
            throw new BusinessException(403403, "无权查看该加工订单");
        }
        return ApiResponse.ok(order);
    }

    @PostMapping("/orders/{id}/notify")
    @RequirePermission(value = {"processing:view", "order:checkout"}, anyOf = true)
    public ApiResponse<?> notifyPickup(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = orderDetail(id, storeId);
        Object creatorValue = order.get("created_by");
        long recipient = creatorValue instanceof Number ? ((Number) creatorValue).longValue() : userId(request);
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:uid,'NOTIFICATION','PROCESSING_READY',:content,'',now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("uid", recipient)
                        .addValue("content", "加工单 " + order.get("order_no") + " 已完成，可通知客户取货"));
        broadcast("PROCESSING_PICKUP_NOTIFY", processingOrderEvent(order, "NOTIFY_PICKUP"));
        return ApiResponse.ok(Map.of("notified", true));
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
        BigDecimal price = optionalDecimal(body.get("price"), 2);
        if (price == null || price.signum() <= 0) {
            price = oldWeight.signum() > 0 && decimal(order.get("store_gold_price")).signum() > 0 ? decimal(order.get("store_gold_price")) : retailGoldPrice(storeId);
        }
        if (price == null || price.signum() <= 0) throw new BusinessException(400722, "未配置足金零售价，无法计价补金，请先在金价管理维护");
        BigDecimal amount = weight.multiply(price).setScale(2, RoundingMode.HALF_UP);
        long operator = userId(request);
        adjustGoldMaterial(storeId, String.valueOf(order.get("order_no")), weight.subtract(oldWeight), operator);
        BigDecimal due = decimal(order.get("due_amount")).subtract(oldAmount).add(amount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        if (due.compareTo(decimal(order.get("paid_amount"))) < 0) throw new BusinessException(409716, "修正后的应收低于已收款，请先核对退款");
        db.jdbc().update("update processing_order set store_gold_weight=:w,store_gold_fineness=:f,store_gold_price=:p,store_gold_amount=:a,due_amount=:due,original_due_amount=case when original_due_amount is null then null else :due+promotion_discount end,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("w", weight).addValue("f", fineness).addValue("p", price).addValue("a", amount).addValue("due", due)
                        .addValue("id", id).addValue("s", storeId));
        log(storeId, operator, "ORDER_STORE_GOLD", "加工单=" + order.get("order_no") + ",补金=" + weight + "g,金额=" + amount + ",应收=" + due);
        Map<String, Object> result = orderDetail(id, storeId);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "STORE_GOLD"));
        broadcast("STOCK_UPDATED", Map.of("storeId", storeId, "processingOrderId", id, "action", "PROCESSING_ADJUST"));
        return ApiResponse.ok(result);
    }

    /** 成品称重与损耗核算：损耗=来料折重+店供金−成品折重−回收屑；千分比超约定值标预警。 */
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
        if (recovered != null && recovered.signum() < 0) throw new BusinessException(400726, "回收屑克重不能小于0");
        Map<String, Object> order = lockedOrder(id, storeId);
        String status = String.valueOf(order.get("status"));
        if ("PENDING".equals(status)) throw new BusinessException(409715, "加工开始前不能登记称重，请先确认加工");
        if ("PICKED_UP".equals(status)) throw new BusinessException(409711, "已取货订单不能再登记称重");
        java.math.BigDecimal oldNet = decimal(order.get("old_gold_weight")).multiply(optionalDecimal(order.get("old_gold_fineness"), 4) == null ? BigDecimal.ONE : decimal(order.get("old_gold_fineness")));
        java.math.BigDecimal base = oldNet.add(decimal(order.get("store_gold_weight")));
        java.math.BigDecimal finishedNet = finishedWeight.multiply(finishedFineness == null ? BigDecimal.ONE : finishedFineness);
        java.math.BigDecimal loss = base.subtract(finishedNet).subtract(recovered == null ? BigDecimal.ZERO : recovered);
        if (loss.signum() < 0) throw new BusinessException(409714,
                "成品折重 " + finishedNet.setScale(3, java.math.RoundingMode.HALF_UP) + "g 超过来料+店供金合计 " + base.setScale(3, java.math.RoundingMode.HALF_UP)
                        + "g，疑似漏登记补金，请先做补金登记再称重");
        java.math.BigDecimal permille = base.signum() > 0 ? loss.divide(base, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(1000)).setScale(2, java.math.RoundingMode.HALF_UP) : null;
        java.math.BigDecimal config = lossConfig(storeId);
        boolean over = permille != null && permille.compareTo(config) > 0;
        db.jdbc().update("update processing_order set finished_weight=:w,finished_fineness=:f,recovered_weight=:r,loss_weight=:l,loss_permille=:p,loss_over=:o,loss_note=:n,loss_time=now(),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("w", finishedWeight).addValue("f", finishedFineness).addValue("r", recovered).addValue("l", loss)
                        .addValue("p", permille).addValue("o", over ? 1 : 0).addValue("n", optionalText(body, "note")).addValue("id", id).addValue("s", storeId));
        log(storeId, userId(request), "ORDER_WEIGHING", "加工单=" + order.get("order_no") + ",成品=" + finishedWeight + "g,损耗=" + loss + "g,千分比=" + permille + (over ? ",超标" : ""));
        Map<String, Object> result = orderDetail(id, storeId);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "WEIGHING"));
        if (over) broadcast("PROCESSING_LOSS_OVER", processingOrderEvent(result, "LOSS_OVER"));
        return ApiResponse.ok(result);
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
        Map<String,Object> result = orderDetail(id, storeId);
        broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "PHOTOS"));
        return ApiResponse.ok(result);
    }

    /** 加工工单/质保凭证 HTML（移动端仅预览；打印统一在收银端确认）。 */
    @GetMapping(value = "/orders/{id}/print", produces = "text/html;charset=UTF-8")
    public String print(@PathVariable long id, @RequestParam(required = false) Boolean preview, HttpServletRequest request) {
        boolean previewMode = Boolean.TRUE.equals(preview);
        long storeId = store(request);
        Map<String, Object> o = orderDetail(id, storeId);
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
        h.append("<div class=\"row\"><span>客户带来旧金</span><b>").append(decimal(o.get("old_gold_weight")).signum() > 0 ? escHtml(decimal(o.get("old_gold_weight")).toPlainString() + "g" + (o.get("old_gold_fineness") == null ? "" : " · " + decimal(o.get("old_gold_fineness")).multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%")) : "无").append("</b></div>");
        h.append("<div class=\"row\"><span>店供补金</span><b>").append(decimal(o.get("store_gold_weight")).signum() > 0 ? escHtml(decimal(o.get("store_gold_weight")).toPlainString() + "g") : "无").append("</b></div>");
        h.append("<div class=\"row\"><span>余料处理</span><b>").append("STORE_DEDUCT".equals(String.valueOf(o.get("residual_gold_handling"))) ? "留店抵扣工费" : "客户带走").append("</b></div></div>");
        h.append("<h2>费用</h2><div class=\"calc\"><div>加工工费</div><div class=\"v\">").append(decimal(o.get("labor_fee")).toPlainString()).append("</div><div>旧料抵扣</div><div class=\"v\">-").append(decimal(o.get("residual_gold_deduction")).toPlainString()).append("</div><div>应收金额</div><div class=\"v\">").append(decimal(o.get("due_amount")).toPlainString()).append("</div><div>已收定金</div><div class=\"v\">").append(decimal(o.get("paid_amount")).toPlainString()).append("</div><div>尾款待收</div><div class=\"v\">").append(decimal(o.get("due_amount")).subtract(decimal(o.get("paid_amount"))).max(BigDecimal.ZERO).toPlainString()).append("</div></div>");
        h.append("<h2>称重记录（g）</h2><div class=\"hand\"><div><span>来料折重</span><b>").append(decimal(o.get("old_gold_weight")).multiply(optionalDecimal(o.get("old_gold_fineness"), 4) == null ? BigDecimal.ONE : decimal(o.get("old_gold_fineness"))).toPlainString()).append("</b></div><div><span>成品实重</span><b>").append(o.get("finished_weight") == null ? "" : escHtml(decimal(o.get("finished_weight")).toPlainString())).append("</b></div><div><span>回收屑</span><b>").append(o.get("recovered_weight") == null ? "" : escHtml(decimal(o.get("recovered_weight")).toPlainString())).append("</b></div><div><span>损耗</span><b>").append(o.get("loss_weight") == null ? "" : escHtml(decimal(o.get("loss_weight")).toPlainString() + (o.get("loss_permille") != null ? "（" + decimal(o.get("loss_permille")).toPlainString() + "‰）" : ""))).append("</b></div><div><span>余料（手写）</span><b></b></div><div><span>融后金重（手写）</span><b></b></div><div><span>加料（手写）</span><b></b></div></div>");
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
        Map<String, Object> o = orderDetail(id, storeId);
        String storeName = String.valueOf(db.one("select store_name from sys_store where store_id=:s", Map.of("s", storeId)).getOrDefault("store_name", "-"));
        return ProcessingWarrantyHtml.build(o, storeName);
    }

    /** 移动端送打印：生成待打印任务，收银端「待打印」确认。 */
    @PostMapping("/orders/{id}/print-request")
    @Transactional
    public ApiResponse<?> printRequest(@PathVariable long id, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> o = orderDetail(id, storeId);
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
        Map<String, Object> result = orderDetail(id, storeId);
        broadcast("PROCESSING_HANDOVER", Map.of("storeId", storeId, "orderId", id, "orderNo", String.valueOf(order.get("order_no"))));
        return ApiResponse.ok(result);
    }

    /** 收银端「前台待办」：所有待加工的加工单（手机转交 + 收银端自开）。 */
    @GetMapping("/handovers")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    public ApiResponse<?> handovers(HttpServletRequest request) {
        long storeId = store(request);
        return ApiResponse.ok(db.list("select o.processing_order_id,o.order_no,o.customer_name,o.customer_phone,o.item_name_snapshot,o.quantity,o.labor_fee,o.due_amount,o.paid_amount,o.pickup_date,o.handover_time,u.real_name craftsman_name from processing_order o left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id where o.store_id=:s and o.status='PENDING' order by coalesce(o.handover_time,o.create_time) desc limit 50", Map.of("s", storeId)));
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
                "coalesce(sum(case when o.loss_weight>=0 then o.loss_weight else 0 end),0) loss_weight," +
                "coalesce(sum(case when o.loss_weight>=0 then o.loss_over else 0 end),0) over_count," +
                "coalesce(sum(case when o.loss_weight>=0 then 1 else 0 end),0) weighed_count," +
                "coalesce(sum(case when o.loss_weight<0 then 1 else 0 end),0) anomaly_count " +
                "from processing_order o left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id where o.store_id=:s and o.loss_weight is not null" + range +
                " group by o.craftsman_id,u.real_name order by over_count desc,loss_weight desc", p);
        long anomalyCount = rows.stream().mapToLong(row -> ((Number) row.getOrDefault("anomaly_count", 0)).longValue()).sum();
        return ApiResponse.ok(Map.of("permille", lossConfig(db.store(r)), "anomalyCount", anomalyCount, "rows", rows));
    }

    /** 损耗逐单明细：历史负损耗保留为异常记录，不进入正常考核汇总。 */
    @GetMapping("/loss-orders") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> lossOrders(@RequestParam(required = false) Long craftsmanId,
                                     @RequestParam(required = false) String from,
                                     @RequestParam(required = false) String to,
                                     @RequestParam(defaultValue = "false") boolean onlyOver,
                                     @RequestParam(defaultValue = "false") boolean anomalies,
                                     HttpServletRequest r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r))
                .addValue("from", from == null || from.isBlank() ? null : from)
                .addValue("to", to == null || to.isBlank() ? null : to);
        StringBuilder where = new StringBuilder(" where o.store_id=:s and o.loss_weight is not null")
                .append(" and (:from is null or o.create_time>=:from) and (:to is null or o.create_time<date_add(:to,interval 1 day))");
        if (craftsmanId != null) {
            if (craftsmanId == 0) where.append(" and o.craftsman_id is null");
            else { where.append(" and o.craftsman_id=:craftsman"); p.addValue("craftsman", craftsmanId); }
        }
        if (anomalies) where.append(" and o.loss_weight<0");
        else if (onlyOver) where.append(" and o.loss_weight>=0 and o.loss_over=1");
        List<Map<String, Object>> rows = db.list("select o.processing_order_id,o.order_no,o.item_name_snapshot,o.customer_name," +
                "coalesce(u.real_name,'未指派') craftsman_name,o.old_gold_weight,o.old_gold_fineness," +
                "round(coalesce(o.old_gold_weight,0)*coalesce(o.old_gold_fineness,1),3) incoming_net_weight," +
                "o.store_gold_weight,o.finished_weight,o.finished_fineness,o.recovered_weight,o.loss_weight,o.loss_permille,o.loss_over,o.loss_note,o.loss_time,o.create_time " +
                "from processing_order o left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id" + where + " order by o.loss_time desc,o.processing_order_id desc limit 500", p);
        return ApiResponse.ok(rows);
    }

    @PutMapping("/orders/{id}")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> updateOrder(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        Map<String, Object> order = lockedOrder(id, storeId);
        if (!"PENDING".equals(order.get("status"))) throw new BusinessException(409703, "仅待加工订单可编辑");
        String name = optionalText(body, "customerName");
        String phone = optionalText(body, "customerPhone");
        if (name != null && name.isBlank()) throw new BusinessException(400712, "客户姓名不能为空");
        if (phone != null && phone.isBlank()) throw new BusinessException(400713, "客户电话不能为空");
        Long craftsman = nullableId(body.get("craftsmanId"));
        requireActiveCraftsman(craftsman, storeId);
        db.jdbc().update("update processing_order set customer_name=coalesce(:name,customer_name),customer_phone=coalesce(:phone,customer_phone),member_id=coalesce(:member,member_id),pickup_date=coalesce(:pickup,pickup_date),craftsman_id=coalesce(:craftsman,craftsman_id),remark=coalesce(:remark,remark),version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("s", storeId).addValue("id", id).addValue("name", name).addValue("phone", phone)
                        .addValue("member", nullableId(body.get("memberId"))).addValue("pickup", body.get("pickupDate")).addValue("craftsman", craftsman).addValue("remark", optionalText(body, "remark")));
        log(storeId, userId(request), "ORDER_UPDATE", "加工单ID=" + id);
        Map<String, Object> result = orderDetail(id, storeId); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "UPDATE")); return ApiResponse.ok(result);
    }

    @PostMapping("/orders/{id}/assign")
    @RequireRoles({"ADMIN", "MANAGER"})
    @Transactional
    public ApiResponse<?> assign(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request); lockedOrder(id, storeId);
        Long craftsman = nullableId(body.get("craftsmanId"));
        requireActiveCraftsman(craftsman, storeId);
        db.jdbc().update("update processing_order set craftsman_id=:craftsman,version=version+1,update_time=now() where processing_order_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("craftsman", craftsman).addValue("id", id).addValue("s", storeId));
        log(storeId, userId(request), "ORDER_ASSIGN", "加工单ID=" + id + ",师傅=" + craftsman);
        Map<String, Object> result = orderDetail(id, storeId); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "ASSIGN")); return ApiResponse.ok(result);
    }

    @PatchMapping("/orders/{id}/status")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> changeStatus(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String next = text(body, "status", "订单状态").toUpperCase(Locale.ROOT);
        if (!ORDER_STATUSES.contains(next)) throw new BusinessException(400715, "订单状态不合法");
        long storeId = store(request); Map<String, Object> order = lockedOrder(id, storeId); String current = String.valueOf(order.get("status"));
        if (!canTransition(current, next)) throw new BusinessException(409704, "状态只能按待加工、加工中、已完成、已取货顺序流转");
        if ("PICKED_UP".equals(next) && decimal(order.get("paid_amount")).compareTo(decimal(order.get("due_amount"))) < 0) throw new BusinessException(409705, "加工单尚有尾款未收，不能取货");
        if ("PICKED_UP".equals(next) && parsePhotoList(order.get("pickup_photos")).isEmpty()) throw new BusinessException(409715, "请先上传取货照片，上传成功后才能确认取货");
        if ("COMPLETED".equals(next)) createCommission(order, storeId);
        String completedSql = "COMPLETED".equals(next) ? ",completed_time=now()" : "";
        String pickupSql = "PICKED_UP".equals(next) ? ",picked_up_time=now()" : "";
        db.jdbc().update("update processing_order set status=:status,version=version+1,update_time=now()" + completedSql + pickupSql + " where processing_order_id=:id and store_id=:s",
                Map.of("status", next, "id", id, "s", storeId));
        log(storeId, userId(request), "ORDER_STATUS", "加工单ID=" + id + "," + current + "->" + next);
        Map<String, Object> result = orderDetail(id, storeId); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "STATUS"));
        Map<String,Object> reportEvent = Map.of("storeId", storeId, "processingOrderId", id, "action", next);
        broadcast("REPORT_UPDATED", reportEvent);
        if ("COMPLETED".equals(next)) broadcast("COMMISSION_UPDATED", reportEvent);
        return ApiResponse.ok(result);
    }

    @PostMapping("/orders/{id}/payments")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER"})
    @Transactional
    public ApiResponse<?> pay(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = store(request);
        String requestId = text(body, "clientRequestId", "clientRequestId");
        Map<String, Object> order = lockedOrder(id, storeId);
        List<Map<String, Object>> replay = db.list("select payment_id,processing_order_id from processing_payment where store_id=:s and client_request_id=:requestId for update", Map.of("s", storeId, "requestId", requestId));
        if (!replay.isEmpty()) {
            if (((Number) replay.get(0).get("processing_order_id")).longValue() != id)
                throw new BusinessException(409708, "支付请求编号已用于其他加工单");
            return ApiResponse.ok(Map.of("processingOrderId", id, "idempotentReplay", true));
        }
        if ("PICKED_UP".equals(order.get("status"))) throw new BusinessException(409706, "已取货订单不能继续收款");
        String paymentType = text(body, "paymentType", "收款类型").toUpperCase(Locale.ROOT);
        if (!PAYMENT_TYPES.contains(paymentType)) throw new BusinessException(400716, "收款类型只能是定金或尾款");
        String payMethod = PaymentChannelPolicy.requireActiveProcessingCollection(db, storeId, text(body, "payMethod", "支付方式"));
        BigDecimal amount = positive(body.get("amount"), "收款金额");
        BigDecimal due = decimal(order.get("due_amount")); BigDecimal paid = decimal(order.get("paid_amount"));
        boolean groupPayment = PaymentChannelPolicy.isGroupChannel(payMethod);
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
            discount = due.subtract(paid).subtract(amount);
        } else if (body.containsKey("voucherNo") && !trimToEmpty(body.get("voucherNo")).isEmpty()) {
            throw new BusinessException(400724, "普通收款不能填写团购核销单号");
        }
        if (paid.add(amount).compareTo(due) > 0) throw new BusinessException(409707, "收款金额超过加工单应收金额");
        if ("BALANCE".equals(paymentType) && !groupPayment && paid.add(amount).compareTo(due) != 0) throw new BusinessException(400717, "尾款金额应等于剩余应收金额");
        if ("BALANCE".equals(payMethod)) {
            if (order.get("member_id") == null) throw new BusinessException(400106, "储值支付必须关联会员");
            int debited = db.jdbc().update("update member set balance=balance-:amount,update_time=now() where member_id=:member and store_id=:s and balance>=:amount",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("member", order.get("member_id")).addValue("amount", amount));
            if (debited != 1) throw new BusinessException(409106, "会员储值余额不足");
            new com.dajin.system.member.MemberBalanceLedger(db).record(storeId,order.get("member_id"),amount.negate(),"PROCESSING",requestId,userId(request));
        }
        long operator = userId(request); String shiftNo = shifts.current(storeId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("order", id).addValue("type", paymentType)
                .addValue("amount", amount).addValue("method", payMethod).addValue("requestId", requestId).addValue("operator", operator).addValue("remark", optionalText(body, "remark"));
        db.jdbc().update("insert into processing_payment(store_id,processing_order_id,payment_type,amount,pay_method,client_request_id,operator_id,remark,create_time) values(:s,:order,:type,:amount,:method,:requestId,:operator,:remark,now())", p);
        db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'INCOME','PROCESSING_FEE',:amount,:method,:orderNo,:operator,:financeRemark,:shift,now())",
                p.addValue("orderNo", order.get("order_no")).addValue("financeRemark", "DEPOSIT".equals(paymentType) ? "加工定金" : "加工尾款").addValue("shift", shiftNo));
        if (groupPayment) {
            p.addValue("discount", discount).addValue("voucher", voucherNo);
            try {
                db.jdbc().update("update processing_order set original_due_amount=due_amount,promotion_discount=:discount,promotion_channel=:method,voucher_no=:voucher,due_amount=due_amount-:discount,paid_amount=paid_amount+:amount,version=version+1,update_time=now() where processing_order_id=:order and store_id=:s", p);
            } catch (DuplicateKeyException e) {
                throw new BusinessException(409717, "团购核销单号已用于其他加工单");
            }
        } else {
            db.jdbc().update("update processing_order set paid_amount=paid_amount+:amount,version=version+1,update_time=now() where processing_order_id=:order and store_id=:s", p);
        }
        log(storeId, operator, "ORDER_PAYMENT", "加工单=" + order.get("order_no") + "," + paymentType + "=" + amount + (groupPayment ? ",团购优惠=" + discount + ",核销号=" + voucherNo : ""));
        Map<String, Object> result = orderDetail(id, storeId); broadcast("PROCESSING_ORDER_UPDATED", processingOrderEvent(result, "PAYMENT"));
        Map<String,Object> financeEvent = Map.of("storeId", storeId, "processingOrderId", id, "action", "PAYMENT");
        broadcast("REPORT_UPDATED", financeEvent); broadcast("SHIFT_UPDATED", financeEvent);
        return ApiResponse.ok(result);
    }

    @GetMapping("/commissions")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> commissions(@RequestParam(required = false) String status, @RequestParam(required = false) Long employeeId, HttpServletRequest request) {
        String sql = "select c.*,o.order_no,o.item_name_snapshot,u.real_name employee_name from processing_commission c join processing_order o on o.processing_order_id=c.processing_order_id and o.store_id=c.store_id left join sys_user u on u.user_id=c.employee_id and u.store_id=c.store_id where c.store_id=:s and (:status is null or c.status=:status) and (:employee is null or c.employee_id=:employee) order by c.commission_id desc";
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
        return ApiResponse.ok(db.one("select coalesce(sum(commission_amount),0) total,coalesce(sum(case when status='PENDING' then commission_amount else 0 end),0) pending,coalesce(sum(case when status='PAID' then commission_amount else 0 end),0) paid,count(*) count from processing_commission where store_id=:s", Map.of("s", store(request))));
    }

    @GetMapping("/statistics")
    @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> statistics(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                                     @RequestParam(required = false) Long craftsmanId, HttpServletRequest request) {
        long storeId = store(request);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("from", blankToNull(from)).addValue("to", blankToNull(to)).addValue("craftsman", craftsmanId);
        String filters = " where o.store_id=:s and (:from is null or date(o.create_time)>=:from) and (:to is null or date(o.create_time)<=:to) and (:craftsman is null or o.craftsman_id=:craftsman)";
        String orderFrom = " from processing_order o" + filters;
        Map<String, Object> summary = db.one("select count(*) order_count,coalesce(sum(case when status='PENDING' then 1 else 0 end),0) pending_count,coalesce(sum(case when status='PROCESSING' then 1 else 0 end),0) processing_count,coalesce(sum(case when status in ('COMPLETED','PICKED_UP') then 1 else 0 end),0) completed_count,coalesce(sum(labor_fee),0) labor_fee,coalesce(sum(due_amount),0) due_amount,coalesce(sum(paid_amount),0) paid_amount,coalesce(sum(due_amount-paid_amount),0) outstanding" + orderFrom, p);
        BigDecimal commission = db.jdbc().queryForObject("select coalesce(sum(c.commission_amount),0) from processing_commission c join processing_order o on o.processing_order_id=c.processing_order_id and o.store_id=c.store_id" + filters, p, BigDecimal.class);
        summary.put("commission_expense", commission == null ? BigDecimal.ZERO : commission);
        summary.put("item_ranking", db.list("select o.item_name_snapshot item_name,count(*) order_count,coalesce(sum(o.labor_fee),0) labor_fee,coalesce(sum(o.paid_amount),0) paid_amount" + orderFrom + " group by o.item_name_snapshot order by paid_amount desc limit 10", p));
        return ApiResponse.ok(summary);
    }

    private Map<String, Object> orderDetail(long id, long storeId) {
        Map<String, Object> order;
        try {
            order = db.one("select o.*,m.name member_name,u.real_name craftsman_name,creator.real_name creator_name from processing_order o left join member m on m.member_id=o.member_id and m.store_id=o.store_id left join sys_user u on u.user_id=o.craftsman_id and u.store_id=o.store_id left join sys_user creator on creator.user_id=o.created_by and creator.store_id=o.store_id where o.processing_order_id=:id and o.store_id=:s", Map.of("id", id, "s", storeId));
        } catch (Exception e) { throw new BusinessException(404701, "加工订单不存在"); }
        order.put("payments", db.list("select p.*,u.real_name operator_name from processing_payment p left join sys_user u on u.user_id=p.operator_id and u.store_id=p.store_id where p.store_id=:s and p.processing_order_id=:id order by p.payment_id", Map.of("s", storeId, "id", id)));
        order.put("commissions", db.list("select c.*,u.real_name employee_name from processing_commission c left join sys_user u on u.user_id=c.employee_id and u.store_id=c.store_id where c.store_id=:s and c.processing_order_id=:id order by c.commission_id", Map.of("s", storeId, "id", id)));
        return order;
    }

    private Map<String, Object> lockedOrder(long id, long storeId) {
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
        int exists = count("select count(*) from processing_commission where processing_order_id=:id and employee_id=:employee", orderId, ((Number) craftsman).longValue(), true);
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

    private BigDecimal retailGoldPrice(long storeId) {
        List<Map<String, Object>> rows = db.list("select price from gold_price where store_id=:s and price_type='足金' order by date desc,price_id desc limit 1", Map.of("s", storeId));
        return rows.isEmpty() ? null : decimal(rows.get(0).get("price"));
    }

    private long goldMaterialId(long storeId) {
        List<Map<String, Object>> goodsRows = db.list("select goods_id from goods where store_id=:s and name='足金用料' and status=1 order by goods_id limit 1", Map.of("s", storeId));
        if (!goodsRows.isEmpty()) return ((Number) goodsRows.get(0).get("goods_id")).longValue();
        List<Map<String, Object>> roots = db.list("select category_id from goods_category where store_id=:s and level=1 and status=1 order by sort,category_id limit 1", Map.of("s", storeId));
        if (roots.isEmpty()) throw new BusinessException(400723, "缺少商品分类，无法建档金料");
        db.jdbc().update("insert into goods(store_id,barcode,name,category_id,weight,cost_price,sale_price,price_type,gold_type,stock,status,images,version) values(:s,:barcode,'足金用料',:category,null,0,0,2,'足金',0,1,'[]',0)",
                new MapSqlParameterSource().addValue("s", storeId).addValue("barcode", "GOLD-MAT-" + storeId).addValue("category", roots.get(0).get("category_id")));
        return db.jdbc().queryForObject("select goods_id from goods where store_id=:s and barcode=:barcode", new MapSqlParameterSource().addValue("s", storeId).addValue("barcode", "GOLD-MAT-" + storeId), Long.class);
    }

    /** 店供金料：扣减"足金用料"商品库存并生成出库流水，保证金料账实一致。 */
    private void deductGoldMaterial(long storeId, long orderId, String orderNo, BigDecimal weight, long operatorId) {
        long goodsId = goldMaterialId(storeId);
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
        long goodsId = goldMaterialId(storeId);
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
    private String pricingUnit(Object value) {
        String unit = value == null ? null : String.valueOf(value).trim();
        if (unit == null || unit.isBlank()) return "按件";
        if (!"按件".equals(unit) && !"按克".equals(unit)) throw new BusinessException(400720, "计价方式只能是按件或按克");
        return unit;
    }
    private String trimToEmpty(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private void ensureUnique(String table, String field, String value, long currentId, long storeId, String message) {
        String idField = "processing_category".equals(table) ? "category_id" : "item_id";
        int found = db.jdbc().queryForObject("select count(*) from " + table + " where store_id=:s and " + field + "=:value and " + idField + "<>:id", Map.of("s", storeId, "value", value, "id", currentId), Integer.class);
        if (found > 0) throw new BusinessException(409709, message);
    }
    private boolean canTransition(String current, String next) { return ("PENDING".equals(current) && "PROCESSING".equals(next)) || ("PROCESSING".equals(current) && "COMPLETED".equals(next)) || ("COMPLETED".equals(current) && "PICKED_UP".equals(next)); }
    private String orderNo() { return "JG" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")) + UUID.randomUUID().toString().replace("-", "").substring(0, 4).toUpperCase(Locale.ROOT); }
    private long store(HttpServletRequest request) { return db.store(request); }
    private long userId(HttpServletRequest request) { Claims claims = (Claims) request.getAttribute("claims"); return claims == null ? 0L : Long.parseLong(claims.getSubject()); }
    private boolean isSales(HttpServletRequest request) { Claims claims = (Claims) request.getAttribute("claims"); return claims != null && "SALES".equalsIgnoreCase(String.valueOf(claims.get("role"))); }
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
}
