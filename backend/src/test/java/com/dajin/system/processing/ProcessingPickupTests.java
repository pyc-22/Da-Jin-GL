package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProcessingPickupTests {
    @Test
    void pickupCannotBeConfirmedBeforeAPhotoIsSaved() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = new HashMap<>(Map.of(
                "processing_order_id", 18L,
                "store_id", 1L,
                "status", "COMPLETED",
                "due_amount", 200,
                "paid_amount", 200,
                "pickup_photos", "[]"
        ));
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).contains("for update") ? List.of(order) : List.of());
        ProcessingController controller = new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.changeStatus(18L, Map.of("status", "PICKED_UP"), request));

        assertEquals(409715, error.getCode());
        verify(db.jdbc(), never()).update(contains("update processing_order set status"), anyMap());
    }

    @Test
    void pickupCanBeConfirmedWhenAPhotoIsAlreadySaved() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = new HashMap<>(Map.of(
                "processing_order_id", 18L,
                "store_id", 1L,
                "order_no", "JG-18",
                "status", "COMPLETED",
                "due_amount", 200,
                "paid_amount", 200,
                "pickup_photos", "[\"/api/file/pickup-1\"]"
        ));
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).contains("for update") ? List.of(order) : List.of());
        when(db.one(anyString(), anyMap())).thenReturn(order);
        ProcessingController controller = new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class));

        controller.changeStatus(18L, Map.of("status", "PICKED_UP"), request);

        verify(db.jdbc()).update(contains("update processing_order set status"), anyMap());
    }
}
