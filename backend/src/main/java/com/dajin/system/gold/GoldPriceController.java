package com.dajin.system.gold;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.config.RequirePermission;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/gold-price")
public class GoldPriceController {
    private final GoldMarketService market;
    private final com.dajin.system.common.DbSupport db;

    public GoldPriceController(GoldMarketService market, com.dajin.system.common.DbSupport db) { this.market = market; this.db = db; }

    @GetMapping("/current") @RequirePermission("gold:view")
    public ApiResponse<?> current(HttpServletRequest request) { return ApiResponse.ok(market.current(db.store(request), false)); }
    @GetMapping("/types") @RequirePermission("gold:view")
    public ApiResponse<?> types(HttpServletRequest request) { return ApiResponse.ok(market.current(db.store(request), false)); }
    @GetMapping("/types/all") @RequirePermission("gold:manage")
    public ApiResponse<?> allTypes(HttpServletRequest request) { return ApiResponse.ok(market.current(db.store(request), true)); }

    public record Req(@NotBlank String priceType, @NotNull @DecimalMin("0") BigDecimal price) {}

    @PostMapping @RequirePermission("gold:manage")
    public ApiResponse<?> save(@Valid @RequestBody Req body, HttpServletRequest request) { return ApiResponse.ok(market.saveManual(db.store(request), userId(request), body.priceType(), body.price())); }
    @PostMapping("/types") @RequirePermission("gold:manage")
    public ApiResponse<?> createType(@RequestBody Map<String, Object> body, HttpServletRequest request) { return ApiResponse.ok(market.createType(db.store(request), userId(request), body)); }
    @PutMapping("/types/{id}") @RequirePermission("gold:manage")
    public ApiResponse<?> updateType(@PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest request) { return ApiResponse.ok(market.updateType(db.store(request), userId(request), id, body)); }
    @DeleteMapping("/types/{id}") @RequirePermission("gold:manage")
    public ApiResponse<?> deleteType(@PathVariable String id, HttpServletRequest request) { market.deleteType(db.store(request), userId(request), id); return ApiResponse.ok(); }

    @GetMapping("/history") @RequirePermission("gold:view")
    public ApiResponse<?> history(@RequestParam(defaultValue = "30") int days, HttpServletRequest request) {
        if (days < 1 || days > 365) throw new BusinessException(400308, "历史天数必须在1到365之间");
        return ApiResponse.ok(db.list("select * from gold_price where store_id=:s and date>=date_sub(curdate(),interval :d day) order by date desc,price_id desc", Map.of("s", db.store(request), "d", days)));
    }
    @GetMapping("/spot") @RequirePermission("gold:view")
    public ApiResponse<?> spot(HttpServletRequest request) { return ApiResponse.ok(market.spot(db.store(request), GoldMarketService.AU_TD)); }
    @GetMapping("/spot/silver") @RequirePermission("gold:view")
    public ApiResponse<?> silverSpot(HttpServletRequest request) { return ApiResponse.ok(market.spot(db.store(request), GoldMarketService.AG_TD)); }
    @GetMapping("/logs") @RequirePermission("gold:view")
    public ApiResponse<?> logs(@RequestParam(defaultValue = "100") int limit, HttpServletRequest request) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return ApiResponse.ok(db.list("select * from gold_price_change_log where store_id=:s order by log_id desc limit " + safeLimit, Map.of("s", db.store(request))));
    }

    static BigDecimal silverPricePerGram(String yuanPerKilogram) { return new BigDecimal(yuanPerKilogram.trim()).divide(BigDecimal.valueOf(1000), 3, java.math.RoundingMode.HALF_UP); }
    static Map<String, Object> unavailableSpot(String message) { Map<String, Object> result = new LinkedHashMap<>(); result.put("price", null); result.put("message", message); return result; }
    static void ensureSilverRecycleType(List<Map<String, Object>> types) {
        boolean exists = types.stream().anyMatch(item -> "银回收价".equals(String.valueOf(item.get("name"))) || "SILVER_RECYCLE".equalsIgnoreCase(String.valueOf(item.get("code"))));
        if (exists) return;
        int nextSort = types.stream().mapToInt(item -> { try { return Integer.parseInt(String.valueOf(item.getOrDefault("sort", 0))); } catch (Exception ignored) { return 0; } }).max().orElse(0) + 1;
        Map<String, Object> item = new LinkedHashMap<>(); item.put("name", "银回收价"); item.put("code", "SILVER_RECYCLE"); item.put("purity", new BigDecimal("99.9")); item.put("price", BigDecimal.ZERO); item.put("sort", nextSort); item.put("status", 1); types.add(item);
    }
    static List<Map<String, Object>> compactTypes(List<Map<String, Object>> types) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : types) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", source.getOrDefault("name", source.get("type_name"))); item.put("code", source.getOrDefault("code", source.get("type_code")));
            item.put("purity", source.getOrDefault("purity", 100)); item.put("price", source.getOrDefault("price", source.getOrDefault("current_price", 0)));
            item.put("sort", source.getOrDefault("sort", result.size() + 1)); item.put("status", source.getOrDefault("status", 1));
            for (String key : List.of("purityCoefficient", "pricingMode", "baseInstrument", "markup", "recycleDeduction", "roundingRule")) if (source.containsKey(key)) item.put(key, source.get(key));
            result.add(item);
        }
        return result;
    }
    private long userId(HttpServletRequest request) { io.jsonwebtoken.Claims claims = (io.jsonwebtoken.Claims) request.getAttribute("claims"); return claims == null ? 0L : Long.parseLong(claims.getSubject()); }
}
