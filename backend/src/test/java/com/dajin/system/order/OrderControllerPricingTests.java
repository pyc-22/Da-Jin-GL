package com.dajin.system.order;

import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.gold.GoldMarketService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderControllerPricingTests {
    private static final String GOODS_SQL = "select status,price_type,weight,sale_price,gold_type,name from goods where goods_id=:g and store_id=:s";

    @Test
    void fixedPriceUsesCatalogAmountInsteadOfClientAmountOrWeight() {
        DbSupport db = mock(DbSupport.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(1L);
        when(db.one(GOODS_SQL, Map.of("g", 301L, "s", 1L))).thenReturn(Map.of(
                "status", 1, "price_type", 3, "weight", new BigDecimal("8.000"),
                "sale_price", new BigDecimal("1280.00"), "gold_type", "足金", "name", "标签价手镯"));

        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper());
        OrderController.Item priced = controller.priceItem(new OrderController.Item(301L, "客户端篡改名称",
                new BigDecimal("1.000"), new BigDecimal("1.00"), BigDecimal.ZERO, 2,
                new BigDecimal("2.00"), List.of()), request);

        assertEquals(new BigDecimal("1280.00"), priced.unitPrice());
        assertEquals(new BigDecimal("2560.00"), priced.subtotal());
        assertEquals(new BigDecimal("8.000"), priced.weight());
        assertEquals("标签价手镯", priced.itemName());
    }

    @Test
    void gramPriceUsesEnteredTotalWeightAndMatchingMetalQuote() {
        DbSupport db = mock(DbSupport.class);
        GoldMarketService market = mock(GoldMarketService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(db.store(request)).thenReturn(1L);
        when(db.one(GOODS_SQL, Map.of("g", 302L, "s", 1L))).thenReturn(Map.of(
                "status", 1, "price_type", 1, "weight", new BigDecimal("2.500"),
                "sale_price", new BigDecimal("99.00"), "gold_type", "18K", "name", "18K项链"));
        when(market.snapshot(1L, "18K", null)).thenReturn(Map.of("salePrice", new BigDecimal("428.00")));

        OrderController controller = new OrderController(db, mock(SyncWebSocketHandler.class), new ObjectMapper(), market);
        OrderController.Item priced = controller.priceItem(new OrderController.Item(302L, "18K项链",
                new BigDecimal("5.000"), new BigDecimal("1.00"), BigDecimal.ZERO, 2,
                new BigDecimal("2.00"), List.of()), request);

        assertEquals(new BigDecimal("428.00"), priced.unitPrice());
        assertEquals(new BigDecimal("2140.00"), priced.subtotal());
        assertEquals(new BigDecimal("5.000"), priced.weight());
        assertEquals(1, priced.qty());
    }
}
