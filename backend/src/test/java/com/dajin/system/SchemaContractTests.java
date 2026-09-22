package com.dajin.system;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class SchemaContractTests {
    @Test void schemaContainsStoreScopedTables() throws Exception {
        String sql = Files.readString(Path.of("..", "db", "schema.sql"));
        Matcher matcher = Pattern.compile("CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\((.*?)\\);", Pattern.DOTALL).matcher(sql);
        Set<String> tables = new HashSet<>();
        while (matcher.find()) {
            tables.add(matcher.group(1));
            assertTrue(matcher.group(2).contains("store_id"), matcher.group(1) + " missing store_id");
        }
        assertTrue(tables.containsAll(Set.of(
                "sys_store", "sys_user", "sys_role", "sys_role_permission", "sys_user_permission",
                "goods_category", "goods", "stock_inbound", "stock_inbound_item",
                "sales_order", "sales_order_item", "member", "finance_record",
                "processing_category", "processing_item", "processing_order",
                "processing_payment", "processing_commission", "print_job", "operation_log"
        )), "required business tables missing: " + tables);
        assertTrue(sql.contains("discount_threshold"));
        assertTrue(sql.contains("recycle_approval_limit"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS pay_channel"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS stock_supplier"));
        assertTrue(sql.contains("uk_stock_supplier_store_code(store_id,supplier_code)"));
        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS sys_role_permission"));
        assertTrue(sql.contains("uk_role_permission(store_id,role_code,permission_code)"));
        assertTrue(sql.contains("uk_user_permission(store_id,user_id,permission_code)"));
        assertTrue(sql.contains("permission_initialized TINYINT NOT NULL DEFAULT 0"));
        assertTrue(sql.contains("ignored_time DATETIME NULL"));
        assertTrue(sql.contains("ignored_by BIGINT NULL"));
        assertTrue(sql.contains("remark VARCHAR(500)"));
        assertTrue(sql.contains("scope_type VARCHAR(24)"));
        assertTrue(sql.contains("client_request_id VARCHAR(64)"));
        assertTrue(sql.contains("uk_stock_check_client(store_id,client_request_id)"));
        assertTrue(sql.contains("idx_stock_check_scope(store_id,status,scope_type,scope_id)"));
        assertTrue(sql.contains("uk_goods_piece_store_no (store_id, piece_no)"));
        assertTrue(sql.contains("piece_nos JSON NULL"));
        assertTrue(sql.contains("cost_snapshot DECIMAL(14,2), piece_nos JSON NULL"));
        assertTrue(sql.contains("'打金师傅','CRAFTSMAN'"));
    }
}
