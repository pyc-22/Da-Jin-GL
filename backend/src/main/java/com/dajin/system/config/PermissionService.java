package com.dajin.system.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PermissionService {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public UserAccess userAccess(long userId, long storeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select u.status user_status,r.status role_status,r.role_code from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.user_id=:uid and u.store_id=:store limit 1",
                Map.of("uid", userId, "store", storeId));
        if (rows.isEmpty()) return null;
        Map<String, Object> row = rows.get(0);
        boolean active = ((Number) row.get("user_status")).intValue() == 1 && ((Number) row.get("role_status")).intValue() == 1;
        return new UserAccess(active, String.valueOf(row.get("role_code")));
    }

    public Set<String> permissions(long storeId, String roleCode) {
        List<String> rows = jdbc.queryForList(
                "select permission_code from sys_role_permission where store_id=:store and role_code=:role order by permission_code",
                Map.of("store", storeId, "role", roleCode), String.class);
        if (!rows.isEmpty()) return new LinkedHashSet<>(rows);
        return legacyPermissions(storeId, roleCode);
    }

    public Set<String> userPermissions(long userId, long storeId, String roleCode) {
        Integer initialized = jdbc.queryForObject(
                "select permission_initialized from sys_user where user_id=:uid and store_id=:store",
                Map.of("uid", userId, "store", storeId), Integer.class);
        if (initialized == null || initialized != 1) return permissions(storeId, roleCode);
        List<String> rows = jdbc.queryForList(
                "select permission_code from sys_user_permission where store_id=:store and user_id=:uid order by permission_code",
                Map.of("store", storeId, "uid", userId), String.class);
        return new LinkedHashSet<>(rows);
    }

    public boolean hasPermission(long userId, long storeId, String roleCode, String permission) {
        if (permission == null || permission.isBlank()) return true;
        Set<String> permissions = userPermissions(userId, storeId, roleCode);
        return permissions.contains("*") || permissions.contains(permission);
    }

    private Set<String> legacyPermissions(long storeId, String roleCode) {
        List<String> values = jdbc.queryForList("select cast(permissions as char) from sys_role where store_id=:store and role_code=:role and status=1", Map.of("store", storeId, "role", roleCode), String.class);
        Set<String> result = new LinkedHashSet<>();
        if (values.isEmpty() || values.get(0) == null) return result;
        try {
            JsonNode node = objectMapper.readTree(values.get(0));
            if (node.isArray()) node.forEach(item -> result.add(item.asText()));
        } catch (Exception ignored) { }
        return result;
    }

    public record UserAccess(boolean active, String roleCode) { }
}
