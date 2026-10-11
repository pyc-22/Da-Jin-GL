package com.dajin.system.shift;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShiftIdempotencyTests {
    private final DbSupport db = mock(DbSupport.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final ShiftService shifts = mock(ShiftService.class);
    private final ShiftController controller = new ShiftController(db, shifts, mock(SyncWebSocketHandler.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void missingClientRequestIdIsRejected() {
        when(db.store(request)).thenReturn(1L);
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.confirm(Map.of("cashActual", 0), request));
        assertEquals(400507, error.getCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void replayReturnsOriginalShiftResult() {
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "content", "{\"confirmed\":true,\"previousShiftNo\":\"S1\",\"nextShiftNo\":\"S2\",\"cashSystem\":10,\"cashActual\":10,\"cashDifference\":0,\"shiftEndedAt\":\"2026-10-11T14:00:00\"}"
        )));

        ApiResponse<?> response = controller.confirm(Map.of("cashActual", 10, "clientRequestId", "shift-replay"), request);
        Map<?, ?> body = (Map<?, ?>) response.data();
        assertEquals("S1", body.get("previousShiftNo"));
        assertEquals("S2", body.get("nextShiftNo"));
        assertEquals(true, body.get("idempotentReplay"));
        verifyNoInteractions(shifts);
    }
}
