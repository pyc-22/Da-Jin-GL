package com.dajin.system.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StockInboundImageTests {
    @Test
    void inboundPhotosArePrependedDeduplicatedAndCapped() throws Exception {
        StockController controller = new StockController(null, null, new ObjectMapper(), null);
        Method merge = StockController.class.getDeclaredMethod("mergeGoodsImages", String.class, List.class);
        merge.setAccessible(true);

        String current = "[\"old-1\",\"old-2\",\"same\",\"old-3\",\"old-4\",\"old-5\",\"old-6\",\"old-7\"]";
        String result = (String) merge.invoke(controller, current, List.of("new-1", "same", "new-2"));

        assertEquals(List.of("new-1", "same", "new-2", "old-1", "old-2", "old-3", "old-4", "old-5", "old-6"),
                new ObjectMapper().readValue(result, List.class));
    }
}
