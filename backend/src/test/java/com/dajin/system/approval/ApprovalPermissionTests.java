package com.dajin.system.approval;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.recycle.RecycleController;
import com.dajin.system.stock.OldMaterialLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;
import java.util.Set;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApprovalPermissionTests {
    private final DbSupport db = mock(DbSupport.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final ApprovalController controller = new ApprovalController(db, mock(SyncWebSocketHandler.class),
            mock(RecycleController.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

    private void setup(Set<String> permissions) {
        request.setAttribute("permissions", permissions);
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        when(db.one(contains("from approval"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("type", "STOCK_CHECK", "biz_id", 9L));
        when(db.one(contains("from stock_check"), anyMap())).thenReturn(Map.of("bill_no", "PD-9"));
    }

    @Test void wildcardAdminCanRejectStockCheck() {
        setup(Set.of("*"));
        assertEquals(200, controller.reject(8, Map.of("remark", "fixture"), request).code());
    }

    @Test void missingStockPermissionDoesNotWriteDecision() {
        setup(Set.of("approval:handle"));
        assertThrows(BusinessException.class, () -> controller.reject(8, Map.of("remark", "fixture"), request));
        verify(jdbc, never()).update(contains("update approval"), any(SqlParameterSource.class));
    }

    @Test void missingOrCrossStoreApprovalDoesNotWriteAndKeepsConflictFeedback() {
        setup(Set.of("*"));
        when(db.one(contains("from approval"), any(SqlParameterSource.class)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        assertEquals(409001, assertThrows(BusinessException.class,
                () -> controller.reject(8, Map.of("remark", "fixture"), request)).getCode());
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }

    @Test void orphanProcessingRefundDetailIsStableAndReadable() {
        setup(Set.of("approval:view"));
        when(db.one(contains("from approval"), anyMap()))
                .thenReturn(Map.of("type", "PROCESSING_REFUND", "biz_id", 91L, "reason", "{}"));
        when(db.one(contains("from approval"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("type", "PROCESSING_REFUND", "biz_id", 91L, "reason", "{}"));
        when(db.list(anyString(), anyMap())).thenReturn(List.of());

        Map<String, Object> body = (Map<String, Object>) controller.detail(62, request).data();
        assertNull(body.get("processingOrder"));
        assertEquals(true, body.get("processingOrderMissing"));
        assertEquals("关联加工单不存在", body.get("processingOrderMessage"));
    }

    @Test void orphanProcessingRefundCanBeRejected() {
        setup(Set.of("approval:handle"));
        when(db.one(contains("from approval"), anyMap()))
                .thenReturn(Map.of("type", "PROCESSING_REFUND", "biz_id", 91L, "reason", "{}"));
        when(db.one(contains("from approval"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("type", "PROCESSING_REFUND", "biz_id", 91L, "reason", "{}"));
        when(db.list(anyString(), anyMap())).thenReturn(List.of());

        assertEquals(200, controller.reject(62, Map.of("remark", "关联单据清理"), request).code());
        verify(jdbc).update(contains("update approval"), any(SqlParameterSource.class));
    }

    @Test void orphanProcessingRefundApprovalIsRejectedBeforeDecisionWrite() {
        setup(Set.of("approval:handle"));
        when(db.one(contains("from approval"), anyMap()))
                .thenReturn(Map.of("type", "PROCESSING_REFUND", "biz_id", 91L, "reason", "{}"));
        when(db.one(contains("from approval"), any(SqlParameterSource.class)))
                .thenReturn(Map.of("type", "PROCESSING_REFUND", "biz_id", 91L, "reason", "{}"));
        when(db.list(anyString(), anyMap())).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> controller.approve(62, Map.of(), request));
        assertEquals(409727, error.getCode());
        verify(jdbc, never()).update(contains("update approval"), any(SqlParameterSource.class));
    }
}
