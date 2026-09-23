package com.dajin.system.member;

import com.dajin.system.common.*;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.pay.PaymentChannelPolicy;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.*;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@RestController @RequestMapping("/api/member") @RequireRoles({"ADMIN", "MANAGER", "SALES", "CASHIER"}) @RequirePermission("member:view")
public class MemberController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    public MemberController(DbSupport db, SyncWebSocketHandler ws){this.db=db;this.ws=ws;}
    @GetMapping("/list") public ApiResponse<?> list(@RequestParam(defaultValue="1") int page,
                                                       @RequestParam(defaultValue="20") int size,
                                                       @RequestParam(required=false) String keyword,
                                                       @RequestParam(required=false) String birthdayFilter,
                                                       @RequestParam(required=false) String birthdayFrom,
                                                       @RequestParam(required=false) String birthdayTo,
                                                       @RequestParam(defaultValue="desc") String birthdayOrder,
                                                       HttpServletRequest r) {
        String k = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        io.jsonwebtoken.Claims c = claims(r);
        String scope = "SALES".equals(String.valueOf(c.get("role"))) ? " and sales_id=:uid" : "";
        String normalizedFrom = blankToNull(birthdayFrom), normalizedTo = blankToNull(birthdayTo);
        String birthdayWhere = birthdayClause(birthdayFilter, normalizedFrom, normalizedTo);
        String order = "asc".equalsIgnoreCase(birthdayOrder) ? "birthday asc, member_id desc" : "birthday desc, member_id desc";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("s", db.store(r)).addValue("k", k).addValue("uid", Long.parseLong(c.getSubject()))
                .addValue("limit", Math.min(Math.max(size, 1), 200)).addValue("off", Math.max(page - 1, 0) * Math.min(Math.max(size, 1), 200))
                .addValue("todayMonth", LocalDate.now(ZoneId.of("Asia/Shanghai")).getMonthValue()).addValue("todayDay", LocalDate.now(ZoneId.of("Asia/Shanghai")).getDayOfMonth())
                .addValue("from", normalizedFrom).addValue("to", normalizedTo);
        String base = " from member where store_id=:s" + scope + " and (name like :k or phone like :k)" + birthdayWhere;
        return ApiResponse.ok(Map.of("records", db.list("select *" + base + " order by " + order + " limit :limit offset :off", p),
                "total", db.jdbc().queryForObject("select count(*)" + base, p, Integer.class)));
    }
    public record Req(@NotBlank String name,@NotBlank @Pattern(regexp="^1[3-9]\\d{9}$", message="手机号格式不合法") String phone,String tags,String birthday,String gender,Long salesId,String source){}
    @PostMapping @RequireRoles({"ADMIN","MANAGER","CASHIER","SALES"}) @RequirePermission("member:create") public ApiResponse<?> create(@Valid @RequestBody Req q,HttpServletRequest r){
        String birthday = blankToNull(q.birthday());
        validateBirthday(birthday);
        io.jsonwebtoken.Claims claims = claims(r);
        Long salesId = q.salesId();
        if ("SALES".equals(String.valueOf(claims.get("role")))) salesId = Long.valueOf(claims.getSubject());
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("s",db.store(r)).addValue("n",q.name()).addValue("p",q.phone()).addValue("tags",q.tags()).addValue("b",birthday).addValue("gender",q.gender()).addValue("sid",salesId).addValue("src",q.source());
        db.jdbc().update("insert into member(store_id,name,phone,tags,balance,points,total_consume,source,sales_id,birthday,gender,create_time,update_time) values(:s,:n,:p,:tags,0,0,0,:src,:sid,:b,:gender,now(),now())",p);
        broadcastMemberUpdated(db.store(r), "CREATE", null);
        return ApiResponse.ok();
    }
    @PutMapping("/{id}") @RequireRoles({"ADMIN","MANAGER"}) public ApiResponse<?> update(@PathVariable long id,@Valid @RequestBody Req q,HttpServletRequest r){
        String birthday = blankToNull(q.birthday());
        validateBirthday(birthday);
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("id",id).addValue("s",db.store(r)).addValue("n",q.name()).addValue("phone",q.phone()).addValue("tags",q.tags()).addValue("birthday",birthday).addValue("gender",q.gender()).addValue("sid",q.salesId()).addValue("source",q.source());
        int changed=db.jdbc().update("update member set name=:n,phone=:phone,tags=:tags,birthday=:birthday,gender=:gender,sales_id=coalesce(:sid,sales_id),source=coalesce(:source,source),update_time=now() where member_id=:id and store_id=:s",p);
        if(changed==0) throw new BusinessException(404301,"会员不存在");
        broadcastMemberUpdated(db.store(r), "UPDATE", id);
        return ApiResponse.ok();
    }
    @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable long id,HttpServletRequest r){assertVisible(id,r);Map<String,Object> p=Map.of("id",id,"s",db.store(r));Map<String,Object> result=new LinkedHashMap<>();result.put("member",db.one("select * from member where member_id=:id and store_id=:s",p));result.put("visits",db.list("select * from visit_task where member_id=:id and store_id=:s order by task_id desc",p));return ApiResponse.ok(result);}
@GetMapping("/{id}/consume") public ApiResponse<?> consume(@PathVariable long id,HttpServletRequest r){assertVisible(id,r);return ApiResponse.ok(db.list("select c.consume_id,c.amount,c.consume_time,c.order_id,o.order_no,o.pay_method,(select group_concat(i.item_name separator '、') from sales_order_item i where i.order_id=c.order_id) as items from member_consume c left join sales_order o on o.order_id=c.order_id where c.order_id is not null and c.member_id=:id and c.store_id=:s order by c.consume_id desc",Map.of("id",id,"s",db.store(r))));}
    @PostMapping("/{id}/claim") public ApiResponse<?> claim(@PathVariable long id,HttpServletRequest r){io.jsonwebtoken.Claims c=claims(r);long uid=Long.parseLong(c.getSubject());long storeId=db.store(r);int changed=db.jdbc().update("update member set sales_id=:u,update_time=now() where member_id=:id and store_id=:s and (sales_id is null or sales_id=0)",Map.of("u",uid,"id",id,"s",storeId));if(changed==0)throw new BusinessException(409201,"会员已被认领");broadcastMemberUpdated(storeId,"CLAIM",id);return ApiResponse.ok();}
    @GetMapping("/pool") public ApiResponse<?> pool(@RequestParam(defaultValue="1") int page,
                                                       @RequestParam(defaultValue="20") int size,
                                                       @RequestParam(required=false) String keyword,
                                                       @RequestParam(required=false) String birthdayFilter,
                                                       @RequestParam(required=false) String birthdayFrom,
                                                       @RequestParam(required=false) String birthdayTo,
                                                       @RequestParam(defaultValue="desc") String birthdayOrder,
                                                       HttpServletRequest r) {
        String k = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        String normalizedFrom = blankToNull(birthdayFrom), normalizedTo = blankToNull(birthdayTo);
        String birthdayWhere = birthdayClause(birthdayFilter, normalizedFrom, normalizedTo);
        String order = "asc".equalsIgnoreCase(birthdayOrder) ? "birthday asc, member_id desc" : "birthday desc, member_id desc";
        int safeSize = Math.min(Math.max(size, 1), 200);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r)).addValue("k", k)
                .addValue("limit", safeSize).addValue("off", Math.max(page - 1, 0) * safeSize)
                .addValue("todayMonth", LocalDate.now(ZoneId.of("Asia/Shanghai")).getMonthValue())
                .addValue("todayDay", LocalDate.now(ZoneId.of("Asia/Shanghai")).getDayOfMonth())
                .addValue("from", normalizedFrom).addValue("to", normalizedTo);
        String base = " from member where store_id=:s and (sales_id is null or sales_id=0) and (name like :k or phone like :k)" + birthdayWhere;
        return ApiResponse.ok(Map.of("records", db.list("select *" + base + " order by " + order + " limit :limit offset :off", p),
                "total", db.jdbc().queryForObject("select count(*)" + base, p, Integer.class)));
    }
    @PostMapping("/{id}/assign") @RequireRoles({"ADMIN","MANAGER"}) public ApiResponse<?> assign(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r){if(q.get("salesId")==null)throw new BusinessException(400301,"salesId不能为空");long storeId=db.store(r);int changed=db.jdbc().update("update member set sales_id=:sales,update_time=now() where member_id=:id and store_id=:s",new MapSqlParameterSource().addValue("sales",q.get("salesId")).addValue("id",id).addValue("s",storeId));if(changed==0)throw new BusinessException(404301,"会员不存在");broadcastMemberUpdated(storeId,"ASSIGN",id);return ApiResponse.ok();}
    @PostMapping("/{id}/balance") @RequireRoles({"ADMIN","MANAGER"})
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public ApiResponse<?> balance(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r) {
        BigDecimal amount;
        try { amount=new BigDecimal(String.valueOf(q.getOrDefault("amount",0))).setScale(2,java.math.RoundingMode.UNNECESSARY); }
        catch (ArithmeticException | NumberFormatException e) { throw new BusinessException(400302,"金额最多保留两位小数"); }
        String type=String.valueOf(q.getOrDefault("type","RECHARGE")).toUpperCase(Locale.ROOT);
        if(amount.signum()<=0 || !Set.of("RECHARGE","DEDUCT").contains(type)) throw new BusinessException(400302,"金额或储值操作类型不合法");
        String client=String.valueOf(q.getOrDefault("clientRequestId","")).trim();
        if(client.isBlank() || client.length()>64) throw new BusinessException(400302,"储值请求编号不能为空且不超过64位");
        long storeId=db.store(r);
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("id",id).addValue("s",storeId).addValue("client",client);
        db.one("select member_id,balance from member where member_id=:id and store_id=:s for update",p);
        var replay=db.list("select member_id,amount from member_balance_record where store_id=:s and type='MANUAL' and reference_no=:client",p);
        BigDecimal delta="DEDUCT".equals(type)?amount.negate():amount;
        if(!replay.isEmpty()) {
            if(((Number)replay.get(0).get("member_id")).longValue()!=id || new BigDecimal(replay.get(0).get("amount").toString()).compareTo(delta)!=0)
                throw new BusinessException(409302,"储值请求编号已被使用");
            return ApiResponse.ok(db.one("select member_id,balance from member where member_id=:id and store_id=:s",p));
        }
        String method=String.valueOf(q.getOrDefault("payMethod","CASH")).toUpperCase(Locale.ROOT);
        if("RECHARGE".equals(type)) method=PaymentChannelPolicy.requireActiveExternal(db,storeId,method);
        p.addValue("delta",delta).addValue("requiredBalance",delta.signum()<0?amount:BigDecimal.ZERO);
        int changed=db.jdbc().update("update member set balance=balance+:delta,update_time=now() where member_id=:id and store_id=:s and balance>=:requiredBalance",p);
        if(changed!=1) throw new BusinessException(409302,"会员不存在或储值余额不足");
        long uid=Long.parseLong(claims(r).getSubject());
        new MemberBalanceLedger(db).record(storeId,id,delta,"MANUAL",client,uid);
        if("RECHARGE".equals(type)) db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'INCOME','MEMBER_RECHARGE',:delta,:method,:no,:uid,'会员充值',:shift,now())",
                p.addValue("method",method).addValue("no","CZ"+UUID.randomUUID().toString().replace("-","").substring(0,28)).addValue("uid",uid).addValue("shift",new com.dajin.system.shift.ShiftService(db).current(storeId)));
        broadcastMemberUpdated(storeId,"BALANCE",id);
        ws.broadcast("SHIFT_UPDATED",Map.of("storeId",storeId));
        ws.broadcast("REPORT_UPDATED",Map.of("storeId",storeId));
        return ApiResponse.ok(db.one("select member_id,balance from member where member_id=:id and store_id=:s",p));
    }
    @GetMapping("/{id}/balance-records") public ApiResponse<?> balanceRecords(@PathVariable long id,HttpServletRequest r) {
        assertVisible(id,r);
        // Legacy adjustments have no reliable closing balance or payment channel.
        return ApiResponse.ok(db.list("select record_id consume_id,amount,balance_after,type,reference_no,create_time consume_time from member_balance_record where member_id=:id and store_id=:s union all select -consume_id,amount,null,'LEGACY',null,consume_time from member_consume where member_id=:id and store_id=:s and order_id is null order by consume_time desc,consume_id desc",Map.of("id",id,"s",db.store(r))));
    }
    private void assertVisible(long id,HttpServletRequest r){io.jsonwebtoken.Claims c=claims(r);if("SALES".equals(String.valueOf(c.get("role")))&&db.jdbc().queryForObject("select count(*) from member where member_id=:id and store_id=:s and sales_id=:uid",new MapSqlParameterSource().addValue("id",id).addValue("s",db.store(r)).addValue("uid",Long.parseLong(c.getSubject())),Integer.class)==0)throw new BusinessException(403402,"会员不属于当前销售");}
    private io.jsonwebtoken.Claims claims(HttpServletRequest r){return (io.jsonwebtoken.Claims)r.getAttribute("claims");}
    private void validateBirthday(String birthday) {
        if (birthday == null || birthday.isBlank()) return;
        try { LocalDate.parse(birthday); } catch (Exception e) { throw new BusinessException(400303, "生日必须是YYYY-MM-DD格式"); }
    }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private String birthdayClause(String filter, String from, String to) {
        if ("today".equalsIgnoreCase(filter)) return " and month(birthday)=:todayMonth and day(birthday)=:todayDay";
        if ("month".equalsIgnoreCase(filter)) return " and month(birthday)=:todayMonth";
        if ("window".equalsIgnoreCase(filter)) {
            String fromMd = blankToNull(from), toMd = blankToNull(to);
            if (fromMd == null || toMd == null) throw new BusinessException(400303, "window筛选需要MM-DD格式的起止日期");
            if (!fromMd.matches("\\d{2}-\\d{2}") || !toMd.matches("\\d{2}-\\d{2}")) throw new BusinessException(400303, "window日期必须是MM-DD格式");
            if (fromMd.compareTo(toMd) > 0) return " and (date_format(birthday,'%m-%d')>=:from or date_format(birthday,'%m-%d')<=:to)";
            return " and date_format(birthday,'%m-%d') between :from and :to";
        }
        String normalizedFrom = blankToNull(from), normalizedTo = blankToNull(to);
        validateBirthday(normalizedFrom); validateBirthday(normalizedTo);
        if (normalizedFrom != null && normalizedTo != null) {
            if (normalizedFrom.compareTo(normalizedTo) > 0) throw new BusinessException(400304, "生日范围起始日期不能晚于结束日期");
            return " and birthday between :from and :to";
        }
        if (normalizedFrom != null) return " and birthday>=:from";
        if (normalizedTo != null) return " and birthday<=:to";
        return "";
    }
    private void broadcastMemberUpdated(long storeId, String action, Long memberId) {
        Runnable publish = () -> {
            Map<String,Object> event = new HashMap<>();
            event.put("storeId", storeId);
            event.put("action", action);
            if (memberId != null) event.put("memberId", memberId);
            ws.broadcast("MEMBER_UPDATED", event);
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else publish.run();
    }
}
