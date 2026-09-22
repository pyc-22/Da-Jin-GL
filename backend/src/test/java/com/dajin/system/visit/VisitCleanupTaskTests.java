package com.dajin.system.visit;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VisitCleanupTaskTests {
    @Test
    void removesOnlyCompletedVisitsOlderThanThreeMonths() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        String sql = "delete from visit_task where status=2 and update_time<date_sub(now(),interval 3 month)";
        when(jdbc.update(eq(sql), eq(Map.of()))).thenReturn(4);

        int deleted = new VisitCleanupTask(jdbc).purgeCompletedBeforeRetention();

        assertEquals(4, deleted);
        verify(jdbc).update(eq(sql), eq(Map.of()));
    }
}
