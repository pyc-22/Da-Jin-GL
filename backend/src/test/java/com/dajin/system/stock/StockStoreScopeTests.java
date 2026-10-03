package com.dajin.system.stock;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StockStoreScopeTests {
    @Test void managerCannotReadOrInboundOtherStore() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("claims", Jwts.claims(Map.of("role", "MANAGER")));
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(1);
        when(db.list(anyString(), anyMap())).thenReturn(java.util.List.of(Map.of("goods_id", 1L)));
        StockController controller = new StockController(db, null, new ObjectMapper(), null);
        assertThrows(BusinessException.class, () -> controller.goodsByBarcode("fixture", null, 2L, request));
        assertThrows(BusinessException.class, () -> controller.createInbound(Map.of("storeId", 2L), request));
        verify(db, never()).jdbc();
    }
}
