package com.dajin.system.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first production administrator from runtime secrets on an empty installation. */
@Component
@Order(20)
public class ProductionAdminBootstrap implements CommandLineRunner {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public ProductionAdminBootstrap(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @Override
    public void run(String... args) {
        Integer users = jdbc.queryForObject("select count(*) from sys_user", Integer.class);
        if (users != null && users > 0) return;

        String username = value("BOOTSTRAP_ADMIN_USERNAME");
        String password = value("BOOTSTRAP_ADMIN_PASSWORD");
        String realName = value("BOOTSTRAP_ADMIN_NAME");
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("Empty database requires BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD");
        }
        if (username.startsWith("CHANGE_ME") || password.startsWith("CHANGE_ME")) {
            throw new IllegalStateException("Replace the BOOTSTRAP_ADMIN_* placeholders before first startup");
        }
        if (!username.matches("[A-Za-z0-9]{4,20}")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_USERNAME must be 4-20 letters or digits");
        }
        if (password.length() < 10 || password.length() > 72 || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be 10-72 characters and contain letters and digits");
        }

        Long roleId = jdbc.queryForObject(
                "select role_id from sys_role where store_id=1 and role_code='ADMIN' and status=1 limit 1",
                Long.class);
        if (roleId == null) throw new IllegalStateException("ADMIN role is missing from the production schema");
        jdbc.update("insert into sys_user(store_id,username,password,real_name,role_id,permission_initialized,status) values(1,?,?,?,?,0,1)",
                username, encoder.encode(password), realName.isBlank() ? "系统管理员" : realName, roleId);
    }

    private String value(String name) {
        return environment.getProperty(name, "").trim();
    }
}
