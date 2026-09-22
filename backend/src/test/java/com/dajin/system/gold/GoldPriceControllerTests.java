package com.dajin.system.gold;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class GoldPriceControllerTests {
    @Test
    void stripsResponseAliasesBeforePersistingTypeDefinitions() {
        Map<String, Object> displayed = new LinkedHashMap<>();
        displayed.put("name", "钯金");
        displayed.put("code", "PALLADIUM");
        displayed.put("purity", new BigDecimal("99.0"));
        displayed.put("price", new BigDecimal("250.00"));
        displayed.put("sort", 6);
        displayed.put("status", 1);
        displayed.put("type_id", "PALLADIUM");
        displayed.put("type_name", "钯金");
        displayed.put("type_code", "PALLADIUM");
        displayed.put("price_type", "钯金");

        List<Map<String, Object>> compact = GoldPriceController.compactTypes(List.of(displayed));

        assertEquals(List.of("name", "code", "purity", "price", "sort", "status"),
                compact.get(0).keySet().stream().toList());
        assertFalse(compact.get(0).containsKey("type_id"));
        assertFalse(compact.get(0).containsKey("price_type"));
    }

    @Test
    void convertsDomesticSilverQuoteFromYuanPerKilogramToYuanPerGram() {
        assertEquals(new BigDecimal("15.835"), GoldPriceController.silverPricePerGram("15835.00"));
    }

    @Test
    void returnsAnEmptyPriceWhenTheRealtimeQuoteIsUnavailable() {
        Map<String, Object> result = GoldPriceController.unavailableSpot("行情获取失败");

        assertNull(result.get("price"));
        assertEquals("行情获取失败", result.get("message"));
    }

    @Test
    void addsAnIndependentSilverRecyclePriceTypeOnlyOnce() {
        List<Map<String, Object>> types = new java.util.ArrayList<>();
        types.add(new LinkedHashMap<>(Map.of("name", "银", "code", "SILVER", "sort", 5)));

        GoldPriceController.ensureSilverRecycleType(types);
        GoldPriceController.ensureSilverRecycleType(types);

        assertEquals(2, types.size());
        assertEquals("银回收价", types.get(1).get("name"));
        assertEquals("SILVER_RECYCLE", types.get(1).get("code"));
        assertEquals(6, types.get(1).get("sort"));
    }
}
