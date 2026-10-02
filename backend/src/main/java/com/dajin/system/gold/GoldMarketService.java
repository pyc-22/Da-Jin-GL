package com.dajin.system.gold;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central market-data and metal-pricing service.  The API layer and all clients
 * consume this service so a price is calculated once and then broadcast with
 * the same quote metadata everywhere.
 */
@Service
public class GoldMarketService {
    public static final String AU_TD = "Au_TD";
    public static final String AG_TD = "Ag_TD";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter OLD_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final long CACHE_SECONDS = 15 * 60L;
    private static final BigDecimal TEN_PERCENT = new BigDecimal("0.10");
    private static final BigDecimal FIVE_PERCENT = new BigDecimal("0.05");
    private final Map<String, Map<String, Object>> MEMORY_CACHE = new ConcurrentHashMap<>();
    private final Map<String, Long> MEMORY_COUNTER = new ConcurrentHashMap<>();
    private final Map<String, Boolean> MEMORY_RESUME_OVERRIDES = new ConcurrentHashMap<>();

    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final Environment environment;
    private final HttpClient http;
    private final Clock clock;
    private final Map<Long, Object> storeLocks = new ConcurrentHashMap<>();
    private final Map<String, Object> quoteLocks = new ConcurrentHashMap<>();

    @Autowired
    public GoldMarketService(DbSupport db, SyncWebSocketHandler ws, ObjectMapper mapper,
                             StringRedisTemplate redis, Environment environment) {
        this(db, ws, mapper, redis, environment, Clock.system(ZONE),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    }

    GoldMarketService(DbSupport db, SyncWebSocketHandler ws, ObjectMapper mapper,
                      StringRedisTemplate redis, Environment environment, Clock clock, HttpClient http) {
        this.db = db;
        this.ws = ws;
        this.mapper = mapper;
        this.redis = redis;
        this.environment = environment;
        this.clock = clock;
        this.http = http;
    }

    @Scheduled(fixedDelay = 60_000L, initialDelay = 5_000L)
    public void scheduledRefresh() {
        try {
            for (Map<String, Object> row : db.list("select store_id from sys_store where status=1", Map.of())) {
                refreshStore(((Number) row.get("store_id")).longValue());
            }
        } catch (Exception ignored) {
            // A temporary database outage must not stop Spring's scheduler.
        }
    }

    public List<Map<String, Object>> current(long storeId, boolean includeDisabled) {
        synchronized (storeLocks.computeIfAbsent(storeId, ignored -> new Object())) {
            refreshStore(storeId);
            return loadTypes(storeId, includeDisabled);
        }
    }

    public void refreshStore(long storeId) {
        synchronized (storeLocks.computeIfAbsent(storeId, ignored -> new Object())) {
            Map<String, Map<String, Object>> quotes = new LinkedHashMap<>();
            quotes.put(AU_TD, refreshQuote(storeId, AU_TD));
            quotes.put(AG_TD, refreshQuote(storeId, AG_TD));
            repriceAuto(storeId, quotes, false);
        }
    }

    public Map<String, Object> spot(long storeId, String instrument) {
        Map<String, Object> quote = refreshQuote(storeId, instrument);
        if (quote == null) return unavailable("行情获取失败");
        return publicQuote(quote);
    }

    public List<Map<String, Object>> loadTypes(long storeId, boolean includeDisabled) {
        List<Map<String, Object>> definitions = readDefinitions(storeId);
        Map<String, Map<String, Object>> latest = latestPrices(storeId);
        Map<String, Map<String, Object>> quotes = quoteRows(storeId);
        Map<String, Object> recycleFallback = new HashMap<>();
        for (Map<String, Object> item : definitions) {
            if (isRecycleType(item)) recycleFallback.put(String.valueOf(item.get("baseInstrument")), item);
        }
        int sort = 0;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> item : definitions) {
            item.putIfAbsent("sort", ++sort);
            item.putIfAbsent("status", 1);
            item.putIfAbsent("code", item.getOrDefault("name", ""));
            item.putIfAbsent("purity", 100);
            String name = String.valueOf(item.getOrDefault("name", ""));
            String code = String.valueOf(item.getOrDefault("code", name));
            Map<String, Object> price = latest.getOrDefault(name, latest.get(code));
            Map<String, Object> quote = quotes.get(baseInstrument(item));
            Map<String, Object> cached = readCache(cacheKey(storeId, baseInstrument(item)));
            if (cached != null && !expired(cached)) quote = cached;
            if (quote == null) quote = isTradingSession(LocalDateTime.now(clock), holidays(storeId))
                    ? unavailable("尚无有效行情，请检查数据源配置或稍后刷新；保留门店现价")
                    : closedQuote(null, LocalDateTime.now(clock));
            BigDecimal sale = decimal(price == null ? null : price.get("sale_price"));
            BigDecimal recycle = decimal(price == null ? null : price.get("recycle_price"));
            BigDecimal legacy = decimal(price == null ? null : price.get("price"));
            boolean recycleType = isRecycleType(item);
            if (sale == null) sale = recycleType ? BigDecimal.ZERO : (legacy == null ? legacyOr(item.get("price")) : legacy);
            if (recycle == null) recycle = recycleType ? (legacy == null ? legacyOr(item.get("price")) : legacy) : peerRecycle(item, recycleFallback, latest);
            item.put("salePrice", sale);
            item.put("recyclePrice", recycle == null ? BigDecimal.ZERO : recycle);
            item.put("price", recycleType ? (recycle == null ? BigDecimal.ZERO : recycle) : sale);
            item.put("baseInstrument", baseInstrument(item));
            item.put("purityCoefficient", coefficient(item));
            item.put("markup", decimalOrZero(item.get("markup")));
            item.put("recycleDeduction", decimalOrZero(item.get("recycleDeduction")));
            item.put("pricingMode", pricingMode(item));
            item.put("roundingRule", roundingRule(item));
            item.put("type_id", code);
            item.put("type_name", name);
            item.put("type_code", code);
            item.put("price_type", name);
            if (quote != null) {
                item.put("basePrice", quote.get("price"));
                item.put("quoteTime", quote.get("quote_time"));
                item.put("source", quote.get("source"));
                item.put("marketStatus", quote.get("market_status"));
                item.put("marketMessage", quote.get("message"));
                item.put("autoFrozen", number(quote.get("auto_frozen")) == 1);
                item.put("rawUnit", quote.get("raw_unit"));
            }
            item.put("pricingWarning", autoValidationMessage(item, decimal(quote.get("price"))));
            if (price != null) {
                item.put("pricingSource", price.get("source"));
                item.put("pricingQuoteTime", price.get("quote_time"));
                item.put("pricingMarketStatus", price.get("market_status"));
                item.put("pricingFrozen", number(price.get("auto_frozen")) == 1);
            }
            if (includeDisabled || number(item.get("status")) == 1) result.add(item);
        }
        result.sort(Comparator.comparingInt(x -> number(x.getOrDefault("sort", 0))));
        return result;
    }

    public Map<String, Object> saveManual(long storeId, long userId, String priceType, BigDecimal price) {
        List<Map<String, Object>> definitions = readDefinitions(storeId);
        Map<String, Object> item = find(definitions, priceType);
        if (item == null) {
            item = new LinkedHashMap<>();
            item.put("name", priceType); item.put("code", priceType); item.put("purity", 100);
            item.put("price", price); item.put("sort", definitions.size() + 1); item.put("status", 1);
            definitions.add(item);
        }
        item.put("price", price);
        item.put("pricingMode", "MANUAL");
        persistDefinitions(storeId, definitions);
        Map<String, Object> previous = latestPrices(storeId).get(String.valueOf(item.get("name")));
        BigDecimal previousSale = previous == null ? null : decimal(previous.get("sale_price"));
        BigDecimal previousRecycle = previous == null ? null : decimal(previous.get("recycle_price"));
        BigDecimal sale = isRecycleType(item) ? previousSale : price;
        BigDecimal recycle = isRecycleType(item) ? price : previousRecycle;
        insertPrice(storeId, item, recycle, sale, previous == null ? null : decimal(previous.get("base_price")), "MANUAL", null, "MANUAL", false);
        logChange(storeId, userId, item, previous, sale, recycle, "MANUAL");
        broadcastPrice(storeId, item, sale, recycle, "MANUAL", null);
        return find(loadTypes(storeId, true), String.valueOf(item.get("code")));
    }

    public Map<String, Object> updateType(long storeId, long userId, String id, Map<String, Object> request) {
        List<Map<String, Object>> definitions = readDefinitions(storeId);
        Map<String, Object> item = find(definitions, id);
        if (item == null) throw new BusinessException(404301, "贵金属类型不存在");
        Map<String, Object> previous = latestPrices(storeId).get(String.valueOf(item.get("name")));
        applyDefinitionUpdate(item, request);
        // Validate before any configuration write or resume side effect.
        validateAuto(storeId, item);
        persistDefinitions(storeId, definitions);
        if (Boolean.TRUE.equals(request.get("resumeAuto")) || "true".equalsIgnoreCase(String.valueOf(request.get("resumeAuto")))) {
            clearFrozen(storeId, baseInstrument(item));
        }
        Map<String, Map<String, Object>> quotes = new LinkedHashMap<>();
        quotes.put(AU_TD, refreshQuote(storeId, AU_TD)); quotes.put(AG_TD, refreshQuote(storeId, AG_TD));
        repriceAuto(storeId, quotes, true);
        if (!"AUTO".equals(pricingMode(item))) {
            Map<String, Object> latest = latestPrices(storeId).get(String.valueOf(item.get("name")));
            logChange(storeId, userId, item, previous, latest == null ? null : decimal(latest.get("sale_price")), latest == null ? null : decimal(latest.get("recycle_price")), "MANUAL_CONFIG");
        }
        logOperation(storeId, userId, "TYPE_CONFIG_UPDATE", "金类=" + item.get("name"));
        ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", storeId, "action", "CONFIG_UPDATE", "typeId", id));
        return find(loadTypes(storeId, true), String.valueOf(item.get("code")));
    }

    public Map<String, Object> createType(long storeId, long userId, Map<String, Object> request) {
        List<Map<String, Object>> definitions = readDefinitions(storeId);
        String name = requiredText(request, "name", "类型名称");
        String code = requiredText(request, "code", "类型编码");
        if (find(definitions, name) != null || find(definitions, code) != null) throw new BusinessException(409301, "类型名称或编码已存在");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name); item.put("code", code); item.put("purity", request.getOrDefault("purity", 99.9));
        item.put("price", request.getOrDefault("price", BigDecimal.ZERO)); item.put("sort", request.getOrDefault("sort", definitions.size() + 1)); item.put("status", 1);
        item.put("pricingMode", "MANUAL"); item.put("baseInstrument", code.toUpperCase(Locale.ROOT).contains("SILVER") ? AG_TD : AU_TD);
        item.put("purityCoefficient", coefficient(item)); item.put("markup", BigDecimal.ZERO); item.put("recycleDeduction", BigDecimal.ZERO); item.put("roundingRule", "NONE");
        applyDefinitionUpdate(item, request);
        validateAuto(storeId, item);
        definitions.add(item); persistDefinitions(storeId, definitions);
        logOperation(storeId, userId, "TYPE_CREATE", "金类=" + name);
        ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", storeId, "action", "CREATE", "code", code));
        return item;
    }

    public void deleteType(long storeId, long userId, String id) {
        List<Map<String, Object>> definitions = readDefinitions(storeId);
        Map<String, Object> item = find(definitions, id);
        if (item == null) throw new BusinessException(404301, "贵金属类型不存在");
        int history = db.jdbc().queryForObject("select count(*) from gold_price where store_id=:s and (price_type=:n or price_type=:c)", Map.of("s", storeId, "n", item.get("name"), "c", item.get("code")), Integer.class);
        int goods = db.jdbc().queryForObject("select count(*) from goods where store_id=:s and (gold_type=:n or gold_type=:c)", Map.of("s", storeId, "n", item.get("name"), "c", item.get("code")), Integer.class);
        if (history > 0 || goods > 0) item.put("status", 0); else definitions.remove(item);
        persistDefinitions(storeId, definitions);
        logOperation(storeId, userId, history > 0 || goods > 0 ? "TYPE_DISABLE" : "TYPE_DELETE", "金类=" + id);
        ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", storeId, "action", "DELETE", "typeId", id));
    }

    public Map<String, Object> snapshot(long storeId, String metalType, BigDecimal requestedUnitPrice) {
        if (metalType == null || metalType.isBlank()) return Map.of();
        Map<String, Object> item = find(readDefinitions(storeId), metalType);
        if (item == null) return Map.of();
        if ("AUTO".equals(pricingMode(item))) refreshStore(storeId);
        item = find(readDefinitions(storeId), metalType);
        Map<String, Object> latest = latestPrices(storeId).get(String.valueOf(item.get("name")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("goldType", item.get("name")); result.put("baseInstrument", baseInstrument(item));
        result.put("basePrice", latest == null ? null : latest.get("base_price"));
        result.put("pricingMode", pricingMode(item)); result.put("purityCoefficient", coefficient(item));
        result.put("markup", decimalOrZero(item.get("markup"))); result.put("recycleDeduction", decimalOrZero(item.get("recycleDeduction")));
        BigDecimal latestSale = latest == null ? null : decimal(latest.get("sale_price"));
        if (latestSale == null || latestSale.signum() <= 0) latestSale = latest == null ? null : decimal(latest.get("price"));
        result.put("salePrice", latestSale == null ? requestedUnitPrice : latestSale);
        result.put("recyclePrice", latest == null ? null : latest.get("recycle_price"));
        result.put("quoteTime", latest == null ? null : latest.get("quote_time")); result.put("source", latest == null ? null : latest.get("source"));
        result.put("marketStatus", latest == null ? null : latest.get("market_status"));
        return result;
    }

    public static BigDecimal coefficient(Map<String, Object> item) {
        BigDecimal explicit = decimal(item.get("purityCoefficient"));
        if (explicit != null && explicit.signum() > 0 && explicit.compareTo(BigDecimal.ONE) <= 0) return explicit;
        BigDecimal purity = decimal(item.get("purity"));
        return purity == null ? BigDecimal.ONE : (purity.compareTo(BigDecimal.ONE) > 0 ? purity.movePointLeft(2) : purity);
    }

    public static BigDecimal calculate(BigDecimal base, BigDecimal purity, BigDecimal markup, BigDecimal deduction, String rule, boolean recycle) {
        BigDecimal value = base.multiply(purity).add(recycle ? deduction.negate() : markup);
        return round(value.max(BigDecimal.ZERO), rule);
    }

    public static BigDecimal round(BigDecimal value, String rule) {
        if (value == null) return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        return switch (String.valueOf(rule == null ? "NONE" : rule).toUpperCase(Locale.ROOT)) {
            case "TENTH", "角" -> value.setScale(1, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            case "YUAN", "元" -> value.setScale(0, RoundingMode.HALF_UP).setScale(2, RoundingMode.HALF_UP);
            case "TAIL_8", "8" -> value.setScale(0, RoundingMode.FLOOR).add(new BigDecimal("0.80")).setScale(2);
            case "TAIL_9", "9" -> value.setScale(0, RoundingMode.FLOOR).add(new BigDecimal("0.90")).setScale(2);
            default -> value.setScale(2, RoundingMode.HALF_UP);
        };
    }

    public static boolean isTradingSession(LocalDateTime time, Set<LocalDate> holidays) {
        if (time == null) return false;
        LocalDate date = time.toLocalDate(); LocalTime t = time.toLocalTime();
        if (t.isBefore(LocalTime.of(2, 30))) return nightSession(date.minusDays(1), holidays);
        if (!tradingDay(date, holidays)) return false;
        if (!t.isBefore(LocalTime.of(20, 0))) return nightSession(date, holidays);
        return !t.isBefore(LocalTime.of(9, 0)) && (t.isBefore(LocalTime.of(11, 30))
                || (!t.isBefore(LocalTime.of(13, 30)) && !t.isAfter(LocalTime.of(15, 30))));
    }

    private static boolean tradingDay(LocalDate date, Set<LocalDate> holidays) {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    private static boolean nightSession(LocalDate date, Set<LocalDate> holidays) {
        if (!tradingDay(date, holidays)) return false;
        LocalDate next = date.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) next = next.plusDays(1);
        return !holidays.contains(next);
    }

    private Map<String, Object> refreshQuote(long storeId, String instrument) {
        synchronized (quoteLocks.computeIfAbsent(cacheKey(storeId, instrument), ignored -> new Object())) {
            LocalDateTime now = LocalDateTime.now(clock);
            boolean open = isTradingSession(now, holidays(storeId));
            Map<String, Object> previous = loadQuote(storeId, instrument);
            String key = cacheKey(storeId, instrument);
            if (!open && quotePrice(previous) != null && quotePrice(previous).signum() > 0) {
                Map<String, Object> closed = closedQuote(previous, now);
                if (!"CLOSED".equals(previous.get("market_status"))) saveQuote(closed);
                writeCache(key, closed);
                return closed;
            }
            Map<String, Object> cached = readCache(key);
            if (cached != null && !expired(cached) && (!open || !"CLOSED".equals(cached.get("market_status")))) return cached;
            if (open && previous != null && !expired(previous) && "OPEN".equals(previous.get("market_status"))) {
                writeCache(key, previous);
                return previous;
            }
            Map<String, Object> upstream = fetchSharedQuote(instrument);
            Map<String, Object> result;
            if (!acceptQuote(upstream, previous)) {
                String reason = quotePrice(upstream) == null ? String.valueOf(upstream.get("message")) : "行情偏离上一档超过10%，已拒绝异常报价";
                result = failedQuote(previous, reason, now);
                result.put("_retryAt", upstream.getOrDefault("_retryAt", clock.millis() + 120_000L));
                if (!open) {
                    result.put("market_status", "CLOSED");
                    result.put("message", "休市，暂无有效收盘参考价；" + reason + "；保留门店现价");
                }
            } else {
                boolean resumeOverride = takeResumeOverride(storeId, instrument);
                boolean frozen = !resumeOverride && previous != null && (number(previous.get("auto_frozen")) == 1
                        || deviates(quotePrice(upstream), quotePrice(previous), freezeThreshold(storeId)));
                result = new LinkedHashMap<>(frozen ? previous : upstream);
                result.put("auto_frozen", frozen ? 1 : 0);
                result.put("fetched_at", now.toString());
                if (frozen) {
                    result.put("market_status", "FROZEN");
                    result.put("message", "行情异常，已冻结上一档价格；请管理员核对后恢复自动定价");
                } else if (!open) {
                    result = closedQuote(result, now);
                } else if (staleQuote(result, now)) {
                    result.put("market_status", "ERROR");
                    result.put("message", "报价时间已过期，以下为旧价；保留门店现价，等待有效行情");
                } else if ("OPEN".equals(result.get("market_status"))) {
                    result.put("_fresh", true);
                }
            }
            result.put("store_id", storeId); result.put("instrument", instrument);
            result.putIfAbsent("auto_frozen", 0);
            saveQuote(result);
            writeCache(key, result);
            return result;
        }
    }

    /** A provider call is shared by every store and caller. Failure backoff is shared too. */
    private Map<String, Object> fetchSharedQuote(String instrument) {
        String key = "dajin:gold:upstream:" + instrument;
        synchronized (quoteLocks.computeIfAbsent(key, ignored -> new Object())) {
            Map<String, Object> cached = readCache(key);
            if (cached != null && !expired(cached)) return new LinkedHashMap<>(cached);
            Map<String, Object> result = null;
            List<String> failures = new ArrayList<>();
            if (environment.getProperty("JISU_APPKEY", "").isBlank()) failures.add("极速数据密钥未配置");
            else if (!tryTake("jisu:" + instrument, 90)) failures.add("极速数据今日调用额度已用尽");
            else try {
                result = fetchJisu(instrument);
                if (quotePrice(result) == null || quotePrice(result).signum() <= 0) { result = null; failures.add("极速数据报价无效"); }
            } catch (Exception ignored) { failures.add("极速数据请求失败，请检查密钥或服务状态"); }
            if (result == null) {
                if (environment.getProperty("TIANAPI_KEY", "").isBlank()) failures.add("天行数据密钥未配置");
                else if (!tryTake("tianapi", 60)) failures.add("天行数据今日调用额度已用尽");
                else try {
                    result = fetchTian(instrument);
                    if (quotePrice(result) == null || quotePrice(result).signum() <= 0) { result = null; failures.add("天行数据报价无效"); }
                } catch (Exception ignored) { failures.add("天行数据请求失败，请检查密钥或服务状态"); }
            }
            if (result == null) {
                int attempts = cached == null ? 1 : number(cached.get("_failures")) + 1;
                long delay = Math.min(CACHE_SECONDS, 120L * (1L << Math.min(attempts - 1, 3)));
                result = unavailable(String.join("；", failures));
                result.put("_failures", attempts);
                result.put("_retryAt", clock.millis() + delay * 1000);
            }
            result.put("fetched_at", LocalDateTime.now(clock).toString());
            writeCache(key, result);
            return new LinkedHashMap<>(result);
        }
    }

    private boolean staleQuote(Map<String, Object> quote, LocalDateTime now) {
        Object raw = quote.get("quote_time");
        try {
            LocalDateTime time = raw instanceof java.sql.Timestamp t ? t.toLocalDateTime()
                    : LocalDateTime.parse(String.valueOf(raw).replace('T', ' '), TIME);
            return time.isAfter(now.plusMinutes(5)) || Duration.between(time, now).toSeconds() > CACHE_SECONDS;
        } catch (Exception ignored) { return true; }
    }

    static String autoValidationMessage(Map<String, Object> item, BigDecimal base) {
        if (!"AUTO".equals(item.get("pricingMode")) || number(item.getOrDefault("status", 1)) != 1) return "";
        BigDecimal markup = decimalOrZero(item.get("markup")), deduction = decimalOrZero(item.get("recycleDeduction"));
        if (markup.signum() == 0 && deduction.signum() == 0) return "自动定价的卖价加价和回收扣减至少填写一项大于0的金额，确保卖价高于回收价";
        if (base == null || base.signum() <= 0) return "暂无有效基准行情，请先刷新行情或使用手动定价；现价保持不变";
        String rule = String.valueOf(item.getOrDefault("roundingRule", "NONE"));
        BigDecimal sale = calculate(base, coefficient(item), markup, deduction, rule, false);
        BigDecimal recycle = calculate(base, coefficient(item), markup, deduction, rule, true);
        return sale.compareTo(recycle) > 0 ? "" : "取整后卖价须高于回收价，请增加加价或扣减，或调整取整规则；现价保持不变";
    }

    private void validateAuto(long storeId, Map<String, Object> item) {
        if (!"AUTO".equals(pricingMode(item)) || number(item.getOrDefault("status", 1)) != 1) return;
        if (decimalOrZero(item.get("markup")).signum() == 0 && decimalOrZero(item.get("recycleDeduction")).signum() == 0)
            throw new BusinessException(400308, autoValidationMessage(item, null));
        Map<String, Object> quote = refreshQuote(storeId, baseInstrument(item));
        String message = autoValidationMessage(item, quotePrice(quote));
        if (!message.isBlank()) throw new BusinessException(400308, message);
        if ("ERROR".equals(quote.get("market_status"))) throw new BusinessException(400309, "行情异常，请先恢复有效行情后启用自动定价；现价保持不变");
    }

    private void repriceAuto(long storeId, Map<String, Map<String, Object>> quotes, boolean force) {
        List<Map<String, Object>> definitions = readDefinitions(storeId);
        Map<String, Map<String, Object>> latest = latestPrices(storeId);
        for (Map<String, Object> item : definitions) {
            if (number(item.get("status")) != 1 || !"AUTO".equals(pricingMode(item))) continue;
            Map<String, Object> quote = quotes.get(baseInstrument(item));
            if (quote == null || (!force && !Boolean.TRUE.equals(quote.get("_fresh"))) || !"OPEN".equals(String.valueOf(quote.get("market_status"))) || number(quote.get("auto_frozen")) == 1) continue;
            BigDecimal base = decimal(quote.get("price")); if (base == null || base.signum() <= 0) continue;
            BigDecimal sale = calculate(base, coefficient(item), decimalOrZero(item.get("markup")), decimalOrZero(item.get("recycleDeduction")), roundingRule(item), false);
            BigDecimal recycle = calculate(base, coefficient(item), decimalOrZero(item.get("markup")), decimalOrZero(item.get("recycleDeduction")), roundingRule(item), true);
            if (sale.compareTo(recycle) <= 0) { logOperation(storeId, 0, "AUTO_PRICE_BLOCKED", "金类=" + item.get("name") + ",卖价不高于回收价"); continue; }
            Map<String, Object> old = latest.get(String.valueOf(item.get("name")));
            insertPrice(storeId, item, recycle, sale, base, "AUTO", quote.get("quote_time"), String.valueOf(quote.get("source")), number(quote.get("auto_frozen")) == 1);
            logChange(storeId, 0, item, old, sale, recycle, String.valueOf(quote.get("source")), base);
            broadcastPrice(storeId, item, sale, recycle, String.valueOf(quote.get("source")), quote);
        }
    }

    private Map<String, Object> fetchJisu(String instrument) throws Exception {
        String appkey = environment.getProperty("JISU_APPKEY", "").trim();
        if (appkey.isBlank()) throw new IllegalStateException("missing JISU_APPKEY");
        String path = AG_TD.equals(instrument) ? "/silver/shgold" : "/gold/shgold";
        String body = get("https://api.jisuapi.com" + path + "?appkey=" + URLEncoder.encode(appkey, StandardCharsets.UTF_8));
        JsonNode root = mapper.readTree(body); if (!"0".equals(root.path("status").asText())) throw new IllegalStateException("jisu business failure");
        String target = AG_TD.equals(instrument) ? "Ag(T+D)" : "Au(T+D)";
        for (JsonNode row : root.path("result")) if (target.equals(row.path("type").asText())) {
            BigDecimal raw = new BigDecimal(row.path("price").asText());
            Map<String, Object> result = quote(instrument, AG_TD.equals(instrument) ? raw.divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP) : raw, raw, "极速", row.path("updatetime").asText(), "OPEN", AG_TD.equals(instrument) ? "元/千克" : "元/克");
            return result;
        }
        throw new IllegalStateException("missing instrument");
    }

    private Map<String, Object> fetchTian(String instrument) throws Exception {
        String key = environment.getProperty("TIANAPI_KEY", "").trim(); if (key.isBlank()) throw new IllegalStateException("missing TIANAPI_KEY");
        String kind = AG_TD.equals(instrument) ? "agTplusD" : "auTplusD";
        String body = get("https://apis.tianapi.com/gold/index?key=" + URLEncoder.encode(key, StandardCharsets.UTF_8) + "&kinds=" + kind);
        JsonNode root = mapper.readTree(body); if (root.path("code").asInt() != 200) throw new IllegalStateException("tian business failure");
        for (JsonNode row : root.path("result").path("list")) if (kind.equals(row.path("code").asText())) {
            BigDecimal raw = new BigDecimal(row.path("latestprice").asText());
            return quote(instrument, AG_TD.equals(instrument) ? raw.divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP) : raw, raw, "天行", row.path("updatetime").asText(), "on".equalsIgnoreCase(row.path("status").asText()) ? "OPEN" : "CLOSED", AG_TD.equals(instrument) ? "元/千克" : "元/克");
        }
        throw new IllegalStateException("missing instrument");
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IllegalStateException("http " + response.statusCode());
        return response.body();
    }

    private Map<String, Object> quote(String instrument, BigDecimal price, BigDecimal raw, String source, String quoteTime, String status, String unit) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("instrument", instrument); result.put("price", price); result.put("raw_price", raw); result.put("raw_unit", unit); result.put("quote_time", quoteTime); result.put("source", source); result.put("market_status", status); result.put("message", null); return result;
    }

    private boolean acceptQuote(Map<String, Object> quote, Map<String, Object> previous) {
        BigDecimal value = quotePrice(quote); if (value == null || value.signum() <= 0) return false;
        return previous == null || !deviates(value, decimal(previous.get("price")), TEN_PERCENT);
    }

    private boolean deviates(BigDecimal current, BigDecimal previous, BigDecimal threshold) {
        if (current == null || previous == null || previous.signum() <= 0) return false;
        return current.subtract(previous).abs().divide(previous, 8, RoundingMode.HALF_UP).compareTo(threshold) > 0;
    }

    private Map<String, Object> failedQuote(Map<String, Object> previous, String reason, LocalDateTime now) {
        if (previous == null || quotePrice(previous) == null) return unavailable("行情获取失败；" + reason + "；保留门店现价");
        Map<String, Object> result = new LinkedHashMap<>(previous); result.put("market_status", "ERROR"); result.put("message", "行情获取失败；" + reason + "；以下为 " + oldTime(previous, now) + " 旧价"); result.put("failure_reason", reason); return result;
    }

    private Map<String, Object> closedQuote(Map<String, Object> previous, LocalDateTime now) {
        if (previous == null || quotePrice(previous) == null) { Map<String, Object> result = unavailable("休市，暂无有效收盘参考价；保留门店现价"); result.put("market_status", "CLOSED"); return result; }
        Map<String, Object> result = new LinkedHashMap<>(previous); result.put("market_status", "CLOSED"); result.put("message", "休市，以下为 " + oldTime(previous, now) + " 收盘参考价"); return result;
    }

    private Map<String, Object> unavailable(String message) { Map<String, Object> result = new LinkedHashMap<>(); result.put("price", null); result.put("market_status", "ERROR"); result.put("message", message); return result; }
    private Map<String, Object> publicQuote(Map<String, Object> raw) { Map<String, Object> result = new LinkedHashMap<>(raw); result.remove("store_id"); result.remove("fetched_at"); result.remove("auto_frozen"); result.keySet().removeIf(key -> key.startsWith("_")); return result; }
    private String oldTime(Map<String, Object> row, LocalDateTime now) {
        Object raw = row.get("quote_time");
        if (raw instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().format(OLD_TIME);
        if (raw instanceof java.sql.Date date) return date.toLocalDate().atStartOfDay().format(OLD_TIME);
        String value = String.valueOf(raw == null ? "" : raw);
        try { return LocalDateTime.parse(value, TIME).format(OLD_TIME); } catch (Exception ignored) { }
        try { return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME).format(OLD_TIME); } catch (Exception ignored) { }
        try { return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay().format(OLD_TIME); } catch (Exception ignored) { }
        return now.format(OLD_TIME);
    }

    private void saveQuote(Map<String, Object> quote) {
        db.jdbc().update("insert into gold_market_quote(store_id,instrument_code,price,raw_price,raw_unit,quote_time,source,market_status,message,auto_frozen,fetched_at,update_time) values(:s,:i,:p,:raw,:unit,:qt,:source,:status,:message,:frozen,:fetched,now()) on duplicate key update price=values(price),raw_price=values(raw_price),raw_unit=values(raw_unit),quote_time=values(quote_time),source=values(source),market_status=values(market_status),message=values(message),auto_frozen=values(auto_frozen),fetched_at=values(fetched_at),update_time=now()", new MapSqlParameterSource().addValue("s", quote.get("store_id")).addValue("i", quote.get("instrument")).addValue("p", quote.get("price")).addValue("raw", quote.get("raw_price")).addValue("unit", quote.get("raw_unit")).addValue("qt", quote.get("quote_time")).addValue("source", quote.get("source")).addValue("status", quote.get("market_status")).addValue("message", quote.get("message")).addValue("frozen", quote.getOrDefault("auto_frozen", 0)).addValue("fetched", quote.get("fetched_at")));
    }

    private void insertPrice(long storeId, Map<String, Object> item, BigDecimal recycle, BigDecimal sale, BigDecimal base, String mode, Object quoteTime, String source, boolean frozen) {
        db.jdbc().update("insert into gold_price(store_id,price_type,price,sale_price,recycle_price,base_price,base_instrument,purity_coefficient,markup,recycle_deduction,rounding_rule,pricing_mode,source,quote_time,market_status,auto_frozen,date,operator_id,create_time) values(:s,:t,:price,:sale,:recycle,:base,:instrument,:purity,:markup,:deduction,:rounding,:mode,:source,:qt,:status,:frozen,curdate(),:uid,now())", new MapSqlParameterSource().addValue("s", storeId).addValue("t", item.get("name")).addValue("price", isRecycleType(item) ? recycle : sale).addValue("sale", sale).addValue("recycle", recycle).addValue("base", base).addValue("instrument", baseInstrument(item)).addValue("purity", coefficient(item)).addValue("markup", decimalOrZero(item.get("markup"))).addValue("deduction", decimalOrZero(item.get("recycleDeduction"))).addValue("rounding", roundingRule(item)).addValue("mode", mode).addValue("source", source).addValue("qt", quoteTime).addValue("status", frozen ? "FROZEN" : "OPEN").addValue("frozen", frozen ? 1 : 0).addValue("uid", 0));
    }

    private void broadcastPrice(long storeId, Map<String, Object> item, BigDecimal sale, BigDecimal recycle, String source, Map<String, Object> quote) {
        Map<String, Object> event = new LinkedHashMap<>(); event.put("storeId", storeId); event.put("priceType", item.get("name")); event.put("price", isRecycleType(item) ? recycle : sale); event.put("salePrice", sale); event.put("recyclePrice", recycle); event.put("source", source); event.put("quoteTime", quote == null ? null : quote.get("quote_time")); event.put("marketStatus", quote == null ? null : quote.get("market_status")); event.put("pricingMode", pricingMode(item)); ws.broadcast("GOLD_PRICE_UPDATED", event);
    }

    private void logChange(long storeId, long userId, Map<String, Object> item, Map<String, Object> old, BigDecimal sale, BigDecimal recycle, String source) {
        logChange(storeId, userId, item, old, sale, recycle, source, old == null ? null : decimal(old.get("base_price")));
    }

    private void logChange(long storeId, long userId, Map<String, Object> item, Map<String, Object> old, BigDecimal sale, BigDecimal recycle, String source, BigDecimal basePrice) {
        db.jdbc().update("insert into gold_price_change_log(store_id,operator_id,price_type,base_instrument,base_price,purity_coefficient,markup,recycle_deduction,old_sale_price,old_recycle_price,new_sale_price,new_recycle_price,source,detail,create_time) values(:s,:uid,:t,:instrument,:base,:purity,:markup,:deduction,:oldSale,:oldRecycle,:sale,:recycle,:source,:detail,now())", new MapSqlParameterSource().addValue("s", storeId).addValue("uid", userId).addValue("t", item.get("name")).addValue("instrument", baseInstrument(item)).addValue("base", basePrice).addValue("purity", coefficient(item)).addValue("markup", decimalOrZero(item.get("markup"))).addValue("deduction", decimalOrZero(item.get("recycleDeduction"))).addValue("oldSale", old == null ? null : old.get("sale_price")).addValue("oldRecycle", old == null ? null : old.get("recycle_price")).addValue("sale", sale).addValue("recycle", recycle).addValue("source", source).addValue("detail", "pricingMode=" + pricingMode(item)));
    }

    private void logOperation(long storeId, long userId, String action, String content) { db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,'GOLD_PRICE',:a,:c,'',now())", Map.of("s", storeId, "u", userId, "a", action, "c", content)); }

    private Map<String, Map<String, Object>> latestPrices(long storeId) { Map<String, Map<String, Object>> result = new HashMap<>(); for (Map<String, Object> row : db.list("select gp.* from gold_price gp join (select price_type,max(price_id) price_id from gold_price where store_id=:s group by price_type) x on x.price_id=gp.price_id where gp.store_id=:s", Map.of("s", storeId))) result.put(String.valueOf(row.get("price_type")), row); return result; }
    private Map<String, Map<String, Object>> quoteRows(long storeId) { Map<String, Map<String, Object>> result = new HashMap<>(); for (Map<String, Object> row : db.list("select * from gold_market_quote where store_id=:s", Map.of("s", storeId))) result.put(String.valueOf(row.get("instrument_code")), row); return result; }
    private Map<String, Object> loadQuote(long storeId, String instrument) { List<Map<String, Object>> rows = db.list("select * from gold_market_quote where store_id=:s and instrument_code=:i limit 1", Map.of("s", storeId, "i", instrument)); if (rows.isEmpty()) return null; Map<String, Object> row = rows.get(0); row.put("instrument", instrument); return row; }
    private BigDecimal peerRecycle(Map<String, Object> item, Map<String, Object> fallback, Map<String, Map<String, Object>> latest) {
        Object peerValue = fallback.get(baseInstrument(item));
        if (!(peerValue instanceof Map<?, ?>)) return null;
        @SuppressWarnings("unchecked") Map<String, Object> peer = (Map<String, Object>) peerValue;
        Map<String, Object> row = latest.get(String.valueOf(peer.get("name")));
        return row == null ? null : decimal(row.get("price"));
    }
    private String cacheKey(long storeId, String instrument) { return "dajin:gold:quote:" + storeId + ":" + instrument; }
    private boolean expired(Map<String, Object> row) { if (row.get("_retryAt") instanceof Number retry) return clock.millis() >= retry.longValue(); Object value = row.get("fetched_at"); try { LocalDateTime at = value instanceof java.sql.Timestamp timestamp ? timestamp.toLocalDateTime() : LocalDateTime.parse(String.valueOf(value)); return Duration.between(at, LocalDateTime.now(clock)).getSeconds() >= CACHE_SECONDS; } catch (Exception e) { return true; } }
    private Map<String, Object> readCache(String key) { try { String raw = redis.opsForValue().get(key); return raw == null ? MEMORY_CACHE.get(key) : mapper.readValue(raw, new TypeReference<>() {}); } catch (Exception e) { return MEMORY_CACHE.get(key); } }
    private void writeCache(String key, Map<String, Object> row) {
        Map<String, Object> cached = new LinkedHashMap<>(row);
        cached.remove("_fresh");
        MEMORY_CACHE.put(key, cached);
        try { redis.opsForValue().set(key, mapper.writeValueAsString(cached), Duration.ofSeconds(CACHE_SECONDS)); } catch (Exception ignored) { }
    }
    private BigDecimal freezeThreshold(long storeId) {
        try {
            BigDecimal value = decimal(db.jdbc().queryForObject("select config_value from sys_config where store_id=:s and config_key='gold_market_freeze_threshold' and enabled=1", Map.of("s", storeId), String.class));
            if (value != null && value.signum() >= 0 && value.compareTo(BigDecimal.ONE) <= 0) return value;
        } catch (Exception ignored) { }
        return FIVE_PERCENT;
    }
    private boolean takeResumeOverride(long storeId, String instrument) {
        return MEMORY_RESUME_OVERRIDES.remove(resumeKey(storeId, instrument)) != null;
    }
    private String resumeKey(long storeId, String instrument) { return "dajin:gold:resume:" + storeId + ":" + instrument; }
    private boolean tryTake(String name, long limit) { String key = "dajin:gold:limit:" + name + ":" + LocalDate.now(clock); try { Long value = redis.opsForValue().increment(key); if (value != null && value == 1) redis.expire(key, Duration.ofDays(2)); if (value != null && value > limit) { redis.opsForValue().decrement(key); return false; } return true; } catch (Exception e) { long value = MEMORY_COUNTER.merge(key, 1L, Long::sum); if (value > limit) { MEMORY_COUNTER.computeIfPresent(key, (k, v) -> Math.max(0, v - 1)); return false; } return true; } }
    private Set<LocalDate> holidays(long storeId) { String raw; try { raw = db.jdbc().queryForObject("select config_value from sys_config where store_id=:s and config_key='gold_market_holidays' and enabled=1", Map.of("s", storeId), String.class); } catch (Exception e) { raw = "[]"; } Set<LocalDate> result = GoldTradingCalendar.holidays(); try { JsonNode node = mapper.readTree(raw); if (node.isArray()) node.forEach(n -> { try { result.add(LocalDate.parse(n.asText())); } catch (Exception ignored) { } }); } catch (Exception ignored) { } return result; }
    private void clearFrozen(long storeId, String instrument) {
        db.jdbc().update("update gold_market_quote set auto_frozen=0,message=null,fetched_at=null,update_time=now() where store_id=:s and instrument_code=:i", Map.of("s", storeId, "i", instrument));
        MEMORY_CACHE.remove(cacheKey(storeId, instrument));
        MEMORY_RESUME_OVERRIDES.put(resumeKey(storeId, instrument), Boolean.TRUE);
        try { redis.delete(cacheKey(storeId, instrument)); } catch (Exception ignored) { }
    }

    private List<Map<String, Object>> readDefinitions(long storeId) { String raw; try { raw = db.jdbc().queryForObject("select config_value from sys_config where store_id=:s and config_key='gold_metal_types' and enabled=1", Map.of("s", storeId), String.class); } catch (Exception e) { raw = "[]"; } try { List<Map<String, Object>> result = mapper.readValue(raw, new TypeReference<>() {}); ensureDefaults(result); ensureSilverRecycle(result); return result; } catch (Exception e) { return new ArrayList<>(); } }
    private void persistDefinitions(long storeId, List<Map<String, Object>> items) { try { db.jdbc().update("update sys_config set config_value=:v,update_time=now() where store_id=:s and config_key='gold_metal_types'", Map.of("s", storeId, "v", mapper.writeValueAsString(compact(items)))); } catch (Exception e) { throw new BusinessException(500301, "贵金属类型保存失败"); } }
    private List<Map<String, Object>> compact(List<Map<String, Object>> items) { List<Map<String, Object>> result = new ArrayList<>(); for (Map<String, Object> source : items) { Map<String, Object> item = new LinkedHashMap<>(); for (String key : List.of("name", "code", "purity", "purityCoefficient", "price", "sort", "status", "pricingMode", "baseInstrument", "markup", "recycleDeduction", "roundingRule")) if (source.containsKey(key)) item.put(key, source.get(key)); result.add(item); } return result; }
    private void ensureDefaults(List<Map<String, Object>> items) { for (Map<String, Object> item : items) { String code = String.valueOf(item.getOrDefault("code", "")); item.putIfAbsent("pricingMode", "MANUAL"); item.putIfAbsent("baseInstrument", code.toUpperCase(Locale.ROOT).contains("SILVER") ? AG_TD : AU_TD); item.putIfAbsent("purityCoefficient", coefficient(item)); item.putIfAbsent("markup", BigDecimal.ZERO); item.putIfAbsent("recycleDeduction", BigDecimal.ZERO); item.putIfAbsent("roundingRule", "NONE"); } }
    private void ensureSilverRecycle(List<Map<String, Object>> items) { if (find(items, "SILVER_RECYCLE") != null || find(items, "银回收价") != null) return; Map<String, Object> item = new LinkedHashMap<>(); item.put("name", "银回收价"); item.put("code", "SILVER_RECYCLE"); item.put("purity", 99.9); item.put("purityCoefficient", new BigDecimal("0.999")); item.put("price", BigDecimal.ZERO); item.put("sort", items.size() + 1); item.put("status", 1); item.put("pricingMode", "MANUAL"); item.put("baseInstrument", AG_TD); item.put("markup", BigDecimal.ZERO); item.put("recycleDeduction", BigDecimal.ZERO); item.put("roundingRule", "NONE"); items.add(item); }
    private void applyDefinitionUpdate(Map<String, Object> item, Map<String, Object> q) {
        if (q.containsKey("name")) item.put("name", requiredText(q, "name", "类型名称"));
        if (q.containsKey("code")) item.put("code", requiredText(q, "code", "类型编码"));
        if (q.containsKey("purity")) {
            BigDecimal purity = decimalRequired(q.get("purity"), "成色");
            if (purity.signum() <= 0 || purity.compareTo(BigDecimal.valueOf(100)) > 0) throw new BusinessException(400301, "成色必须在0到100之间");
            item.put("purity", purity);
            if (!q.containsKey("purityCoefficient")) item.put("purityCoefficient", purity.movePointLeft(2));
        }
        if (q.containsKey("purityCoefficient")) {
            BigDecimal c = decimalRequired(q.get("purityCoefficient"), "成色系数");
            if (c.signum() <= 0 || c.compareTo(BigDecimal.ONE) > 0) throw new BusinessException(400301, "成色系数必须在0到1之间");
            item.put("purityCoefficient", c);
        }
        if (q.containsKey("pricingMode")) {
            String mode = String.valueOf(q.get("pricingMode")).toUpperCase(Locale.ROOT);
            if (!Set.of("AUTO", "MANUAL").contains(mode)) throw new BusinessException(400304, "定价方式不合法");
            item.put("pricingMode", mode);
        }
        if (q.containsKey("baseInstrument")) {
            String base = String.valueOf(q.get("baseInstrument"));
            if (!Set.of(AU_TD, AG_TD).contains(base)) throw new BusinessException(400305, "基准品种不合法");
            item.put("baseInstrument", base);
        }
        for (String key : List.of("markup", "recycleDeduction")) if (q.containsKey(key)) {
            BigDecimal value = decimalRequired(q.get(key), key);
            if (value.signum() < 0) throw new BusinessException(400306, "加价和回收扣减不能小于0");
            item.put(key, value);
        }
        if (q.containsKey("roundingRule")) {
            String rule = String.valueOf(q.get("roundingRule"));
            if (!Set.of("NONE", "TENTH", "YUAN", "TAIL_8", "TAIL_9").contains(rule)) throw new BusinessException(400307, "取整规则不合法");
            item.put("roundingRule", rule);
        }
        if (q.containsKey("sort")) item.put("sort", decimalRequired(q.get("sort"), "排序").intValueExact());
        if (q.containsKey("status")) item.put("status", number(q.get("status")) == 0 ? 0 : 1);
    }
    private String baseInstrument(Map<String, Object> item) { String base = String.valueOf(item.getOrDefault("baseInstrument", "")); if (Set.of(AU_TD, AG_TD).contains(base)) return base; return String.valueOf(item.getOrDefault("code", "")).toUpperCase(Locale.ROOT).contains("SILVER") ? AG_TD : AU_TD; }
    private String pricingMode(Map<String, Object> item) { return "AUTO".equalsIgnoreCase(String.valueOf(item.getOrDefault("pricingMode", "MANUAL"))) ? "AUTO" : "MANUAL"; }
    private String roundingRule(Map<String, Object> item) { return String.valueOf(item.getOrDefault("roundingRule", "NONE")); }
    private boolean isRecycleType(Map<String, Object> item) { String value = (String.valueOf(item.getOrDefault("code", "")) + String.valueOf(item.getOrDefault("name", ""))).toUpperCase(Locale.ROOT); return value.contains("RECYCLE") || String.valueOf(item.getOrDefault("name", "")).contains("回收"); }
    private Map<String, Object> find(List<Map<String, Object>> rows, String id) { return rows.stream().filter(x -> id.equals(String.valueOf(x.get("name"))) || id.equals(String.valueOf(x.get("code"))) || id.equals(String.valueOf(x.get("type_id")))).findFirst().orElse(null); }
    private String requiredText(Map<String, Object> q, String key, String label) { String value = String.valueOf(q.getOrDefault(key, "")).trim(); if (value.isBlank() || value.length() > 50) throw new BusinessException(400300, label + "不能为空且不能超过50个字符"); return value; }
    private BigDecimal decimalRequired(Object value, String label) { BigDecimal result = decimal(value); if (result == null) throw new BusinessException(400303, label + "格式错误"); return result; }
    private static BigDecimal decimal(Object value) { if (value == null || "null".equalsIgnoreCase(String.valueOf(value))) return null; try { return new BigDecimal(String.valueOf(value)); } catch (Exception e) { return null; } }
    private static BigDecimal decimalOrZero(Object value) { BigDecimal result = decimal(value); return result == null ? BigDecimal.ZERO : result; }
    private static BigDecimal legacyOr(Object value) { return decimal(value); }
    private static BigDecimal quotePrice(Map<String, Object> row) { return decimal(row == null ? null : row.get("price")); }
    private static int number(Object value) { try { return Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return 0; } }
}
