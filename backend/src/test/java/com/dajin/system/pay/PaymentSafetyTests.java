package com.dajin.system.pay;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.*;
import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentSafetyTests {
    @Test
    void refundedOrderCannotDebitMoneyOrInventoryAgain() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.one(contains("from sales_order"), anyMap())).thenReturn(Map.of(
                "status", 5, "total_amount", new BigDecimal("100"), "discount", BigDecimal.ONE,
                "labor_fee", BigDecimal.ZERO, "old_material_deduct", BigDecimal.ZERO,
                "order_no", "REFUNDED-1", "member_id", 1L, "store_id", 1L));
        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(1);
        PayController controller = new PayController(db, mock(SyncWebSocketHandler.class),
                mock(ShiftService.class), mock(OldMaterialLedgerService.class), new ObjectMapper());

        assertThrows(BusinessException.class, () -> controller.pay(Map.of("orderId", 1L,
                "clientRequestId", "new-payment", "amount", 100, "payMethod", "BALANCE"), request));
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }
}
