package com.dajin.system.gold;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GoldMarketRegressionTests {
    private final DbSupport db = mock(DbSupport.class);
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MockEnvironment env = new MockEnvironment();
    private final HttpClient http = mock(HttpClient.class);
    private GoldMarketService service;
    private AdjustableClock clock;
    private static final String DEFINITIONS = """
            [{"name":"足金","code":"GOLD","price":960,"status":1,"pricingMode":"MANUAL",
              "purityCoefficient":0.999,"baseInstrument":"Au_TD","markup":0,"recycleDeduction":0,"roundingRule":"NONE"}]
            """;

    @BeforeEach void setup() {
        when(db.jdbc()).thenReturn(jdbc);
        when(redis.opsForValue()).thenReturn(values);
        when(values.increment(anyString())).thenReturn(1L);
        when(jdbc.queryForObject(contains("gold_metal_types"), anyMap(), eq(String.class))).thenReturn(DEFINITIONS);
        when(jdbc.queryForObject(contains("gold_market_holidays"), anyMap(), eq(String.class))).thenReturn("[]");
        at("2026-09-29T09:15:00");
    }
    private void at(String time) {
        clock = new AdjustableClock(LocalDateTime.parse(time).atZone(ZoneId.of("Asia/Shanghai")).toInstant());
        service = new GoldMarketService(db, mock(SyncWebSocketHandler.class), new ObjectMapper(), redis, env, clock, http);
    }

    @Test void missingKeysDoNotConsumeQuotaAndExposeReason() {
        Map<String, Object> result = service.spot(9101, GoldMarketService.AU_TD);
        verify(values, never()).increment(anyString());
        assertTrue(String.valueOf(result.get("message")).contains("密钥"));
        assertNull(result.get("price"));
    }

    @Test void failedRefreshesShareBackoffAcrossStores() throws Exception {
        env.setProperty("JISU_APPKEY", "fixture");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("fixture"));
        service.spot(9102, GoldMarketService.AU_TD);
        service.spot(9103, GoldMarketService.AU_TD);
        service.spot(9102, GoldMarketService.AU_TD);
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void missingQuoteHasStatusAndMessageOnCurrentTypes() {
        Map<String, Object> row = service.loadTypes(9104, true).get(0);
        assertEquals("ERROR", row.get("marketStatus"));
        assertTrue(String.valueOf(row.get("marketMessage")).contains("行情"));
        assertNull(row.get("basePrice"));
        assertEquals("960", String.valueOf(row.get("salePrice")));
    }

    @Test void invalidAutoIsRejectedBeforeConfigPersistence() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateType(9105, 1, "GOLD", Map.of("pricingMode", "AUTO")));
        assertTrue(error.getMessage().contains("加价"));
        verify(jdbc, never()).update(contains("update sys_config"), anyMap());
    }

    @Test void holidayWithoutQuoteIsClosedNotZero() {
        when(jdbc.queryForObject(contains("gold_market_holidays"), anyMap(), eq(String.class))).thenReturn("[\"2026-10-02\"]");
        at("2026-10-02T10:00:00");
        Map<String, Object> quote = service.spot(9106, GoldMarketService.AU_TD);
        assertEquals("CLOSED", quote.get("market_status"));
        assertNull(quote.get("price"));
        assertTrue(String.valueOf(quote.get("message")).contains("休市"));
    }

    @SuppressWarnings("unchecked")
    private void providerQuote(String price, String time) throws Exception {
        env.setProperty("JISU_APPKEY", "fixture");
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"status\":0,\"result\":[{\"type\":\"Au(T+D)\",\"price\":\"" + price + "\",\"updatetime\":\"" + time + "\"}]}");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Test void successfulConcurrentRequestsShareOneProviderCallAcrossStores() throws Exception {
        providerQuote("906.80", "2026-09-29 09:15:00");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Map<String, Object>>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) { long store = 9200 + i % 3; tasks.add(() -> service.spot(store, GoldMarketService.AU_TD)); }
            for (Future<Map<String, Object>> result : pool.invokeAll(tasks)) {
                assertEquals(new BigDecimal("906.80"), result.get().get("price"));
                assertEquals("OPEN", result.get().get("market_status"));
                assertFalse(result.get().containsKey("_fresh"));
            }
            verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            verify(values, times(1)).increment(anyString());
        } finally { pool.shutdownNow(); }
    }

    @Test void failureBackoffRetriesThenDoublesDelay() throws Exception {
        env.setProperty("JISU_APPKEY", "fixture");
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("fixture"));
        service.spot(9301, GoldMarketService.AU_TD);
        clock.advance(121); service.spot(9301, GoldMarketService.AU_TD);
        clock.advance(121); service.spot(9302, GoldMarketService.AU_TD);
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        clock.advance(120); service.spot(9302, GoldMarketService.AU_TD);
        verify(http, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void exhaustedDailyQuotaMakesNoProviderCall() throws Exception {
        env.setProperty("JISU_APPKEY", "fixture"); env.setProperty("TIANAPI_KEY", "fixture");
        when(values.increment(anyString())).thenReturn(100L);
        assertTrue(String.valueOf(service.spot(9303, GoldMarketService.AU_TD).get("message")).contains("额度已用尽"));
        verify(http, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test void roundedAwaySpreadIsRejectedBeforeSaving() throws Exception {
        providerQuote("900.20", "2026-09-29 09:15:00");
        BusinessException error = assertThrows(BusinessException.class, () -> service.updateType(9304, 1, "GOLD",
                Map.of("pricingMode", "AUTO", "markup", "0.01", "roundingRule", "YUAN")));
        assertTrue(error.getMessage().contains("取整后"));
        verify(jdbc, never()).update(contains("update sys_config"), anyMap());
    }

    @Test void closedSessionCanBootstrapAQuoteWithoutRepricing() throws Exception {
        at("2026-10-02T10:00:00");
        providerQuote("906.80", "2026-09-30 15:29:56");
        Map<String, Object> quote = service.spot(9305, GoldMarketService.AU_TD);
        assertEquals("CLOSED", quote.get("market_status"));
        assertEquals(new BigDecimal("906.80"), quote.get("price"));
        assertFalse(quote.containsKey("_fresh"));
        verify(jdbc, never()).update(contains("insert into gold_price("), any(org.springframework.jdbc.core.namedparam.SqlParameterSource.class));
    }

    @Test void staleProviderOpenFlagDoesNotMakeAnOldQuoteLive() throws Exception {
        providerQuote("906.80", "2026-09-28 15:29:56");
        Map<String, Object> quote = service.spot(9306, GoldMarketService.AU_TD);
        assertEquals("ERROR", quote.get("market_status"));
        assertTrue(String.valueOf(quote.get("message")).contains("报价时间已过期"));
    }

    @Test void largeMoveKeepsLastQuoteAndFreezesAutoPricing() throws Exception {
        providerQuote("960.00", "2026-09-29 09:15:00");
        when(db.list(contains("instrument_code=:i"), anyMap())).thenAnswer(call -> List.of(new HashMap<>(Map.of(
                "price", new BigDecimal("900.00"), "instrument", GoldMarketService.AU_TD, "store_id", 9307L,
                "quote_time", "2026-09-28 15:29:56", "fetched_at", "2026-09-28T15:30:00", "auto_frozen", 0))));
        Map<String, Object> quote = service.spot(9307, GoldMarketService.AU_TD);
        assertEquals("FROZEN", quote.get("market_status"));
        assertEquals(new BigDecimal("900.00"), quote.get("price"));
    }

    private static class AdjustableClock extends Clock {
        private Instant now;
        AdjustableClock(Instant now) { this.now = now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
