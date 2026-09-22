package com.dajin.system.gold;

import com.dajin.system.common.*;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import javax.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/gold-price")
public class GoldPriceController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final ObjectMapper mapper;

    public GoldPriceController(DbSupport db, SyncWebSocketHandler ws, ObjectMapper mapper) { this.db = db; this.ws = ws; this.mapper = mapper; }

    @GetMapping("/current")
    public ApiResponse<?> current(HttpServletRequest r) { return ApiResponse.ok(loadTypes(db.store(r), false)); }
    @GetMapping("/types")
    public ApiResponse<?> types(HttpServletRequest r) { return ApiResponse.ok(loadTypes(db.store(r), false)); }
    @GetMapping("/types/all") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> allTypes(HttpServletRequest r) { return ApiResponse.ok(loadTypes(db.store(r), true)); }

    public record Req(@NotBlank String priceType, @NotNull @DecimalMin("0") BigDecimal price) {}
    @PostMapping @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> save(@Valid @RequestBody Req q, HttpServletRequest r) {
        long store = db.store(r); long uid = userId(r); List<Map<String,Object>> types = loadTypes(store, true); Map<String,Object> type = findType(types, q.priceType());
        if (type == null) { type = new LinkedHashMap<>(); type.put("name", q.priceType()); type.put("code", q.priceType()); type.put("purity", 100); type.put("price", q.price()); type.put("sort", types.size() + 1); type.put("status", 1); types.add(type); saveTypes(store, types); }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", store).addValue("t", q.priceType()).addValue("p", q.price()).addValue("uid", uid);
        db.jdbc().update("insert into gold_price(store_id,price_type,price,date,operator_id,create_time) values(:s,:t,:p,curdate(),:uid,now())", p);
        type.put("price", q.price()); saveTypes(store, types); log(store, uid, "UPDATE_PRICE", q.priceType() + "=" + q.price());
        ws.broadcast("GOLD_PRICE_UPDATED", Map.of("storeId", store, "priceType", q.priceType(), "price", q.price())); return ApiResponse.ok();
    }

    @PostMapping("/types") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> createType(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        long store = db.store(r); String name = text(q, "name", "类型名称"); String code = text(q, "code", "类型编码");
        BigDecimal purity = decimal(q.getOrDefault("purity", 100), "成色"); BigDecimal price = decimal(q.getOrDefault("price", 0), "价格");
        if (purity.signum() <= 0 || purity.compareTo(BigDecimal.valueOf(100)) > 0) throw new BusinessException(400301, "成色必须在0到100之间");
        if (price.signum() < 0) throw new BusinessException(400302, "价格不能小于0");
        List<Map<String,Object>> types = loadTypes(store, true); if (findType(types, name) != null || findType(types, code) != null) throw new BusinessException(409301, "类型名称或编码已存在");
        Map<String,Object> item = new LinkedHashMap<>(); item.put("name", name); item.put("code", code); item.put("purity", purity); item.put("price", price); item.put("sort", q.getOrDefault("sort", types.size() + 1)); item.put("status", 1); types.add(item); saveTypes(store, types);
         log(store, userId(r), "CREATE_TYPE", name + "(" + code + ")"); ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", store, "action", "CREATE", "code", code)); return ApiResponse.ok(item);
    }

    @PutMapping("/types/{id}") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> updateType(@PathVariable String id, @RequestBody Map<String,Object> q, HttpServletRequest r) {
        long store = db.store(r); List<Map<String,Object>> types = loadTypes(store, true); Map<String,Object> item = findType(types, id); if (item == null) throw new BusinessException(404301, "贵金属类型不存在");
        String nextName = q.get("name") == null ? String.valueOf(item.get("name")) : text(q, "name", "类型名称");
        String nextCode = q.get("code") == null ? String.valueOf(item.get("code")) : text(q, "code", "类型编码");
        boolean duplicate = types.stream().anyMatch(other -> other != item && (nextName.equals(String.valueOf(other.get("name"))) || nextName.equals(String.valueOf(other.get("code"))) || nextCode.equals(String.valueOf(other.get("name"))) || nextCode.equals(String.valueOf(other.get("code")))));
        if (duplicate) throw new BusinessException(409301, "类型名称或编码已存在");
        if (q.get("name") != null) item.put("name", text(q, "name", "类型名称")); if (q.get("code") != null) item.put("code", text(q, "code", "类型编码"));
        if (q.get("purity") != null) { BigDecimal p = decimal(q.get("purity"), "成色"); if (p.signum() <= 0 || p.compareTo(BigDecimal.valueOf(100)) > 0) throw new BusinessException(400301, "成色必须在0到100之间"); item.put("purity", p); }
        if (q.get("price") != null) { BigDecimal p = decimal(q.get("price"), "价格"); if (p.signum() < 0) throw new BusinessException(400302, "价格不能小于0"); item.put("price", p); }
         if (q.get("sort") != null) item.put("sort", q.get("sort")); if (q.get("status") != null) item.put("status", q.get("status")); saveTypes(store, types); String action=q.get("status")==null?"UPDATE_TYPE":(Integer.parseInt(String.valueOf(q.get("status")))==1?"ENABLE_TYPE":"DISABLE_TYPE"); log(store, userId(r), action, "id=" + id); ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", store, "action", action, "typeId", id)); return ApiResponse.ok(item);
    }

    @DeleteMapping("/types/{id}") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> deleteType(@PathVariable String id, HttpServletRequest r) {
        long store = db.store(r); List<Map<String,Object>> types = loadTypes(store, true); Map<String,Object> item = findType(types, id); if (item == null) throw new BusinessException(404301, "贵金属类型不存在");
        String name = String.valueOf(item.get("name")), code = String.valueOf(item.get("code")); int history = db.jdbc().queryForObject("select count(*) from gold_price where store_id=:s and (price_type=:n or price_type=:c)", Map.of("s", store, "n", name, "c", code), Integer.class); int goods = db.jdbc().queryForObject("select count(*) from goods where store_id=:s and (gold_type=:n or gold_type=:c)", Map.of("s", store, "n", name, "c", code), Integer.class);
         if (history > 0 || goods > 0) { item.put("status", 0); saveTypes(store, types); log(store, userId(r), "DISABLE_TYPE", id); ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", store, "action", "DISABLE_TYPE", "typeId", id)); return ApiResponse.ok(Map.of("disabled", true)); }
         types.remove(item); saveTypes(store, types); log(store, userId(r), "DELETE_TYPE", id); ws.broadcast("GOLD_TYPES_UPDATED", Map.of("storeId", store, "action", "DELETE", "typeId", id)); return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/history") public ApiResponse<?> history(@RequestParam(defaultValue="30") int days, HttpServletRequest r) { return ApiResponse.ok(db.list("select * from gold_price where store_id=:s and date>=date_sub(curdate(),interval :d day) order by date desc,price_id desc", Map.of("s", db.store(r), "d", days))); }

    private static final Object SPOT_LOCK = new Object();
    private static volatile Map<String, Object> spotCache;
    private static volatile long spotCacheAt;
    private static volatile Map<String, Object> silverSpotCache;
    private static volatile long silverSpotCacheAt;

    /** 实时行情参考：仅作店长填价参考，不直接写入卖价。 */
    @GetMapping("/spot") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> spot() {
        synchronized (SPOT_LOCK) {
            if (spotCache != null && System.currentTimeMillis() - spotCacheAt < 300_000L) return ApiResponse.ok(spotCache);
        }
        Map<String, Object> spot = fetchSpot();
        if (spot == null) return ApiResponse.ok(unavailableSpot("行情获取失败，请手动填写金价"));
        synchronized (SPOT_LOCK) { spotCache = spot; spotCacheAt = System.currentTimeMillis(); }
        return ApiResponse.ok(spot);
    }

    /** 国内白银延期 Ag(T+D)，原始单位为元/千克，接口统一返回元/克。 */
    @GetMapping("/spot/silver") @RequireRoles({"ADMIN", "MANAGER"})
    public ApiResponse<?> silverSpot() {
        synchronized (SPOT_LOCK) {
            if (silverSpotCache != null && System.currentTimeMillis() - silverSpotCacheAt < 300_000L) return ApiResponse.ok(silverSpotCache);
        }
        Map<String, Object> spot;
        try { spot = fetchSilverSina(); }
        catch (Exception e) { return ApiResponse.ok(unavailableSpot("白银行情获取失败，请保留门店手动价格")); }
        synchronized (SPOT_LOCK) { silverSpotCache = spot; silverSpotCacheAt = System.currentTimeMillis(); }
        return ApiResponse.ok(spot);
    }

    private Map<String, Object> fetchSpot() {
        try { return fetchJijinhao(); } catch (Exception ignored) { }
        try { return fetchSina(); } catch (Exception ignored) { }
        return null;
    }

    /** 金投网：上海黄金交易所 Au9999 实时价。 */
    private Map<String, Object> fetchJijinhao() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://api.jijinhao.com/quoteCenter/realTime/getList?codes=JO_9223"))
                .timeout(java.time.Duration.ofSeconds(5)).header("User-Agent", "Mozilla/5.0").header("Referer", "https://www.jijinhao.com/").GET().build();
        String body = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body();
        com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(body).path("data").path("JO_9223");
        java.math.BigDecimal price = new java.math.BigDecimal(node.path("price").asText("0"));
        if (price.signum() <= 0 || price.doubleValue() > 5000) throw new IllegalStateException("行情价格异常");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", price); result.put("name", node.path("name").asText("黄金9999"));
        result.put("time", node.path("time").asText("")); result.put("source", "上海黄金交易所Au9999");
        return result;
    }

    /** 回退源：新浪财经黄金延期(AuTD)。 */
    private Map<String, Object> fetchSina() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://hq.sinajs.cn/list=gds_AUTD"))
                .timeout(java.time.Duration.ofSeconds(5)).header("User-Agent", "Mozilla/5.0").header("Referer", "https://finance.sina.com.cn").GET().build();
        byte[] raw = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
        String body = new String(raw, java.nio.charset.Charset.forName("GBK"));
        String[] parts = body.substring(body.indexOf('"') + 1, body.lastIndexOf('"')).split(",");
        java.math.BigDecimal price = parts.length > 3 ? new java.math.BigDecimal(parts[3].trim()) : java.math.BigDecimal.ZERO;
        if (price.doubleValue() < 100 || price.doubleValue() > 5000) throw new IllegalStateException("行情价格异常");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", price); result.put("name", "黄金延期AuTD");
        result.put("time", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        result.put("source", "新浪财经AuTD");
        return result;
    }

    private Map<String, Object> fetchSilverSina() throws Exception {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://hq.sinajs.cn/list=gds_AGTD"))
                .timeout(java.time.Duration.ofSeconds(5)).header("User-Agent", "Mozilla/5.0").header("Referer", "https://finance.sina.com.cn").GET().build();
        byte[] raw = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray()).body();
        String body = new String(raw, java.nio.charset.Charset.forName("GBK"));
        if (!body.contains("\"") || body.lastIndexOf('"') <= body.indexOf('"')) throw new IllegalStateException("白银行情响应异常");
        String[] parts = body.substring(body.indexOf('"') + 1, body.lastIndexOf('"')).split(",");
        if (parts.length < 14) throw new IllegalStateException("白银行情字段不足");
        BigDecimal rawPrice = new BigDecimal(parts[0].trim());
        BigDecimal price = silverPricePerGram(parts[0]);
        if (price.compareTo(new BigDecimal("0.1")) < 0 || price.compareTo(new BigDecimal("1000")) > 0) throw new IllegalStateException("白银行情价格异常");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", price); result.put("rawPrice", rawPrice); result.put("rawUnit", "元/千克"); result.put("unit", "元/克");
        result.put("name", parts[13].isBlank() ? "白银延期Ag(T+D)" : parts[13]);
        result.put("time", parts[12] + " " + parts[6]); result.put("source", "新浪财经·上海黄金交易所Ag(T+D)");
        return result;
    }

    static BigDecimal silverPricePerGram(String yuanPerKilogram) {
        return new BigDecimal(yuanPerKilogram.trim()).divide(BigDecimal.valueOf(1000), 3, java.math.RoundingMode.HALF_UP);
    }

    static Map<String, Object> unavailableSpot(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("price", null);
        result.put("message", message);
        return result;
    }

    private List<Map<String,Object>> loadTypes(long store, boolean includeDisabled) {
        String raw; try { Map<String,Object> row = db.one("select config_value from sys_config where store_id=:s and config_key='gold_metal_types' and enabled=1", Map.of("s", store)); raw = String.valueOf(row.get("config_value")); } catch (Exception e) { raw = "[]"; }
        List<Map<String,Object>> result; try { result = mapper.readValue(raw, new TypeReference<List<Map<String,Object>>>() {}); } catch (Exception e) { result = new ArrayList<>(); }
        ensureSilverRecycleType(result);
        List<Map<String,Object>> latest = db.list("select gp.price_type,gp.price from gold_price gp join (select price_type,max(price_id) price_id from gold_price where store_id=:s and date=curdate() group by price_type) x on x.price_id=gp.price_id where gp.store_id=:s", Map.of("s", store));
        int index = 0; for (Map<String,Object> item : result) { item.putIfAbsent("sort", ++index); item.putIfAbsent("status", 1); item.putIfAbsent("code", item.getOrDefault("name", "")); item.putIfAbsent("purity", 100); for (Map<String,Object> row : latest) if (String.valueOf(row.get("price_type")).equals(String.valueOf(item.get("name"))) || String.valueOf(row.get("price_type")).equals(String.valueOf(item.get("code")))) item.put("price", row.get("price")); item.put("type_id", item.get("code")); item.put("type_name", item.get("name")); item.put("type_code", item.get("code")); item.put("price_type", item.get("name")); }
        result.sort(Comparator.comparingInt(x -> Integer.parseInt(String.valueOf(x.getOrDefault("sort", 0))))); if (!includeDisabled) result.removeIf(x -> Integer.parseInt(String.valueOf(x.getOrDefault("status", 1))) != 1); return result;
    }
    private Map<String,Object> findType(List<Map<String,Object>> types, String id) { return types.stream().filter(x -> id.equals(String.valueOf(x.get("type_id"))) || id.equals(String.valueOf(x.get("name"))) || id.equals(String.valueOf(x.get("code")))).findFirst().orElse(null); }
    /**
     * Persist only the editable type definition. loadTypes adds display aliases
     * (type_id/type_name/type_code/price_type) for the UI; writing those aliases
     * back made the JSON grow on every save and overflowed older varchar(500)
     * sys_config columns.
     */
    private void saveTypes(long store, List<Map<String,Object>> types) {
        try {
            String json = mapper.writeValueAsString(compactTypes(types));
            db.jdbc().update("update sys_config set config_value=:v,update_time=now() where store_id=:s and config_key='gold_metal_types'", Map.of("s", store, "v", json));
        } catch (Exception e) {
            throw new BusinessException(500301, "贵金属类型保存失败");
        }
    }

    static List<Map<String, Object>> compactTypes(List<Map<String, Object>> types) {
        List<Map<String, Object>> persisted = new ArrayList<>();
        for (Map<String, Object> source : types) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", source.getOrDefault("name", source.get("type_name")));
                item.put("code", source.getOrDefault("code", source.get("type_code")));
                item.put("purity", source.getOrDefault("purity", 100));
                item.put("price", source.getOrDefault("price", source.getOrDefault("current_price", 0)));
                item.put("sort", source.getOrDefault("sort", persisted.size() + 1));
                item.put("status", source.getOrDefault("status", 1));
                persisted.add(item);
        }
        return persisted;
    }

    static void ensureSilverRecycleType(List<Map<String, Object>> types) {
        boolean exists = types.stream().anyMatch(item -> "银回收价".equals(String.valueOf(item.get("name")))
                || "SILVER_RECYCLE".equalsIgnoreCase(String.valueOf(item.get("code"))));
        if (exists) return;
        int nextSort = types.stream().mapToInt(item -> {
            try { return Integer.parseInt(String.valueOf(item.getOrDefault("sort", 0))); }
            catch (Exception ignored) { return 0; }
        }).max().orElse(0) + 1;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", "银回收价");
        item.put("code", "SILVER_RECYCLE");
        item.put("purity", new BigDecimal("99.9"));
        item.put("price", BigDecimal.ZERO);
        item.put("sort", nextSort);
        item.put("status", 1);
        types.add(item);
    }
    private String text(Map<String,Object> q, String key, String label) { String v = q.get(key) == null ? "" : String.valueOf(q.get(key)).trim(); if (v.isEmpty() || v.length() > 50) throw new BusinessException(400300, label + "不能为空且不能超过50个字符"); return v; }
    private BigDecimal decimal(Object v, String label) { try { return new BigDecimal(String.valueOf(v)); } catch (Exception e) { throw new BusinessException(400303, label + "格式错误"); } }
    private long userId(HttpServletRequest r) { io.jsonwebtoken.Claims c = (io.jsonwebtoken.Claims) r.getAttribute("claims"); return c == null ? 0L : Long.parseLong(c.getSubject()); }
    private void log(long store, long user, String action, String content) { db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,'GOLD_PRICE',:a,:c,'',now())", Map.of("s", store, "u", user, "a", action, "c", content)); }
}
