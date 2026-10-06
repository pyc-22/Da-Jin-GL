package com.dajin.system.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ReportingPermissionMigrationTests {
    @Test void initializesOnlyUnmarkedStoresThenPreservesAdministratorChanges() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("select s.store_id"), eq(Long.class), eq("manager_report_permissions_v1")))
                .thenReturn(List.of(3L)).thenReturn(List.of());
        when(jdbc.queryForList(contains("select permission_code"), eq(String.class), eq(3L)))
                .thenReturn(List.of("report:view", "report:daily", "report:commission", "report:processing", "report:recycle"));
        var migration = new ReportingPermissionMigration(jdbc);
        migration.run();
        for (String code : List.of("report:daily", "report:commission", "report:processing", "report:recycle")) {
            verify(jdbc).update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'MANAGER',?)", 3L, code);
        }
        verify(jdbc, never()).update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'MANAGER',?)", 3L, "report:monthly");
        verify(jdbc, never()).update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'MANAGER',?)", 3L, "report:store-performance");
        verify(jdbc, times(2)).update(contains("permission_code in ('*','report:store-performance','report:monthly')"), eq(3L));
        verify(jdbc).update(contains("insert into sys_config"), eq(3L), eq("manager_report_permissions_v1"));
        clearInvocations(jdbc);
        migration.run();
        verify(jdbc).queryForList(contains("not exists"), eq(Long.class), eq("manager_report_permissions_v1"));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void legacyWildcardExpansionExcludesInitiallyDisabledReports() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        assertFalse(PermissionCatalog.defaults("MANAGER").contains("*"));
        assertTrue(PermissionCatalog.defaults("ADMIN").contains("*"));
        // Historical recycle ownership never derives from the payment operator.
        new SchemaCompatibilityMigration(jdbc).migrateRecycleCreators();
        verify(jdbc).update(argThat((String sql) -> sql.contains("ro.created_by is null")
                && sql.contains("a.store_id=ro.store_id") && sql.contains("l.store_id=ro.store_id")
                && sql.contains("a.creator=l.creator") && sql.contains("having count(distinct")
                && !sql.contains("finance_record")));
    }

    @Test void salesOrderCreateMigrationWritesBothPermissionRelations() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("select s.store_id"), eq(Long.class), eq("sales_order_create_v3")))
                .thenReturn(List.of(3L));
        var migration = new SchemaCompatibilityMigration(jdbc);
        var method = SchemaCompatibilityMigration.class.getDeclaredMethod("upgradeSalesOrderCreatePermission");
        method.setAccessible(true);
        method.invoke(migration);
        verify(jdbc).update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'SALES','order:create')", 3L);
        verify(jdbc).update(contains("insert ignore into sys_user_permission"), eq(3L));
        verify(jdbc).update(contains("insert into sys_config"), eq(3L), eq("sales_order_create_v3"));
    }

    @Test void salesPermissionReconciliationRebuildsBothRelationsAndLeavesMarker() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("select s.store_id"), eq(Long.class), eq("sales_permissions_reconcile_v1")))
                .thenReturn(List.of(3L));
        var migration = new SchemaCompatibilityMigration(jdbc);
        var method = SchemaCompatibilityMigration.class.getDeclaredMethod("reconcileSalesPermissions");
        method.setAccessible(true);
        method.invoke(migration);

        verify(jdbc).update("delete from sys_role_permission where store_id=? and role_code='SALES'", 3L);
        verify(jdbc).update(contains("delete p from sys_user_permission"), eq(3L));
        verify(jdbc).update("insert ignore into sys_role_permission(store_id,role_code,permission_code) values(?,'SALES',?)", 3L, "order:create");
        verify(jdbc).update(contains("insert ignore into sys_user_permission"), eq("order:create"), eq(3L));
        verify(jdbc).update(contains("update sys_role set permissions=?"), anyString(), eq(3L));
        verify(jdbc).update(contains("insert into sys_config"), eq(3L), eq("sales_permissions_reconcile_v1"));
    }
}
