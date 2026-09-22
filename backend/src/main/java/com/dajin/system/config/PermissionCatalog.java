package com.dajin.system.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PermissionCatalog {
    private PermissionCatalog() { }

    private static final List<Group> GROUPS = List.of(
            group("dashboard", "仪表盘", item("dashboard:view", "查看经营看板")),
            group("goods", "商品管理", item("goods:search", "查询商品"), item("goods:manage", "新增、编辑及上下架商品")),
            group("stock", "库存管理", item("stock:view", "查看库存"), item("stock:inbound:create", "盘点入库"), item("stock:transfer", "库存调拨"),
                    item("stock:check:view", "查看盘点"), item("stock:check:create", "发起盘点"),
                    item("stock:check:submit", "提交盘点"), item("stock:check:approve", "审批盘点")),
            group("order", "销售收银", item("order:create", "销售开单"), item("order:checkout", "订单结算"), item("order:refund", "退款申请")),
            group("member", "会员管理", item("member:view", "查看会员"), item("member:view:all", "查看全店会员"), item("member:create", "新增会员"), item("member:follow", "会员跟进回访")),
            group("processing", "加工管理", item("processing:view", "查看及处理加工业务")),
            group("recycle", "回收以旧换新", item("recycle:view", "查看及处理回收业务")),
            group("report", "报表", item("report:view", "查看业绩报表"), item("report:view:all", "查看全店报表")),
            group("staff", "人员管理", item("staff:manage", "员工与角色管理")),
            group("shift", "交班结算", item("shift:confirm", "交班结算")),
            group("notification", "消息通知", item("notification:view", "查看消息")),
            group("system", "系统设置", item("gold:manage", "金价设置"), item("system:manage", "系统设置"))
    );

    private static final Map<String, Set<String>> DEFAULTS = Map.of(
            "ADMIN", Set.of("*"),
            "MANAGER", Set.of("dashboard:view", "goods:search", "goods:manage", "stock:view", "stock:inbound:create", "stock:transfer", "stock:check:view", "stock:check:create", "stock:check:submit", "stock:check:approve", "order:create", "order:checkout", "order:refund", "member:view", "member:view:all", "member:create", "member:follow", "processing:view", "recycle:view", "report:view", "report:view:all", "staff:manage", "shift:confirm", "notification:view", "gold:manage"),
            "SALES", Set.of("dashboard:view", "goods:search", "stock:inbound:create", "stock:check:view", "stock:check:create", "stock:check:submit", "member:view", "member:create", "member:follow", "processing:view", "recycle:view", "report:view", "notification:view"),
            "CASHIER", Set.of("dashboard:view", "goods:search", "order:create", "order:checkout", "processing:view", "member:view", "member:view:all", "member:create", "shift:confirm", "notification:view"),
            "CRAFTSMAN", Set.of("processing:view")
    );

    public static List<Map<String, Object>> tree() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Group group : GROUPS) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "group:" + group.code());
            node.put("label", group.label());
            List<Map<String, String>> children = new ArrayList<>();
            for (Item item : group.items()) children.add(Map.of("id", item.code(), "label", item.label(), "permissionCode", item.code()));
            node.put("children", children);
            result.add(node);
        }
        return result;
    }

    public static Set<String> codes() {
        Set<String> result = new LinkedHashSet<>();
        for (Group group : GROUPS) for (Item item : group.items()) result.add(item.code());
        return result;
    }

    public static Set<String> defaults(String roleCode) {
        return DEFAULTS.getOrDefault(roleCode == null ? "" : roleCode.toUpperCase(), Set.of());
    }

    private static Group group(String code, String label, Item... items) { return new Group(code, label, List.of(items)); }
    private static Item item(String code, String label) { return new Item(code, label); }
    private record Group(String code, String label, List<Item> items) { }
    private record Item(String code, String label) { }
}
