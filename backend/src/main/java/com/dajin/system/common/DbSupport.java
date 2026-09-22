package com.dajin.system.common;

import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Component;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@Component
public class DbSupport {
    private final NamedParameterJdbcTemplate jdbc;
    public DbSupport(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public NamedParameterJdbcTemplate jdbc() { return jdbc; }
    public long store(HttpServletRequest req) { Object v=req.getAttribute("storeId"); return v==null?1L:((Number)v).longValue(); }
    public Map<String,Object> one(String sql, Map<String,?> p) { return jdbc.queryForMap(sql,p); }
    public List<Map<String,Object>> list(String sql, Map<String,?> p) { return jdbc.queryForList(sql,p); }
    public Map<String,Object> one(String sql, SqlParameterSource p) { return jdbc.queryForMap(sql,p); }
    public List<Map<String,Object>> list(String sql, SqlParameterSource p) { return jdbc.queryForList(sql,p); }
}
