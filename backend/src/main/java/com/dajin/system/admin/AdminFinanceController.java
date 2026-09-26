package com.dajin.system.admin;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
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
    @RequirePermission("report:view:all")
    public ApiResponse<?> records(@RequestParam(required = false) String payMethod,
                                  @RequestParam(required = false) String start,
                                  @RequestParam(required = false) String end,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "500") int pageSize,
                                  @RequestParam(defaultValue = "false") boolean all,
                                  HttpServletRequest request) {
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
                + "where f.store_id=:s and (:m is null or f.pay_method=:m) "
                + "and (:start is null or f.create_time>=:start) "
                + "and (:end is null or f.create_time<date_add(:end,interval 1 day)) "
                + "order by f.finance_id desc" + limitSql, query));
    }

    @GetMapping("/finance/shifts")
    @RequirePermission("shift:confirm")
    public ApiResponse<?> shifts(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select log_id shift_id,user_id,content,create_time from operation_log where store_id=:s and module='SHIFT' and action='CONFIRM' order by log_id desc limit 200", params(request)));
    }

    @GetMapping("/finance/summary")
    @RequirePermission("report:view:all")
    public ApiResponse<?> summary(@RequestParam(required = false) String start,
                                  @RequestParam(required = false) String end,
                                  HttpServletRequest request) {
        String rangeStart = start;
        String rangeEnd = end;
        if ((start == null || start.isBlank()) && (end == null || end.isBlank())) {
            rangeStart = LocalDate.now().toString();
            rangeEnd = rangeStart;
        }
        MapSqlParameterSource query = params(request).addValue("start", rangeStart).addValue("end", rangeEnd);
        String businessType = "case "
                + "when type='EXPENSE' and category='SALE_REFUND' then 'SALE_REFUND' "
                + "when type='EXPENSE' and category='RECYCLE' then 'RECYCLE_EXPENSE' "
                + "when type='EXPENSE' then 'OTHER_EXPENSE' "
                + "when category='SALE' then 'SALE_INCOME' "
                + "when category='PROCESSING_FEE' then 'PROCESSING_INCOME' "
                + "else 'OTHER_INCOME' end";
        return ApiResponse.ok(db.list("select type," + businessType + " business_type,"
                + "coalesce(pay_method,'未指定') pay_method,sum(amount) amount,count(*) count "
                + "from finance_record where store_id=:s "
                + "and (:start is null or create_time>=:start) "
                + "and (:end is null or create_time<date_add(:end,interval 1 day)) "
                + "group by type," + businessType + ",pay_method "
                + "order by type desc,business_type,pay_method", query));
    }

    @GetMapping("/finance/gross-profit")
    @RequirePermission("report:view:all")
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
    @RequirePermission("report:view:all")
    public ApiResponse<?> commissionRules(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select * from commission_rule where store_id=:s order by rule_id desc", params(request)));
    }

    @GetMapping("/commission/records")
    @RequirePermission("report:view:all")
    public ApiResponse<?> commissionRecords(@RequestParam(required = false) String month, HttpServletRequest request) {
        return ApiResponse.ok(db.list("select cr.*,u.real_name from commission_record cr left join sys_user u on u.user_id=cr.user_id and u.store_id=cr.store_id where cr.store_id=:s and cr.month=coalesce(:m,date_format(curdate(),'%Y-%m')) order by commission_amount desc", params(request).addValue("m", month)));
    }
}
