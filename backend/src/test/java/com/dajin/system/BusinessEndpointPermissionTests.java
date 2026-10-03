package com.dajin.system;

import com.dajin.system.config.RequirePermission;
import com.dajin.system.recycle.RecycleController;
import com.dajin.system.shift.ShiftController;
import com.dajin.system.approval.ApprovalController;
import com.dajin.system.member.MemberController;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BusinessEndpointPermissionTests {
    @Test
    void recycleEndpointsRequireRecyclePermission() throws Exception {
        assertPermission(RecycleController.class.getMethod("create", java.util.Map.class, HttpServletRequest.class), "recycle:view");
        assertPermission(RecycleController.class.getMethod("list", HttpServletRequest.class), "recycle:view");
    }

    @Test
    void shiftEndpointsRequireShiftConfirmationPermission() throws Exception {
        assertPermission(ShiftController.class.getMethod("info", HttpServletRequest.class), "shift:confirm");
        assertPermission(ShiftController.class.getMethod("confirm", java.util.Map.class, HttpServletRequest.class), "shift:confirm");
    }

    @Test
    void approvalEndpointsRequireGenericHandlingPermission() throws Exception {
        assertPermission(ApprovalController.class.getMethod("approve", long.class, java.util.Map.class, HttpServletRequest.class), "approval:handle");
        assertPermission(ApprovalController.class.getMethod("reject", long.class, java.util.Map.class, HttpServletRequest.class), "approval:handle");
    }

    @Test
    void memberManagementEndpointsUseSpecificPermissions() throws Exception {
        assertPermission(MemberController.class.getMethod("update", long.class, MemberController.Req.class, HttpServletRequest.class), "member:manage");
        assertPermission(MemberController.class.getMethod("balance", long.class, java.util.Map.class, HttpServletRequest.class), "member:manage");
        assertPermission(MemberController.class.getMethod("assign", long.class, java.util.Map.class, HttpServletRequest.class), "member:manage");
        assertPermission(MemberController.class.getMethod("claim", long.class, HttpServletRequest.class), "member:follow");
    }

    private void assertPermission(Method method, String expected) {
        RequirePermission annotation = method.getAnnotation(RequirePermission.class);
        assertArrayEquals(new String[]{expected}, annotation.value(), method.toString());
    }
}
