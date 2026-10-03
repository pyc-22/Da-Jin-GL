package com.dajin.system.config;

/** Additional module boundaries for management-console endpoints shared with other clients. */
public final class ManagerPermissionPolicy {
    private ManagerPermissionPolicy() { }

    public static String required(String path, String method) {
        boolean read = "GET".equalsIgnoreCase(method);
        if (path.startsWith("/api/admin/")) {
            String name = path.substring("/api/admin/".length());
            if (name.startsWith("dashboard")) return "dashboard:view";
            if (name.startsWith("categories")) return read ? "goods:search" : "goods:manage";
            if (name.startsWith("stock/checks")) return "stock:check:view";
            if (name.startsWith("stock/in") || name.startsWith("stock/out")) return "stock:transfer";
            if (name.startsWith("stock/")) return "stock:view";
            if (name.startsWith("approval/")) return "approval:view";
            if (name.startsWith("sales")) return "order:checkout";
            if (name.startsWith("staff") || name.startsWith("roles") || name.startsWith("schedules")) return "staff:manage";
            if (name.startsWith("commission")) return read ? "report:view:all" : "commission:manage";
            if (name.startsWith("finance")) return "report:view:all";
            if (name.startsWith("store") || name.startsWith("config") || name.startsWith("logs") || name.startsWith("backups")) return "system:manage";
        }
        if (path.startsWith("/api/gold-price/")) {
            if (path.equals("/api/gold-price/current") || path.equals("/api/gold-price/types")) return null;
            return read ? "gold:view" : "gold:manage";
        }
        if (path.equals("/api/gold-price")) return read ? "gold:view" : "gold:manage";
        if (path.startsWith("/api/pay/channels")) return "system:manage";
        if (path.startsWith("/api/stock/old-material")) return read ? "stock:view" : "stock:transfer";
        if (path.equals("/api/system/config")) return "system:manage";
        if (path.equals("/api/system/target")) return read ? "dashboard:view" : "system:manage";
        if (path.startsWith("/api/approval/")) return read ? "approval:view" : "approval:handle";
        if (path.startsWith("/api/processing/")) {
            String name = path.substring("/api/processing/".length());
            if (name.startsWith("commissions")) return "processing:commissions";
            if (name.startsWith("loss-")) return "processing:loss";
            if (name.startsWith("categories") || name.startsWith("items") || name.startsWith("craftsmen")) return read ? "processing:view|processing:items" : "processing:items";
            if (name.startsWith("statistics") || name.startsWith("handovers")) return "processing:view";
            if (name.startsWith("orders")) return read ? "processing:view" : "processing:manage";
            return "processing:manage";
        }
        if (path.startsWith("/api/member/")) {
            if (path.endsWith("/balance") || path.endsWith("/assign") || path.matches(".*/\\d+$")) return "member:manage";
            if (path.endsWith("/claim")) return "member:follow";
            if ("PUT".equalsIgnoreCase(method)) return "member:manage";
            return read ? "member:view" : "member:follow";
        }
        if (path.equals("/api/member")) return read ? "member:view" : "member:create";
        if (path.startsWith("/api/visit/")) return "member:follow";
        if (path.startsWith("/api/notification")) return "notification:view";
        if (path.startsWith("/api/commission/")) return "commission:manage";
        if (path.startsWith("/api/order/")) return read ? "order:checkout" : null;
        return null;
    }
}
