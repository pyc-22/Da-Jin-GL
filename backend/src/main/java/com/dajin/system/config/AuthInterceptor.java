package com.dajin.system.config;

import io.jsonwebtoken.Claims;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import javax.servlet.http.*;

@Component
public class AuthInterceptor implements HandlerInterceptor {
    private final JwtService jwt;
    private final PermissionService permissions;
    public AuthInterceptor(JwtService jwt, PermissionService permissions) { this.jwt = jwt; this.permissions = permissions; }
    @Override public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        // Let WebConfig add CORS headers to browser preflight requests before JWT checks.
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String path=req.getRequestURI(); if (path.startsWith("/api/auth/") || path.startsWith("/api/file/") || path.equals("/api/user/login") || path.startsWith("/swagger") || path.startsWith("/v3/api-docs") || path.equals("/actuator/health")) return true;
        String value=req.getHeader("Authorization");
        if (value==null || !value.startsWith("Bearer ")) { res.setStatus(401); return false; }
        try { Claims c=jwt.parse(value.substring(7)); long userId=Long.parseLong(c.getSubject()); long storeId=((Number)c.get("storeId")).longValue();
            PermissionService.UserAccess access=permissions.userAccess(userId,storeId);
            if(access==null || !access.active() || !access.roleCode().equals(String.valueOf(c.get("role")))) { res.setStatus(401); return false; }
            req.setAttribute("claims", c); req.setAttribute("storeId", storeId);
            if (handler instanceof HandlerMethod method) {
                RequireRoles roles = method.getMethodAnnotation(RequireRoles.class);
                if (roles == null) roles = method.getBeanType().getAnnotation(RequireRoles.class);
                if (roles != null && java.util.Arrays.stream(roles.value()).noneMatch(v -> v.equals(access.roleCode()))) { res.setStatus(403); return false; }
                RequirePermission required = method.getMethodAnnotation(RequirePermission.class);
                if (required == null) required = method.getBeanType().getAnnotation(RequirePermission.class);
                if (required != null) {
                    boolean allowed = required.anyOf()
                            ? java.util.Arrays.stream(required.value()).anyMatch(code -> permissions.hasPermission(userId, storeId, access.roleCode(), code))
                            : java.util.Arrays.stream(required.value()).allMatch(code -> permissions.hasPermission(userId, storeId, access.roleCode(), code));
                    if (!allowed) { res.setStatus(403); return false; }
                }
            }
            return true; }
        catch (Exception e) { res.setStatus(401); return false; }
    }
}
