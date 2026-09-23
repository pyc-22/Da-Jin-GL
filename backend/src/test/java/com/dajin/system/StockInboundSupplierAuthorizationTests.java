package com.dajin.system;

import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.stock.StockController;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockInboundSupplierAuthorizationTests {
    @Test
    void supplierSelectorSupportsEveryRoleAllowedToCreateInboundOrders() throws Exception {
        Method method = StockController.class.getMethod("inboundSuppliers", Long.class, HttpServletRequest.class);
        RequireRoles roles = method.getAnnotation(RequireRoles.class);
        RequirePermission permission = method.getAnnotation(RequirePermission.class);

        assertEquals(Set.of("ADMIN", "MANAGER", "CASHIER", "SALES"), Set.copyOf(Arrays.asList(roles.value())));
        assertEquals(Set.of("stock:inbound:create"), Set.copyOf(Arrays.asList(permission.value())));
    }

    @Test
    void inboundReadEndpointsSupportEveryRoleAllowedToCreateInboundOrders() throws Exception {
        for (Method method : List.of(
                StockController.class.getMethod("inboundHistory", HttpServletRequest.class),
                StockController.class.getMethod("inboundDetailEndpoint", long.class, HttpServletRequest.class),
                StockController.class.getMethod("inboundPending", HttpServletRequest.class))) {
            RequireRoles roles = method.getAnnotation(RequireRoles.class);
            RequirePermission permission = method.getAnnotation(RequirePermission.class);

            assertEquals(Set.of("ADMIN", "MANAGER", "CASHIER", "SALES"), Set.copyOf(Arrays.asList(roles.value())));
            assertEquals(Set.of("stock:inbound:create"), Set.copyOf(Arrays.asList(permission.value())));
        }
    }
}
