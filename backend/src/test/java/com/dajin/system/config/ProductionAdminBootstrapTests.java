package com.dajin.system.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

class ProductionAdminBootstrapTests {
    @Test
    void leavesExistingInstallationUnchanged() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select count(*) from sys_user", Integer.class)).thenReturn(1);

        new ProductionAdminBootstrap(jdbc, new MockEnvironment()).run();

        verify(jdbc, never()).update(contains("insert into sys_user"), any(), any(), any(), any());
    }

    @Test
    void rejectsUnconfiguredEmptyInstallation() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select count(*) from sys_user", Integer.class)).thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> new ProductionAdminBootstrap(jdbc, new MockEnvironment()).run());
    }

    @Test
    void createsHashedAdministratorFromEnvironment() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select count(*) from sys_user", Integer.class)).thenReturn(0);
        when(jdbc.queryForObject(contains("role_code='ADMIN'"), eq(Long.class))).thenReturn(1L);
        MockEnvironment environment = new MockEnvironment()
                .withProperty("BOOTSTRAP_ADMIN_USERNAME", "storeadmin")
                .withProperty("BOOTSTRAP_ADMIN_PASSWORD", "Release2026Secure")
                .withProperty("BOOTSTRAP_ADMIN_NAME", "门店管理员");

        new ProductionAdminBootstrap(jdbc, environment).run();

        verify(jdbc).update(contains("insert into sys_user"),
                eq("storeadmin"), argThat(value -> new BCryptPasswordEncoder().matches("Release2026Secure", String.valueOf(value))),
                eq("门店管理员"), eq(1L));
    }
}
