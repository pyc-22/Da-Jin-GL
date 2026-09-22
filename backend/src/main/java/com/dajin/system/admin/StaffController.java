package com.dajin.system.admin;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/admin/staff")
@RequireRoles({"ADMIN", "MANAGER"})
@RequirePermission("staff:manage")
public class StaffController {
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9]{4,20}$");
    private static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{6,20}$");
    private static final Pattern PHONE = Pattern.compile("^1\\d{10}$");
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public StaffController(DbSupport db, SyncWebSocketHandler ws) { this.db = db; this.ws = ws; }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String keyword,
                               @RequestParam(required = false) Integer status,
                               HttpServletRequest request) {
        if (status != null && status != 0 && status != 1) throw new BusinessException(400421, "员工状态不合法");
        String key = keyword == null ? "" : keyword.trim();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("store", db.store(request)).addValue("status", status).addValue("keyword", "%" + key + "%").addValue("blank", key);
        return ApiResponse.ok(db.list("select u.user_id,u.store_id,u.username,u.real_name,u.role_id,r.role_code,r.role_name,u.phone,u.entry_date,u.remark,u.status,s.store_name,u.create_time "
                + "from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id join sys_store s on s.store_id=u.store_id "
                + "where u.store_id=:store and (:status is null or u.status=:status) and (:blank='' or u.real_name like :keyword or u.username like :keyword or coalesce(u.phone,'') like :keyword) order by u.user_id desc", params));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable long id, HttpServletRequest request) {
        try {
            return ApiResponse.ok(db.one("select u.user_id,u.store_id,u.username,u.real_name,u.role_id,r.role_code,r.role_name,u.phone,u.entry_date,u.remark,u.status,s.store_name,u.create_time from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id join sys_store s on s.store_id=u.store_id where u.user_id=:id and u.store_id=:store", Map.of("id", id, "store", db.store(request))));
        } catch (Exception e) { throw new BusinessException(404421, "员工不存在"); }
    }

    @GetMapping("/check-username")
    public ApiResponse<?> checkUsername(@RequestParam String username,
                                        @RequestParam(required = false) Long excludeId) {
        String value = username == null ? "" : username.trim();
        if (!USERNAME.matcher(value).matches()) return ApiResponse.ok(Map.of("available", false, "message", "账号须为4-20位字母或数字"));
        int count = db.jdbc().queryForObject("select count(*) from sys_user where username=:username and (:exclude is null or user_id<>:exclude)", new MapSqlParameterSource().addValue("username", value).addValue("exclude", excludeId), Integer.class);
        return ApiResponse.ok(Map.of("available", count == 0, "message", count == 0 ? "账号可用" : "登录账号已存在"));
    }

    @PostMapping
    @Transactional
    public ApiResponse<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ValidStaff input = validate(body, request, null, true);
        MapSqlParameterSource params = baseParams(input).addValue("password", encoder.encode(input.password()));
        db.jdbc().update("insert into sys_user(store_id,username,password,real_name,role_id,phone,entry_date,remark,status,create_time,update_time) values(:store,:username,:password,:realName,:roleId,:phone,:entryDate,:remark,:status,now(),now())", params);
        Long id = db.jdbc().queryForObject("select user_id from sys_user where username=:username and store_id=:store", Map.of("username", input.username(), "store", input.storeId()), Long.class);
        initializePermissions(id, input.storeId(), input.roleCode());
        log(request, "CREATE", "新增移动端员工账号=" + input.username() + ",角色=" + input.roleCode());
        ws.broadcast("STAFF_UPDATED", Map.of("storeId", input.storeId(), "userId", id, "action", "CREATE", "roleCode", input.roleCode()));
        return ApiResponse.ok(Map.of("userId", id));
    }

    @PutMapping("/{id}")
    @Transactional
    public ApiResponse<?> update(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        assertManageableTarget(id, request);
        ValidStaff input = validate(body, request, id, false);
        MapSqlParameterSource params = baseParams(input).addValue("id", id).addValue("password", input.password() == null ? null : encoder.encode(input.password()));
        db.jdbc().update("update sys_user set username=:username,real_name=:realName,role_id=:roleId,phone=:phone,entry_date=:entryDate,remark=:remark,status=:status,password=coalesce(:password,password),update_time=now(),version=version+1 where user_id=:id and store_id=:store", params);
        log(request, "UPDATE", "编辑员工ID=" + id + ",角色=" + input.roleCode());
        ws.broadcast("STAFF_UPDATED", Map.of("storeId", input.storeId(), "userId", id, "action", "UPDATE", "roleCode", input.roleCode()));
        return ApiResponse.ok(Map.of("userId", id));
    }

    @PutMapping("/{id}/status")
    public ApiResponse<?> updateStatus(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body,
                                       @RequestParam(required = false) Integer status, HttpServletRequest request) {
        int next = status != null ? status : intValue(body == null ? null : body.get("status"), -1);
        if (next != 0 && next != 1) throw new BusinessException(400421, "员工状态不合法");
        if (id == userId(request) && next == 0) throw new BusinessException(409423, "当前登录账号不能禁用自己");
        assertManageableTarget(id, request);
        int changed = db.jdbc().update("update sys_user set status=:status,update_time=now(),version=version+1 where user_id=:id and store_id=:store", Map.of("status", next, "id", id, "store", db.store(request)));
        if (changed == 0) throw new BusinessException(404421, "员工不存在");
        log(request, next == 1 ? "ENABLE" : "DISABLE", "员工ID=" + id);
        ws.broadcast("STAFF_UPDATED", Map.of("storeId", db.store(request), "userId", id, "action", next == 1 ? "ENABLE" : "DISABLE", "status", next));
        return ApiResponse.ok(Map.of("status", next));
    }

    @DeleteMapping("/{id}")
    @Transactional
    public ApiResponse<?> delete(@PathVariable long id, HttpServletRequest request) {
        if (id == userId(request)) throw new BusinessException(409424, "当前登录账号不能删除自己");
        Map<String, Object> user = assertManageableTarget(id, request);
        long store = db.store(request);
        int linked = count("select count(*) from sales_order where store_id=:store and (cashier_id=:id or sales_id=:id)", store, id)
                + count("select count(*) from approval where store_id=:store and (applicant_id=:id or approver_id=:id)", store, id)
                + count("select count(*) from visit_task where store_id=:store and sales_id=:id", store, id)
                + count("select count(*) from commission_record where store_id=:store and user_id=:id", store, id)
                + count("select count(*) from processing_order where store_id=:store and (craftsman_id=:id or created_by=:id)", store, id);
        if (linked > 0) throw new BusinessException(409422, "该员工有业务记录，无法删除，请改用禁用");
        db.jdbc().update("delete from sys_user_permission where user_id=:id and store_id=:store", Map.of("id", id, "store", store));
        db.jdbc().update("delete from sys_user where user_id=:id and store_id=:store", Map.of("id", id, "store", store));
        log(request, "DELETE", "删除员工账号=" + user.get("username"));
        ws.broadcast("STAFF_UPDATED", Map.of("storeId", store, "userId", id, "action", "DELETE"));
        return ApiResponse.ok(Map.of("deleted", true));
    }

    private ValidStaff validate(Map<String, Object> body, HttpServletRequest request, Long excludeId, boolean creating) {
        String username = text(body.get("username")); String realName = text(body.get("realName")); String password = text(body.get("password"));
        String confirm = text(body.get("confirmPassword")); String phone = text(body.get("phone")); String entryDate = text(body.get("entryDate")); String remark = text(body.get("remark"));
        if (realName.isBlank()) throw new BusinessException(400420, "请输入姓名");
        if (!USERNAME.matcher(username).matches()) throw new BusinessException(400425, "账号须为4-20位字母或数字");
        int exists = db.jdbc().queryForObject("select count(*) from sys_user where username=:username and (:exclude is null or user_id<>:exclude)", new MapSqlParameterSource().addValue("username", username).addValue("exclude", excludeId), Integer.class);
        if (exists > 0) throw new BusinessException(409420, "登录账号已存在");
        if (creating && password.isBlank()) throw new BusinessException(400426, "请输入初始密码");
        if (!password.isBlank() && !PASSWORD.matcher(password).matches()) throw new BusinessException(400427, "密码须为6-20位且同时包含字母和数字");
        if (!password.isBlank() && !confirm.isBlank() && !password.equals(confirm)) throw new BusinessException(400428, "两次输入的密码不一致");
        if (!phone.isBlank() && !PHONE.matcher(phone).matches()) throw new BusinessException(400429, "请输入正确的11位手机号");
        if (entryDate.isBlank()) entryDate = LocalDate.now().toString();
        try { LocalDate.parse(entryDate); } catch (DateTimeParseException e) { throw new BusinessException(400430, "入职日期格式不正确"); }
        int status = intValue(body.get("status"), 1); if (status != 0 && status != 1) throw new BusinessException(400421, "员工状态不合法");
        long requestedStore = longValue(body.get("storeId"), db.store(request));
        if (requestedStore != db.store(request)) throw new BusinessException(403425, "所属门店超出当前账号管理范围");
        Map<String, Object> role = resolveRole(body, request);
        return new ValidStaff(username, realName, password.isBlank() ? null : password, phone.isBlank() ? null : phone, entryDate, remark.isBlank() ? null : remark, status, requestedStore, ((Number) role.get("role_id")).longValue(), String.valueOf(role.get("role_code")));
    }

    private Map<String, Object> resolveRole(Map<String, Object> body, HttpServletRequest request) {
        Object roleId = body.get("roleId"); Object roleCode = body.get("roleCode");
        if (roleId == null && roleCode == null) throw new BusinessException(400431, "请选择角色");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("store", db.store(request)).addValue("roleId", roleId).addValue("roleCode", roleCode == null ? null : text(roleCode).toUpperCase());
        Map<String, Object> role;
        try { role = db.one("select role_id,role_code from sys_role where store_id=:store and status=1 and ((:roleId is not null and role_id=:roleId) or (:roleCode is not null and role_code=:roleCode)) limit 1", params); }
        catch (Exception e) { throw new BusinessException(400432, "所选角色不存在或已禁用"); }
        String operatorRole = role(request); String target = String.valueOf(role.get("role_code"));
        if (!StaffRolePolicy.canManage(operatorRole, target)) throw new BusinessException(403426, "店长只能分配销售、前台收银或打金师傅角色");
        return role;
    }

    private MapSqlParameterSource baseParams(ValidStaff input) {
        return new MapSqlParameterSource().addValue("store", input.storeId()).addValue("username", input.username()).addValue("realName", input.realName()).addValue("roleId", input.roleId()).addValue("phone", input.phone()).addValue("entryDate", input.entryDate()).addValue("remark", input.remark()).addValue("status", input.status());
    }
    private void initializePermissions(long userId, long storeId, String roleCode) {
        db.jdbc().update("insert ignore into sys_user_permission(store_id,user_id,permission_code) select :store,:user,permission_code from sys_role_permission where store_id=:store and role_code=:role",
                Map.of("store", storeId, "user", userId, "role", roleCode));
        db.jdbc().update("update sys_user set permission_initialized=1 where user_id=:user and store_id=:store", Map.of("user", userId, "store", storeId));
    }
    private Map<String, Object> assertExists(long id, HttpServletRequest request) { try { return db.one("select u.username,r.role_code from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.user_id=:id and u.store_id=:store", Map.of("id", id, "store", db.store(request))); } catch (Exception e) { throw new BusinessException(404421, "员工不存在"); } }
    private Map<String, Object> assertManageableTarget(long id, HttpServletRequest request) {
        Map<String, Object> user = assertExists(id, request);
        if (!StaffRolePolicy.canManage(role(request), String.valueOf(user.get("role_code"))))
            throw new BusinessException(403427, "店长只能管理销售、前台收银或打金师傅账号");
        return user;
    }
    private int count(String sql, long store, long id) { return db.jdbc().queryForObject(sql, Map.of("store", store, "id", id), Integer.class); }
    private void log(HttpServletRequest request, String action, String content) { db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:store,:user,'STAFF',:action,:content,:ip,now())", new MapSqlParameterSource().addValue("store", db.store(request)).addValue("user", userId(request)).addValue("action", action).addValue("content", content).addValue("ip", request.getRemoteAddr())); }
    private long userId(HttpServletRequest request) { return Long.parseLong(((io.jsonwebtoken.Claims) request.getAttribute("claims")).getSubject()); }
    private String role(HttpServletRequest request) { return String.valueOf(((io.jsonwebtoken.Claims) request.getAttribute("claims")).get("role")); }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private long longValue(Object value, long fallback) { try { return value == null || text(value).isBlank() ? fallback : Long.parseLong(String.valueOf(value)); } catch (Exception e) { throw new BusinessException(400433, "门店参数格式不正确"); } }
    private record ValidStaff(String username, String realName, String password, String phone, String entryDate, String remark, int status, long storeId, long roleId, String roleCode) { }
}
