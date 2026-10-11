package com.dajin.system.shift;

import com.dajin.system.common.*;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.SyncWebSocketHandler;
import com.dajin.system.pay.PaymentChannelPolicy;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/shift")
public class ShiftController {
    private final DbSupport db;
    private final ShiftService shifts;
    private final SyncWebSocketHandler ws;
    private final ObjectMapper objectMapper;

    public ShiftController(DbSupport db, ShiftService shifts, SyncWebSocketHandler ws) { this(db, shifts, ws, new ObjectMapper()); }
    public ShiftController(DbSupport db, ShiftService shifts, SyncWebSocketHandler ws, ObjectMapper objectMapper) { this.db = db; this.shifts = shifts; this.ws = ws; this.objectMapper = objectMapper; }

    @GetMapping("/info")
    @RequirePermission("shift:confirm")
    public ApiResponse<?> info(HttpServletRequest r) {
        long storeId = db.store(r);
        String shiftNo = shifts.current(storeId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("shift", shiftNo);
        List<Map<String,Object>> lines = new ArrayList<>(db.list("select f.pay_method,pc.channel_name,sum(f.amount) amount,count(*) count from finance_record f left join pay_channel pc on pc.store_id=f.store_id and pc.channel_code=f.pay_method where f.store_id=:s and f.type='INCOME' and f.shift_no=:shift and f.category<>'PROCESSING_FEE' group by f.pay_method,pc.channel_name", p));
        // Processing payments keep one aggregate finance row for audit, but
        // the shift display must reconcile the real channel components. Match
        // a component to its own payment request so a deposit and a later
        // balance payment on the same order never land in the wrong shift.
        List<Map<String,Object>> componentLines = db.list("select c.pay_method,pc.channel_name,sum(c.amount) amount,count(*) count "
                + "from processing_payment_component c join processing_payment pp on pp.payment_id=c.payment_id and pp.store_id=c.store_id "
                + "join processing_order o on o.processing_order_id=pp.processing_order_id and o.store_id=pp.store_id "
                + "left join pay_channel pc on pc.store_id=c.store_id and pc.channel_code=c.pay_method "
                // A payment can have more than one historical finance row
                // (for example after a retry or a legacy backfill).  Joining
                // those rows multiplies every component in the shift total.
                // Use EXISTS as the membership test so each component is
                // counted once regardless of duplicate ledger history.
                + "where c.store_id=:s and pp.payment_type<>'WITHDRAW' and c.status='ACTIVE' and c.reversal_of_component_id is null "
                + "and exists (select 1 from finance_record f where f.store_id=pp.store_id and f.type='INCOME' and f.category='PROCESSING_FEE' and f.shift_no=:shift and f.related_bill_no=o.order_no "
                + "and ((pp.client_request_id is not null and f.client_request_id=pp.client_request_id) "
                + "or (pp.client_request_id is null and f.client_request_id is null and f.amount=pp.amount and f.pay_method=pp.pay_method))) "
                + "group by c.pay_method,pc.channel_name", p);
        lines = mergeChannelLines(lines, componentLines);
        BigDecimal total = lines.stream().map(x -> new BigDecimal(String.valueOf(x.get("amount")))).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 现金口径 = 现金收入 − 现金支出（如现金支付的回收款），否则现金实点必然出差异
        BigDecimal cashSystem = cashTotal(storeId, shiftNo);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("shiftNo", shiftNo);
        result.put("lines", lines);
        // A COMBINATION finance row remains the single ledger entry, while
        // componentLines exposes the real channel split for reconciliation.
        result.put("componentLines", componentLines);
        result.put("total", total);
        result.put("cashSystem", cashSystem);
        return ApiResponse.ok(result);
    }

    /** Merge aliases such as CASH/XIANJIN and configured cash labels into one
     * row.  A combination payment is represented by its components, so each
     * real channel appears exactly once in the shift summary. */
    private List<Map<String,Object>> mergeChannelLines(List<Map<String,Object>> ledger,
                                                        List<Map<String,Object>> components) {
        Map<String, Map<String,Object>> merged = new LinkedHashMap<>();
        for (Map<String,Object> row : concat(ledger, components)) {
            String code = text(row.get("pay_method"));
            String name = row.get("channel_name") == null ? null : text(row.get("channel_name"));
            String key = PaymentChannelPolicy.isCashChannel(code, name) ? "CASH" : code.toUpperCase(Locale.ROOT);
            Map<String,Object> target = merged.computeIfAbsent(key, ignored -> {
                Map<String,Object> copy = new LinkedHashMap<>();
                copy.put("pay_method", key);
                copy.put("channel_name", PaymentChannelPolicy.isCashChannel(code, name) ? (name == null || name.isBlank() ? "现金" : name) : name);
                copy.put("amount", BigDecimal.ZERO);
                copy.put("count", 0L);
                return copy;
            });
            target.put("amount", decimal(target.get("amount")).add(decimal(row.get("amount"))));
            target.put("count", ((Number) target.get("count")).longValue() + number(row.get("count")));
            if ((target.get("channel_name") == null || String.valueOf(target.get("channel_name")).isBlank()) && name != null) target.put("channel_name", name);
        }
        return new ArrayList<>(merged.values());
    }

    private List<Map<String,Object>> concat(List<Map<String,Object>> first, List<Map<String,Object>> second) {
        List<Map<String,Object>> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private long number(Object value) { return value == null ? 0L : new BigDecimal(String.valueOf(value)).longValue(); }

    @PostMapping("/confirm")
    @RequirePermission("shift:confirm")
    @Transactional
    public ApiResponse<?> confirm(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        long storeId = db.store(r);
        String clientRequestId = q.get("clientRequestId") == null ? "" : String.valueOf(q.get("clientRequestId")).trim();
        if (clientRequestId.isBlank() || clientRequestId.length() > 64)
            throw new BusinessException(400507, "交班请求号必填，长度上限64个字符");
        // A cashier can submit the handover more than once while the response is in flight.
        // The unique operation-log key is the durable idempotency record; do not rotate again.
        List<Map<String, Object>> previous = db.jdbc().queryForList(
                "select content from operation_log where store_id=:s and module='SHIFT' and action='CONFIRM' and client_request_id=:client limit 1",
                Map.of("s", storeId, "client", clientRequestId));
        if (!previous.isEmpty()) {
            return replay(previous.get(0).get("content"), storeId);
        }
        String endedShiftNo = shifts.current(storeId);
        BigDecimal systemCash = cashTotal(storeId, endedShiftNo);
        BigDecimal cashActual = new BigDecimal(String.valueOf(q.getOrDefault("cashActual", 0)));
        BigDecimal difference = cashActual.subtract(systemCash);
        String remark = q.get("remark") == null ? "" : String.valueOf(q.get("remark")).trim();
        if (difference.signum() != 0 && remark.isBlank()) throw new BusinessException(400501, "现金有差异时必须填写交班备注");
        Map<String,Object> content = new LinkedHashMap<>();
        content.put("shiftNo", endedShiftNo);
        content.put("cashSystem", systemCash);
        content.put("cashActual", cashActual);
        content.put("cashDifference", difference);
        content.put("remark", remark);
        // The unique key on operation_log makes concurrent double-clicks atomic.
        // INSERT IGNORE lets the losing request return the new current shift instead of rotating twice.
        int claimed = db.jdbc().update("insert ignore into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'SHIFT','CONFIRM',:content,:client,'',now())", new MapSqlParameterSource().addValue("s", storeId).addValue("uid", userId(r)).addValue("content", content.toString()).addValue("client", clientRequestId));
        if (claimed == 0) {
            // 另一个并发请求已抢到换班权：等它提交事务并轮转班次后再读，避免返回换班前的旧班次号
            String currentShift = shifts.current(storeId);
            for (int i = 0; i < 20 && currentShift.equals(endedShiftNo); i++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                currentShift = shifts.current(storeId);
            }
            List<Map<String, Object>> replay = db.jdbc().queryForList(
                    "select content from operation_log where store_id=:s and module='SHIFT' and action='CONFIRM' and client_request_id=:client limit 1",
                    Map.of("s", storeId, "client", clientRequestId));
            return replay.isEmpty() ? ApiResponse.ok(Map.of("confirmed", true, "idempotentReplay", true,
                    "nextShiftNo", currentShift, "currentShiftNo", currentShift)) : replay(replay.get(0).get("content"), storeId);
        }
        String nextShiftNo = shifts.rotate(storeId);
        Map<String,Object> event = Map.of("storeId", storeId, "action", "CONFIRM", "previousShiftNo", endedShiftNo, "nextShiftNo", nextShiftNo);
        ws.broadcast("SHIFT_UPDATED", event);
        ws.broadcast("REPORT_UPDATED", event);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", true);
        result.put("previousShiftNo", endedShiftNo);
        result.put("nextShiftNo", nextShiftNo);
        result.put("cashSystem", systemCash);
        result.put("cashActual", cashActual);
        result.put("cashDifference", difference);
        result.put("shiftEndedAt", LocalDateTime.now().toString());
        db.jdbc().update("update operation_log set content=:content where store_id=:s and module='SHIFT' and action='CONFIRM' and client_request_id=:client", Map.of("content", serialize(result), "s", storeId, "client", clientRequestId));
        return ApiResponse.ok(result);
    }

    private ApiResponse<?> replay(Object raw, long storeId) {
        try {
            Map<String, Object> result = objectMapper.readValue(String.valueOf(raw), new TypeReference<Map<String, Object>>() {});
            if (Boolean.TRUE.equals(result.get("confirmed"))) {
                result.put("idempotentReplay", true);
                return ApiResponse.ok(result);
            }
        } catch (Exception ignored) { }
        String currentShift = shifts.current(storeId);
        return ApiResponse.ok(Map.of("confirmed", true, "idempotentReplay", true,
                "nextShiftNo", currentShift, "currentShiftNo", currentShift));
    }

    private String serialize(Map<String, Object> result) {
        try { return objectMapper.writeValueAsString(result); }
        catch (Exception ignored) { return result.toString(); }
    }

    private long userId(HttpServletRequest r) { io.jsonwebtoken.Claims c = (io.jsonwebtoken.Claims) r.getAttribute("claims"); return c == null ? 0L : Long.parseLong(c.getSubject()); }

    private BigDecimal cashTotal(long storeId, String shiftNo) {
        // Keep the channel name even after a channel is disabled: historical
        // cash receipts still belong in the closed-shift cash total.
        List<Map<String,Object>> rows = db.list("select f.type,f.amount,f.pay_method,pc.channel_name from finance_record f left join pay_channel pc on pc.store_id=f.store_id and pc.channel_code=f.pay_method where f.store_id=:s and f.shift_no=:shift and f.type in ('INCOME','EXPENSE') and f.category<>'PROCESSING_FEE'", Map.of("s", storeId, "shift", shiftNo));
        BigDecimal ledgerCash = rows.stream().filter(row -> PaymentChannelPolicy.isCashChannel(String.valueOf(row.get("pay_method")), row.get("channel_name") == null ? null : String.valueOf(row.get("channel_name"))))
                .map(row -> "EXPENSE".equals(String.valueOf(row.get("type"))) ? decimal(row.get("amount")).negate() : decimal(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String,Object>> processingRows = db.list("select c.pay_method,pc.channel_name,c.amount from processing_payment_component c "
                + "join processing_payment pp on pp.payment_id=c.payment_id and pp.store_id=c.store_id "
                + "join processing_order o on o.processing_order_id=pp.processing_order_id and o.store_id=pp.store_id "
                + "left join pay_channel pc on pc.store_id=c.store_id and pc.channel_code=c.pay_method "
                + "where c.store_id=:s and pp.payment_type<>'WITHDRAW' and c.status='ACTIVE' and c.reversal_of_component_id is null "
                + "and exists (select 1 from finance_record f where f.store_id=pp.store_id and f.type='INCOME' and f.category='PROCESSING_FEE' and f.shift_no=:shift and f.related_bill_no=o.order_no "
                + "and ((pp.client_request_id is not null and f.client_request_id=pp.client_request_id) or (pp.client_request_id is null and f.client_request_id is null and f.amount=pp.amount and f.pay_method=pp.pay_method)))", new MapSqlParameterSource().addValue("s", storeId).addValue("shift", shiftNo));
        BigDecimal processingCash = processingRows.stream()
                .filter(row -> PaymentChannelPolicy.isCashChannel(String.valueOf(row.get("pay_method")), row.get("channel_name") == null ? null : String.valueOf(row.get("channel_name"))))
                .map(row -> decimal(row.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return ledgerCash.add(processingCash);
    }

    private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value)); }
}
