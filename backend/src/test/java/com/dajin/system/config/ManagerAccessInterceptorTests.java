package com.dajin.system.config;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagerAccessInterceptorTests {
    static class Endpoint { public void handle() { } }
    private final HandlerMethod handler;

    ManagerAccessInterceptorTests() throws NoSuchMethodException {
        handler = new HandlerMethod(new Endpoint(), Endpoint.class.getMethod("handle"));
    }

    @Test void deniedManagerWriteReturns403EvenWhenTheMenuWasPreviouslyVisible() throws Exception {
        JwtService jwt = mock(JwtService.class);
        PermissionService permissions = mock(PermissionService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/gold-price/types/3");
        when(request.getMethod()).thenReturn("PUT");
        when(request.getHeader("Authorization")).thenReturn("Bearer token");
        when(jwt.parse("token")).thenReturn(Jwts.claims().setSubject("8").setIssuedAt(new java.util.Date()).setExpiration(new java.util.Date(System.currentTimeMillis() + 60000)));
        var claims = jwt.parse("token");
        claims.put("storeId", 3);
        claims.put("role", "MANAGER");
        when(permissions.userAccess(8L, 3L)).thenReturn(new PermissionService.UserAccess(true, "MANAGER"));
        when(permissions.hasPermission(8L, 3L, "MANAGER", "gold:manage")).thenReturn(false);

        assertFalse(new AuthInterceptor(jwt, permissions).preHandle(request, response, handler));
        verify(response).setStatus(403);
    }

    @Test void userInfoRequiresAuthentication() throws Exception {        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/auth/me");
        when(request.getMethod()).thenReturn("GET");
        assertFalse(new AuthInterceptor(mock(JwtService.class), mock(PermissionService.class))
                .preHandle(request, response, handler));
        verify(response).setStatus(401);
    }

    @Test void anonymousMemberRegistrationFromQrCodeIsAllowed() throws Exception {
        // 客户扫「我的专属二维码」打开登记页后是未登录状态，必须能匿名提交登记
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/public/member/register");
        when(request.getMethod()).thenReturn("POST");
        assertTrue(new AuthInterceptor(mock(JwtService.class), mock(PermissionService.class))
                .preHandle(request, response, handler));
        verify(response, never()).setStatus(anyInt());
    }

    @Test void authenticatedEndpointsStillRejectAnonymousRequests() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getRequestURI()).thenReturn("/api/processing/orders");
        when(request.getMethod()).thenReturn("GET");
        assertFalse(new AuthInterceptor(mock(JwtService.class), mock(PermissionService.class))
                .preHandle(request, response, handler));
        verify(response).setStatus(401);
    }
}
