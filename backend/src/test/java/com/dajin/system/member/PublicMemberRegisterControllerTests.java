package com.dajin.system.member;

import com.dajin.system.common.DbSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PublicMemberRegisterControllerTests {
    @Test void rejectsSalespersonFromAnotherStore() {
        DbSupport db = mock(DbSupport.class); NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc); when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        var controller = new PublicMemberRegisterController(db);
        var error = assertThrows(com.dajin.system.common.BusinessException.class, () -> controller.register(new PublicMemberRegisterController.Req("会员", "13800138000", "", null, 7L, 2L)));
        assertEquals("销售人员与门店不匹配", error.getMessage());
    }

    @Test void rejectsDuplicatePhoneInStore() {
        DbSupport db = mock(DbSupport.class); NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc); when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1, 1);
        var controller = new PublicMemberRegisterController(db);
        var error = assertThrows(com.dajin.system.common.BusinessException.class, () -> controller.register(new PublicMemberRegisterController.Req("会员", "13800138000", "", null, 7L, 2L)));
        assertEquals("手机号已登记", error.getMessage());
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test void insertsRegistrationWithQrOwnership() {
        DbSupport db = mock(DbSupport.class); NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc); when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Integer.class))).thenReturn(1, 0);
        var controller = new PublicMemberRegisterController(db);
        controller.register(new PublicMemberRegisterController.Req("会员", "13800138000", "女", "1990-01-01", 7L, 2L));
        verify(jdbc).update(anyString(), any(MapSqlParameterSource.class));
    }
}
