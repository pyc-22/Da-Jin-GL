package com.dajin.system.stock;

import com.dajin.system.common.DbSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OldMaterialLedgerServiceTests {
    @Test
    void recordsManualMaterialUsingEffectiveWeight() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        OldMaterialLedgerService service = new OldMaterialLedgerService(db);

        service.recordMaterial(1L, 42L, "MANUAL:test", new BigDecimal("10.000"),
                new BigDecimal("0.9650"), new BigDecimal("5580.00"), 7L);

        verify(jdbc).update(contains("insert into stock_in"), any(MapSqlParameterSource.class));
        assertEquals(new BigDecimal("9.650"),
                OldMaterialLedgerService.effectiveWeight(new BigDecimal("10.000"), new BigDecimal("0.9650")));
    }

    @Test
    void synchronizesExistingLedgerEntryWhenMaterialChanges() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.update(contains("update stock_in"), any(MapSqlParameterSource.class))).thenReturn(1);
        OldMaterialLedgerService service = new OldMaterialLedgerService(db);

        service.synchronizeMaterial(1L, 42L, "RECYCLE:9", new BigDecimal("20.000"),
                new BigDecimal("0.7500"), new BigDecimal("8600.00"), 7L);

        verify(jdbc).update(contains("update stock_in"), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(contains("insert into stock_in"), any(MapSqlParameterSource.class));
    }
}
