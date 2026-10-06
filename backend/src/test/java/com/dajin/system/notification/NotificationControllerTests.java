package com.dajin.system.notification;

import com.dajin.system.common.DbSupport;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 消息列表排除已删除项；滑动删除与清空都是软删除（action=DELETED），操作日志保留。 */
class NotificationControllerTests {
    private record Fixture(NotificationController controller, DbSupport db, NamedParameterJdbcTemplate jdbc, HttpServletRequest request) { }

    private Fixture fixture() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("45");
        when(request.getAttribute("claims")).thenReturn(claims);
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenReturn(List.of());
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        return new Fixture(new NotificationController(db), db, jdbc, request);
    }

    @Test
    void listSkipsDeletedMessages() {
        Fixture f = fixture();

        f.controller.list(f.request);

        verify(f.db).list(contains("action<>'DELETED'"), any(SqlParameterSource.class));
    }

    @Test
    void removingAMessageSoftDeletesIt() {
        Fixture f = fixture();

        f.controller.remove(7L, f.request);

        verify(f.jdbc).update(contains("set action='DELETED'"), any(SqlParameterSource.class));
        verify(f.jdbc, never()).update(contains("delete from operation_log"), any(SqlParameterSource.class));
    }

    @Test
    void clearingMarksEveryOwnNotificationDeleted() {
        Fixture f = fixture();

        f.controller.clear(f.request);

        verify(f.jdbc).update(contains("user_id=:uid and action<>'DELETED'"), any(SqlParameterSource.class));
    }
}
