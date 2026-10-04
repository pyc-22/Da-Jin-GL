package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProcessingResidualMaterialTests {
    private record Fixture(DbSupport db, NamedParameterJdbcTemplate jdbc, OldMaterialLedgerService ledger,
                           HttpServletRequest request, ProcessingController controller, Map<String, Object> order) { }

    private Fixture fixture(int paid, int recorded) {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        OldMaterialLedgerService ledger = mock(OldMaterialLedgerService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = new HashMap<>(Map.of(
                "processing_order_id", 18L, "store_id", 1L, "order_no", "JG-18",
                "status", "PROCESSING", "due_amount", 100, "paid_amount", paid,
                "labor_fee", 100, "store_gold_amount", 0, "residual_material_recorded", recorded,
                "residual_gold_handling", "TAKE_AWAY"
        ));
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            if (sql.contains("for update")) return List.of(order);
            if (sql.contains("from gold_price")) return List.of(Map.of("price", 50));
            return List.of();
        });
        when(db.one(anyString(), anyMap())).thenReturn(order);
        when(jdbc.queryForObject(contains("select material_id"), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(901L);
        return new Fixture(db, jdbc, ledger, request,
                new ProcessingController(db, mock(SyncWebSocketHandler.class), mock(ShiftService.class), ledger), order);
    }

    @Test
    void recordsResidualMaterialOnlyWhenProcessingIsCompleted() {
        Fixture f = fixture(0, 0);

        f.controller.changeStatus(18L, Map.of(
                "status", "COMPLETED", "residualGoldHandling", "STORE_DEDUCT",
                "residualMaterialType", "足金旧料", "residualGoldWeight", 1,
                "residualGoldFineness", 0.9
        ), f.request);

        verify(f.ledger).recordMaterial(1L, 901L, "PROCESSING:18",
                new java.math.BigDecimal("1.000"), new java.math.BigDecimal("0.9000"),
                new java.math.BigDecimal("45.00"), 0L);
        verify(f.jdbc).update(contains("residual_material_type=:type"), any(SqlParameterSource.class));
        verify(f.jdbc).update(contains("set status=:status"), anyMap());
    }

    @Test
    void rejectsResidualDeductionBelowExistingDeposit() {
        Fixture f = fixture(60, 0);

        BusinessException error = assertThrows(BusinessException.class, () -> f.controller.changeStatus(18L, Map.of(
                "status", "COMPLETED", "residualGoldHandling", "STORE_DEDUCT",
                "residualMaterialType", "足金旧料", "residualGoldWeight", 1,
                "residualGoldFineness", 0.9
        ), f.request));

        assertEquals(409716, error.getCode());
        verifyNoInteractions(f.ledger);
        verify(f.jdbc, never()).update(contains("set status=:status"), anyMap());
    }

    @Test
    void recordedResidualMaterialIsNotInsertedAgain() {
        Fixture f = fixture(0, 1);

        f.controller.changeStatus(18L, Map.of("status", "COMPLETED", "residualGoldHandling", "STORE_DEDUCT"), f.request);

        verifyNoInteractions(f.ledger);
        verify(f.jdbc, never()).update(contains("insert into old_material"), any(SqlParameterSource.class));
        verify(f.jdbc).update(contains("set status=:status"), anyMap());
    }
}
