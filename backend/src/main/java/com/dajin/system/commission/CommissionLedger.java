package com.dajin.system.commission;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.order.SalesAmounts;
import com.dajin.system.processing.ProcessingAmounts;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Canonical monthly sales and processing-sales totals rebuilt in the caller's transaction. */
public final class CommissionLedger {
    private final DbSupport db;
    public CommissionLedger(DbSupport db) { this.db = db; }

    /** Rates are fractions: 0.02 means 2%. */
    public BigDecimal rate(long store) { return configRate(store, "default_commission_rate", new BigDecimal("0.02")); }
    public BigDecimal processingRate(long store) { return configRate(store, "processing_sales_commission_rate", new BigDecimal("0.01")); }

    /** Resolves an enabled category/amount rule, falling back to the fixed store rate. */
    public BigDecimal ruleRate(long store, String type, String category, BigDecimal amount, BigDecimal fallback) {
        List<Map<String,Object>> rows = db.list("select rate, `condition` from commission_rule where store_id=:s and type=:t and status=1 order by rule_id", Map.of("s", store, "t", type));
        BigDecimal selected = null;
        for (Map<String,Object> row : rows) {
            try {
                Map<?,?> c = new ObjectMapper().readValue(String.valueOf(row.getOrDefault("condition", "{}")), Map.class);
                Object expected = c.get("category");
                if (expected != null && !String.valueOf(expected).isBlank() && (category == null || !String.valueOf(expected).equals(category))) continue;
                BigDecimal min = decimal(c.get("minAmount"), null), max = decimal(c.get("maxAmount"), null);
                if (amount != null && min != null && amount.compareTo(min) < 0) continue;
                if (amount != null && max != null && amount.compareTo(max) > 0) continue;
                selected = decimal(row.get("rate"), null);
                if (selected != null) break;
            } catch (Exception ignored) { /* malformed rule is ignored; fixed rate remains effective */ }
        }
        return selected == null ? fallback : selected;
    }

    public void recordSale(long store, long orderId) {
        lock(store);
        db.jdbc().update("update sales_order set commission_rate_snapshot=:rate where store_id=:s and order_id=:o and commission_rate_snapshot is null",
                new MapSqlParameterSource().addValue("s", store).addValue("o", orderId).addValue("rate", rate(store)));
        rebuildForOrder(store, orderId);
    }

    public void rebuildForOrder(long store, long orderId) {
        String month = db.jdbc().queryForObject("select date_format(coalesce(paid_time,create_time),'%Y-%m') from sales_order where store_id=:s and order_id=:o",
                Map.of("s", store, "o", orderId), String.class);
        rebuild(store, month);
    }

    public void rebuildForProcessingOrder(long store, long orderId) {
        String month = db.jdbc().queryForObject("select date_format(coalesce(picked_up_time,completed_time,create_time),'%Y-%m') from processing_order where store_id=:s and processing_order_id=:o",
                Map.of("s", store, "o", orderId), String.class);
        if (month != null && !month.isBlank()) rebuild(store, month);
    }

    public int rebuild(long store, String month) {
        if (month == null || !month.matches("\\d{4}-(0[1-9]|1[0-2])")) throw new BusinessException(400501, "核算月份不合法");
        lock(store);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", store).addValue("m", month)
                .addValue("rate", rate(store)).addValue("processingRate", processingRate(store));
        db.jdbc().update("delete from commission_record where store_id=:s and month=:m", p);

        String paid = SalesAmounts.actualPaid("o");
        String sales = "select o.store_id,o.sales_id user_id,sum(" + paid + ") sales_amount,0 processing_base,0 processing_commission,"
                + "sum(round((" + paid + ")*coalesce(o.commission_rate_snapshot,"
                + "coalesce((select cr.rate from commission_rule cr where cr.store_id=o.store_id and cr.type='SALE' and cr.status=1 "
                + "and (json_extract(cr.condition,'$.category') is null or exists (select 1 from sales_order_item soi "
                + "join goods g on g.goods_id=soi.goods_id and g.store_id=soi.store_id where soi.store_id=o.store_id and soi.order_id=o.order_id "
                + "and json_unquote(json_extract(cr.condition,'$.category'))=cast(g.category_id as char))) "
                + "and (json_extract(cr.condition,'$.minAmount') is null or " + paid + ">=cast(json_unquote(json_extract(cr.condition,'$.minAmount')) as decimal(12,2))) "
                + "and (json_extract(cr.condition,'$.maxAmount') is null or " + paid + "<=cast(json_unquote(json_extract(cr.condition,'$.maxAmount')) as decimal(12,2))) order by cr.rule_id limit 1),:rate)),2)) sales_commission,count(*) sales_orders,0 processing_orders "
                + "from sales_order o where o.store_id=:s and o.status=1 and o.sales_id is not null "
                + "and date_format(coalesce(o.paid_time,o.create_time),'%Y-%m')=:m group by o.store_id,o.sales_id";

        String base = ProcessingAmounts.laborBase("p");
        String processing = "select p.store_id,p.sales_id user_id,0 sales_amount,sum(" + base + ") processing_base,"
                + "sum(round((" + base + ")*coalesce(p.sales_commission_rate_snapshot,"
                + "coalesce((select cr.rate from commission_rule cr where cr.store_id=p.store_id and cr.type='PROCESSING' and cr.status=1 order by cr.rule_id limit 1),:processingRate)),2)) processing_commission,"
                + "0 sales_commission,0 sales_orders,count(*) processing_orders from processing_order p "
                + "where p.store_id=:s and p.status='PICKED_UP' and p.sales_id is not null "
                + "and date_format(coalesce(p.picked_up_time,p.completed_time,p.create_time),'%Y-%m')=:m group by p.store_id,p.sales_id";

        String sql = "insert into commission_record(store_id,user_id,month,sales_amount,processing_base,processing_commission,commission_amount,detail,create_time) "
                + "select x.store_id,x.user_id,:m,sum(x.sales_amount),sum(x.processing_base),sum(x.processing_commission),sum(x.sales_commission+x.processing_commission),"
                + "json_object('sales_orders',sum(x.sales_orders),'sales_amount',round(sum(x.sales_amount),2),'sales_commission',round(sum(x.sales_commission),2),'processing_orders',sum(x.processing_orders),"
                + "'processing_base',round(sum(x.processing_base),2),'processing_commission',round(sum(x.processing_commission),2)),now() "
                + "from (" + sales + " union all " + processing + ") x group by x.store_id,x.user_id";
        return db.jdbc().update(sql, p);
    }

    private BigDecimal configRate(long store, String key, BigDecimal fallback) {
        var rows = db.list("select config_value from sys_config where store_id=:s and config_key=:k and enabled=1", Map.of("s", store, "k", key));
        if (rows.isEmpty() || rows.get(0).get("config_value") == null) return fallback;
        try { return new BigDecimal(String.valueOf(rows.get(0).get("config_value"))); } catch (NumberFormatException ignored) { return fallback; }
    }
    private static BigDecimal decimal(Object value, BigDecimal fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try { return new BigDecimal(String.valueOf(value)); } catch (Exception e) { return fallback; }
    }
    private void lock(long store) { db.one("select store_id from sys_store where store_id=:s for update", Map.of("s", store)); }
}
