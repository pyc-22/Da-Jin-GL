package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会员卡上绑定的导购若是系统管理员（停用/非销售），开单不能再因为兜底导购而失败。
 * 显式传入的导购仍严格校验，防止误选。
 */
class ProcessingGuideFallbackTests {
    private record Fixture(ProcessingController controller, NamedParameterJdbcTemplate jdbc, HttpServletRequest request) { }

    private Fixture fixture() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        Map<String, Object> order = new HashMap<>(Map.of(
                "processing_order_id", 70L, "store_id", 1L, "order_no", "JG-70", "status", "PENDING",
                "due_amount", 30, "paid_amount", 0, "labor_fee", 30, "store_gold_amount", 0, "quantity", 1));
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.one(anyString(), anyMap())).thenReturn(order);
        when(db.list(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            // 会员 8200 绑定的导购是 user_id=1 的系统管理员（不是销售）
            if (sql.contains("from member")) return List.of(Map.of("sales_id", 1));
            if (sql.contains("from processing_item")) return List.of(Map.of("item_id", 5, "name", "戒指", "labor_fee", 30));
            if (sql.contains("from gold_price")) return List.of(Map.of("price", 50));
            return List.of();
        });
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0, String.class);
            return sql.contains("role_code='SALES'") ? 0 : 1;
        });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class))).thenReturn(70L);
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        ProcessingController controller = new ProcessingController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class));
        return new Fixture(controller, jdbc, request);
    }

    private Map<String, Object> orderBody(Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of(
                "memberId", 8200, "customerName", "王小红", "customerPhone", "13800001234",
                "processingItemId", 5, "quantity", 1));
        body.putAll(extra);
        return body;
    }

    @Test
    void memberBoundToANonSalesAccountIsSavedWithoutAGuide() {
        Fixture f = fixture();

        assertNotNull(f.controller.createOrder(orderBody(Map.of()), f.request));

        ArgumentCaptor<SqlParameterSource> captor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(f.jdbc).update(contains("insert into processing_order"), captor.capture());
        assertNull(captor.getValue().getValue("sales"), "会员绑定的失效导购不应写进订单");
    }

    @Test
    void explicitInactiveGuideIsStillRejected() {
        Fixture f = fixture();

        BusinessException error = assertThrows(BusinessException.class,
                () -> f.controller.createOrder(orderBody(Map.of("salesId", 1)), f.request));

        assertEquals(400732, error.getCode());
        verify(f.jdbc, never()).update(contains("insert into processing_order"), any(SqlParameterSource.class));
    }
}
