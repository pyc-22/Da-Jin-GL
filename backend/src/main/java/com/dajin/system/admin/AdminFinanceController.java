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
                .addValue("start", start)
                .addValue("end", end)
                .addValue("limit", size)
                .addValue("offset", offset);
        String limitSql = all ? "" : " limit :limit offset :offset";
        return ApiResponse.ok(db.list("select f.*,p.processing_order_id,p.original_due_amount processing_original_due_amount,"
                + "coalesce(p.promotion_discount,0) processing_promotion_discount,"
                + "p.promotion_channel processing_promotion_channel,p.voucher_no processing_voucher_no "
                + "from finance_record f left join processing_order p on p.store_id=f.store_id "
                + "and p.order_no=f.related_bill_no and f.category='PROCESSING_FEE' "
                + "where f.store_id=:s" + financeFilter(request, "f.") + " and (:m is null or f.pay_method=:m) "
                + "and (:start is null or f.create_time>=:start) "
                + "and (:end is null or f.create_time<date_add(:end,interval 1 day)) "
                + "order by f.finance_id desc" + limitSql, query));
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
            rangeStart = LocalDate.now().toString();
            rangeEnd = rangeStart;
        }
        MapSqlParameterSource query = params(request).addValue("start", rangeStart).addValue("end", rangeEnd);
        String businessType = "case "
                + "when type='EXPENSE' and category='SALE_REFUND' then 'SALE_REFUND' "
                + "when type='EXPENSE' and category in ('SALE_WITHDRAW','PROCESSING_WITHDRAW') then 'WITHDRAW_EXPENSE' "
                + "when type='INCOME' and category='PROCESSING_WITHDRAW' then 'WITHDRAW_REVERSAL' "
                + "when type='EXPENSE' and category='RECYCLE' then 'RECYCLE_EXPENSE' "
                + "when type='EXPENSE' then 'OTHER_EXPENSE' "
                + "when category='SALE' then 'SALE_INCOME' "
                + "when category='PROCESSING_FEE' then 'PROCESSING_INCOME' "
                + "else 'OTHER_INCOME' end";
        return ApiResponse.ok(db.list("select type," + businessType + " business_type,"
                + "coalesce(pay_method,'未指定') pay_method,sum(amount) amount,count(*) count "
                + "from finance_record where store_id=:s " + financeFilter(request, "")
                + "and (:start is null or create_time>=:start) "
                + "and (:end is null or create_time<date_add(:end,interval 1 day)) "
                + "group by type," + businessType + ",pay_method "
                + "order by type desc,business_type,pay_method", query));
    }

    @GetMapping("/finance/gross-profit")
    @RequirePermission("report:store-performance")
    public ApiResponse<?> grossProfit(@RequestParam(required = false) String start,
                                      @RequestParam(required = false) String end,
                                      HttpServletRequest request) {
        MapSqlParameterSource query = params(request).addValue("start", start).addValue("end", end);
        String sql = "select root.category_id,root.name category,"
                + "coalesce(sum(case when o.status=1 then i.subtotal else 0 end),0) revenue,"
                + "coalesce(sum(case when o.status=1 then coalesce(i.cost_snapshot,0) else 0 end),0) cost,"
                + "coalesce(sum(case when o.status=1 then i.subtotal-coalesce(i.cost_snapshot,0) else 0 end),0) gross_profit,"
                + "case when coalesce(sum(case when o.status=1 then i.subtotal else 0 end),0)=0 then 0 "
                + "else coalesce(sum(case when o.status=1 then i.subtotal-coalesce(i.cost_snapshot,0) else 0 end),0)/sum(case when o.status=1 then i.subtotal else 0 end) end gross_margin "
                + "from goods_category root left join goods_category child on child.parent_id=root.category_id and child.store_id=root.store_id "
                + "left join goods g on g.category_id=child.category_id and g.store_id=child.store_id "
                + "left join sales_order_item i on i.goods_id=g.goods_id and i.store_id=g.store_id "
                + "left join sales_order o on o.order_id=i.order_id and o.store_id=i.store_id "
                + "and (:start is null or o.create_time>=:start) "
                + "and (:end is null or o.create_time<date_add(:end,interval 1 day)) "
                + "where root.store_id=:s and root.level=1 and root.status=1 "
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
        MapSqlParameterSource p = params(request).addValue("m",
                month == null || month.isBlank() ? LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM")) : month);
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
                + "where pp.store_id=:s and pp.status='COMPLETED' and date_format(coalesce(pp.completed_time,pp.create_time),'%Y-%m')=:m group by pp.sales_id) pending "
                + "on pending.sales_id=u.user_id "
                + "where u.store_id=:s and u.status=1 and (cr.user_id is not null or pending.cnt is not null "
                + "or exists (select 1 from processing_order p2 where p2.store_id=u.store_id and p2.sales_id=u.user_id and p2.status<>'WITHDRAWN' and date_format(p2.create_time,'%Y-%m')=:m) "
                + "or exists (select 1 from sales_order o where o.store_id=u.store_id and o.sales_id=u.user_id and o.status<>6 and date_format(o.create_time,'%Y-%m')=:m)) "
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
}
