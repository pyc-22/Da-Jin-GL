package com.dajin.system.processing;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProcessingWithdrawalTests {
    @Test
    void withdrawnOrderRejectsPaymentAndStatusChanges() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = order("WITHDRAWN", 9);
        when(db.store(request)).thenReturn(1L);
        when(db.list(contains("from processing_order"), anyMap())).thenReturn(List.of(order));
        when(db.jdbc()).thenReturn(jdbc);
        ProcessingController controller = controller(db);

        BusinessException paymentError = assertThrows(BusinessException.class, () -> controller.pay(18L,
                Map.of("clientRequestId", "withdrawn-pay", "paymentType", "BALANCE", "payMethod", "CASH", "amount", 10), request));
        BusinessException statusError = assertThrows(BusinessException.class, () -> controller.changeStatus(18L,
                Map.of("status", "PICKED_UP"), request));

        assertEquals(409704, paymentError.getCode());
        assertEquals(409704, statusError.getCode());
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void withdrawnOrderIsHiddenFromNormalDetailAndNotificationRoutes() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(1L);
        when(db.one(contains("from processing_order"), anyMap())).thenReturn(order("WITHDRAWN", 9));
        when(db.jdbc()).thenReturn(jdbc);
        ProcessingController controller = controller(db);

        BusinessException detailError = assertThrows(BusinessException.class, () -> controller.detail(18L, request));
        BusinessException notifyError = assertThrows(BusinessException.class, () -> controller.notifyPickup(18L, request));

        assertEquals(409704, detailError.getCode());
        assertEquals(409704, notifyError.getCode());
        verify(jdbc, never()).update(anyString(), anyMap());
    }

    @Test
    void retryWithOldVersionReturnsIdempotentSuccessForWithdrawnOrder() {
        DbSupport db = mock(DbSupport.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = order("WITHDRAWN", 9);
        Map<String, Object> linked = new HashMap<>();
        linked.put("source_sales_order_id", null);
        when(db.store(request)).thenReturn(1L);
        when(db.list(contains("select source_sales_order_id"), anyMap())).thenReturn(List.of(linked));
        when(db.list(contains("for update"), anyMap())).thenReturn(List.of(order));
        when(db.list(contains("from operation_log"), anyMap())).thenReturn(List.of());
        when(request.getAttribute("claims")).thenReturn(Jwts.claims(Map.of("role", "CASHIER")).setSubject("7"));
        ProcessingController controller = controller(db);

        ApiResponse<?> response = controller.withdraw(18L, Map.of("version", 3), request);

        Map<?, ?> result = (Map<?, ?>) response.data();
        assertEquals("WITHDRAWN", result.get("status"));
        assertEquals(true, result.get("idempotentReplay"));
        assertEquals(true, result.get("withdrawn"));
    }

    private ProcessingController controller(DbSupport db) {
        return new ProcessingController(db, mock(SyncWebSocketHandler.class), mock(ShiftService.class), mock(OldMaterialLedgerService.class));
    }

    private Map<String, Object> order(String status, int version) {
        Map<String, Object> order = new HashMap<>();
        order.put("processing_order_id", 18L);
        order.put("store_id", 1L);
        order.put("order_no", "JG-18");
        order.put("status", status);
        order.put("version", version);
        order.put("source_sales_order_id", null);
        return order;
    }
}
