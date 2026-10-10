package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingArchiveTests {
    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PROCESSING", "COMPLETED"})
    void onlyPickedUpOrdersCanBeArchived(String status) {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.store(any())).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenReturn(List.of(order(status, 9L)));

        var ex = assertThrows(BusinessException.class, () -> controller(db).archiveOnMobile(12L, Map.of(), managerRequest(1L)));
        assertEquals(409716, ex.getCode());
        verify(jdbc, never()).update(startsWith("update processing_order set mobile_archived"), anyMap());
    }

    @Test
    void salesCanArchiveTheirOwnPickedUpOrder() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.store(any())).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenReturn(List.of(order("PICKED_UP", 27L)));

        controller(db).archiveOnMobile(12L, Map.of(), salesRequest(27L));

        verify(jdbc).update(contains("set mobile_archived=1"), anyMap());
    }

    @Test
    void salesMayArchiveOnlyTheirOwnPickedUpOrder() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.store(any())).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenReturn(List.of(order("PICKED_UP", 99L)));

        var ex = assertThrows(BusinessException.class, () -> controller(db).archiveOnMobile(12L, Map.of(), salesRequest(27L)));
        assertEquals(403403, ex.getCode());
        verify(jdbc, never()).update(startsWith("update processing_order set mobile_archived"), anyMap());
    }

    @Test
    void managerArchiveKeepsTheManagementRecordAndOnlyMarksMobileArchive() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.store(any())).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenReturn(List.of(order("PICKED_UP", 99L)));

        controller(db).archiveOnMobile(12L, Map.of(), managerRequest(1L));

        var sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(sql.capture(), anyMap());
        String archiveSql = sql.getAllValues().stream().filter(value -> value.contains("set mobile_archived=1")).findFirst().orElseThrow();
        assertEquals(false, archiveSql.toLowerCase().contains("delete from processing_order"));
    }

    private ProcessingController controller(DbSupport db) {
        return new ProcessingController(db, mock(SyncWebSocketHandler.class), mock(ShiftService.class), mock(OldMaterialLedgerService.class));
    }

    private Map<String, Object> order(String status, long creator) {
        Map<String, Object> order = new HashMap<>();
        order.put("processing_order_id", 12L);
        order.put("store_id", 1L);
        order.put("order_no", "JG-12");
        order.put("status", status);
        order.put("created_by", creator);
        return order;
    }

    private MockHttpServletRequest managerRequest(long userId) {
        return request("MANAGER", userId);
    }

    private MockHttpServletRequest salesRequest(long userId) {
        return request("SALES", userId);
    }

    private MockHttpServletRequest request(String role, long userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("storeId", 1L);
        Claims claims = mock(Claims.class);
        when(claims.get("role")).thenReturn(role);
        when(claims.getSubject()).thenReturn(String.valueOf(userId));
        request.setAttribute("claims", claims);
        return request;
    }
}
