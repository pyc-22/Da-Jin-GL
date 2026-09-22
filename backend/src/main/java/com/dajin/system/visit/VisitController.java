package com.dajin.system.visit;

import com.dajin.system.common.*;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @RequestMapping("/api/visit") @RequirePermission("member:follow")
public class VisitController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    public VisitController(DbSupport db, SyncWebSocketHandler ws) { this.db = db; this.ws = ws; }
    @GetMapping("/tasks") public ApiResponse<?> tasks(@RequestParam(required=false) Integer status,
                                                       @RequestParam(required=false) String visitType,
                                                       @RequestParam(required=false) String keyword,
                                                       HttpServletRequest req) {
        MapSqlParameterSource p=params(req).addValue("status",status).addValue("type",blank(visitType)).addValue("keyword",keyword==null||keyword.isBlank()?"%":"%"+keyword.trim()+"%");
        String sql="select v.*,m.name member_name,coalesce(v.phone_snapshot,m.phone) phone,coalesce(v.gender_snapshot,m.gender) gender,coalesce(v.order_no_snapshot,o.order_no) order_no,coalesce(v.amount_snapshot,o.pay_amount,0) pay_amount,coalesce(v.purchase_time_snapshot,o.create_time) purchase_time,u.real_name sales_name "
                +"from visit_task v join member m on m.member_id=v.member_id and m.store_id=v.store_id left join sales_order o on o.order_id=v.order_id and o.store_id=v.store_id left join sys_user u on u.user_id=v.sales_id and u.store_id=v.store_id "
                +"where v.store_id=:s"+salesScope(req)+" and (:status is null or v.status=:status) and (:type is null or v.visit_type=:type) and (m.name like :keyword or coalesce(v.phone_snapshot,m.phone) like :keyword or coalesce(v.order_no_snapshot,o.order_no,'') like :keyword) order by v.plan_time,v.task_id";
        return ApiResponse.ok(db.list(sql,p));
    }
    @GetMapping("/stats") public ApiResponse<?> stats(HttpServletRequest req) {
        return ApiResponse.ok(db.one("select count(*) total,sum(status=1) pending,sum(status=2) completed,sum(call_result='CONNECTED') connected,sum(call_result in ('NO_ANSWER','REJECTED','INVALID','UNREACHABLE')) unsuccessful from visit_task v where v.store_id=:s"+salesScope(req),params(req)));
    }
    @PostMapping("/tasks") @Transactional public ApiResponse<?> createTask(@RequestBody Map<String,Object> body,HttpServletRequest req) {
        long memberId=longValue(body.get("memberId")); io.jsonwebtoken.Claims c=claims(req); long uid=Long.parseLong(c.getSubject()); long storeId=db.store(req);
        Map<String,Object> member=db.one("select member_id,sales_id,phone,gender from member where member_id=:m and store_id=:s",Map.of("m",memberId,"s",storeId));
        if("SALES".equals(String.valueOf(c.get("role"))) && (member.get("sales_id")==null || ((Number)member.get("sales_id")).longValue()!=uid)) throw new BusinessException(403401,"会员不属于当前销售");
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("s",storeId).addValue("m",memberId).addValue("sales",body.getOrDefault("salesId",uid)).addValue("type",body.getOrDefault("visitType","FOLLOW_UP")).addValue("plan",dateValue(body.get("planTime"))).addValue("status",1).addValue("phone",member.get("phone")).addValue("gender",member.get("gender"));
        db.jdbc().update("insert into visit_task(store_id,member_id,sales_id,visit_type,plan_time,status,phone_snapshot,gender_snapshot,create_time,update_time) values(:s,:m,:sales,:type,:plan,:status,:phone,:gender,now(),now())",p);
        broadcast(storeId,"CREATE",null);
        return ApiResponse.ok();
    }
    @PostMapping("/complete") @Transactional public ApiResponse<?> complete(@RequestBody Map<String,Object> body, HttpServletRequest req) { return record(body,req); }
    @PostMapping("/tasks/{id}/call") public ApiResponse<?> startCall(@PathVariable long id,HttpServletRequest req) {
        io.jsonwebtoken.Claims c=claims(req); String scope="SALES".equals(String.valueOf(c.get("role")))?" and sales_id=:uid":"";
        MapSqlParameterSource p=params(req).addValue("id",id);
        int changed=db.jdbc().update("update visit_task set call_started_at=now(),update_time=now() where task_id=:id and store_id=:s"+scope,p);
        if(changed==0)throw new BusinessException(404401,"回访任务不存在或不属于当前销售");
        broadcast(db.store(req),"CALL",id);
        return ApiResponse.ok(Map.of("taskId",id,"started",true));
    }
    @PutMapping("/tasks/{id}/assign") @RequireRoles({"ADMIN","MANAGER"}) public ApiResponse<?> assign(@PathVariable long id,@RequestBody Map<String,Object> body,HttpServletRequest req) {
        if(body.get("salesId")==null)throw new BusinessException(400405,"请选择接收员工");
        long salesId=Long.parseLong(String.valueOf(body.get("salesId")));
        if(db.jdbc().queryForObject("select count(*) from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.user_id=:uid and u.store_id=:s and u.status=1 and r.role_code in ('SALES','MANAGER')",Map.of("uid",salesId,"s",db.store(req)),Integer.class)==0)throw new BusinessException(400406,"接收员工不存在或已禁用");
        long storeId=db.store(req);
        int changed=db.jdbc().update("update visit_task set sales_id=:sales,update_time=now() where task_id=:id and store_id=:s",Map.of("sales",salesId,"id",id,"s",storeId));
        if(changed==0)throw new BusinessException(404401,"回访任务不存在");
        broadcast(storeId,"ASSIGN",id);
        return ApiResponse.ok(Map.of("taskId",id,"salesId",salesId));
    }
    @PostMapping("/record") @Transactional public ApiResponse<?> record(@RequestBody Map<String,Object> body, HttpServletRequest req) {
        io.jsonwebtoken.Claims c=claims(req); String scope="SALES".equals(String.valueOf(c.get("role")))?" and sales_id=:uid":""; long storeId=db.store(req); Object taskId=body.getOrDefault("taskId",body.get("id"));
        MapSqlParameterSource p=new MapSqlParameterSource().addValue("id",taskId).addValue("record",body.get("record")).addValue("callResult",body.getOrDefault("callResult",body.get("result"))).addValue("callStartedAt",dateValue(body.get("callStartedAt"))).addValue("s",storeId).addValue("uid",Long.parseLong(c.getSubject()));
        var tasks=db.list("select status from visit_task where task_id=:id and store_id=:s"+scope+" for update",p);
        if(tasks.isEmpty()) throw new BusinessException(404401,"回访任务不存在或不属于当前销售");
        if(((Number)tasks.get(0).get("status")).intValue()==2) return ApiResponse.ok(Map.of("completed",true,"idempotentReplay",true));
        int changed=db.jdbc().update("update visit_task set status=2,record=:record,call_result=:callResult,call_started_at=coalesce(:callStartedAt,call_started_at),update_time=now() where task_id=:id and store_id=:s"+scope,p); if(changed==0) throw new BusinessException(404401,"回访任务不存在或不属于当前销售");
        Object next=body.get("nextFollowUp"); if(next!=null && !String.valueOf(next).isBlank()) db.jdbc().update("insert into visit_task(store_id,member_id,sales_id,order_id,order_no_snapshot,amount_snapshot,purchase_time_snapshot,phone_snapshot,gender_snapshot,visit_type,followup_no,plan_time,status,record,create_time,update_time) select store_id,member_id,sales_id,order_id,order_no_snapshot,amount_snapshot,purchase_time_snapshot,phone_snapshot,gender_snapshot,'FOLLOW_UP',followup_no+1,:plan,1,null,now(),now() from visit_task where task_id=:id and store_id=:s",p.addValue("plan",dateValue(next)));
        broadcast(storeId,"COMPLETE",taskId==null?null:Long.parseLong(String.valueOf(taskId)));
        return ApiResponse.ok(Map.of("completed",true,"nextFollowUp",next==null?"":next));
    }
    private String salesScope(HttpServletRequest req){return "SALES".equals(String.valueOf(claims(req).get("role")))?" and v.sales_id=:uid":"";}
    private MapSqlParameterSource params(HttpServletRequest req){io.jsonwebtoken.Claims c=claims(req);return new MapSqlParameterSource().addValue("s",db.store(req)).addValue("uid",Long.parseLong(c.getSubject()));}
    private io.jsonwebtoken.Claims claims(HttpServletRequest req){return (io.jsonwebtoken.Claims)req.getAttribute("claims");}
    private long longValue(Object x){if(x==null)throw new BusinessException(400404,"memberId不能为空");return Long.parseLong(String.valueOf(x));}
    private String dateValue(Object x){
        if(x==null)return null;
        String v=String.valueOf(x).replace('T',' ').trim();
        try{
            java.time.LocalDateTime ldt=java.time.LocalDateTime.parse(v.substring(0,Math.min(16,v.length())),java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return ldt.atZone(java.time.ZoneId.of("Asia/Shanghai")).withZoneSameInstant(java.time.ZoneId.of("UTC")).toLocalDateTime().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }catch(Exception e){return v;}
    }
    private String blank(String x){return x==null||x.isBlank()?null:x.trim();}
    private void broadcast(long storeId,String action,Long taskId){Map<String,Object> event=new LinkedHashMap<>();event.put("storeId",storeId);event.put("action",action);if(taskId!=null)event.put("taskId",taskId);ws.broadcast("VISIT_TASK_UPDATED",event);}
}
