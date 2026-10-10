package com.dajin.system.report;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.ReportAccess;
import com.dajin.system.order.SalesAmounts;
import com.dajin.system.processing.ProcessingAmounts;
import io.jsonwebtoken.Claims;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.*;

/** Report APIs shared by the mobile app and the administration portal. */
@RestController
@RequestMapping("/api/report")
@RequirePermission("report:view")
public class ReportController {
    private final DbSupport db;
    public ReportController(DbSupport db) { this.db = db; }

    @GetMapping("/daily") @RequirePermission("report:view")
    public ApiResponse<?> daily(@RequestParam(required = false) String date, HttpServletRequest request) {
        requireManagerReport(request, "report:daily");
        String day = date == null || date.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")).toString() : date;
        Map<String,Object> result = db.one("select count(*) order_count,coalesce(sum(" + actualPaid("o") + "),0) amount,coalesce(sum(o.labor_fee),0) labor from sales_order o where o.store_id=:s" + salesWhere(request,"o.sales_id") + " and date(o.create_time)=:day and (o.status=1 or (o.status=0 and o.pay_amount>0))", scope(request).addValue("day", day));
        String processingScope = isSales(request) ? " and exists (select 1 from processing_order po where po.store_id=f.store_id and po.order_no=f.related_bill_no and po.sales_id=:uid)" : "";
        BigDecimal processing = db.jdbc().queryForObject("select coalesce(sum(f.amount),0) from finance_record f where f.store_id=:s and f.type='INCOME' and f.category='PROCESSING_FEE'" + activeProcessingFinance("f") + " and date(f.create_time)=:day" + processingScope, scope(request).addValue("day", day), BigDecimal.class);
        result.put("processing_amount", processing);
        result.put("turnover", new BigDecimal(String.valueOf(result.getOrDefault("amount", "0"))).add(processing));
        return ApiResponse.ok(ReportAccess.filter(request, result));
    }

    @GetMapping("/monthly") @RequirePermission("report:view")
    public ApiResponse<?> monthly(@RequestParam(required = false) String month, HttpServletRequest request) {
        requireManagerReport(request, "report:monthly");
        String value = month == null || month.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().substring(0,7) : month;
        String processingScope = isSales(request) ? " and exists (select 1 from processing_order po where po.store_id=f.store_id and po.order_no=f.related_bill_no and po.sales_id=:uid)" : "";
        return ApiResponse.ok(ReportAccess.filter(request, db.list("select day,sum(order_count) order_count,sum(amount)+sum(processing_amount) amount,sum(amount) sales_amount,sum(processing_amount) processing_amount,sum(amount)+sum(processing_amount) turnover from ("
                + "select date(o.create_time) day,count(*) order_count,coalesce(sum(" + actualPaid("o") + "),0) amount,0 processing_amount from sales_order o where o.store_id=:s"
                + salesWhere(request,"o.sales_id") + " and date_format(o.create_time,'%Y-%m')=:month and (o.status=1 or (o.status=0 and o.pay_amount>0)) group by date(o.create_time)"
                + " union all select date(f.create_time) day,0 order_count,0 amount,coalesce(sum(f.amount),0) processing_amount from finance_record f where f.store_id=:s"
                + processingScope + " and f.type='INCOME' and f.category='PROCESSING_FEE'" + activeProcessingFinance("f") + " and date_format(f.create_time,'%Y-%m')=:month group by date(f.create_time)"
                + ") daily group by day order by day", scope(request).addValue("month", value))));
    }

    @GetMapping("/commission") @RequirePermission("report:view")
    public ApiResponse<?> commission(@RequestParam(required = false) String month, HttpServletRequest request) {
        requireManagerReport(request, "report:commission");
        String value = month == null || month.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().substring(0,7) : month;
        return ApiResponse.ok(ReportAccess.filter(request, db.list("select cr.*,u.real_name employee_name from commission_record cr left join sys_user u on u.user_id=cr.user_id and u.store_id=cr.store_id where cr.store_id=:s" + salesWhere(request,"cr.user_id") + " and cr.month=:month order by cr.commission_amount desc", scope(request).addValue("month", value))));
    }

    @GetMapping("/performance")
    public ApiResponse<?> performance(@RequestParam(required = false) Long userId, @RequestParam(required = false) String month, HttpServletRequest request) {
        requireManagerReport(request, "report:store-performance");
        Long selected = selectedEmployee(request,userId); String value = month == null || month.isBlank() ? LocalDate.now(ZoneId.of("Asia/Shanghai")).toString().substring(0,7) : month;
        MapSqlParameterSource p = scope(request).addValue("selectedUid",selected).addValue("month",value); String where = selected == null ? "" : " and o.sales_id=:selectedUid";
        Map<String,Object> result = new LinkedHashMap<>(db.one("select coalesce(sum(" + actualPaid("o") + "),0) amount,count(*) order_count,coalesce(avg(" + actualPaid("o") + "),0) avg_order from sales_order o where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0)) and date_format(o.create_time,'%Y-%m')=:month"+ (selected == null ? "" : " and o.sales_id=:selectedUid"),p));
        Range monthRange = new Range(value + "-01", LocalDate.parse(value + "-01").withDayOfMonth(1).plusMonths(1).minusDays(1).toString(), "本月");
        Map<String, Object> commission = commissionTotals(request, monthRange, selected);
        result.put("sales_commission", commission.get("sales_commission"));
        result.put("processing_base", commission.get("processing_base"));
        result.put("processing_commission", commission.get("processing_commission"));
        result.put("commission", commission.get("commission"));
        result.put("trend", db.list("select date(o.create_time) day,coalesce(sum(" + actualPaid("o") + "),0) amount,count(*) order_count from sales_order o where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0)) and o.create_time>=date_sub(curdate(),interval 6 day)"+(selected == null ? "" : " and o.sales_id=:selectedUid")+" group by date(o.create_time) order by day",p));
        List<Map<String,Object>> performanceEmployees = db.list("select o.sales_id user_id,coalesce(u.real_name,'未分配') employee_name,coalesce(sum(" + actualPaid("o") + "),0) sales_amount,count(o.order_id) order_count,0 processing_base,0 processing_commission,0 commission_amount from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0)) and o.sales_id is not null and date_format(o.create_time,'%Y-%m')=:month"+where+" group by o.sales_id,u.real_name order by sales_amount desc",p);
        mergeCommission(performanceEmployees, commissionRows(request, monthRange, selected));
        result.put("employees", performanceEmployees);
        return ApiResponse.ok(ReportAccess.filter(request, result));
    }

    @GetMapping("/overview")
    public ApiResponse<?> overview(@RequestParam(required = false) String timeType,@RequestParam(required = false) String startDate,@RequestParam(required = false) String endDate,@RequestParam(required = false) Long employeeId,@RequestParam(required = false) String category,HttpServletRequest request) {
        requireManagerReport(request, "report:store-performance");
        Range range=range(timeType,startDate,endDate); MapSqlParameterSource p=params(request,range,employeeId); String scope=orderScope(request,employeeId,"o"); String categoryFilter=category==null||category.isBlank()?"":" and coalesce(root.name,'未分类')=:category"; p.addValue("category",category);
        String orderFilter=" from sales_order o1 where o1.store_id=:s and (o1.status=1 or (o1.status=0 and o1.pay_amount>0))"+range.sql("o1.create_time")+orderScope(request,employeeId,"o1");
        String itemFilter=" from sales_order_item oi1 join sales_order o2 on o2.order_id=oi1.order_id and o2.store_id=oi1.store_id where o2.store_id=:s and (o2.status=1 or (o2.status=0 and o2.pay_amount>0))"+range.sql("o2.create_time")+orderScope(request,employeeId,"o2");
        String procScope=selectedEmployee(request,employeeId)==null?"":" and exists (select 1 from processing_order po where po.store_id=f.store_id and po.order_no=f.related_bill_no and po.sales_id=:employeeId)";
        Map<String,Object> summary=db.one("select coalesce((select sum("+actualPaid("o1")+")"+orderFilter+"),0) sales_amount,coalesce((select sum(f.amount) from finance_record f where f.store_id=:s and f.type='INCOME' and f.category='PROCESSING_FEE'"+activeProcessingFinance("f")+range.sql("f.create_time")+procScope+"),0) processing_amount,coalesce((select count(*)"+orderFilter+"),0) order_count,coalesce((select sum(oi1.qty)"+itemFilter+"),0) item_count,coalesce((select sum(oi1.weight*oi1.qty)"+itemFilter+"),0) gold_weight,coalesce((select sum(oi1.subtotal-coalesce(oi1.cost_snapshot,0))"+itemFilter+"),0) gross_profit,coalesce((select sum("+actualPaid("o1")+") from sales_order o1 where o1.store_id=:s and (o1.status=1 or (o1.status=0 and o1.pay_amount>0)) and o1.member_id is not null"+range.sql("o1.create_time")+orderScope(request,employeeId,"o1")+"),0) member_sales",p); double amount=number(summary.get("sales_amount")); summary.put("actual_paid",amount); summary.put("turnover",amount+number(summary.get("processing_amount"))); summary.put("avg_order",amount/Math.max(1,number(summary.get("order_count")))); summary.put("gross_margin",amount==0?0:number(summary.get("gross_profit"))/amount*100d);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("scope",isSales(request)?"PERSONAL":"STORE"); result.put("range",Map.of("start",range.start,"end",range.end,"label",range.label)); result.put("summary",summary);
        result.put("trend",db.list("select day,sum(order_count) order_count,sum(amount)+sum(processing_amount) amount,sum(amount) sales_amount,sum(processing_amount) processing_amount,sum(amount)+sum(processing_amount) turnover from ("
                + "select date(o.create_time) day,coalesce(sum("+actualPaid("o")+"),0) amount,count(*) order_count,0 processing_amount from sales_order o where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0))"+range.sql("o.create_time")+scope+" group by date(o.create_time)"
                + " union all select date(f.create_time) day,0 amount,0 order_count,coalesce(sum(f.amount),0) processing_amount from finance_record f where f.store_id=:s and f.type='INCOME' and f.category='PROCESSING_FEE'"+activeProcessingFinance("f")+range.sql("f.create_time")+procScope+" group by date(f.create_time)"
                + ") daily group by day order by day",p));
        result.put("categories",db.list("select coalesce(root.name,'未分类') category,coalesce(sum(oi.subtotal),0) amount,coalesce(sum(oi.qty),0) item_count,coalesce(sum(oi.weight*oi.qty),0) weight from sales_order o join sales_order_item oi on oi.order_id=o.order_id and oi.store_id=o.store_id left join goods g on g.goods_id=oi.goods_id and g.store_id=oi.store_id left join goods_category child on child.category_id=g.category_id and child.store_id=g.store_id left join goods_category root on root.category_id=case when child.level=2 then child.parent_id else child.category_id end and root.store_id=child.store_id where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0))"+range.sql("o.create_time")+scope+categoryFilter+" group by root.category_id,root.name order by amount desc",p));
        List<Map<String,Object>> overviewEmployees=db.list("select o.sales_id user_id,coalesce(u.real_name,'未分配') name,coalesce(sum("+actualPaid("o")+"),0) amount,coalesce(sum("+actualPaid("o")+"),0) actual_paid,count(*) order_count,coalesce((select sum(oi2.qty) from sales_order_item oi2 where oi2.store_id=o.store_id and oi2.order_id in (select o2.order_id from sales_order o2 where o2.store_id=o.store_id and o2.sales_id=o.sales_id and (o2.status=1 or (o2.status=0 and o2.pay_amount>0))"+range.sql("o2.create_time")+")),0) item_count,coalesce((select sum(oi2.weight*oi2.qty) from sales_order_item oi2 where oi2.store_id=o.store_id and oi2.order_id in (select o2.order_id from sales_order o2 where o2.store_id=o.store_id and o2.sales_id=o.sales_id and (o2.status=1 or (o2.status=0 and o2.pay_amount>0))"+range.sql("o2.create_time")+")),0) weight,0 processing_base,0 processing_commission,0 commission from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0)) and o.sales_id is not null"+range.sql("o.create_time")+scope+" group by o.sales_id,u.real_name order by amount desc",p);
        mergeCommission(overviewEmployees, commissionRows(request, range, employeeId));
        result.put("employees", overviewEmployees);
        result.put("records",db.list("select o.order_id,o.order_no,date_format(o.create_time,'%Y-%m-%d %H:%i') date,coalesce(group_concat(distinct oi.item_name order by oi.item_name separator '、'),'订单') goods_name,coalesce(max(root.name),'未分类') category,"+originalDue("o")+" original_amount,coalesce(o.settlement_discount,0) settlement_discount,"+discountedDue("o")+" discounted_amount,"+actualPaid("o")+" actual_paid,"+remainingDue("o")+" remaining_amount,o.settlement_discount_reason,coalesce(o.pay_method,'') pay_method,coalesce(u.real_name,'未分配') employee_name,"+actualPaid("o")+" amount,coalesce(sum(oi.weight*oi.qty),0) weight from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id left join sales_order_item oi on oi.order_id=o.order_id and oi.store_id=o.store_id left join goods g on g.goods_id=oi.goods_id and g.store_id=oi.store_id left join goods_category child on child.category_id=g.category_id and child.store_id=g.store_id left join goods_category root on root.category_id=case when child.level=2 then child.parent_id else child.category_id end and root.store_id=child.store_id where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0))"+range.sql("o.create_time")+scope+categoryFilter+" group by o.order_id,o.order_no,o.create_time,o.settlement_discount,o.settlement_discount_reason,o.pay_method,o.total_amount,o.discount,o.labor_fee,o.old_material_deduct,o.pay_amount,u.real_name order by o.create_time desc limit 20",p));
        return ApiResponse.ok(ReportAccess.filter(request, result));
    }

    @GetMapping("/sales")
    public ApiResponse<?> sales(@RequestParam(required = false) String timeType,@RequestParam(required = false) String startDate,@RequestParam(required = false) String endDate,@RequestParam(required = false) Long employeeId,@RequestParam(required = false) String category,@RequestParam(defaultValue = "1") int page,@RequestParam(defaultValue = "20") int pageSize,HttpServletRequest request) {
        requireManagerReport(request, "report:store-performance");
        Range range=range(timeType,startDate,endDate); MapSqlParameterSource p=params(request,range,employeeId).addValue("category",category).addValue("limit",Math.max(1,Math.min(pageSize,100))).addValue("offset",Math.max(0,page-1)*Math.max(1,Math.min(pageSize,100))); String scope=orderScope(request,employeeId,"o"); String cf=category==null||category.isBlank()?"":" and coalesce(root.name,'未分类')=:category";
        Map<String,Object> result=new LinkedHashMap<>(); result.put("summary",overview(timeType,startDate,endDate,employeeId,category,request).data()); result.put("records",db.list("select o.order_id,o.order_no,date_format(o.create_time,'%Y-%m-%d %H:%i') date,coalesce(group_concat(distinct oi.item_name order by oi.item_name separator '、'),'订单') goods_name,coalesce(max(root.name),'未分类') category,"+originalDue("o")+" original_amount,coalesce(o.settlement_discount,0) settlement_discount,"+discountedDue("o")+" discounted_amount,"+actualPaid("o")+" actual_paid,"+remainingDue("o")+" remaining_amount,o.settlement_discount_reason,coalesce(o.pay_method,'') pay_method,coalesce(u.real_name,'未分配') employee_name,"+actualPaid("o")+" amount,coalesce(sum(oi.weight*oi.qty),0) weight,coalesce(sum(oi.qty),0) quantity from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id left join sales_order_item oi on oi.order_id=o.order_id and oi.store_id=o.store_id left join goods g on g.goods_id=oi.goods_id and g.store_id=oi.store_id left join goods_category child on child.category_id=g.category_id and child.store_id=g.store_id left join goods_category root on root.category_id=case when child.level=2 then child.parent_id else child.category_id end and root.store_id=child.store_id where o.store_id=:s and (o.status=1 or (o.status=0 and o.pay_amount>0))"+range.sql("o.create_time")+scope+cf+" group by o.order_id,o.order_no,o.create_time,o.settlement_discount,o.settlement_discount_reason,o.pay_method,o.total_amount,o.discount,o.labor_fee,o.old_material_deduct,o.pay_amount,u.real_name order by o.create_time desc limit :limit offset :offset",p)); return ApiResponse.ok(ReportAccess.filter(request, result));
    }

    @GetMapping("/employee")
    public ApiResponse<?> employee(@RequestParam(required = false) String timeType,@RequestParam(required = false) String startDate,@RequestParam(required = false) String endDate,@RequestParam(required = false) Long employeeId,HttpServletRequest request) {
        requireManagerReport(request, "report:store-performance");
        Range range=range(timeType,startDate,endDate); MapSqlParameterSource p=params(request,range,employeeId); String scope=orderScope(request,employeeId,"o"); List<Map<String,Object>> rows=db.list("select o.sales_id user_id,coalesce(u.real_name,'未分配') name,coalesce(sum("+actualPaid("o")+"),0) sales_amount,coalesce(sum("+actualPaid("o")+"),0) actual_paid,count(*) order_count,coalesce((select sum(oi2.qty) from sales_order_item oi2 where oi2.store_id=o.store_id and oi2.order_id in (select o2.order_id from sales_order o2 left join member m2 on m2.member_id=o2.member_id and m2.store_id=o2.store_id where o2.store_id=o.store_id and o2.sales_id=o.sales_id and (o2.member_id is null or m2.deleted=0) and (o2.status=1 or (o2.status=0 and o2.pay_amount>0))"+range.sql("o2.create_time")+")),0) item_count,coalesce((select sum(oi2.weight*oi2.qty) from sales_order_item oi2 where oi2.store_id=o.store_id and oi2.order_id in (select o2.order_id from sales_order o2 left join member m2 on m2.member_id=o2.member_id and m2.store_id=o2.store_id where o2.store_id=o.store_id and o2.sales_id=o.sales_id and (o2.member_id is null or m2.deleted=0) and (o2.status=1 or (o2.status=0 and o2.pay_amount>0))"+range.sql("o2.create_time")+")),0) weight,0 processing_base,0 processing_commission,0 commission from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id left join member m on m.member_id=o.member_id and m.store_id=o.store_id where o.store_id=:s and (o.member_id is null or m.deleted=0) and (o.status=1 or (o.status=0 and o.pay_amount>0)) and o.sales_id is not null"+range.sql("o.create_time")+scope+" group by o.sales_id,u.real_name order by sales_amount desc",p);
        mergeCommission(rows, commissionRows(request, range, employeeId));
        if (!isSales(request)) {
            Set<String> existing = new HashSet<>(); for (Map<String,Object> row : rows) existing.add(String.valueOf(row.get("user_id")));
            for (Map<String,Object> user : db.list("select u.user_id,u.real_name name from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.store_id=:s and u.status=1 and r.role_code='SALES'", scope(request))) {
                if (!existing.contains(String.valueOf(user.get("user_id")))) { Map<String,Object> zero = new LinkedHashMap<>(user); zero.put("sales_amount",0); zero.put("order_count",0); zero.put("item_count",0); zero.put("weight",0); zero.put("commission",0); rows.add(zero); }
            }
        } else if (rows.isEmpty()) {
            Map<String,Object> me = db.one("select user_id,real_name name from sys_user where user_id=:uid and store_id=:s", scope(request)); me.put("sales_amount",0); me.put("order_count",0); me.put("item_count",0); me.put("weight",0); me.put("commission",0); rows.add(me);
        }
        for(Map<String,Object> row:rows){if(row.get("user_id")==null){row.put("categories",List.of());row.put("trend",List.of());row.put("member_count",0);row.put("recycle_amount",0);continue;} p.addValue("uidRow",row.get("user_id")); String ew=" and o.sales_id=:uidRow"; row.put("categories",db.list("select coalesce(root.name,'未分类') category,coalesce(sum(oi.subtotal),0) amount,coalesce(sum(oi.qty),0) item_count,coalesce(sum(oi.weight*oi.qty),0) weight from sales_order o join sales_order_item oi on oi.order_id=o.order_id and oi.store_id=o.store_id left join member m on m.member_id=o.member_id and m.store_id=o.store_id left join goods g on g.goods_id=oi.goods_id and g.store_id=oi.store_id left join goods_category child on child.category_id=g.category_id and child.store_id=g.store_id left join goods_category root on root.category_id=case when child.level=2 then child.parent_id else child.category_id end and root.store_id=child.store_id where o.store_id=:s and (o.member_id is null or m.deleted=0) and (o.status=1 or (o.status=0 and o.pay_amount>0))"+range.sql("o.create_time")+ew+" group by root.category_id,root.name order by amount desc",p)); row.put("trend",db.list("select date(o.create_time) day,coalesce(sum("+actualPaid("o")+"),0) amount from sales_order o left join member m on m.member_id=o.member_id and m.store_id=o.store_id where o.store_id=:s and (o.member_id is null or m.deleted=0) and (o.status=1 or (o.status=0 and o.pay_amount>0))"+range.sql("o.create_time")+ew+" group by date(o.create_time) order by day",p)); row.put("member_count",db.jdbc().queryForObject("select count(distinct o.member_id) from sales_order o left join member m on m.member_id=o.member_id and m.store_id=o.store_id where o.store_id=:s and (o.member_id is null or m.deleted=0) and (o.status=1 or (o.status=0 and o.pay_amount>0)) and o.member_id is not null"+range.sql("o.create_time")+ew,p,Number.class)); row.put("recycle_amount",db.jdbc().queryForObject("select coalesce(sum(ro.total_amount),0) from recycle_order ro where ro.store_id=:s"+range.sql("ro.create_time")+" and ro.created_by=:uidRow",p,Number.class)); }
        return ApiResponse.ok(ReportAccess.filter(request, Map.of("scope",isSales(request)?"PERSONAL":"STORE","employees",rows)));
    }

    @GetMapping("/recycle")
    public ApiResponse<?> recycle(@RequestParam(required = false) String start,@RequestParam(required = false) String end,HttpServletRequest request){ requireManagerReport(request, "report:recycle"); MapSqlParameterSource p=scope(request).addValue("start",start).addValue("end",end); String range=" and (:start is null or ro.create_time>=:start) and (:end is null or ro.create_time<date_add(:end,interval 1 day))"; String user=isSales(request)?" and ro.created_by=:uid":""; String tradeInUser=isSales(request)?" and ti.operator_id=:uid":""; Map<String,Object> summary=db.one("select count(*) order_count,coalesce(sum(ro.weight),0) weight,coalesce(sum(ro.total_amount),0) amount,coalesce(sum(ro.deduct_loss),0) deduct_loss from recycle_order ro where ro.store_id=:s"+range+user,p); Map<String,Object> result=new LinkedHashMap<>(summary); result.put("trend",db.list("select date(ro.create_time) day,coalesce(sum(ro.weight),0) weight,count(*) order_count from recycle_order ro where ro.store_id=:s"+range+user+" group by date(ro.create_time) order by day",p)); result.put("records",db.list("select ro.recycle_order_id,ro.bill_no,ro.material_type,ro.weight,ro.purity,ro.deduct_loss,ro.total_amount,ro.status,ro.create_time from recycle_order ro where ro.store_id=:s"+range+user+" order by ro.recycle_order_id desc limit 200",p)); result.put("trade_in_count",db.jdbc().queryForObject("select count(*) from trade_in ti where ti.store_id=:s and (:start is null or ti.create_time>=:start) and (:end is null or ti.create_time<date_add(:end,interval 1 day))"+tradeInUser,p,Number.class)); result.put("trade_in_amount",db.jdbc().queryForObject("select coalesce(sum(ti.diff_amount),0) from trade_in ti where ti.store_id=:s and (:start is null or ti.create_time>=:start) and (:end is null or ti.create_time<date_add(:end,interval 1 day))"+tradeInUser,p,Number.class));
        result.put("trade_in_records",db.list("select ti.trade_in_id,ti.bill_no,ti.old_material_info,ti.new_goods_info,ti.diff_amount,ti.status,date_format(ti.create_time,'%Y-%m-%d %H:%i') create_time from trade_in ti where ti.store_id=:s and (:start is null or ti.create_time>=:start) and (:end is null or ti.create_time<date_add(:end,interval 1 day))"+tradeInUser+" order by ti.trade_in_id desc limit 100",p));
        return ApiResponse.ok(ReportAccess.filter(request, result)); }
    @GetMapping("/recycle-detail") public ApiResponse<?> recycleDetail(@RequestParam(required = false) String timeType,@RequestParam(required = false) String startDate,@RequestParam(required = false) String endDate,HttpServletRequest request){ Range r=range(timeType,startDate,endDate); return recycle(r.start,r.end,request); }

    @GetMapping("/member")
    public ApiResponse<?> member(@RequestParam(required = false) String timeType,
                                 @RequestParam(required = false) String startDate,
                                 @RequestParam(required = false) String endDate,
                                 HttpServletRequest request) {
        requireManagerReport(request, "report:store-performance");
        Range r = range(timeType, startDate, endDate);
        MapSqlParameterSource p = params(request, r, null);
        String memberScope = salesWhere(request, "m.sales_id");
        String totalScope = salesWhere(request, "mx.sales_id");
        String balanceScope = salesWhere(request, "mb.sales_id");
        String activeScope = salesWhere(request, "ma.sales_id");
        Map<String,Object> summary = db.one(
                "select count(*) new_members,"
                        + "(select count(*) from member mx where mx.store_id=:s and mx.deleted=0" + totalScope + ") total_members,"
                        + "coalesce((select sum(mc.amount) from member_consume mc "
                        + "join member mx on mx.member_id=mc.member_id and mx.store_id=mc.store_id "
                        + "where mc.store_id=:s and mc.order_id is not null and mx.deleted=0" + totalScope + r.sql("mc.consume_time") + "),0) consume_amount,"
                        + "coalesce((select sum(mb.balance) from member mb where mb.store_id=:s and mb.deleted=0"
                        + balanceScope + "),0) balance_total,"
                        + "coalesce((select count(*) from member ma where ma.store_id=:s and ma.deleted=0"
                        + activeScope + " and ma.total_consume>0),0) active_members "
                        + "from member m where m.store_id=:s and m.deleted=0" + memberScope + r.sql("m.create_time"), p);
        Map<String,Object> result = new LinkedHashMap<>(summary);
        result.put("records", db.list(
                "select m.member_id,m.name,m.phone,m.gender,m.total_consume,m.create_time "
                        + "from member m where m.store_id=:s and m.deleted=0" + memberScope + r.sql("m.create_time")
                        + " order by m.total_consume desc limit 100", p));
        return ApiResponse.ok(ReportAccess.filter(request, result));
    }

    @GetMapping("/gold")
    public ApiResponse<?> gold(@RequestParam(required = false) String timeType,
                               @RequestParam(required = false) String startDate,
                               @RequestParam(required = false) String endDate,
                               @RequestParam(required = false) String priceType,
                               HttpServletRequest request) {
        requireManagerReport(request, "report:store-performance");
        Range r = range(timeType, startDate, endDate);
        MapSqlParameterSource p = params(request, r, null).addValue("priceType", priceType);
        String type = priceType == null || priceType.isBlank() ? "" : " and gp.price_type=:priceType";
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> prices = db.list(
                "select gp.date,gp.price,gp.price_type from gold_price gp where gp.store_id=:s" + type
                        + " and gp.date between :startDate and :endDate order by gp.date,gp.price_id",
                p);
        result.put("prices", prices);
        double sum = 0, max = 0, min = prices.isEmpty() ? 0 : Double.MAX_VALUE;
        for (Map<String, Object> row : prices) {
            double value = number(row.get("price"));
            sum += value;
            max = Math.max(max, value);
            min = Math.min(min, value);
        }
        result.put("average", prices.isEmpty() ? 0 : sum / prices.size());
        result.put("max", max);
        result.put("min", min);
        result.put("salesWeight", db.list(
                "select date(o.create_time) day,coalesce(sum(oi.weight*oi.qty),0) weight "
                        + "from sales_order o join sales_order_item oi on oi.order_id=o.order_id and oi.store_id=o.store_id "
                        + "where o.store_id=:s and o.status=1" + r.sql("o.create_time")
                        + orderScope(request, null, "o")
                        + " group by date(o.create_time) order by day",
                p));

        // A day can have several price updates. Pick one price per day before
        // joining sales, otherwise each order is counted once per quote.
        String dailyPrice = "select gp.date,gp.price from ("
                + "select gp.date,gp.price,gp.price_type,gp.price_id,"
                + "row_number() over (partition by gp.date order by "
                + "case when :priceType is not null and :priceType<>'' and gp.price_type=:priceType then 0 "
                + "when (:priceType is null or :priceType='') and gp.price_type='足金' then 1 else 2 end, gp.price_id desc) rn "
                + "from gold_price gp where gp.store_id=:s and gp.date between :startDate and :endDate"
                + ") gp where gp.rn=1";
        result.put("distribution", db.list(
                "select floor(gp.price/10)*10 price_bucket,coalesce(sum(oi.weight*oi.qty),0) weight "
                        + "from (" + dailyPrice + ") gp "
                        + "join sales_order o on date(o.create_time)=gp.date and o.store_id=:s "
                        + "join sales_order_item oi on oi.order_id=o.order_id and oi.store_id=o.store_id "
                        + "where o.status=1" + r.sql("o.create_time") + orderScope(request, null, "o")
                        + " group by floor(gp.price/10)*10 order by price_bucket",
                p));
        return ApiResponse.ok(ReportAccess.filter(request, result));
    }

    private void requireManagerReport(HttpServletRequest request, String permission) { ReportAccess.require(request, permission); }
    private MapSqlParameterSource scope(HttpServletRequest r){ return new MapSqlParameterSource().addValue("s",db.store(r)).addValue("uid",userId(r)); }
    private MapSqlParameterSource params(HttpServletRequest r,Range range,Long employee){ return scope(r).addValue("startDate",range.start).addValue("endDate",range.end).addValue("employeeId",selectedEmployee(r,employee)); }
    private String orderScope(HttpServletRequest r,Long employee,String alias){ return selectedEmployee(r,employee)==null?"":" and "+alias+".sales_id=:employeeId"; }
    private Long selectedEmployee(HttpServletRequest r,Long requested){ return isSales(r)?Long.valueOf(userId(r)):requested; }
    private String salesWhere(HttpServletRequest r,String column){ return isSales(r)?" and "+column+"=:uid":""; }
    private String activeProcessingFinance(String alias){ return " and not exists (select 1 from processing_order withdrawn where withdrawn.store_id="+alias+".store_id and withdrawn.order_no="+alias+".related_bill_no and withdrawn.status='WITHDRAWN')"; }
    private boolean isSales(HttpServletRequest r){ Claims c=(Claims)r.getAttribute("claims"); return c!=null&&"SALES".equalsIgnoreCase(String.valueOf(c.get("role"))); }
    private long userId(HttpServletRequest r){ Claims c=(Claims)r.getAttribute("claims"); return c==null?0L:Long.parseLong(c.getSubject()); }
    /** Prefer the immutable finance ledger, while keeping historical orders reportable. */
    private String actualPaid(String alias){
        return SalesAmounts.actualPaid(alias);
    }
    private String originalDue(String alias){
        return SalesAmounts.originalDue(alias);
    }
    private String discountedDue(String alias){
        return SalesAmounts.discountedDue(alias);
    }
    private String remainingDue(String alias){
        return SalesAmounts.remainingDue(alias);
    }

    private Map<String,Object> commissionTotals(HttpServletRequest request, Range range, Long employee) {
        BigDecimal sales = BigDecimal.ZERO, processingBase = BigDecimal.ZERO, processing = BigDecimal.ZERO;
        for (Map<String,Object> row : commissionRows(request, range, employee)) {
            sales = sales.add(big(row.get("sales_commission")));
            processingBase = processingBase.add(big(row.get("processing_base")));
            processing = processing.add(big(row.get("processing_commission")));
        }
        return Map.of("sales_commission", sales, "processing_base", processingBase,
                "processing_commission", processing, "commission", sales.add(processing));
    }

    private List<Map<String,Object>> commissionRows(HttpServletRequest request, Range range, Long employee) {
        if (!ReportAccess.enabled(request, "report:commission")) return List.of();
        Long selected = selectedEmployee(request, employee);
        MapSqlParameterSource p = params(request, range, employee);
        String employeeFilter = selected == null ? "" : " and o.sales_id=:employeeId";
        String processingEmployeeFilter = selected == null ? "" : " and p.sales_id=:employeeId";
        String salesRate = "(select coalesce(max(cast(c.config_value as decimal(10,6))),0.02) from sys_config c where c.store_id=o.store_id and c.config_key='default_commission_rate' and c.enabled=1)";
        String processingRate = "(select coalesce(max(cast(c.config_value as decimal(10,6))),0.01) from sys_config c where c.store_id=p.store_id and c.config_key='processing_sales_commission_rate' and c.enabled=1)";
        String paid = actualPaid("o");
        String salesSql = "select o.sales_id user_id,coalesce(u.real_name,'未分配') employee_name,sum(round((" + paid + ")*coalesce(o.commission_rate_snapshot," + salesRate + "),2)) sales_commission,0 processing_base,0 processing_commission,count(*) sales_orders,0 processing_orders "
                + "from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id where o.store_id=:s and o.status=1 and o.sales_id is not null "
                + range.sql("coalesce(o.paid_time,o.create_time)") + employeeFilter + " group by o.sales_id,u.real_name";
        String processingBase = ProcessingAmounts.laborBase("p");
        String processingCommission = ProcessingAmounts.commission("p", processingRate);
        String processingSql = "select p.sales_id user_id,coalesce(u.real_name,'未分配') employee_name,0 sales_commission,sum(" + processingBase + ") processing_base,sum(" + processingCommission + ") processing_commission,0 sales_orders,count(*) processing_orders "
                + "from processing_order p left join sys_user u on u.user_id=p.sales_id and u.store_id=p.store_id where p.store_id=:s and p.status='PICKED_UP' and p.sales_id is not null "
                + range.sql(ProcessingAmounts.settledDate("p")) + processingEmployeeFilter + " group by p.sales_id,u.real_name";
        return db.list(salesSql + " union all " + processingSql, p);
    }

    private void mergeCommission(List<Map<String,Object>> rows, List<Map<String,Object>> commissions) {
        Map<String,Map<String,Object>> byUser = new LinkedHashMap<>();
        for (Map<String,Object> row : rows) {
            String key = String.valueOf(row.get("user_id"));
            row.put("sales_order_count", number(row.get("order_count")));
            row.putIfAbsent("processing_base", BigDecimal.ZERO);
            row.putIfAbsent("processing_commission", BigDecimal.ZERO);
            row.putIfAbsent("sales_commission", BigDecimal.ZERO);
            byUser.put(key, row);
        }
        for (Map<String,Object> source : commissions) {
            String key = String.valueOf(source.get("user_id"));
            Map<String,Object> row = byUser.get(key);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("user_id", source.get("user_id"));
                row.put("name", source.get("employee_name"));
                row.put("employee_name", source.get("employee_name"));
                row.put("sales_amount", BigDecimal.ZERO);
                row.put("actual_paid", BigDecimal.ZERO);
                row.put("amount", BigDecimal.ZERO);
                row.put("order_count", 0);
                row.put("sales_order_count", 0);
                row.put("item_count", 0);
                row.put("weight", BigDecimal.ZERO);
                row.put("processing_base", BigDecimal.ZERO);
                row.put("processing_commission", BigDecimal.ZERO);
                row.put("sales_commission", BigDecimal.ZERO);
                rows.add(row);
                byUser.put(key, row);
            }
            row.put("sales_commission", big(row.get("sales_commission")).add(big(source.get("sales_commission"))));
            row.put("processing_base", big(row.get("processing_base")).add(big(source.get("processing_base"))));
            row.put("processing_commission", big(row.get("processing_commission")).add(big(source.get("processing_commission"))));
            row.put("order_count", big(row.get("sales_order_count")).add(big(row.get("processing_orders"))).add(big(source.get("processing_orders"))).intValue());
            row.put("processing_orders", big(row.get("processing_orders")).add(big(source.get("processing_orders"))));
            BigDecimal total = big(row.get("sales_commission")).add(big(row.get("processing_commission")));
            row.put("commission", total);
            row.put("commission_amount", total);
        }
        for (Map<String,Object> row : rows) {
            row.putIfAbsent("processing_orders", BigDecimal.ZERO);
            BigDecimal total = big(row.get("sales_commission")).add(big(row.get("processing_commission")));
            row.put("commission", total);
            row.put("commission_amount", total);
        }
    }

    private BigDecimal big(Object value) {
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    private Range range(String type,String start,String end){ LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai")),from,to; switch(type==null?"month":type){ case "today"->from=to=today; case "yesterday"->from=to=today.minusDays(1); case "week"->{from=today.minusDays(today.getDayOfWeek().getValue()-1L);to=today;} case "lastMonth"->{LocalDate f=today.withDayOfMonth(1).minusMonths(1);from=f;to=f.plusMonths(1).minusDays(1);} case "custom"->{from=parse(start,today.withDayOfMonth(1));to=parse(end,today);} default->{from=today.withDayOfMonth(1);to=today;} } return new Range(from.toString(),to.toString(),label(type)); }
    private LocalDate parse(String value,LocalDate fallback){ try{return value==null||value.isBlank()?fallback:LocalDate.parse(value);}catch(DateTimeParseException e){throw new BusinessException(400422,"日期格式应为YYYY-MM-DD");} }
    private String label(String type){ return switch(type==null?"month":type){case "today"->"今天";case "yesterday"->"昨天";case "week"->"本周";case "lastMonth"->"上月";case "custom"->"自定义";default->"本月";}; }
    private double number(Object value){return value==null?0d:Double.parseDouble(String.valueOf(value));}
    private record Range(String start,String end,String label){ String sql(String column){return " and "+column+">=:startDate and "+column+"<date_add(:endDate,interval 1 day)";} }
}
