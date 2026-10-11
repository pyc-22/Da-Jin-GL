package com.dajin.system.recycle;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecycleIdempotencyTests {
    private final DbSupport db = mock(DbSupport.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final RecycleController controller = new RecycleController(db, mock(SyncWebSocketHandler.class),
            mock(ShiftService.class), mock(OldMaterialLedgerService.class));
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    @Test
    void missingClientRequestIdIsRejected() {
        when(db.store(request)).thenReturn(1L);
        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.create(Map.of("weight", 1, "purity", 0.999), request));
        assertEquals(400407, error.getCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void replayReturnsStoredRecycleResult() {
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "content", "{\"recycleOrderId\":12,\"billNo\":\"HS-12\",\"amount\":99.50,\"status\":1,\"approvalRequired\":false}"
        )));

        ApiResponse<?> response = controller.create(Map.of("clientRequestId", "recycle-replay"), request);
        Map<?, ?> body = (Map<?, ?>) response.data();
        assertEquals(12, body.get("recycleOrderId"));
        assertEquals("HS-12", body.get("billNo"));
        assertEquals(true, body.get("idempotentReplay"));
        verify(jdbc, times(1)).queryForList(anyString(), anyMap());
    }
}
