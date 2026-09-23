package com.dajin.system.notification;

import com.dajin.system.config.SyncWebSocketHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** 每日提醒：未结加工单超期、取货到期未取、库存不足；写入店长/管理员消息中心并 WS 推送。 */
@Component
public class ReminderTask {
    private final NamedParameterJdbcTemplate jdbc;
    private final SyncWebSocketHandler ws;

    public ReminderTask(NamedParameterJdbcTemplate jdbc, SyncWebSocketHandler ws) { this.jdbc = jdbc; this.ws = ws; }

    // 一天多次生成；push 的幂等键按天，同日重跑不会重复插入
    @Scheduled(cron = "0 30 8,12,16,19 * * ?")
    @Transactional
    public void daily() {
        List<Long> stores = jdbc.queryForList("select store_id from sys_store where status=1", Map.of(), Long.class);
        for (Long store : stores) remind(store);
    }

    void remind(long store) {
        List<Long> receivers = jdbc.queryForList(
                "select u.user_id from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.store_id=:s and u.status=1 and r.status=1 and r.role_code in ('ADMIN','MANAGER','CASHIER')",
                Map.of("s", store), Long.class);
        if (receivers.isEmpty()) return;
        boolean any = false;
        any |= push(store, receivers, "stale-orders",
                jdbc.queryForObject("select count(*) from processing_order where store_id=:s and status in ('PENDING','PROCESSING') and create_time<date_sub(now(),interval 3 day)",
                        Map.of("s", store), Integer.class), "笔加工单超过 3 天未完成，请跟进");
        any |= push(store, receivers, "pickup-due",
                jdbc.queryForObject("select count(*) from processing_order where store_id=:s and status in ('PENDING','PROCESSING','COMPLETED') and pickup_date is not null and pickup_date<=curdate()",
                        Map.of("s", store), Integer.class), "笔加工单已到/超过预计取货日期");
        any |= push(store, receivers, "stock-warning",
                jdbc.queryForObject("select count(*) from goods where store_id=:s and status=1 and stock<=5",
                        Map.of("s", store), Integer.class), "件商品库存不足（≤5），建议补货或盘点");
        if (any) ws.broadcast("REMINDER_REFRESH", Map.of("storeId", store));
    }

    private boolean push(long store, List<Long> receivers, String key, int count, String tail) {
        if (count <= 0) return false;
        String content = "今日提醒：" + count + " " + tail;
        for (Long uid : receivers) {
            jdbc.update("insert ignore into operation_log(store_id,user_id,module,action,content,ip,create_time,client_request_id) values(:s,:u,'NOTIFICATION','REMIND',:c,'',now(),:rid)",
                    new MapSqlParameterSource().addValue("s", store).addValue("u", uid).addValue("c", content).addValue("rid", "REMIND-" + key + "-" + store + "-" + uid + "-" + new java.text.SimpleDateFormat("yyyyMMdd").format(new java.util.Date())));
        }
        return true;
    }
}
