package com.dajin.system.admin;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.ReportAccess;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Finance-facing read endpoints kept separate from operational administration routes. */
@RestController
@RequestMapping("/api/admin")
@RequireRoles({"ADMIN", "MANAGER"})
public class AdminFinanceController {
    private final DbSupport db;

    public AdminFinanceController(DbSupport db) { this.db = db; }

    private MapSqlParameterSource params(HttpServletRequest request) {
        return new MapSqlParameterSource("s", db.store(request));
    }

    @GetMapping("/finance/records")
    @RequirePermission(value = {"report:daily", "report:monthly"}, anyOf = true)
    public ApiResponse<?> records(@RequestParam(required = false) String payMethod,
                                  @RequestParam(required = false) String start,
                                  @RequestParam(required = false) String end,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "500") int pageSize,
                                  @RequestParam(defaultValue = "false") boolean all,
                                  HttpServletRequest request) {
        requireFinanceReport(request);
        int size = Math.max(1, Math.min(pageSize, 500));
        int offset = Math.max(0, page - 1) * size;
        MapSqlParameterSource query = params(request)
                .addValue("m", payMethod)
                .addValue("startAt", dateBoundary(start, false))
                .addValue("endAt", dateBoundary(end, true))
                .addValue("limit", size)
                .addValue("offset", offset);
        String limitSql = all ? "" : " limit :limit offset :offset";
        List<Map<String,Object>> rows = db.list("select f.*,p.processing_order_id,p.original_due_amount processing_original_due_amount,"
                + "coalesce(p.promotion_discount,0) processing_promotion_discount,"
                + "p.promotion_channel processing_promotion_channel,p.voucher_no processing_voucher_no "
                + "from finance_record f left join processing_order p on p.store_id=f.store_id "
                + "and p.order_no=f.related_bill_no and f.category='PROCESSING_FEE' "
                + "where f.store_id=:s" + financeFilter(request, "f.") + " and (:m is null or f.pay_method=:m) "
                + "and (f.category<>'PROCESSING_FEE' or p.processing_order_id is null or p.status<>'WITHDRAWN') "
                + "and (f.category<>'PROCESSING_FEE' or p.processing_order_id is null or p.member_id is null or exists (select 1 from member pm where pm.store_id=p.store_id and pm.member_id=p.member_id and pm.deleted=0)) "
                 + "and (:startAt is null or f.create_time>=:startAt) "
                 + "and (:endAt is null or f.create_time<:endAt) "
                 + "order by f.finance_id desc" + limitSql, query);
        // A combination payment keeps one aggregate finance row for audit, but
        // expose its real channel split alongside that row for the finance UI
        // and exports. Legacy payments without component rows simply return an
        // empty list and retain their original aggregate presentation.
        for (Map<String,Object> row : rows) {
            Object orderId = row.get("processing_order_id");
            if (orderId == null) continue;
            Object financeClient = row.get("client_request_id");
            Object financeAmount = row.get("amount");
            Object financeMethod = row.get("pay_method");
            row.put("processing_components", db.list("select c.component_id,c.payment_id,c.pay_method,c.amount,c.voucher_no,c.status,c.create_time,pc.channel_name pay_method_name "
                    + "from processing_payment_component c left join pay_channel pc on pc.store_id=c.store_id and pc.channel_code=c.pay_method "
                    + "join processing_payment pp on pp.store_id=c.store_id and pp.payment_id=c.payment_id "
                    + "where c.store_id=:s and c.processing_order_id=:order and ("
                    + "(:financeClient is not null and pp.client_request_id=:financeClient) or "
                    + "(:financeClient is null and pp.client_request_id is null and pp.amount=:financeAmount and pp.pay_method=:financeMethod)) "
                    + "order by c.component_id", new MapSqlParameterSource().addValue("s", db.store(request)).addValue("order", orderId)
                    .addValue("financeClient", financeClient).addValue("financeAmount", financeAmount).addValue("financeMethod", financeMethod)));
        }
        return ApiResponse.ok(rows);
    }

    @GetMapping("/finance/shifts")
    @RequirePermission(value = {"shift:confirm", "report:daily", "report:monthly"}, anyOf = true)
    public ApiResponse<?> shifts(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select log_id shift_id,user_id,content,create_time from operation_log where store_id=:s and module='SHIFT' and action='CONFIRM' order by log_id desc limit 200", params(request)));
    }

    @GetMapping("/finance/summary")
    @RequirePermission(value = {"report:daily", "report:monthly"}, anyOf = true)
    public ApiResponse<?> summary(@RequestParam(required = false) String start,
                                  @RequestParam(required = false) String end,
                                  HttpServletRequest request) {
        requireFinanceReport(request);
        String rangeStart = start;
        String rangeEnd = end;
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            rangeStart = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            rangeEnd = rangeStart;
        }
        MapSqlParameterSource query = params(request).addValue("startAt", dateBoundary(rangeStart, false)).addValue("endAt", dateBoundary(rangeEnd, true));
        String businessType = "case "
                + "when type='EXPENSE' and category='SALE_REFUND' then 'SALE_REFUND' "
                + "when type='EXPENSE' and category in ('SALE_WITHDRAW','PROCESSING_WITHDRAW') then 'WITHDRAW_EXPENSE' "
                + "when type='INCOME' and category='PROCESSING_WITHDRAW' then 'WITHDRAW_REVERSAL' "
                + "when type='EXPENSE' and category='RECYCLE' then 'RECYCLE_EXPENSE' "
                + "when type='EXPENSE' then 'OTHER_EXPENSE' "
                + "when category='SALE' then 'SALE_INCOME' "
                + "when category='PROCESSING_FEE' then 'PROCESSING_INCOME' "
                + "else 'OTHER_INCOME' end";
        List<Map<String,Object>> summary = new ArrayList<>(db.list("select type," + businessType + " business_type,"
                + "coalesce(pay_method,'未指定') pay_method,sum(amount) amount,count(*) count "
                + "from finance_record where store_id=:s " + financeFilter(request, "")
                // Every processing payment has component rows (including a
                // single-channel payment).  Do not return the aggregate
                // PROCESSING_FEE row here, otherwise the component breakdown
                // below would double-count ordinary cash/wechat payments.
                + "and category<>'PROCESSING_FEE' "
                + "and (:startAt is null or create_time>=:startAt) "
                + "and (:endAt is null or create_time<:endAt) "
                + "group by type," + businessType + ",pay_method "
                + "order by type desc,business_type,pay_method", query));
        // Components belong to one processing_payment, while an order can have
        // several finance rows (deposit, balance, discount settlement).  Joining
        // by order number would multiply every component by that row count.  Use
        // an EXISTS predicate tied to the payment's request id instead, so each
        // component is counted exactly once.  The amount/order fallback keeps
        // legacy payments (created before request ids were recorded) visible.
        summary.addAll(db.list("select 'INCOME' type,'PROCESSING_INCOME' business_type,c.pay_method,"
                + "sum(c.amount) amount,count(*) count,1 component_breakdown "
                + "from processing_payment_component c "
                + "join processing_payment pp on pp.payment_id=c.payment_id and pp.store_id=c.store_id "
                + "join processing_order po on po.processing_order_id=pp.processing_order_id and po.store_id=pp.store_id "
                + "where c.store_id=:s and c.status='ACTIVE' and c.reversal_of_component_id is null and po.status<>'WITHDRAWN' and (po.member_id is null or exists (select 1 from member pm where pm.store_id=po.store_id and pm.member_id=po.member_id and pm.deleted=0)) "
                + "and exists (select 1 from finance_record f where f.store_id=pp.store_id and f.category='PROCESSING_FEE' and f.type='INCOME' "
                + "and ((pp.client_request_id is not null and f.client_request_id=pp.client_request_id) "
                + "or (pp.client_request_id is null and f.related_bill_no=po.order_no and f.amount=pp.amount and f.pay_method=pp.pay_method)) "
                + financeFilter(request, "f.")
                + "and (:startAt is null or f.create_time>=:startAt) and (:endAt is null or f.create_time<:endAt)) "
                + "group by c.pay_method", query));
        return ApiResponse.ok(summary);
    }

    @GetMapping("/finance/gross-profit")
    @RequirePermission("report:store-performance")
    public ApiResponse<?> grossProfit(@RequestParam(required = false) String start,
                                      @RequestParam(required = false) String end,
                                      HttpServletRequest request) {
        MapSqlParameterSource query = params(request).addValue("startAt", dateBoundary(start, false)).addValue("endAt", dateBoundary(end, true));
        String sql = "select root.category_id,root.name category,"
                + "coalesce(sum(case when o.status=1 then i.subtotal else 0 end),0) revenue,"
                + "coalesce(sum(case when o.status=1 then coalesce(i.cost_snapshot,0) else 0 end),0) cost,"
                + "coalesce(sum(case when o.status=1 then i.subtotal-coalesce(i.cost_snapshot,0) else 0 end),0) gross_profit,"
                + "case when coalesce(sum(case when o.status=1 then i.subtotal else 0 end),0)=0 then 0 "
                + "else coalesce(sum(case when o.status=1 then i.subtotal-coalesce(i.cost_snapshot,0) else 0 end),0)/sum(case when o.status=1 then i.subtotal else 0 end) end gross_margin "
                + "from goods_category root left join goods_category child on child.parent_id=root.category_id and child.store_id=root.store_id "
                + "left join goods g on g.category_id=child.category_id and g.store_id=child.store_id "
                + "left join sales_order_item i on i.goods_id=g.goods_id and i.store_id=g.store_id "
                + "left join sales_order o on o.order_id=i.order_id and o.store_id=i.store_id left join member m on m.member_id=o.member_id and m.store_id=o.store_id "
                + "and (:startAt is null or o.create_time>=:startAt) "
                + "and (:endAt is null or o.create_time<:endAt) "
                + "where root.store_id=:s and root.level=1 and root.status=1 and (o.member_id is null or m.deleted=0) "
                + "group by root.category_id,root.name,root.sort order by root.sort,root.category_id";
        return ApiResponse.ok(db.list(sql, query));
    }

    @GetMapping("/commission/rules")
    @RequirePermission(value = {"report:commission", "commission:manage"}, anyOf = true)
    public ApiResponse<?> commissionRules(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select * from commission_rule where store_id=:s order by rule_id desc", params(request)));
    }

    @GetMapping("/commission/records")
    @RequirePermission("report:commission")
    public ApiResponse<?> commissionRecords(@RequestParam(required = false) String month, HttpServletRequest request) {
        // 提成只在「已取货」后计提到 commission_record；但只回有记录的人会让老板以为漏算（本月有单未取货的导购一条都不显示）。
        // 所以这里把"本月开过单/有待取货/已有提成"的导购全列出来，并附上待取货的预估提成。
        String monthValue = month == null || month.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")) : month.trim();
        java.time.YearMonth parsedMonth;
        try { parsedMonth = java.time.YearMonth.parse(monthValue); }
        catch (java.time.format.DateTimeParseException ex) { throw new com.dajin.system.common.BusinessException(400422, "月份格式应为YYYY-MM"); }
        MapSqlParameterSource p = params(request).addValue("m", monthValue)
                .addValue("monthStart", parsedMonth.atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .addValue("monthEnd", parsedMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        java.util.List<java.util.Map<String, Object>> rateRows = db.list("select config_value from sys_config where store_id=:s and config_key='processing_sales_commission_rate' and enabled=1", p);
        java.math.BigDecimal rate = java.math.BigDecimal.valueOf(0.01);
        if (!rateRows.isEmpty() && rateRows.get(0).get("config_value") != null) {
            try { rate = new java.math.BigDecimal(String.valueOf(rateRows.get(0).get("config_value"))); } catch (NumberFormatException ignored) { }
        }
        p.addValue("rate", rate);
        String base = com.dajin.system.processing.ProcessingAmounts.laborBase("pp");
        return ApiResponse.ok(db.list("select u.user_id,coalesce(u.real_name,u.username) real_name,coalesce(cr.month,:m) month,"
                + "coalesce(cr.sales_amount,0) sales_amount,coalesce(cr.processing_base,0) processing_base,"
                + "coalesce(cr.processing_commission,0) processing_commission,coalesce(cr.commission_amount,0) commission_amount,cr.detail,"
                + "coalesce(pending.cnt,0) pending_pickup_orders,coalesce(pending.base_amount,0) pending_pickup_base,"
                + "round(coalesce(pending.base_amount,0)*:rate,2) pending_pickup_commission "
                + "from sys_user u "
                + "left join commission_record cr on cr.store_id=u.store_id and cr.user_id=u.user_id and cr.month=:m "
                + "left join (select pp.sales_id,count(*) cnt,sum(" + base + ") base_amount from processing_order pp "
                + "where pp.store_id=:s and (pp.member_id is null or exists (select 1 from member pm where pm.store_id=pp.store_id and pm.member_id=pp.member_id and pm.deleted=0)) and pp.status='COMPLETED' and coalesce(pp.completed_time,pp.create_time)>=:monthStart and coalesce(pp.completed_time,pp.create_time)<:monthEnd group by pp.sales_id) pending "
                + "on pending.sales_id=u.user_id "
                + "where u.store_id=:s and u.status=1 and (cr.user_id is not null or pending.cnt is not null "
                + "or exists (select 1 from processing_order p2 where p2.store_id=u.store_id and p2.sales_id=u.user_id and (p2.member_id is null or exists (select 1 from member p2m where p2m.store_id=p2.store_id and p2m.member_id=p2.member_id and p2m.deleted=0)) and p2.status<>'WITHDRAWN' and p2.create_time>=:monthStart and p2.create_time<:monthEnd) "
                + "or exists (select 1 from sales_order o where o.store_id=u.store_id and o.sales_id=u.user_id and o.status<>6 and o.create_time>=:monthStart and o.create_time<:monthEnd)) "
                + "order by coalesce(cr.commission_amount,0) desc,coalesce(pending.cnt,0) desc,u.user_id", p));
    }

    private void requireFinanceReport(HttpServletRequest request) {
        String kind = request.getParameter("reportType");
        if (kind != null && !java.util.Set.of("daily", "monthly").contains(kind))
            throw new com.dajin.system.common.BusinessException(400431, "财务报表类型不正确");
        ReportAccess.require(request, "monthly".equals(kind) ? "report:monthly" : "report:daily");
    }

    private String financeFilter(HttpServletRequest request, String prefix) {
        String filter = "";
        if (!ReportAccess.enabled(request, "report:processing")) filter += " and " + prefix + "category<>'PROCESSING_FEE'";
        if (!ReportAccess.enabled(request, "report:recycle")) filter += " and " + prefix + "category<>'RECYCLE'";
        if (!ReportAccess.enabled(request, "report:commission")) filter += " and " + prefix + "category not in ('COMMISSION','PROCESSING_COMMISSION')";
        return filter;
    }

    private String dateBoundary(String value, boolean exclusiveEnd) {
        if (value == null || value.isBlank()) return null;
        try {
            LocalDate date = LocalDate.parse(value.trim());
            if (exclusiveEnd) date = date.plusDays(1);
            return date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toLocalDateTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (java.time.format.DateTimeParseException ex) {
            throw new com.dajin.system.common.BusinessException(400422, "日期格式应为YYYY-MM-DD");
        }
    }
}
