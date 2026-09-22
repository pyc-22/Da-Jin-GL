package com.dajin.system.admin;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Management-console read/write endpoints. The queries deliberately stay close to the schema so the console
 * remains useful on a fresh installation without introducing a second persistence model. */
@RestController
@RequestMapping("/api/admin")
@RequireRoles({"ADMIN", "MANAGER"})
public class AdminController {
    private final DbSupport db; private final SyncWebSocketHandler ws;
    private final BackupService backupService;
    public AdminController(DbSupport db, SyncWebSocketHandler ws, BackupService backupService) { this.db = db; this.ws = ws; this.backupService = backupService; }
    private long store(HttpServletRequest r) { return db.store(r); }
    private MapSqlParameterSource p(HttpServletRequest r) { return new MapSqlParameterSource("s", store(r)); }
    private void assertAssignableRole(Object roleId, HttpServletRequest r) {
        if (roleId == null) throw new BusinessException(400431, "请选择角色");
        Map<String,Object> target = db.one("select role_code from sys_role where store_id=:s and role_id=:id and status=1", Map.of("s", store(r), "id", roleId));
        assertManageableRole(String.valueOf(target.get("role_code")), r);
    }
    private void assertManageableUser(long id, HttpServletRequest r) {
        Map<String,Object> target = db.one("select r.role_code from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.user_id=:id and u.store_id=:s", Map.of("s", store(r), "id", id));
        assertManageableRole(String.valueOf(target.get("role_code")), r);
    }
    private void assertManageableRole(String targetRole, HttpServletRequest r) {
        Object claims = r.getAttribute("claims");
        String operator = claims instanceof io.jsonwebtoken.Claims c ? String.valueOf(c.get("role")) : "";
        if (!Set.of("ADMIN", "MANAGER").contains(operator) || !StaffRolePolicy.canManage(operator, targetRole))
            throw new BusinessException(403426, "店长只能管理销售、收银员和打金师傅账号");
    }

    @GetMapping("/dashboard") @RequirePermission("dashboard:view")
    public ApiResponse<?> dashboard(HttpServletRequest r) {
        MapSqlParameterSource p = p(r);
        Map<String, Object> kpi = db.one("select coalesce(sum(pay_amount),0) revenue, count(*) order_count, coalesce((select sum(i.weight*i.qty) from sales_order_item i join sales_order oi on oi.order_id=i.order_id and oi.store_id=i.store_id where oi.store_id=:s and date(oi.create_time)=curdate() and oi.status=1),0) weight from sales_order o where o.store_id=:s and date(o.create_time)=curdate() and o.status=1", p);
        Number dayGross = db.jdbc().queryForObject("select coalesce(sum(i.subtotal-coalesce(i.cost_snapshot,0)),0) from sales_order_item i join sales_order o on o.order_id=i.order_id and o.store_id=i.store_id where i.store_id=:s and date(o.create_time)=curdate() and o.status=1", p, Number.class);
        // 营业额口径与交班合计一致：商品销售 + 加工费收入（毛利/毛利率仍按商品销售算）
        Number processingToday = db.jdbc().queryForObject("select coalesce(sum(amount),0) from finance_record where store_id=:s and type='INCOME' and category='PROCESSING_FEE' and date(create_time)=curdate()", p, Number.class);
        Number revenue = (Number) kpi.getOrDefault("revenue", 0);
        BigDecimal turnover = new BigDecimal(String.valueOf(revenue)).add(new BigDecimal(String.valueOf(processingToday)));
        Number gross = dayGross == null ? 0 : dayGross;
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("revenue", turnover); result.put("salesRevenue", revenue); result.put("processingRevenue", processingToday); result.put("orderCount", kpi.get("order_count")); result.put("weight", kpi.get("weight"));
        result.put("grossMargin", revenue.doubleValue() == 0 ? 0 : gross.doubleValue() / revenue.doubleValue());
        result.put("pendingApproval", db.jdbc().queryForObject("select count(*) from approval where store_id=:s and status=1", p, Integer.class));
        result.put("stockWarnings", db.jdbc().queryForObject("select count(*) from goods where store_id=:s and stock<=5", p, Integer.class));
        Map<String, BigDecimal> processingByDay = new LinkedHashMap<>();
        for (Map<String,Object> row : db.list("select date(f.create_time) day,sum(f.amount) amount from finance_record f where f.store_id=:s and f.type='INCOME' and f.category='PROCESSING_FEE' and f.create_time>=date_sub(curdate(),interval 6 day) group by date(f.create_time)", p)) {
            processingByDay.put(String.valueOf(row.get("day")), new BigDecimal(String.valueOf(row.get("amount"))));
        }
        List<Map<String,Object>> trend = new ArrayList<>();
        for (Map<String,Object> row : db.list("select date(o.create_time) day,coalesce(sum(o.pay_amount),0) amount,count(o.order_id) order_count from sales_order o where o.store_id=:s and o.status=1 and o.create_time>=date_sub(curdate(),interval 6 day) group by date(o.create_time)", p)) {
            String day = String.valueOf(row.get("day"));
            BigDecimal merged = new BigDecimal(String.valueOf(row.get("amount"))).add(processingByDay.getOrDefault(day, BigDecimal.ZERO));
            row.put("amount", merged);
            trend.add(row); processingByDay.remove(day);
        }
        for (Map.Entry<String, BigDecimal> extra : processingByDay.entrySet()) {
            trend.add(new LinkedHashMap<>(Map.of("day", extra.getKey(), "amount", extra.getValue(), "order_count", 0)));
        }
        trend.sort(Comparator.comparing(a -> String.valueOf(((Map<?,?>) a).get("day"))));
        result.put("trend", trend);
        result.put("ranking", db.list("select o.sales_id user_id,coalesce(u.real_name,'未分配') name,coalesce(sum(o.pay_amount),0) amount,count(o.order_id) order_count from sales_order o left join sys_user u on u.user_id=o.sales_id and u.store_id=o.store_id where o.store_id=:s and o.status=1 and date(o.create_time)>=date_sub(curdate(),interval 6 day) group by o.sales_id,u.real_name order by amount desc limit 10", p));
        return ApiResponse.ok(result);
    }

    @GetMapping("/categories") @RequirePermission("goods:search")
    public ApiResponse<?> categories(HttpServletRequest r) { return ApiResponse.ok(db.list("select * from goods_category where store_id=:s order by level,parent_id,sort,category_id", p(r))); }
    @PostMapping("/categories") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("goods:manage")
    public ApiResponse<?> createCategory(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        String name = q.get("name") == null ? "" : String.valueOf(q.get("name")).trim();
        if (name.isBlank() || name.length() > 100) throw new BusinessException(400410, "分类名称不能为空且不能超过100个字符");
        long storeId = store(r); long operatorId = userId(r); long parentId = longValue(q.get("parentId"), 0L);
        int level = parentId == 0 ? 1 : 2;
        if (parentId != 0 && db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and category_id=:id and level=1 and status=1", Map.of("s", storeId, "id", parentId), Integer.class) == 0) throw new BusinessException(400411, "二级分类必须选择有效的一级分类");
        String code = q.get("categoryCode") == null ? "CAT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : String.valueOf(q.get("categoryCode")).trim();
        if (code.isBlank() || code.length() > 50) throw new BusinessException(400412, "分类编码不能为空且不能超过50个字符");
        int sameName = db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and name=:n and parent_id=:parent", new MapSqlParameterSource().addValue("s", storeId).addValue("n", name).addValue("parent", parentId), Integer.class);
        if (sameName > 0) throw new BusinessException(409410, "该层级下已存在同名分类");
        int sameCode = db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and category_code=:code", new MapSqlParameterSource().addValue("s", storeId).addValue("code", code), Integer.class);
        if (sameCode > 0) throw new BusinessException(409412, "分类编码已存在");
        db.jdbc().update("insert into goods_category(store_id,name,category_code,parent_id,level,sort,status,create_time,update_time) values(:s,:n,:code,:parent,:level,:sort,1,now(),now())", new MapSqlParameterSource().addValue("s",storeId).addValue("n",name).addValue("code",code).addValue("parent",parentId).addValue("level",level).addValue("sort",longValue(q.get("sort"), 0L)));
        logOperation(storeId, operatorId, "CATEGORY", "CREATE", "分类=" + name); ws.broadcast("CATEGORIES_UPDATED", Map.of("storeId", storeId, "action", "create", "name", name)); return ApiResponse.ok();
    }
    @PutMapping("/categories/{id}") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("goods:manage")
    public ApiResponse<?> updateCategory(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r) {
        long storeId = store(r); String name = q.get("name") == null ? null : String.valueOf(q.get("name")).trim();
        if (name != null && (name.isBlank() || name.length() > 100)) throw new BusinessException(400410, "分类名称不能为空且不能超过100个字符");
        String code = q.get("categoryCode") == null ? null : String.valueOf(q.get("categoryCode")).trim();
        if (code != null && (code.isBlank() || code.length() > 50)) throw new BusinessException(400412, "分类编码不能为空且不能超过50个字符");
        if (code != null && db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and category_code=:code and category_id<>:id", Map.of("s", storeId, "code", code, "id", id), Integer.class) > 0) throw new BusinessException(409412, "分类编码已存在");
        Map<String,Object> current;
        try { current = db.one("select parent_id,level from goods_category where category_id=:id and store_id=:s", Map.of("id",id,"s",storeId)); }
        catch (Exception e) { throw new BusinessException(404410, "分类不存在"); }
        Object parent = q.get("parentId"); long parentId = parent == null ? longValue(current.get("parent_id"), 0L) : longValue(parent, 0L);
        int level = parentId == 0 ? 1 : 2;
        if (name != null && db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and name=:n and parent_id=:parent and category_id<>:id", new MapSqlParameterSource().addValue("s", storeId).addValue("n", name).addValue("parent", parentId).addValue("id", id), Integer.class) > 0) throw new BusinessException(409410, "该层级下已存在同名分类");
        if (parentId == id || (parentId != 0 && db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and category_id=:parent and level=1 and status=1", Map.of("s",storeId,"parent",parentId), Integer.class)==0)) throw new BusinessException(400411, "父级分类无效");
        int children = db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and parent_id=:id", Map.of("s",storeId,"id",id), Integer.class);
        int currentLevel = ((Number) current.getOrDefault("level", 1)).intValue();
        if (currentLevel == 1 && level == 2 && children > 0) throw new BusinessException(409413, "一级分类存在子类，不能移动到二级分类");
        db.jdbc().update("update goods_category set name=coalesce(:n,name),category_code=coalesce(:code,category_code),parent_id=:parent,level=:level,sort=coalesce(:sort,sort),status=coalesce(:status,status),update_time=now() where category_id=:id and store_id=:s", new MapSqlParameterSource().addValue("s",storeId).addValue("id",id).addValue("n",name).addValue("code",code).addValue("parent",parentId).addValue("level",level).addValue("sort",q.get("sort")).addValue("status",q.get("status")));
        logOperation(storeId, userId(r), "CATEGORY", "UPDATE", "分类ID=" + id); ws.broadcast("CATEGORIES_UPDATED", Map.of("storeId", storeId, "action", "update", "id", id)); return ApiResponse.ok();
    }
    @DeleteMapping("/categories/{id}") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("goods:manage")
    public ApiResponse<?> deleteCategory(@PathVariable long id,HttpServletRequest r){
        long storeId = store(r); Map<String,Object> category;
        try { category = db.one("select name from goods_category where category_id=:id and store_id=:s", Map.of("id", id, "s", storeId)); }
        catch (Exception e) { throw new BusinessException(404410, "分类不存在"); }
        int goods = db.jdbc().queryForObject("select count(*) from goods where category_id=:id and store_id=:s", Map.of("id",id,"s",storeId), Integer.class);
        int children = db.jdbc().queryForObject("select count(*) from goods_category where parent_id=:id and store_id=:s", Map.of("id",id,"s",storeId), Integer.class);
        if (goods > 0 || children > 0) throw new BusinessException(409411, "该分类存在关联数据，请改用禁用");
        db.jdbc().update("delete from goods_category where category_id=:id and store_id=:s",new MapSqlParameterSource().addValues(Map.of("id",id,"s",storeId)));
        logOperation(storeId, userId(r), "CATEGORY", "DELETE", "分类=" + category.get("name")); ws.broadcast("CATEGORIES_UPDATED", Map.of("storeId", storeId, "action", "delete", "id", id)); return ApiResponse.ok();
    }

    @GetMapping("/stock/overview") @RequirePermission("stock:view")
    public ApiResponse<?> stockOverview(HttpServletRequest r){
        MapSqlParameterSource q=p(r);
        // Inventory is the complete goods master.  A goods.status of 0 means it is
        // not currently listed for sale, but it must remain visible in stock.
        List<Map<String,Object>> roots=db.list("select p.category_id,p.name category,p.category_code,p.parent_id,p.level,p.status,coalesce(sum(g.stock),0) quantity,coalesce(sum(case when g.status=1 then g.stock else 0 end),0) sale_quantity,count(distinct g.goods_id) goods_count,count(distinct case when g.status=1 then g.goods_id end) sale_goods_count,coalesce(sum(g.stock*g.cost_price),0) amount from goods_category p left join goods_category c on c.parent_id=p.category_id and c.level=2 and c.store_id=p.store_id left join goods g on g.category_id=c.category_id and g.store_id=c.store_id where p.store_id=:s and p.level=1 group by p.category_id,p.name,p.category_code,p.parent_id,p.level,p.status order by p.sort,p.category_id",q);
        List<Map<String,Object>> children=db.list("select c.category_id,c.name category,c.category_code,c.parent_id,c.level,c.status,coalesce(sum(g.stock),0) quantity,coalesce(sum(case when g.status=1 then g.stock else 0 end),0) sale_quantity,count(distinct g.goods_id) goods_count,count(distinct case when g.status=1 then g.goods_id end) sale_goods_count,coalesce(sum(g.stock*g.cost_price),0) amount from goods_category c left join goods g on g.category_id=c.category_id and g.store_id=c.store_id where c.store_id=:s and c.level=2 group by c.category_id,c.name,c.category_code,c.parent_id,c.level,c.status order by c.parent_id,c.sort,c.category_id",q);
        Map<Long,List<Map<String,Object>>> grouped=new LinkedHashMap<>(); for(Map<String,Object> child:children) grouped.computeIfAbsent(((Number)child.get("parent_id")).longValue(), k->new ArrayList<>()).add(child);
        for(Map<String,Object> root:roots) root.put("children",grouped.getOrDefault(((Number)root.get("category_id")).longValue(),List.of()));
        return ApiResponse.ok(roots);
    }
    @GetMapping("/stock/in") @RequirePermission("stock:transfer") public ApiResponse<?> stockIn(HttpServletRequest r){return ApiResponse.ok(db.list("select si.*,g.name goods_name from stock_in si left join goods g on g.goods_id=si.goods_id and g.store_id=si.store_id where si.store_id=:s order by si.stock_in_id desc limit 200",p(r)));}
    @GetMapping("/stock/out") @RequirePermission("stock:transfer") public ApiResponse<?> stockOut(HttpServletRequest r){return ApiResponse.ok(db.list("select so.*,g.name goods_name from stock_out so left join goods g on g.goods_id=so.goods_id and g.store_id=so.store_id where so.store_id=:s order by so.stock_out_id desc limit 200",p(r)));}
    @GetMapping("/stock/warnings") @RequirePermission("stock:view") public ApiResponse<?> stockWarnings(HttpServletRequest r){return ApiResponse.ok(db.list("select g.*,c.name category,(select gp.image from goods_piece gp where gp.store_id=g.store_id and gp.goods_id=g.goods_id and gp.status=1 order by gp.piece_id limit 1) piece_image from goods g left join goods_category c on c.category_id=g.category_id and c.store_id=g.store_id where g.store_id=:s and g.stock<=5 order by g.stock,g.goods_id",p(r)));}
    @GetMapping("/stock/checks") @RequirePermission("stock:check:view") public ApiResponse<?> stockChecks(HttpServletRequest r){
        return ApiResponse.ok(db.list("select sc.*,u.real_name operator_name,a.approval_id,a.status approval_status,coalesce(sc.approve_remark,a.approve_remark) decision_remark,"
                + "case when sc.scope_type='STORE' then st.store_name when sc.scope_type in ('CATEGORY_L1','CATEGORY_L2') then c.name when sc.scope_type='GOODS' then g.name else '自定义商品' end scope_name "
                + "from stock_check sc left join sys_user u on u.user_id=sc.operator_id and u.store_id=sc.store_id "
                + "left join approval a on a.store_id=sc.store_id and a.type='STOCK_CHECK' and a.biz_id=sc.check_id "
                + "left join sys_store st on st.store_id=sc.store_id left join goods_category c on c.category_id=sc.scope_id and c.store_id=sc.store_id "
                + "left join goods g on g.goods_id=sc.scope_id and g.store_id=sc.store_id where sc.store_id=:s order by sc.check_id desc limit 200",p(r)));
    }

    @GetMapping("/approval/history") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("stock:check:approve")
    public ApiResponse<?> approvalHistory(HttpServletRequest r){return ApiResponse.ok(db.list("select * from approval where store_id=:s and status<>1 order by approval_id desc limit 200",p(r)));}

    @GetMapping("/sales") @RequirePermission("order:checkout") public ApiResponse<?> sales(@RequestParam(required=false)String keyword,@RequestParam(required=false)Integer status,HttpServletRequest r){MapSqlParameterSource q=p(r).addValue("k",keyword==null?"%":"%"+keyword+"%").addValue("st",status);return ApiResponse.ok(db.list("select o.*,u.real_name cashier from sales_order o left join sys_user u on u.user_id=o.cashier_id and u.store_id=o.store_id where o.store_id=:s and (o.order_no like :k or coalesce(o.pay_method,'') like :k) and (:st is null or o.status=:st) order by o.order_id desc limit 500",q));}
    @GetMapping("/roles") @RequirePermission("staff:manage") public ApiResponse<?> roles(HttpServletRequest r){return ApiResponse.ok(db.list("select * from sys_role where store_id=:s order by role_id",p(r)));}
    @PostMapping("/roles") @RequireRoles({"ADMIN"}) public ApiResponse<?> createRole(@RequestBody Map<String,Object> q,HttpServletRequest r){long storeId=store(r);db.jdbc().update("insert into sys_role(store_id,role_name,role_code,permissions,status) values(:s,:n,:c,:perm,1)",new MapSqlParameterSource().addValue("s",storeId).addValue("n",q.get("roleName")).addValue("c",q.get("roleCode")).addValue("perm",q.getOrDefault("permissions","[]")));ws.broadcast("ROLE_UPDATED",Map.of("storeId",storeId,"action","CREATE","roleCode",String.valueOf(q.get("roleCode"))));return ApiResponse.ok();}
    @PutMapping("/roles/{id}") @RequireRoles({"ADMIN"}) public ApiResponse<?> updateRole(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r){long storeId=store(r);db.jdbc().update("update sys_role set role_name=coalesce(:n,role_name),permissions=coalesce(:perm,permissions),status=coalesce(:status,status),update_time=now() where role_id=:id and store_id=:s",new MapSqlParameterSource().addValue("s",storeId).addValue("id",id).addValue("n",q.get("roleName")).addValue("perm",q.get("permissions")).addValue("status",q.get("status")));ws.broadcast("ROLE_UPDATED",Map.of("storeId",storeId,"action","UPDATE","roleId",id));return ApiResponse.ok();}
    @PostMapping("/users") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage") public ApiResponse<?> createUser(@RequestBody Map<String,Object> q,HttpServletRequest r){
        assertAssignableRole(q.get("roleId"), r);
        String username = q.get("username") == null ? "" : String.valueOf(q.get("username")).trim(); String realName = q.get("realName") == null ? "" : String.valueOf(q.get("realName")).trim();
        if (username.isBlank() || realName.isBlank()) throw new BusinessException(400420, "账号和姓名不能为空");
        long storeId=store(r); int exists=db.jdbc().queryForObject("select count(*) from sys_user where store_id=:s and username=:u",Map.of("s",storeId,"u",username),Integer.class); if(exists>0)throw new BusinessException(409420,"登录账号已存在");
         db.jdbc().update("insert into sys_user(store_id,username,password,real_name,role_id,phone,entry_date,status) values(:s,:u,:pwd,:name,:role,:phone,:entry,1)",new MapSqlParameterSource().addValue("s",storeId).addValue("u",username).addValue("pwd",q.getOrDefault("password","admin")).addValue("name",realName).addValue("role",q.get("roleId")).addValue("phone",q.get("phone")).addValue("entry",q.get("entryDate"))); logOperation(storeId,userId(r),"USER","CREATE","账号="+username);ws.broadcast("STAFF_UPDATED",Map.of("storeId",storeId,"action","CREATE","username",username)); return ApiResponse.ok();}
    @PutMapping("/users/{id}") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage") public ApiResponse<?> updateUser(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r){assertManageableUser(id,r);if(q.get("roleId")!=null)assertAssignableRole(q.get("roleId"),r);long storeId=store(r);db.jdbc().update("update sys_user set real_name=coalesce(:name,real_name),role_id=coalesce(:role,role_id),phone=coalesce(:phone,phone),entry_date=coalesce(:entry,entry_date),status=coalesce(:status,status),update_time=now() where user_id=:id and store_id=:s",new MapSqlParameterSource().addValue("s",storeId).addValue("id",id).addValue("name",q.get("realName")).addValue("role",q.get("roleId")).addValue("phone",q.get("phone")).addValue("entry",q.get("entryDate")).addValue("status",q.get("status")));ws.broadcast("STAFF_UPDATED",Map.of("storeId",storeId,"action","UPDATE","userId",id));return ApiResponse.ok();}
    @PatchMapping("/users/{id}/status") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage") public ApiResponse<?> userStatus(@PathVariable long id,@RequestParam int status,HttpServletRequest r){
        assertManageableUser(id,r);
        if(status!=0&&status!=1) throw new BusinessException(400421,"员工状态不合法"); long storeId=store(r); int changed=db.jdbc().update("update sys_user set status=:st,update_time=now() where user_id=:id and store_id=:s",Map.of("st",status,"id",id,"s",storeId)); if(changed==0)throw new BusinessException(404421,"员工不存在"); logOperation(storeId,userId(r),"USER",status==1?"ENABLE":"DISABLE","员工ID="+id); ws.broadcast("STAFF_UPDATED",Map.of("storeId",storeId,"action",status==1?"ENABLE":"DISABLE","userId",id,"status",status)); return ApiResponse.ok(Map.of("status",status)); }
    @DeleteMapping("/users/{id}") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage") public ApiResponse<?> deleteUser(@PathVariable long id,HttpServletRequest r){
        assertManageableUser(id,r);
        long storeId=store(r); Map<String,Object> user; try{user=db.one("select username from sys_user where user_id=:id and store_id=:s",Map.of("id",id,"s",storeId));}catch(Exception e){throw new BusinessException(404422,"员工不存在");}
        int linked=0; linked+=count("select count(*) from sales_order where store_id=:s and (cashier_id=:id or sales_id=:id)",storeId,id); linked+=count("select count(*) from approval where store_id=:s and (applicant_id=:id or approver_id=:id)",storeId,id); linked+=count("select count(*) from visit_task where store_id=:s and sales_id=:id",storeId,id); linked+=count("select count(*) from commission_record where store_id=:s and user_id=:id",storeId,id);
        if(linked>0) throw new BusinessException(409422,"员工已有业务数据，只能禁用"); db.jdbc().update("delete from sys_user where user_id=:id and store_id=:s",Map.of("id",id,"s",storeId)); logOperation(storeId,userId(r),"USER","DELETE","账号="+user.get("username")); ws.broadcast("STAFF_UPDATED",Map.of("storeId",storeId,"action","DELETE","userId",id)); return ApiResponse.ok(Map.of("deleted",true)); }

    @PostMapping("/commission/rules") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("report:view:all") public ApiResponse<?> createCommissionRule(@RequestBody Map<String,Object> q,HttpServletRequest r){long storeId=store(r);db.jdbc().update("insert into commission_rule(store_id,type,rate,`condition`,description,status) values(:s,:type,:rate,:condition,:description,1)",new MapSqlParameterSource().addValue("s",storeId).addValue("type",q.get("type")).addValue("rate",q.getOrDefault("rate",0)).addValue("condition",q.getOrDefault("condition","{}")).addValue("description",q.get("description")));ws.broadcast("COMMISSION_UPDATED",Map.of("storeId",storeId,"action","RULE_CREATE"));return ApiResponse.ok();}
    @PutMapping("/commission/rules/{id}") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("report:view:all") public ApiResponse<?> updateCommissionRule(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r){long storeId=store(r);db.jdbc().update("update commission_rule set rate=coalesce(:rate,rate),description=coalesce(:description,description),status=coalesce(:status,status),update_time=now() where rule_id=:id and store_id=:s",new MapSqlParameterSource().addValue("s",storeId).addValue("id",id).addValue("rate",q.get("rate")).addValue("description",q.get("description")).addValue("status",q.get("status")));ws.broadcast("COMMISSION_UPDATED",Map.of("storeId",storeId,"action","RULE_UPDATE","ruleId",id));return ApiResponse.ok();}

    @PutMapping("/store") @RequireRoles({"ADMIN"}) public ApiResponse<?> updateStore(@RequestBody Map<String,Object> q,HttpServletRequest r){long storeId=store(r);db.jdbc().update("update sys_store set store_name=coalesce(:name,store_name),address=coalesce(:address,address),phone=coalesce(:phone,phone),logo=coalesce(:logo,logo),update_time=now() where store_id=:s",new MapSqlParameterSource().addValue("s",storeId).addValue("name",q.get("storeName")).addValue("address",q.get("address")).addValue("phone",q.get("phone")).addValue("logo",q.get("logo")));ws.broadcast("STORE_UPDATED",Map.of("storeId",storeId));return ApiResponse.ok();}
    @PutMapping("/config") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission(value={"system:manage","gold:manage"}, anyOf=true)
    public ApiResponse<?> updateConfig(@RequestBody Map<String,Object> q,HttpServletRequest r){
        long storeId=store(r); String key=text(q,"configKey","config_key",null).trim();
        if(key.isBlank()||key.length()>100)throw new BusinessException(400415,"配置项名称不能为空且不能超过100个字符");
        String value=text(q,"configValue","config_value",""); if(value.length()>2000)throw new BusinessException(400416,"配置值不能超过2000个字符");
        List<Map<String,Object>> existingRows=db.list("select config_group,config_sort,enabled,description from sys_config where store_id=:s and config_key=:k order by config_id limit 1",Map.of("s",storeId,"k",key));
        Map<String,Object> existing=existingRows.isEmpty()?Map.of():existingRows.get(0);
        String group=text(q,"configGroup","config_group",existing.getOrDefault("config_group","SYSTEM")).trim();
        if(group.isBlank()||group.length()>50)throw new BusinessException(400417,"配置分组不合法");
        int sort=intValue(q,"configSort","config_sort",number(existing.getOrDefault("config_sort",0)));
        if(sort<0||sort>9999)throw new BusinessException(400418,"配置排序必须在0到9999之间");
        int enabled=intValue(q,"enabled",null,number(existing.getOrDefault("enabled",1)));
        if(enabled!=0&&enabled!=1)throw new BusinessException(400419,"配置启用状态不合法");
        String description=text(q,"description",null,existing.get("description")); if(description.length()>255)throw new BusinessException(400420,"配置说明不能超过255个字符");
        db.jdbc().update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) values(:s,:g,:k,:v,:d,:sort,:enabled) "
                +"on duplicate key update config_value=values(config_value),description=values(description),config_sort=values(config_sort),enabled=values(enabled),update_time=now()",
                new MapSqlParameterSource().addValue("s",storeId).addValue("g",group).addValue("k",key).addValue("v",value).addValue("d",description.isBlank()?null:description).addValue("sort",sort).addValue("enabled",enabled));
        logOperation(storeId,userId(r),"SYSTEM","CONFIG_UPDATE","配置项="+key);ws.broadcast("CONFIG_UPDATED",Map.of("storeId",storeId,"configKey",key));return ApiResponse.ok();
    }
    @GetMapping("/logs") @RequirePermission("system:manage") public ApiResponse<?> logs(@RequestParam(required=false)String module,HttpServletRequest r){return ApiResponse.ok(db.list("select * from operation_log where store_id=:s and (:m is null or module=:m) order by log_id desc limit 500",p(r).addValue("m",module)));}
    @GetMapping("/backups") @RequireRoles({"ADMIN"}) public ApiResponse<?> backups(HttpServletRequest r){return ApiResponse.ok(backupService.list());}
    @PostMapping("/backups") @RequireRoles({"ADMIN"}) public ApiResponse<?> backup(HttpServletRequest r){
        Map<String,Object> result = backupService.create();
        var claims = (io.jsonwebtoken.Claims) r.getAttribute("claims");
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:uid,'SYSTEM','BACKUP',:content,'',now())",
                p(r).addValue("uid",claims == null ? 0 : Long.parseLong(claims.getSubject())).addValue("content",result.get("fileName")));
        return ApiResponse.ok(result);
    }

    /** 排班以操作日志作为轻量事件存储，避免改变当前26张核心表；content保存可回放的JSON。 */
    @GetMapping("/schedules") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage")
    public ApiResponse<?> schedules(@RequestParam(required=false) String start, @RequestParam(required=false) String end, HttpServletRequest r) {
        MapSqlParameterSource q = p(r).addValue("start", start).addValue("end", end);
        return ApiResponse.ok(db.list("select log_id schedule_id, content, create_time from operation_log where store_id=:s and module='SCHEDULE' and (:start is null or json_unquote(json_extract(content,'$.date'))>=:start) and (:end is null or json_unquote(json_extract(content,'$.date'))<=:end) order by json_unquote(json_extract(content,'$.date')), log_id", q));
    }
    @PostMapping("/schedules") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage")
    public ApiResponse<?> createSchedule(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        String content = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(q).toString();
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,0,'SCHEDULE','UPSERT',:content,'',now())", p(r).addValue("content", content));
        ws.broadcast("SCHEDULE_UPDATED", Map.of("storeId", store(r), "action", "UPSERT"));
        return ApiResponse.ok(Map.of("saved", true));
    }
    @DeleteMapping("/schedules/{id}") @RequireRoles({"ADMIN","MANAGER"}) @RequirePermission("staff:manage")
    public ApiResponse<?> deleteSchedule(@PathVariable long id, HttpServletRequest r) {
        db.jdbc().update("delete from operation_log where log_id=:id and store_id=:s and module='SCHEDULE'", p(r).addValue("id", id));
        ws.broadcast("SCHEDULE_UPDATED", Map.of("storeId", store(r), "action", "DELETE", "scheduleId", id));
        return ApiResponse.ok();
    }

    private long longValue(Object value, long fallback) {
        if (value == null || String.valueOf(value).trim().isEmpty()) return fallback;
        try { return Long.parseLong(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new BusinessException(400414, "分类参数格式不正确"); }
    }
    private static String text(Map<String,Object> q,String primary,String alternate,Object fallback){Object value=q.containsKey(primary)?q.get(primary):alternate==null?null:q.containsKey(alternate)?q.get(alternate):fallback;return value==null?"":String.valueOf(value);}
    private static int intValue(Map<String,Object> q,String primary,String alternate,int fallback){Object value=q.containsKey(primary)?q.get(primary):alternate==null?fallback:q.containsKey(alternate)?q.get(alternate):fallback;try{return value==null?fallback:new BigDecimal(String.valueOf(value)).intValueExact();}catch(Exception e){throw new BusinessException(400418,"配置排序或启用状态格式不正确");}}
    private static int number(Object value){try{return value==null?0:new BigDecimal(String.valueOf(value)).intValue();}catch(Exception e){return 0;}}
    private int count(String sql,long storeId,long userId){return db.jdbc().queryForObject(sql,Map.of("s",storeId,"id",userId),Integer.class);}
    private long userId(HttpServletRequest r){io.jsonwebtoken.Claims c=(io.jsonwebtoken.Claims)r.getAttribute("claims");return c==null?0L:Long.parseLong(c.getSubject());}
    private void logOperation(long storeId,long operatorId,String module,String action,String content){db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,:m,:a,:c,'',now())",Map.of("s",storeId,"u",operatorId,"m",module,"a",action,"c",content));}
}
