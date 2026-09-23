package com.dajin.system.shift;

import com.dajin.system.common.DbSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Component
public class ShiftService {
    private static final String KEY = "current_shift_no";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private final DbSupport db;

    public ShiftService(DbSupport db) { this.db = db; }

    public String current(long storeId) {
        List<Map<String, Object>> rows = db.list("select config_value from sys_config where store_id=:s and config_key=:k limit 1", Map.of("s", storeId, "k", KEY));
        if (!rows.isEmpty() && rows.get(0).get("config_value") != null && !String.valueOf(rows.get(0).get("config_value")).isBlank()) return String.valueOf(rows.get(0).get("config_value"));
        String opened = next();
        db.jdbc().update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) values(:s,'SYSTEM',:k,:v,'当前收银班次',99,1) on duplicate key update config_value=values(config_value),update_time=now()", new MapSqlParameterSource().addValue("s", storeId).addValue("k", KEY).addValue("v", opened));
        return opened;
    }

    public String rotate(long storeId) {
        String opened = next();
        db.jdbc().update("insert into sys_config(store_id,config_group,config_key,config_value,description,config_sort,enabled) values(:s,'SYSTEM',:k,:v,'当前收银班次',99,1) on duplicate key update config_value=values(config_value),update_time=now()", new MapSqlParameterSource().addValue("s", storeId).addValue("k", KEY).addValue("v", opened));
        return opened;
    }

    private String next() { return "SHIFT-" + LocalDateTime.now().format(FORMAT); }
}
