package com.dajin.system.admin;

import java.util.Set;

final class StaffRolePolicy {
    private static final Set<String> MANAGER_ASSIGNABLE = Set.of("CASHIER", "SALES", "CRAFTSMAN");

    private StaffRolePolicy() { }

    static boolean canManage(String operatorRole, String targetRole) {
        return "ADMIN".equals(operatorRole) || MANAGER_ASSIGNABLE.contains(targetRole);
    }
}
