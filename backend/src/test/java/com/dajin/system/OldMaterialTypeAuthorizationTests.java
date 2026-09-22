package com.dajin.system;

import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.stock.StockController;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OldMaterialTypeAuthorizationTests {
    @Test
    void salesOrderCreatorCanReadOldMaterialTypes() throws Exception {
        Method method = StockController.class.getMethod("oldMaterialTypes", HttpServletRequest.class);

        assertTrue(allows(method, "SALES", Set.of("order:create")),
                "销售开单账号必须能读取旧料类型");
    }

    @Test
    void salesOrderCreatorCannotMaintainOldMaterialTypes() throws Exception {
        Method create = StockController.class.getMethod(
                "createOldMaterialType", Map.class, HttpServletRequest.class);
        Method update = StockController.class.getMethod(
                "updateOldMaterialType", long.class, Map.class, HttpServletRequest.class);
        Method changeStatus = StockController.class.getMethod(
                "oldMaterialTypeStatus", long.class, int.class, HttpServletRequest.class);
        Method delete = StockController.class.getMethod(
                "deleteOldMaterialType", long.class, HttpServletRequest.class);

        Set<String> permissions = Set.of("order:create");
        assertFalse(allows(create, "SALES", permissions));
        assertFalse(allows(update, "SALES", permissions));
        assertFalse(allows(changeStatus, "SALES", permissions));
        assertFalse(allows(delete, "SALES", permissions));
    }

    private boolean allows(Method method, String role, Set<String> permissions) {
        RequireRoles roles = method.getAnnotation(RequireRoles.class);
        if (roles != null && Arrays.stream(roles.value()).noneMatch(role::equals)) return false;

        RequirePermission required = method.getAnnotation(RequirePermission.class);
        if (required == null) return true;
        if (permissions.contains("*")) return true;
        return required.anyOf()
                ? Arrays.stream(required.value()).anyMatch(permissions::contains)
                : Arrays.stream(required.value()).allMatch(permissions::contains);
    }
}
