package com.dajin.system.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ManagerPermissionPolicyTests {
    @Test void managementModulesEnforceTheirOwnPermissions() {
        assertEquals("goods:manage", ManagerPermissionPolicy.required("/api/admin/categories/3", "PUT"));
        assertEquals("gold:view", ManagerPermissionPolicy.required("/api/gold-price/spot", "GET"));
        assertEquals("gold:manage", ManagerPermissionPolicy.required("/api/gold-price/types/3", "PUT"));
        assertEquals("system:manage", ManagerPermissionPolicy.required("/api/pay/channels", "POST"));
        assertEquals("processing:view", ManagerPermissionPolicy.required("/api/processing/orders/3", "GET"));
        assertEquals("processing:manage", ManagerPermissionPolicy.required("/api/processing/orders/3/payments", "POST"));
        assertEquals("processing:commissions", ManagerPermissionPolicy.required("/api/processing/commissions/3/pay", "PATCH"));
        assertEquals("member:manage", ManagerPermissionPolicy.required("/api/member/3/balance", "POST"));
        assertEquals("stock:transfer", ManagerPermissionPolicy.required("/api/stock/old-material/3", "PUT"));
        assertEquals("system:manage", ManagerPermissionPolicy.required("/api/system/config", "GET"));
        assertEquals("report:view:all", ManagerPermissionPolicy.required("/api/admin/finance/summary", "GET"));
        assertNull(ManagerPermissionPolicy.required("/api/system/store-info", "GET"));
    }

    @Test void actionPermissionsBringTheirReadPrerequisites() {
        var selected = PermissionCatalog.withPrerequisites(java.util.Set.of("gold:manage", "processing:manage", "commission:manage"));
        assertEquals(true, selected.containsAll(java.util.Set.of("gold:view", "processing:view", "report:view:all", "report:view")));
    }
}
