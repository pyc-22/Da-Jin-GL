package com.dajin.system.processing;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.shift.ShiftService;
import com.dajin.system.stock.OldMaterialLedgerService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingCraftsmanTests {
    @Test
    void craftsmanOptionsOnlyContainActiveCraftsmanRole() {
        DbSupport db = mock(DbSupport.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(9L);
        when(db.list(anyString(), anyMap())).thenReturn(List.of());

        controller(db).craftsmen(request);

        verify(db).list(contains("r.role_code='CRAFTSMAN'"), eq(Map.of("s", 9L)));
    }

    @Test
    void assignmentRejectsAnActiveEmployeeWithoutCraftsmanRole() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(1L);
        when(db.jdbc()).thenReturn(jdbc);
        when(db.list(contains("for update"), anyMap())).thenReturn(List.of(Map.of("processing_order_id", 12L)));
        when(jdbc.queryForObject(contains("r.role_code='CRAFTSMAN'"), any(Map.class), eq(Integer.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> controller(db).assign(12L, Map.of("craftsmanId", 3L), request));
    }

    private ProcessingController controller(DbSupport db) {
        return new ProcessingController(db, mock(SyncWebSocketHandler.class), mock(ShiftService.class), mock(OldMaterialLedgerService.class));
    }
}
