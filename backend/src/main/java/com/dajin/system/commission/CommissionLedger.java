package com.dajin.system.commission;

import com.dajin.system.common.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.math.BigDecimal;
import java.util.Map;

/** Canonical monthly totals rebuilt from settled orders inside the caller's transaction. */
public final class CommissionLedger {
    private final DbSupport db;
    public CommissionLedger(DbSupport db) { this.db=db; }
    public BigDecimal rate(long store) {
        var rows=db.list("select config_value from sys_config where store_id=:s and config_key='default_commission_rate' and enabled=1",Map.of("s",store));
        return rows.isEmpty()?BigDecimal.ZERO:new BigDecimal(String.valueOf(rows.get(0).get("config_value")));
    }
    public void recordSale(long store,long orderId) {
        lock(store);
        db.jdbc().update("update sales_order set commission_rate_snapshot=:rate where store_id=:s and order_id=:o and commission_rate_snapshot is null",
                new MapSqlParameterSource().addValue("s",store).addValue("o",orderId).addValue("rate",rate(store)));
        rebuildForOrder(store,orderId);
    }
    public void rebuildForOrder(long store,long orderId) {
        String month=db.jdbc().queryForObject("select date_format(coalesce(paid_time,create_time),'%Y-%m') from sales_order where store_id=:s and order_id=:o",Map.of("s",store,"o",orderId),String.class);
        rebuild(store,month);
    }
    public int rebuild(long store,String month) {
        if(month==null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new BusinessException(400501,"核算月份不合法");
        lock(store);
        var p=new MapSqlParameterSource().addValue("s",store).addValue("m",month).addValue("rate",rate(store));
        db.jdbc().update("delete from commission_record where store_id=:s and month=:m",p);
        return db.jdbc().update("insert into commission_record(store_id,user_id,month,sales_amount,commission_amount,detail,create_time) select store_id,sales_id,:m,sum(pay_amount),sum(round(pay_amount*coalesce(commission_rate_snapshot,:rate),2)),'{\"source\":\"SETTLED_ORDERS\"}',now() from sales_order where store_id=:s and status=1 and sales_id is not null and date_format(coalesce(paid_time,create_time),'%Y-%m')=:m group by store_id,sales_id",p);
    }
    private void lock(long store) { db.one("select store_id from sys_store where store_id=:s for update",Map.of("s",store)); }
}
