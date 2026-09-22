package com.dajin.system.auth;

import com.dajin.system.common.*; import com.dajin.system.config.JwtService; import com.dajin.system.config.PermissionService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate; import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest; import javax.validation.Valid; import javax.validation.constraints.*; import java.util.*;
import io.jsonwebtoken.Claims;

@RestController @RequestMapping({"/api/auth", "/api/user"})
public class AuthController {
    private final NamedParameterJdbcTemplate jdbc; private final JwtService jwt; private final PermissionService permissionService; private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    public AuthController(NamedParameterJdbcTemplate jdbc, JwtService jwt, PermissionService permissionService){this.jdbc=jdbc;this.jwt=jwt;this.permissionService=permissionService;}
    public record LoginReq(@NotBlank String username,@NotBlank String password, String clientType){}
    @PostMapping("/login") public ApiResponse<?> login(@Valid @RequestBody LoginReq req){
        List<Map<String,Object>> rows=jdbc.queryForList("select u.user_id,u.username,u.password,u.real_name,u.role_id,u.store_id,u.status,u.phone,u.entry_date,u.remark,r.role_code,r.role_name,s.store_name from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id join sys_store s on s.store_id=u.store_id where u.username=:u and r.status=1 limit 1",Map.of("u",req.username()));
        if(rows.isEmpty()) throw new BusinessException(401001,"用户名或密码错误");
        if (((Number) rows.get(0).get("status")).intValue() != 1) throw new BusinessException(403002,"账号已禁用");
        String stored = String.valueOf(rows.get(0).get("password"));
        boolean matched = stored.startsWith("$2") ? encoder.matches(req.password(), stored) : req.password().equals(stored);
        if (!matched) throw new BusinessException(401001,"用户名或密码错误");
        String roleCode = String.valueOf(rows.get(0).get("role_code"));
        String clientType = req.clientType() == null ? "" : req.clientType().trim().toUpperCase(Locale.ROOT);
        boolean allowed = switch (clientType) {
            case "ADMIN_WEB" -> roleCode.equals("ADMIN") || roleCode.equals("MANAGER");
            // CASHIER is the mobile storekeeper/inbound role. SALES intentionally
            // remains excluded from stock-in access by the route and permissions.
            case "MOBILE" -> roleCode.equals("ADMIN") || roleCode.equals("MANAGER") || roleCode.equals("CASHIER") || roleCode.equals("SALES");
            case "FRONT_PC" -> roleCode.equals("ADMIN") || roleCode.equals("MANAGER") || roleCode.equals("CASHIER");
            default -> true;
        };
        if (!allowed) throw new BusinessException(403001, "当前角色无权登录此终端");
        if (!stored.startsWith("$2")) jdbc.update("update sys_user set password=:p where user_id=:id", Map.of("p", encoder.encode(req.password()), "id", rows.get(0).get("user_id")));
        Map<String,Object> u=new LinkedHashMap<>(rows.get(0)); u.remove("password");
        long userId=((Number)u.get("user_id")).longValue(); long storeId=((Number)u.get("store_id")).longValue();
        List<String> permissions=new ArrayList<>(permissionService.userPermissions(userId,storeId,roleCode));
        u.put("permissions",permissions);
        String token=jwt.issue(userId,req.username(),roleCode,storeId);
        Map<String,Object> userInfo=new LinkedHashMap<>();
        userInfo.put("userId",userId); userInfo.put("username",u.get("username")); userInfo.put("realName",u.get("real_name"));
        userInfo.put("roleCode",roleCode); userInfo.put("roleName",u.get("role_name")); userInfo.put("storeId",storeId); userInfo.put("storeName",u.get("store_name")); userInfo.put("permissions",permissions);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("token",token); result.put("user",u); result.put("userInfo",userInfo); result.put("permissions",permissions);
        return ApiResponse.ok(result);
    }
    @PostMapping("/logout") public ApiResponse<Void> logout(){return ApiResponse.ok();}
    @GetMapping("/me") public ApiResponse<?> currentUser(HttpServletRequest request){
        Claims claims=(Claims)request.getAttribute("claims");
        long userId=Long.parseLong(claims.getSubject()); long storeId=((Number)claims.get("storeId")).longValue();
        List<Map<String,Object>> rows=jdbc.queryForList("select u.user_id,u.username,u.password,u.real_name,u.role_id,u.store_id,u.status,u.phone,u.entry_date,u.remark,r.role_code,r.role_name,s.store_name from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id join sys_store s on s.store_id=u.store_id where u.user_id=:id and u.store_id=:s and u.status=1 and r.status=1 limit 1",Map.of("id",userId,"s",storeId));
        if(rows.isEmpty()) throw new BusinessException(401001,"登录状态已失效");
        Map<String,Object> user=new LinkedHashMap<>(rows.get(0)); user.remove("password");
        String roleCode=String.valueOf(user.get("role_code")); List<String> permissions=new ArrayList<>(permissionService.userPermissions(userId,storeId,roleCode)); user.put("permissions",permissions);
        Map<String,Object> userInfo=new LinkedHashMap<>(); userInfo.put("userId",userId); userInfo.put("username",user.get("username")); userInfo.put("realName",user.get("real_name")); userInfo.put("roleCode",roleCode); userInfo.put("roleName",user.get("role_name")); userInfo.put("storeId",storeId); userInfo.put("storeName",user.get("store_name")); userInfo.put("permissions",permissions);
        Map<String,Object> result=new LinkedHashMap<>(); result.put("user",user); result.put("userInfo",userInfo); result.put("permissions",permissions);
        return ApiResponse.ok(result);
    }
}
