package com.dajin.system.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;

/** Keeps existing local databases compatible with additive schema changes. */
@Component
@Order(10)
public class SchemaCompatibilityMigration implements CommandLineRunner {
    private final JdbcTemplate jdbc;

    public SchemaCompatibilityMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void run(String... args) {
        // Type definitions are JSON and grow with each store's configurable
        // metals. Expand legacy varchar(500) columns before the first write.
        jdbc.update("alter table sys_config modify column config_value varchar(2000) character set utf8mb4 collate utf8mb4_unicode_ci not null");
        addColumn("goods", "gold_type", "VARCHAR(50) NULL DEFAULT '足金' AFTER price_type");
        addColumn("goods", "certificate_no", "VARCHAR(64) NULL AFTER gold_type");
        normalizeGoodsStock();
        addColumn("goods_category", "category_code", "VARCHAR(50) NULL AFTER name");
        addColumn("goods_category", "level", "TINYINT NULL DEFAULT 1 AFTER parent_id");
        addColumn("old_material", "direction", "TINYINT NOT NULL DEFAULT 1 AFTER status");
        addColumn("stock_check", "detail", "JSON NULL AFTER total_diff");
        addColumn("stock_check", "scope_type", "VARCHAR(24) NOT NULL DEFAULT 'GOODS' AFTER bill_no");
        addColumn("stock_check", "scope_id", "BIGINT NULL AFTER scope_type");
        addColumn("stock_check", "remark", "VARCHAR(500) NULL AFTER scope_id");
        addColumn("stock_check", "client_request_id", "VARCHAR(64) NULL AFTER remark");
        addColumn("sales_order", "shift_no", "VARCHAR(48) NULL AFTER client_request_id");
        addColumn("sales_order", "old_material_excess", "DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER old_material_deduct");
        addColumn("sales_order", "old_material_payout_method", "VARCHAR(50) NULL AFTER old_material_excess");
        normalizePendingOldMaterialExcess();
        addColumn("finance_record", "shift_no", "VARCHAR(48) NULL AFTER remark");
        addColumn("sales_order_item", "cost_snapshot", "DECIMAL(14,2) NULL AFTER subtotal");
        addColumn("sales_order_item", "piece_nos", "JSON NULL AFTER cost_snapshot");
        addColumn("trade_in", "operator_id", "BIGINT NULL AFTER approval_id");
        addColumn("member", "birthday", "DATE NULL AFTER sales_id");
        addColumn("member", "gender", "VARCHAR(16) NULL AFTER birthday");
        addColumn("visit_task", "order_id", "BIGINT NULL AFTER member_id");
        addColumn("visit_task", "order_no_snapshot", "VARCHAR(32) NULL AFTER order_id");
        addColumn("visit_task", "amount_snapshot", "DECIMAL(12,2) NULL AFTER order_no_snapshot");
        addColumn("visit_task", "purchase_time_snapshot", "DATETIME NULL AFTER amount_snapshot");
        addColumn("visit_task", "phone_snapshot", "VARCHAR(32) NULL AFTER purchase_time_snapshot");
        addColumn("visit_task", "gender_snapshot", "VARCHAR(16) NULL AFTER phone_snapshot");
        addColumn("visit_task", "call_result", "VARCHAR(24) NULL AFTER record");
        addColumn("visit_task", "call_started_at", "DATETIME NULL AFTER call_result");
        addColumn("processing_order", "commission_rate_snapshot", "DECIMAL(5,2) NOT NULL DEFAULT 0 AFTER unit_labor_fee");
        addColumn("sys_user", "remark", "VARCHAR(500) NULL AFTER entry_date");
        addColumn("sys_user", "permission_initialized", "TINYINT NOT NULL DEFAULT 0 AFTER remark");
        addColumn("sys_role", "description", "VARCHAR(255) NULL AFTER role_code");
        addIndex("sales_order", "idx_order_shift", "store_id,shift_no");
        addIndex("finance_record", "idx_finance_shift", "store_id,shift_no");
        addIndex("goods", "idx_goods_gold_type", "store_id,gold_type");
        addIndex("member", "idx_member_birthday", "store_id,birthday");
        addIndex("stock_check", "uk_stock_check_client", "store_id,client_request_id", true);
        addIndex("stock_check", "idx_stock_check_scope", "store_id,status,scope_type,scope_id");
        addIndex("stock_check", "idx_stock_check_operator_time", "store_id,operator_id,create_time");
        addIndex("trade_in", "idx_tradein_operator_time", "store_id,operator_id,create_time");
        createProcessingTables();
        addColumn("processing_order", "original_due_amount", "DECIMAL(12,2) NULL AFTER due_amount");
        addColumn("processing_order", "promotion_discount", "DECIMAL(12,2) NOT NULL DEFAULT 0 AFTER original_due_amount");
        addColumn("processing_order", "promotion_channel", "VARCHAR(50) NULL AFTER promotion_discount");
        addColumn("processing_order", "voucher_no", "VARCHAR(100) NULL AFTER promotion_channel");
        addIndex("processing_order", "uk_processing_voucher", "store_id,promotion_channel,voucher_no", true);
        jdbc.update("insert ignore into pay_channel(store_id,channel_name,channel_code,sort,status) "
                + "select s.store_id,'抖音团购','DOUYIN_GROUP',7,1 from sys_store s where not exists "
                + "(select 1 from sys_config c where c.store_id=s.store_id and c.config_group='SYSTEM' and c.config_key='group_channels_seeded')");
        jdbc.update("insert ignore into pay_channel(store_id,channel_name,channel_code,sort,status) "
                + "select s.store_id,'美团团购','MEITUAN_GROUP',8,1 from sys_store s where not exists "
                + "(select 1 from sys_config c where c.store_id=s.store_id and c.config_group='SYSTEM' and c.config_key='group_channels_seeded')");
        jdbc.update("insert ignore into sys_config(store_id,config_group,config_key,config_value,description) "
                + "select store_id,'SYSTEM','group_channels_seeded','1','团购渠道初始化标记' from sys_store");
        upgradeAuditColumns();
        createOldMaterialTypeTable();
        createStockInboundTables();
        migrateGoodsPieceIdentity();
        createStockSupplierTable();
        createAccessControlTables();
        upgradeSalesMobilePermissions();
        addColumn("print_job", "ignored_time", "DATETIME NULL AFTER printed_by");
        addColumn("print_job", "ignored_by", "BIGINT NULL AFTER ignored_time");
        seedStockInboundPermissions();
        addColumn("stock_inbound_item", "certificate_no", "VARCHAR(64) NULL AFTER label_price");
        addColumn("stock_inbound_item", "piece_nos", "JSON NULL AFTER images");
        seedProcessingData();
        migrateGoodsCategories();
        migrateOrderCostSnapshots();
        migrateOldMaterialTypes();
        seedVisitTaskCompatibility();
        // Older local databases may predate the configurable old-material types seed.
        // Keep an operator's existing list, while restoring the default when the row is missing.
        // Some early seed scripts serialized a missing key as the literal text "null".
        // Remove those rows so they cannot surface as an editable system parameter.
        jdbc.update("delete from sys_config where config_key is null or trim(config_key) = '' or lower(trim(config_key)) = 'null'");
        jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) "
                + "values(1,'SYSTEM','old_material_types','[{\"name\":\"足金999\",\"status\":1},{\"name\":\"足金990\",\"status\":1},{\"name\":\"22K金\",\"status\":1},{\"name\":\"18K金\",\"status\":1},{\"name\":\"14K金\",\"status\":1},{\"name\":\"铂金950\",\"status\":1},{\"name\":\"铂金900\",\"status\":1},{\"name\":\"纯银\",\"status\":1}]','旧料类型（库存类型管理）',4,1) "
                + "on duplicate key update config_value=if(config_value is null or trim(config_value)='' "
                + "or config_value='[\"足金旧料\",\"18K旧料\",\"铂金旧料\"]',values(config_value),config_value), "
                + "description=coalesce(description,values(description)), enabled=1");
        jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) values(1,'SYSTEM','gold_metal_types','[{\"name\":\"足金\",\"code\":\"GOLD\",\"purity\":99.9,\"price\":612,\"sort\":1,\"status\":1},{\"name\":\"回收金价\",\"code\":\"RECYCLE\",\"purity\":99.9,\"price\":578,\"sort\":2,\"status\":1},{\"name\":\"18K\",\"code\":\"18K\",\"purity\":75,\"price\":428,\"sort\":3,\"status\":1},{\"name\":\"铂金\",\"code\":\"PLATINUM\",\"purity\":95,\"price\":0,\"sort\":4,\"status\":1},{\"name\":\"银\",\"code\":\"SILVER\",\"purity\":99.9,\"price\":0,\"sort\":5,\"status\":1},{\"name\":\"银回收价\",\"code\":\"SILVER_RECYCLE\",\"purity\":99.9,\"price\":0,\"sort\":6,\"status\":1}]','贵金属类型（JSON数组）',5,1) on duplicate key update config_value=if(config_value is null or trim(config_value)='',values(config_value),config_value),enabled=1");
    }

    private void upgradeAuditColumns() {
        jdbc.execute("create table if not exists member_balance_record (record_id bigint primary key auto_increment,store_id bigint not null,member_id bigint not null,amount decimal(12,2) not null,balance_after decimal(12,2) not null,type varchar(30) not null,reference_no varchar(64) not null,operator_id bigint,create_time datetime not null default current_timestamp,unique key uk_balance_ref(store_id,type,reference_no),key idx_balance_member(store_id,member_id,record_id))");
        addColumn("sales_order", "paid_time", "DATETIME NULL");
        addColumn("sales_order", "commission_rate_snapshot", "DECIMAL(10,6) NULL");
        addColumn("sales_order", "handover", "TINYINT NOT NULL DEFAULT 0");
        addIndex("sales_order", "idx_order_paid", "store_id,status,paid_time");
        addColumn("processing_item", "pricing_unit", "VARCHAR(10) NOT NULL DEFAULT '按件'");
        addColumn("processing_item", "duration_text", "VARCHAR(30) NOT NULL DEFAULT ''");
        addColumn("processing_item", "process_steps", "VARCHAR(200) NOT NULL DEFAULT ''");
        addColumn("processing_order", "commission_rate_snapshot", "DECIMAL(5,2) NOT NULL DEFAULT 0");
        addColumn("processing_order", "pricing_unit", "VARCHAR(10) NOT NULL DEFAULT '按件'");
        addColumn("processing_order", "billing_weight", "DECIMAL(10,3) NULL");
        addColumn("processing_order", "store_gold_weight", "DECIMAL(10,3) NOT NULL DEFAULT 0");
        addColumn("processing_order", "store_gold_fineness", "DECIMAL(6,4) NULL");
        addColumn("processing_order", "store_gold_price", "DECIMAL(12,2) NOT NULL DEFAULT 0");
        addColumn("processing_order", "store_gold_amount", "DECIMAL(12,2) NOT NULL DEFAULT 0");
        addColumn("processing_order", "store_gold_goods_id", "BIGINT NULL");
        addColumn("processing_order", "store_gold_deducted", "TINYINT NOT NULL DEFAULT 0");
        addColumn("processing_order", "incoming_photos", "TEXT NULL");
        addColumn("processing_order", "weigh_photos", "TEXT NULL");
        addColumn("processing_order", "pickup_photos", "TEXT NULL");
        addColumn("processing_order", "finished_weight", "DECIMAL(10,3) NULL");
        addColumn("processing_order", "finished_fineness", "DECIMAL(6,4) NULL");
        addColumn("processing_order", "recovered_weight", "DECIMAL(10,3) NULL");
        addColumn("processing_order", "loss_weight", "DECIMAL(10,3) NULL");
        addColumn("processing_order", "loss_permille", "DECIMAL(8,2) NULL");
        addColumn("processing_order", "loss_over", "TINYINT NOT NULL DEFAULT 0");
        addColumn("processing_order", "loss_note", "VARCHAR(500) NULL");
        addColumn("processing_order", "loss_time", "DATETIME NULL");
        addColumn("processing_order", "handover", "TINYINT NOT NULL DEFAULT 0");
        addColumn("processing_order", "handover_time", "DATETIME NULL");
        addColumn("visit_task", "followup_no", "INT NOT NULL DEFAULT 0");
        String fields=jdbc.queryForObject("select group_concat(column_name order by seq_in_index) from information_schema.statistics where table_schema=database() and table_name='visit_task' and index_name='uk_visit_order_type'",String.class);
        if(fields!=null && !fields.contains("followup_no")) jdbc.execute("alter table visit_task drop index uk_visit_order_type");
        addIndex("visit_task","uk_visit_order_type","store_id,order_id,visit_type,followup_no",true);
    }

    private void normalizePendingOldMaterialExcess() {
        jdbc.update("update sales_order set old_material_excess=round(old_material_deduct-(total_amount*discount+labor_fee),2), "
                + "old_material_deduct=round(total_amount*discount+labor_fee,2), "
                + "old_material_payout_method=coalesce(old_material_payout_method,'CASH') "
                + "where status in (0,3) and old_material_deduct>total_amount*discount+labor_fee");
    }

    private void seedVisitTaskCompatibility() {
        jdbc.update("update visit_task v join member m on m.member_id=v.member_id and m.store_id=v.store_id set v.phone_snapshot=coalesce(v.phone_snapshot,m.phone),v.gender_snapshot=coalesce(v.gender_snapshot,m.gender) where v.phone_snapshot is null or v.gender_snapshot is null");
        jdbc.update("insert ignore into visit_task(store_id,member_id,sales_id,order_id,order_no_snapshot,amount_snapshot,purchase_time_snapshot,phone_snapshot,gender_snapshot,visit_type,plan_time,status,create_time,update_time) "
                + "select o.store_id,o.member_id,coalesce(o.sales_id,0),o.order_id,o.order_no,o.pay_amount,o.create_time,m.phone,m.gender,'PURCHASE_3D',date_add(o.create_time,interval 3 day),1,o.create_time,o.update_time "
                + "from sales_order o join member m on m.member_id=o.member_id and m.store_id=o.store_id "
                + "where o.status=1 and o.member_id is not null and o.create_time>=date_sub(now(),interval 3 month) "
                + "and not exists (select 1 from visit_task v where v.store_id=o.store_id and v.order_id=o.order_id and v.visit_type='PURCHASE_3D')");
    }

    private void createAccessControlTables() {
        jdbc.execute("create table if not exists sys_role_permission (id bigint primary key auto_increment,store_id bigint not null,role_code varchar(50) not null,permission_code varchar(100) not null,created_at datetime not null default current_timestamp,unique key uk_role_permission(store_id,role_code,permission_code),key idx_role_permission_code(store_id,permission_code))");
        jdbc.execute("create table if not exists sys_user_permission (id bigint primary key auto_increment,store_id bigint not null,user_id bigint not null,permission_code varchar(100) not null,created_at datetime not null default current_timestamp,unique key uk_user_permission(store_id,user_id,permission_code),key idx_user_permission_code(store_id,permission_code),key idx_user_permission_user(store_id,user_id))");
        jdbc.update("insert ignore into sys_role(store_id,role_name,role_code,description,permissions,status) "
                + "select store_id,'打金师傅','CRAFTSMAN','加工订单承接、损耗与提成归属','[]',1 from sys_store");
        jdbc.update("update sys_role set description=case role_code when 'ADMIN' then '系统全部权限' when 'MANAGER' then '门店经营及管理权限' when 'CASHIER' then '收银、会员及交班权限' when 'SALES' then '个人业绩、会员及回访权限' when 'CRAFTSMAN' then '加工订单承接、损耗与提成归属' else description end where description is null or trim(description)='' ");
        for (String roleCode : java.util.List.of("ADMIN", "MANAGER", "CASHIER", "SALES", "CRAFTSMAN")) {
            for (String permission : PermissionCatalog.defaults(roleCode)) {
                jdbc.update("insert ignore into sys_role_permission(store_id,role_code,permission_code) select store_id,role_code,? from sys_role where role_code=?", permission, roleCode);
            }
        }
        // Preserve permissions added by older installations before the relation table became authoritative.
        java.util.List<java.util.Map<String,Object>> roles = jdbc.queryForList("select store_id,role_code,cast(permissions as char) permissions from sys_role where permissions is not null and json_valid(permissions)=1");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (java.util.Map<String,Object> role : roles) {
            try {
                com.fasterxml.jackson.databind.JsonNode values = mapper.readTree(String.valueOf(role.get("permissions")));
                if (!values.isArray()) continue;
                for (com.fasterxml.jackson.databind.JsonNode value : values) {
                    String code = value.asText("").trim();
                    if (!code.isEmpty()) jdbc.update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,?,?)", role.get("store_id"), role.get("role_code"), code);
                }
            } catch (Exception ignored) { }
        }
        jdbc.update("insert ignore into sys_user_permission(store_id,user_id,permission_code) "
                + "select u.store_id,u.user_id,rp.permission_code from sys_user u "
                + "join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                + "join sys_role_permission rp on rp.store_id=u.store_id and rp.role_code=r.role_code "
                + "where u.permission_initialized=0");
        jdbc.update("update sys_user set permission_initialized=1 where permission_initialized=0");
    }

    /** Adds the expanded sales workflow once without re-applying it after later per-user edits. */
    private void upgradeSalesMobilePermissions() {
        String marker = "sales_mobile_permissions_v2";
        java.util.List<Long> stores = jdbc.queryForList(
                "select s.store_id from sys_store s where not exists (select 1 from sys_config c where c.store_id=s.store_id and c.config_group='MIGRATION' and c.config_key=?)",
                Long.class, marker);
        java.util.List<String> permissions = java.util.List.of(
                "stock:inbound:create", "stock:check:view", "stock:check:create", "stock:check:submit",
                "recycle:view", "processing:view", "member:view", "member:create", "member:follow");
        for (Long storeId : stores) {
            for (String permission : permissions) {
                jdbc.update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'SALES',?)", storeId, permission);
                jdbc.update("insert ignore into sys_user_permission(store_id,user_id,permission_code) "
                                + "select u.store_id,u.user_id,? from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id "
                                + "where u.store_id=? and r.role_code='SALES'",
                        permission, storeId);
            }
            jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) "
                            + "values(?,'MIGRATION',?,'1','销售默认移动端权限升级',0,1)",
                    storeId, marker);
        }
    }

    private void createOldMaterialTypeTable() {
        jdbc.execute("create table if not exists old_material_type (type_id bigint primary key auto_increment,store_id bigint not null,name varchar(50) not null,sort int not null default 0,status tinyint not null default 1,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,unique key uk_old_material_type_store_name(store_id,name),key idx_old_material_type_store_status(store_id,status,sort))");
    }

    private void createStockInboundTables() {
        jdbc.execute("create table if not exists stock_inbound (inbound_id bigint primary key auto_increment,store_id bigint not null,inbound_no varchar(40) not null,inbound_type varchar(24) not null,source_id bigint null,remark varchar(500),status varchar(16) not null default 'COMPLETED',total_quantity decimal(12,3) not null default 0,total_weight decimal(12,3) not null default 0,total_amount decimal(14,2) not null default 0,operator_id bigint,client_request_id varchar(64),create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,unique key uk_stock_inbound_store_no(store_id,inbound_no),unique key uk_stock_inbound_client(store_id,client_request_id),key idx_stock_inbound_store_time(store_id,create_time),key idx_stock_inbound_status(store_id,status))");
        jdbc.execute("create table if not exists stock_inbound_item (inbound_item_id bigint primary key auto_increment,inbound_id bigint not null,store_id bigint not null,goods_id bigint null,barcode varchar(64) not null,name varchar(100) not null,category_id bigint null,gold_weight decimal(12,3) null,label_price decimal(14,2) not null default 0,certificate_no varchar(64) null,quantity decimal(12,3) not null default 1,images json null,piece_nos json null,create_time datetime not null default current_timestamp,key idx_stock_inbound_item_inbound(store_id,inbound_id),key idx_stock_inbound_item_goods(store_id,goods_id))");
    }

    private void migrateGoodsPieceIdentity() {
        jdbc.execute("create table if not exists goods_piece (piece_id bigint primary key auto_increment,store_id bigint not null,goods_id bigint not null,piece_no varchar(80) not null,image text,status tinyint not null default 1,inbound_id bigint,sales_order_id bigint,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,key idx_piece_goods(store_id,goods_id,status),key idx_piece_inbound(inbound_id))");
        jdbc.execute("alter table goods_piece modify column piece_no varchar(80) not null");
        java.util.List<java.util.Map<String,Object>> duplicates = jdbc.queryForList(
                "select store_id,piece_no from goods_piece group by store_id,piece_no having count(*)>1");
        for (java.util.Map<String,Object> duplicate : duplicates) {
            java.util.List<Long> ids = jdbc.queryForList(
                    "select piece_id from goods_piece where store_id=? and piece_no=? order by piece_id",
                    Long.class, duplicate.get("store_id"), duplicate.get("piece_no"));
            String original = String.valueOf(duplicate.get("piece_no"));
            for (int i = 1; i < ids.size(); i++) {
                String suffix = "-P" + ids.get(i);
                String prefix = original.substring(0, Math.min(original.length(), 80 - suffix.length()));
                jdbc.update("update goods_piece set piece_no=? where piece_id=?", prefix + suffix, ids.get(i));
            }
        }
        addIndex("goods_piece", "uk_goods_piece_store_no", "store_id,piece_no", true);
    }

    private void createStockSupplierTable() {
        jdbc.execute("create table if not exists stock_supplier (supplier_id bigint primary key auto_increment,store_id bigint not null,supplier_name varchar(100) not null,supplier_code varchar(50) not null,status tinyint not null default 1,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,unique key uk_stock_supplier_store_code(store_id,supplier_code),key idx_stock_supplier_store_status(store_id,status,supplier_name))");
        jdbc.update("insert ignore into stock_supplier(store_id,supplier_name,supplier_code,status) select store_id,'默认供应商','DEFAULT_SUPPLIER',1 from sys_store");
    }

    private void seedStockInboundPermissions() {
        jdbc.update("update sys_role set permissions='[\"*\"]' where role_code='ADMIN' and (permissions is null or trim(cast(permissions as char))='')");
        jdbc.update("update sys_role set permissions=case "
                + "when permissions is null or json_valid(permissions)=0 then json_array('stock:inbound:create') "
                + "when json_contains(permissions,json_quote('stock:inbound:create'))=0 then json_array_append(permissions,'$','stock:inbound:create') "
                + "else permissions end where role_code in ('MANAGER','CASHIER')");
        jdbc.update("update sys_role set permissions='[]' where role_code='SALES' and permissions is null");
    }

    /** Imports the legacy sys_config JSON list once; old_material_type is authoritative afterwards. */
    private void migrateOldMaterialTypes() {
        Integer done = jdbc.queryForObject("select count(*) from sys_config where store_id=1 and config_key='old_material_types_migrated'", Integer.class);
        if (done != null && done > 0) return;
        String raw = null;
        try {
            raw = jdbc.queryForObject("select config_value from sys_config where store_id=1 and config_key='old_material_types'", String.class);
        } catch (Exception ignored) { }
        if (raw != null && !raw.isBlank()) {
            try {
                com.fasterxml.jackson.databind.JsonNode nodes = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
                if (nodes.isArray()) {
                    int sort = 1;
                    for (com.fasterxml.jackson.databind.JsonNode node : nodes) {
                        String name = node.isTextual() ? node.asText() : node.path("name").asText("");
                        int status = node.path("status").isMissingNode() ? 1 : (node.path("status").asInt(1) == 0 ? 0 : 1);
                        if (name.isBlank()) continue;
                        jdbc.update("insert ignore into old_material_type(store_id,name,sort,status) values(1,?,?,?)", name.trim(), sort++, status);
                    }
                }
            } catch (Exception ignored) { }
        }
        jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) "
                + "values(1,'SYSTEM','old_material_types_migrated','1','旧料类型已迁移到独立表 old_material_type（一次性标记）',98,1) "
                + "on duplicate key update config_value=values(config_value)");
    }

    private void createProcessingTables() {
        jdbc.execute("create table if not exists processing_category (category_id bigint primary key auto_increment,store_id bigint not null,name varchar(64) not null,category_code varchar(32) not null,sort int not null default 0,status tinyint not null default 1,created_by bigint,updated_by bigint,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,unique key uk_processing_category_store_code(store_id,category_code),key idx_processing_category_store_status(store_id,status,sort))");
        jdbc.execute("create table if not exists processing_item (item_id bigint primary key auto_increment,store_id bigint not null,category_id bigint not null,name varchar(100) not null,item_code varchar(32) not null,labor_fee decimal(12,2) not null default 0,processing_days smallint not null default 0,commission_rate decimal(5,2) not null default 0,status tinyint not null default 1,remark varchar(500),created_by bigint,updated_by bigint,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,unique key uk_processing_item_store_code(store_id,item_code),key idx_processing_item_category(store_id,category_id,status))");
        jdbc.execute("create table if not exists processing_order (processing_order_id bigint primary key auto_increment,store_id bigint not null,order_no varchar(32) not null,member_id bigint,customer_name varchar(64) not null,customer_phone varchar(32) not null,processing_item_id bigint not null,item_name_snapshot varchar(100) not null,unit_labor_fee decimal(12,2) not null default 0,commission_rate_snapshot decimal(5,2) not null default 0,quantity int not null default 1,labor_fee decimal(12,2) not null default 0,old_gold_weight decimal(10,3),old_gold_fineness decimal(6,4),residual_material_type varchar(50),residual_gold_weight decimal(10,3),residual_gold_fineness decimal(6,4),residual_gold_handling varchar(24) not null default 'TAKE_AWAY',residual_gold_deduction decimal(12,2) not null default 0,residual_material_recorded tinyint not null default 0,due_amount decimal(12,2) not null default 0,paid_amount decimal(12,2) not null default 0,pickup_date date,craftsman_id bigint,status varchar(24) not null default 'PENDING',remark varchar(500),source_sales_order_id bigint,created_by bigint,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,completed_time datetime null,picked_up_time datetime null,version int not null default 0,unique key uk_processing_order_store_no(store_id,order_no),key idx_processing_order_status(store_id,status,create_time),key idx_processing_order_craftsman(store_id,craftsman_id,status))");
        jdbc.execute("create table if not exists processing_payment (payment_id bigint primary key auto_increment,store_id bigint not null,processing_order_id bigint not null,payment_type varchar(16) not null,amount decimal(12,2) not null,pay_method varchar(50) not null,client_request_id varchar(64),operator_id bigint,remark varchar(500),create_time datetime not null default current_timestamp,unique key uk_processing_payment_client(store_id,client_request_id),key idx_processing_payment_order(store_id,processing_order_id,create_time))");
        jdbc.execute("create table if not exists processing_commission (commission_id bigint primary key auto_increment,store_id bigint not null,processing_order_id bigint not null,employee_id bigint not null,commission_base decimal(12,2) not null default 0,commission_rate decimal(5,2) not null default 0,commission_amount decimal(12,2) not null default 0,status varchar(16) not null default 'PENDING',paid_at datetime null,paid_by bigint null,create_time datetime not null default current_timestamp,update_time datetime not null default current_timestamp on update current_timestamp,unique key uk_processing_commission_order_employee(processing_order_id,employee_id),key idx_processing_commission_status(store_id,status,employee_id))");
        jdbc.execute("create table if not exists print_job (job_id bigint primary key auto_increment,store_id bigint not null,order_id bigint not null,order_no varchar(32) not null,customer_name varchar(64),customer_phone varchar(32),job_type varchar(20) not null default 'PROCESSING',status varchar(16) not null default 'PENDING',created_by bigint,create_time datetime not null default current_timestamp,printed_time datetime null,printed_by bigint null,ignored_time datetime null,ignored_by bigint null,key idx_print_store_status(store_id,status,create_time))");
    }

    private void seedProcessingData() {
        jdbc.update("insert ignore into processing_category(store_id,name,category_code,sort,status,created_by,updated_by) values(1,'首饰加工','JEWELRY',1,1,1,1),(1,'摆件加工','ORNAMENT',2,1,1,1),(1,'维修保养','REPAIR',3,1,1,1),(1,'定制加工','CUSTOM',4,1,1,1)");
        seedProcessingItem("JEWELRY", "手镯加工", "BRACELET_PROCESS", 200, 3, 10);
        seedProcessingItem("JEWELRY", "项链加工", "NECKLACE_PROCESS", 150, 2, 10);
        seedProcessingItem("JEWELRY", "戒指加工", "RING_PROCESS", 100, 2, 10);
        seedProcessingItem("REPAIR", "焊接", "WELDING", 50, 1, 15);
        seedProcessingItem("REPAIR", "改圈", "RING_RESIZE", 30, 1, 15);
        seedProcessingItem("REPAIR", "抛光", "POLISH", 20, 1, 15);
    }

    private void seedProcessingItem(String categoryCode, String name, String code, int fee, int days, int rate) {
        jdbc.update("insert ignore into processing_item(store_id,category_id,name,item_code,labor_fee,processing_days,commission_rate,status,created_by,updated_by) select 1,category_id,?,?,?,?,?,1,1,1 from processing_category where store_id=1 and category_code=?", name, code, fee, days, rate, categoryCode);
    }

    /** Backfills legacy orders once. New orders always write this immutable line-cost snapshot. */
    private void migrateOrderCostSnapshots() {
        Integer migrated = jdbc.queryForObject("select count(*) from sys_config where store_id=1 and config_key='cost_price_unit_v1'", Integer.class);
        Integer repaired = jdbc.queryForObject("select count(*) from sys_config where store_id=1 and config_key='cost_price_unit_v2'", Integer.class);
        if (migrated != null && migrated > 0 && repaired != null && repaired > 0) return;

        if (migrated == null || migrated == 0) {
            // Legacy rows stored a finished item's total cost in goods.cost_price.
            // Normalize the product master before deriving any historical snapshots.
            jdbc.update("update goods set cost_price=round(cost_price/nullif(weight,0),2) "
                    + "where store_id=1 and price_type=1 and weight>0 and cost_price>0");
            jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) "
                    + "values(1,'SYSTEM','cost_price_unit_v1','1','按克商品成本价已统一为每克成本（一次性迁移标记）',99,1)");
        }

        if (repaired == null || repaired == 0) {
            // v1 populated snapshots using the pre-normalization semantics. Rebuild
            // only rows that existed before v1, preserving immutable snapshots on
            // orders created after the new order endpoint was deployed.
            Timestamp cutoff = jdbc.queryForObject("select create_time from sys_config where store_id=1 and config_key='cost_price_unit_v1'", Timestamp.class);
            if (cutoff != null) {
                jdbc.update("update sales_order_item i "
                        + "left join goods g on g.goods_id=i.goods_id and g.store_id=i.store_id "
                        + "set i.cost_snapshot=round(least(" 
                        + "case when coalesce(g.price_type,2)=1 "
                        + "then coalesce(g.cost_price,0)*coalesce(i.weight,g.weight,0)*coalesce(i.qty,1) "
                        + "else coalesce(g.cost_price,0)*coalesce(i.qty,1) end, "
                        + "greatest(coalesce(i.subtotal,0),0)),2) "
                        + "where i.create_time<=?", cutoff);
            }
            jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) "
                    + "values(1,'SYSTEM','cost_price_unit_v2','1','历史订单成本快照已按统一单位重算；异常行成本不超过成交额',100,1) "
                    + "on duplicate key update config_value=values(config_value),description=values(description),enabled=1");
        }
    }

    /** Upgrades the legacy flat category list without changing goods or stock quantities. */
    private void migrateGoodsCategories() {
        jdbc.update("update goods_category set level=case when coalesce(parent_id,0)=0 then 1 else 2 end where level is null or level not in (1,2)");
        jdbc.update("update goods_category set category_code=concat('CAT_',category_id) where category_code is null or trim(category_code)=''");
        jdbc.update("update goods_category set category_code=case name when '成品黄金' then 'FINISHED_GOLD' when 'K金' then 'K_GOLD' when '银饰' then 'SILVER' when '加工' then 'PROCESSING' else category_code end where parent_id=0");
        seedRoot("成品黄金", "FINISHED_GOLD", 1);
        seedRoot("K金", "K_GOLD", 2);
        seedRoot("银饰", "SILVER", 3);
        seedRoot("加工", "PROCESSING", 4);
        addCategoryIndex();
        String[][] children = {
                {"FINISHED_GOLD", "手镯", "FINISHED_GOLD_BRACELET", "1"}, {"FINISHED_GOLD", "项链", "FINISHED_GOLD_NECKLACE", "2"},
                {"FINISHED_GOLD", "耳钉", "FINISHED_GOLD_EARRINGS", "3"}, {"FINISHED_GOLD", "戒指", "FINISHED_GOLD_RING", "4"},
                {"FINISHED_GOLD", "吊坠", "FINISHED_GOLD_PENDANT", "5"}, {"FINISHED_GOLD", "手链", "FINISHED_GOLD_BRACELET_CHAIN", "6"},
                {"K_GOLD", "K金项链", "K_GOLD_NECKLACE", "1"}, {"K_GOLD", "K金戒指", "K_GOLD_RING", "2"}, {"K_GOLD", "K金耳钉", "K_GOLD_EARRINGS", "3"},
                {"SILVER", "银手镯", "SILVER_BRACELET", "1"}, {"SILVER", "银项链", "SILVER_NECKLACE", "2"}, {"SILVER", "银戒指", "SILVER_RING", "3"},
                {"PROCESSING", "来料加工", "PROCESSING_MATERIAL", "1"}, {"PROCESSING", "定制加工", "PROCESSING_CUSTOM", "2"}
        };
        for (String[] child : children) seedChild(child[0], child[1], child[2], Integer.parseInt(child[3]));
        jdbc.update("insert into goods_category(store_id,name,category_code,parent_id,level,sort,status) "
                + "select p.store_id,'其他',concat('OTHER_',p.category_id),p.category_id,2,999,1 from goods_category p "
                + "where p.store_id=1 and p.level=1 and not exists (select 1 from goods_category c where c.store_id=p.store_id and c.parent_id=p.category_id and c.category_code=concat('OTHER_',p.category_id))");
        jdbc.update("update goods g join goods_category c on c.category_id=g.category_id and c.store_id=g.store_id "
                + "join goods_category other on other.store_id=c.store_id and other.parent_id=c.category_id and other.category_code=concat('OTHER_',c.category_id) "
                + "set g.category_id=other.category_id where c.level=1");
        jdbc.update("alter table goods_category modify column category_code varchar(50) not null, modify column level tinyint not null default 1");
    }

    private void seedRoot(String name, String code, int sort) {
        Integer existing = jdbc.queryForObject("select count(*) from goods_category where store_id=1 and (category_code=? or name=?)", Integer.class, code, name);
        if (existing != null && existing > 0) {
            jdbc.update("update goods_category set name=?,category_code=?,parent_id=0,level=1,sort=?,status=1 where store_id=1 and (category_code=? or name=?)", name, code, sort, code, name);
        } else {
            jdbc.update("insert into goods_category(store_id,name,category_code,parent_id,level,sort,status) values(?,?,?,0,1,?,1)", 1L, name, code, sort);
        }
    }

    private void seedChild(String parentCode, String name, String code, int sort) {
        jdbc.update("insert into goods_category(store_id,name,category_code,parent_id,level,sort,status) "
                + "select 1,?,?,p.category_id,2,?,1 from goods_category p where p.store_id=1 and p.category_code=? and not exists "
                + "(select 1 from goods_category x where x.store_id=1 and x.category_code=?)", name, code, sort, parentCode, code);
    }

    private void addCategoryIndex() {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name='goods_category' and index_name='uk_goods_category_store_code'", Integer.class);
        if (count != null && count == 0) jdbc.execute("alter table goods_category add unique key uk_goods_category_store_code(store_id,category_code)");
    }

    private void addColumn(String table, String column, String definition) {
        Integer tableExists = jdbc.queryForObject("select count(*) from information_schema.tables where table_schema=database() and table_name=?", Integer.class, table);
        if (tableExists == null || tableExists == 0) return;
        Integer count = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name=? and column_name=?", Integer.class, table, column);
        if (count != null && count == 0) jdbc.execute("alter table " + table + " add column " + column + " " + definition);
    }

    private void addIndex(String table, String index, String fields) { addIndex(table, index, fields, false); }

    private void normalizeGoodsStock() {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.columns where table_schema=database() and table_name='goods' and column_name='stock' and data_type<>'decimal'", Integer.class);
        if (count != null && count > 0) jdbc.execute("alter table goods modify column stock decimal(12,3) not null default 0");
    }
    private void addIndex(String table, String index, String fields, boolean unique) {
        Integer count = jdbc.queryForObject("select count(*) from information_schema.statistics where table_schema=database() and table_name=? and index_name=?", Integer.class, table, index);
        if (count != null && count == 0) jdbc.execute("alter table " + table + " add " + (unique ? "unique key " : "key ") + index + "(" + fields + ")");
    }
}
