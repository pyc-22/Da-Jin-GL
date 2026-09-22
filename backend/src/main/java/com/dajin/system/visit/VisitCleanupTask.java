package com.dajin.system.visit;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
public class VisitCleanupTask {
    private final NamedParameterJdbcTemplate jdbc;

    public VisitCleanupTask(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "0 15 3 * * ?", zone = "Asia/Shanghai")
    @Transactional
    public void daily() {
        purgeCompletedBeforeRetention();
    }

    int purgeCompletedBeforeRetention() {
        return jdbc.update(
                "delete from visit_task where status=2 and update_time<date_sub(now(),interval 3 month)",
                Map.of());
    }
}
