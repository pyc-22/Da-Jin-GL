package com.dajin.system.processing;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingPaymentTests {
    @Test
    void storedValuePaymentRejectsInsufficientMemberBalance() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.store(request)).thenReturn(1L);
        Map<String,Object> order = new HashMap<>(Map.of("processing_order_id", 7L, "order_no", "JG-7",
                "store_id", 1L, "status", "PROCESSING", "member_id", 2L, "due_amount", 100, "paid_amount", 0));
        when(db.list(contains("from processing_order"), anyMap())).thenReturn(List.of(order));
        when(db.list(contains("from processing_order"), any(SqlParameterSource.class))).thenReturn(List.of(order));
        when(db.one(anyString(), anyMap())).thenReturn(order);
        when(db.one(anyString(), any(SqlParameterSource.class))).thenReturn(order);
        when(jdbc.queryForObject(contains("from pay_channel"), anyMap(), eq(Integer.class))).thenReturn(1);
        ProcessingController controller = new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class));

        BusinessException error = assertThrows(BusinessException.class, () -> controller.pay(7L, Map.of(
                "clientRequestId", "balance-7", "paymentType", "DEPOSIT", "payMethod", "BALANCE", "amount", 100), request));
        assertEquals(409106, error.getCode());
        verify(jdbc, never()).update(contains("insert into processing_payment"), any(SqlParameterSource.class));
    }
}
