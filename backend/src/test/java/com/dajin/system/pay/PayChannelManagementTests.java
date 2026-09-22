package com.dajin.system.pay;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PayChannelManagementTests {
    @Test
    void partialStatusUpdatePreservesExistingChannelFieldsAndBroadcasts() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        SyncWebSocketHandler ws = mock(SyncWebSocketHandler.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(7L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(contains("from pay_channel"), anyMap())).thenReturn(List.of(Map.of(
                "channel_id", 4L, "channel_name", "现金", "channel_code", "CASH",
                "sort", 1, "status", 1, "icon", "cash")));
        when(jdbc.queryForObject(contains("count(*)"), anyMap(), eq(Integer.class))).thenReturn(0);
        PayController controller = controller(db, ws);

        controller.updateChannel(4L, Map.of("status", 0), request);

        var parameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("update pay_channel"), parameters.capture());
        assertEquals("现金", parameters.getValue().getValue("n"));
        assertEquals("CASH", parameters.getValue().getValue("c"));
        assertEquals("cash", parameters.getValue().getValue("icon"));
        assertEquals(0, parameters.getValue().getValue("st"));
        verify(ws).broadcast(eq("PAY_CHANNELS_UPDATED"), argThat(event -> event instanceof Map<?, ?> map && "DISABLE".equals(map.get("action"))));
    }

    @Test
    void duplicateChannelCodeIsRejectedBeforeInsert() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(contains("count(*)"), anyMap(), eq(Integer.class))).thenReturn(1);
        PayController controller = controller(db, mock(SyncWebSocketHandler.class));

        BusinessException error = assertThrows(BusinessException.class, () -> controller.createChannel(Map.of(
                "channelName", "云闪付", "channelCode", "UNIONPAY", "sort", 2, "status", 1), request));

        assertEquals(409305, error.getCode());
        verify(jdbc, never()).update(contains("insert into pay_channel"), any(MapSqlParameterSource.class));
    }

    @Test
    void missingChannelCannotBeUpdatedOrDeleted() {
        DbSupport db = mock(DbSupport.class);
        when(db.store(any(HttpServletRequest.class))).thenReturn(1L);
        when(db.jdbc()).thenReturn(mock(NamedParameterJdbcTemplate.class));
        when(db.list(contains("from pay_channel"), anyMap())).thenReturn(List.of());
        PayController controller = controller(db, mock(SyncWebSocketHandler.class));
        HttpServletRequest request = mock(HttpServletRequest.class);

        assertEquals(404305, assertThrows(BusinessException.class,
                () -> controller.updateChannel(99L, Map.of("status", 0), request)).getCode());
        assertEquals(404305, assertThrows(BusinessException.class,
                () -> controller.deleteChannel(99L, request)).getCode());
    }

    @Test
    void activeCustomChannelIsAcceptedAndDisabledChannelIsRejected() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(contains("from pay_channel"), anyMap(), eq(Integer.class))).thenReturn(1, 0);

        assertEquals("UNIONPAY", PaymentChannelPolicy.requireActiveCollection(db, 1L, "unionpay"));
        assertEquals(400310, assertThrows(BusinessException.class,
                () -> PaymentChannelPolicy.requireActiveCollection(db, 1L, "CASH")).getCode());
    }

    private PayController controller(DbSupport db, SyncWebSocketHandler ws) {
        return new PayController(db, ws, mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());
    }
}
