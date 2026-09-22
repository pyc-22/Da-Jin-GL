package com.dajin.system;

import com.dajin.system.config.PermissionCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCatalogTests {
    @Test void salesCanCountButCannotApproveStockChecks() {
        assertTrue(PermissionCatalog.defaults("SALES").contains("stock:inbound:create"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("stock:check:view"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("stock:check:create"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("stock:check:submit"));
        assertFalse(PermissionCatalog.defaults("SALES").contains("stock:check:approve"));
    }

    @Test void salesStartsWithRecycleProcessingAndMemberPermissions() {
        assertTrue(PermissionCatalog.defaults("SALES").contains("recycle:view"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("processing:view"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("member:view"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("member:create"));
        assertTrue(PermissionCatalog.defaults("SALES").contains("member:follow"));
    }

    @Test void managerCanApproveStockChecks() {
        assertTrue(PermissionCatalog.defaults("MANAGER").contains("stock:check:approve"));
    }

    @Test void craftsmanStartsWithProcessingPermissionOnly() {
        assertTrue(PermissionCatalog.defaults("CRAFTSMAN").contains("processing:view"));
        assertFalse(PermissionCatalog.defaults("CRAFTSMAN").contains("order:create"));
        assertFalse(PermissionCatalog.defaults("CRAFTSMAN").contains("staff:manage"));
    }
}
