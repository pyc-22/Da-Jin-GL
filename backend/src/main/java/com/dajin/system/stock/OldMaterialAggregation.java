package com.dajin.system.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the type-level old-material ledger while retaining rows for drill-down. */
public final class OldMaterialAggregation {
    private OldMaterialAggregation() { }

    public static List<Map<String, Object>> aggregate(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Map<String, Object> source : rows) {
            String type = String.valueOf(source.getOrDefault("material_type", "未分类"));
            BigDecimal weight = decimal(source.get("weight"));
            BigDecimal purity = decimal(source.get("purity"));
            BigDecimal value = decimal(source.get("value"));
            BigDecimal direction = decimal(source.getOrDefault("direction", 1));
            BigDecimal effective = weight.multiply(purity).multiply(direction).setScale(3, RoundingMode.HALF_UP);
            Map<String, Object> group = groups.computeIfAbsent(type, key -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("material_type", key);
                created.put("total_weight", BigDecimal.ZERO.setScale(3));
                created.put("total_gross_weight", BigDecimal.ZERO.setScale(3));
                created.put("average_purity", BigDecimal.ZERO.setScale(4));
                created.put("total_value", BigDecimal.ZERO.setScale(2));
                created.put("inbound_count", 0);
                created.put("outbound_count", 0);
                created.put("details", new ArrayList<Map<String, Object>>());
                return created;
            });
            group.put("total_weight", ((BigDecimal) group.get("total_weight")).add(effective).setScale(3, RoundingMode.HALF_UP));
            group.put("total_gross_weight", ((BigDecimal) group.get("total_gross_weight")).add(weight.multiply(direction)).setScale(3, RoundingMode.HALF_UP));
            group.put("total_value", ((BigDecimal) group.get("total_value")).add(value.multiply(direction)).setScale(2, RoundingMode.HALF_UP));
            if (direction.signum() >= 0) group.put("inbound_count", (Integer) group.get("inbound_count") + 1);
            else group.put("outbound_count", (Integer) group.get("outbound_count") + 1);
            Map<String, Object> detail = new LinkedHashMap<>(source);
            detail.put("effective_weight", effective);
            @SuppressWarnings("unchecked") List<Map<String, Object>> details = (List<Map<String, Object>>) group.get("details");
            details.add(detail);
            BigDecimal gross = (BigDecimal) group.get("total_gross_weight");
            BigDecimal totalEffective = (BigDecimal) group.get("total_weight");
            group.put("average_purity", gross.signum() == 0 ? BigDecimal.ZERO.setScale(4)
                    : totalEffective.divide(gross, 4, RoundingMode.HALF_UP));
        }
        return new ArrayList<>(groups.values());
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
