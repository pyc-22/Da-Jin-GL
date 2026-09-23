package com.dajin.system.admin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffRolePolicyTests {
    @Test
    void managerCanOnlyManageSalesCashierAndCraftsmanAccounts() {
        assertTrue(StaffRolePolicy.canManage("MANAGER", "SALES"));
        assertTrue(StaffRolePolicy.canManage("MANAGER", "CASHIER"));
        assertTrue(StaffRolePolicy.canManage("MANAGER", "CRAFTSMAN"));
        assertFalse(StaffRolePolicy.canManage("MANAGER", "MANAGER"));
        assertFalse(StaffRolePolicy.canManage("MANAGER", "ADMIN"));
    }

    @Test
    void administratorCanManageEveryBuiltInRole() {
        assertTrue(StaffRolePolicy.canManage("ADMIN", "ADMIN"));
        assertTrue(StaffRolePolicy.canManage("ADMIN", "MANAGER"));
        assertTrue(StaffRolePolicy.canManage("ADMIN", "CASHIER"));
        assertTrue(StaffRolePolicy.canManage("ADMIN", "SALES"));
        assertTrue(StaffRolePolicy.canManage("ADMIN", "CRAFTSMAN"));
    }
}
