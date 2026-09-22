package com.dajin.system.stock;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OldMaterialAggregationTests {
    @Test
    void aggregatesSameTypeUsingEffectiveWeightAndWeightedPurity() {
        List<Map<String, Object>> rows = List.of(
                Map.of("material_id", 1L, "material_type", "足金999", "weight", new BigDecimal("10.000"),
                        "purity", new BigDecimal("0.9900"), "value", new BigDecimal("5700.00")),
                Map.of("material_id", 2L, "material_type", "足金999", "weight", new BigDecimal("5.000"),
                        "purity", new BigDecimal("0.9000"), "value", new BigDecimal("2600.00"))
        );

        List<Map<String, Object>> result = OldMaterialAggregation.aggregate(rows);

        assertEquals(1, result.size());
        assertEquals(new BigDecimal("14.400"), result.get(0).get("total_weight"));
        assertEquals(new BigDecimal("0.9600"), result.get(0).get("average_purity"));
        assertEquals(new BigDecimal("8300.00"), result.get(0).get("total_value"));
        assertEquals(2, result.get(0).get("inbound_count"));
        assertEquals(2, ((List<?>) result.get(0).get("details")).size());
    }
}
