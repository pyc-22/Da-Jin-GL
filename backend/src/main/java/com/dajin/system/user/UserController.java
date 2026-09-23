package com.dajin.system.user;

import com.dajin.system.common.*;
import com.dajin.system.config.RequireRoles;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/api/user")
@RequireRoles({"ADMIN", "MANAGER"})
public class UserController {
    private final DbSupport db;
    public UserController(DbSupport db) { this.db = db; }
    @GetMapping("/list")
    public ApiResponse<?> list(@RequestParam(required = false) Integer status, HttpServletRequest req) {
        return ApiResponse.ok(db.list("select user_id,username,real_name,role_id,phone,entry_date,status from sys_user where store_id=:s and (:st is null or status=:st) order by user_id desc", new MapSqlParameterSource().addValue("s", db.store(req)).addValue("st", status)));
    }
}
