package com.dajin.system.notification;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.RequirePermission;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

/** Notification read state is kept in operation_log so it survives app refreshes without a new table. */
@RestController
@RequestMapping("/api/notification")
@RequirePermission("notification:view")
public class NotificationController {
    private final DbSupport db;
    public NotificationController(DbSupport db) { this.db = db; }

    @GetMapping
    public ApiResponse<?> list(HttpServletRequest req) {
        long uid = userId(req);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(req)).addValue("uid", uid);
        return ApiResponse.ok(db.list("select log_id notification_id,action,content,create_time from operation_log where store_id=:s and module='NOTIFICATION' and user_id=:uid order by log_id desc limit 100", p));
    }

    @PostMapping("/{id}/read")
    public ApiResponse<?> read(@PathVariable long id, HttpServletRequest req) {
        long uid = userId(req);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(req)).addValue("uid", uid).addValue("id", id);
        int changed = db.jdbc().update("update operation_log set action='READ' where log_id=:id and store_id=:s and user_id=:uid and module='NOTIFICATION'", p);
        return ApiResponse.ok(Map.of("read", changed > 0));
    }

    private long userId(HttpServletRequest req) {
        io.jsonwebtoken.Claims claims = (io.jsonwebtoken.Claims) req.getAttribute("claims");
        return claims == null ? 0 : Long.parseLong(claims.getSubject());
    }
}
