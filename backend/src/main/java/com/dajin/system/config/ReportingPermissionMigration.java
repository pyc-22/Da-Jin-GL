package com.dajin.system.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** Applies the confirmed defaults once per store, never re-enabling later administrator edits. */
@Component
@Order(11)
public class ReportingPermissionMigration implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    public ReportingPermissionMigration(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override @Transactional
    public void run(String... args) throws JsonProcessingException {
        String marker = "manager_report_permissions_v1";
        List<Long> stores = jdbc.queryForList("select s.store_id from sys_store s where not exists (select 1 from sys_config c where c.store_id=s.store_id and c.config_group='MIGRATION' and c.config_key=?)", Long.class, marker);
        Set<String> disabled = Set.of("report:store-performance", "report:monthly");
        Set<String> enabled = Set.of("report:view", "report:commission", "report:daily", "report:processing", "report:recycle");
        for (long store : stores) {
            for (String code : PermissionCatalog.codes()) {
                if (disabled.contains(code)) continue;
                jdbc.update("insert ignore into sys_role_permission(store_id,role_code,permission_code) select store_id,role_code,? from sys_role where store_id=? and role_code='MANAGER' and exists (select 1 from sys_role_permission p where p.store_id=? and p.role_code='MANAGER' and p.permission_code='*')", code, store, store);
                jdbc.update("insert ignore into sys_user_permission(store_id,user_id,permission_code) select u.store_id,u.user_id,? from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.store_id=? and r.role_code='MANAGER' and exists (select 1 from sys_user_permission p where p.store_id=u.store_id and p.user_id=u.user_id and p.permission_code='*')", code, store);
            }
            jdbc.update("delete from sys_role_permission where store_id=? and role_code='MANAGER' and permission_code in ('*','report:store-performance','report:monthly')", store);
            jdbc.update("delete p from sys_user_permission p join sys_user u on u.user_id=p.user_id and u.store_id=p.store_id join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where p.store_id=? and r.role_code='MANAGER' and p.permission_code in ('*','report:store-performance','report:monthly')", store);
            for (String code : enabled) {
                jdbc.update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'MANAGER',?)", store, code);
                jdbc.update("insert ignore into sys_user_permission(store_id,user_id,permission_code) select u.store_id,u.user_id,? from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.store_id=? and r.role_code='MANAGER'", code, store);
            }
            List<String> permissions = jdbc.queryForList("select permission_code from sys_role_permission where store_id=? and role_code='MANAGER' order by permission_code", String.class, store);
            jdbc.update("update sys_role set permissions=?,permission_initialized=1 where store_id=? and role_code='MANAGER'", new ObjectMapper().writeValueAsString(permissions), store);
            jdbc.update("insert into sys_config(store_id,config_group,config_key,config_value,description,enabled) values(?,'MIGRATION',?,'1','店长独立报表权限初始化',1)", store, marker);
        }
    }
}
