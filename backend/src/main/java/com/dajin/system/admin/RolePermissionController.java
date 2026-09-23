package com.dajin.system.admin;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.PermissionCatalog;
import com.dajin.system.config.PermissionService;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
@RequireRoles("ADMIN")
public class RolePermissionController {
    private final DbSupport db; private final PermissionService permissions; private final SyncWebSocketHandler ws; private final ObjectMapper objectMapper = new ObjectMapper();
    public RolePermissionController(DbSupport db, PermissionService permissions, SyncWebSocketHandler ws) { this.db = db; this.permissions = permissions; this.ws = ws; }

    @GetMapping("/permissions/tree")
    public ApiResponse<?> tree() { return ApiResponse.ok(PermissionCatalog.tree()); }

    @GetMapping("/roles/{code}/permissions")
    public ApiResponse<?> rolePermissions(@PathVariable String code, HttpServletRequest request) {
        String roleCode = normalizeRole(code, request);
        Set<String> selected = permissions.permissions(db.store(request), roleCode);
        if (selected.contains("*")) selected = PermissionCatalog.codes();
        return ApiResponse.ok(Map.of("roleCode", roleCode, "permissions", selected));
    }

    @GetMapping("/staff/{id}/permissions")
    public ApiResponse<?> userPermissions(@PathVariable long id, HttpServletRequest request) {
        Map<String, Object> user = user(id, request);
        String roleCode = String.valueOf(user.get("role_code"));
        Set<String> selected = permissions.userPermissions(id, db.store(request), roleCode);
        if (selected.contains("*")) selected = PermissionCatalog.codes();
        return ApiResponse.ok(Map.of("userId", id, "roleCode", roleCode, "permissions", selected));
    }

    @PutMapping("/staff/{id}/permissions")
    @Transactional
    public ApiResponse<?> saveUserPermissions(@PathVariable long id, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        Map<String, Object> user = user(id, request);
        Set<String> selected = selected(body);
        long store = db.store(request);
        db.jdbc().update("delete from sys_user_permission where store_id=:store and user_id=:user", Map.of("store", store, "user", id));
        for (String permission : selected) db.jdbc().update("insert into sys_user_permission(store_id,user_id,permission_code) values(:store,:user,:permission)", Map.of("store", store, "user", id, "permission", permission));
        db.jdbc().update("update sys_user set permission_initialized=1,update_time=now(),version=version+1 where store_id=:store and user_id=:user", Map.of("store", store, "user", id));
        long operatorId = Long.parseLong(((io.jsonwebtoken.Claims) request.getAttribute("claims")).getSubject());
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:store,:operator,'USER_PERMISSION','UPDATE',:content,:ip,now())",
                new MapSqlParameterSource().addValue("store", store).addValue("operator", operatorId).addValue("content", "员工=" + user.get("username") + ",权限数=" + selected.size()).addValue("ip", request.getRemoteAddr()));
        ws.broadcast("USER_PERMISSIONS_UPDATED", Map.of("storeId", store, "userId", id));
        return ApiResponse.ok(Map.of("userId", id, "permissions", selected));
    }

    @PutMapping("/roles/{code}/permissions")
    @Transactional
    public ApiResponse<?> save(@PathVariable String code, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String roleCode = normalizeRole(code, request);
        Set<String> selected = selected(body);
        if ("ADMIN".equals(roleCode)) selected = Set.of("*");
        long store = db.store(request);
        db.jdbc().update("delete from sys_role_permission where store_id=:store and role_code=:role", Map.of("store", store, "role", roleCode));
        for (String permission : selected) db.jdbc().update("insert into sys_role_permission(store_id,role_code,permission_code) values(:store,:role,:permission)", Map.of("store", store, "role", roleCode, "permission", permission));
        try {
            String json = objectMapper.writeValueAsString(new ArrayList<>(selected));
            db.jdbc().update("update sys_role set permissions=:permissions,update_time=now() where store_id=:store and role_code=:role", new MapSqlParameterSource().addValue("permissions", json).addValue("store", store).addValue("role", roleCode));
        } catch (Exception e) { throw new BusinessException(500440, "权限数据保存失败"); }
        long userId = Long.parseLong(((io.jsonwebtoken.Claims) request.getAttribute("claims")).getSubject());
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:store,:user,'ROLE_PERMISSION','UPDATE',:content,:ip,now())", new MapSqlParameterSource().addValue("store", store).addValue("user", userId).addValue("content", "角色=" + roleCode + ",权限数=" + selected.size()).addValue("ip", request.getRemoteAddr()));
        ws.broadcast("ROLE_PERMISSIONS_UPDATED", Map.of("storeId", store, "roleCode", roleCode));
        return ApiResponse.ok(Map.of("roleCode", roleCode, "permissions", selected));
    }

    private Set<String> selected(Map<String, Object> body) {
        Set<String> selected = new LinkedHashSet<>();
        Object raw = body.get("permissions");
        if (raw instanceof Collection<?> values) for (Object value : values) selected.add(String.valueOf(value));
        if (!PermissionCatalog.codes().containsAll(selected)) throw new BusinessException(400440, "权限编码中包含未定义项");
        return selected;
    }

    private Map<String, Object> user(long id, HttpServletRequest request) {
        try {
            return db.one("select u.user_id,u.username,r.role_code from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.user_id=:user and u.store_id=:store",
                    Map.of("user", id, "store", db.store(request)));
        } catch (Exception e) { throw new BusinessException(404421, "员工不存在"); }
    }

    private String normalizeRole(String code, HttpServletRequest request) {
        String role = code == null ? "" : code.trim().toUpperCase();
        int count = db.jdbc().queryForObject("select count(*) from sys_role where store_id=:store and role_code=:role", Map.of("store", db.store(request), "role", role), Integer.class);
        if (count == 0) throw new BusinessException(404440, "角色不存在");
        return role;
    }
}
