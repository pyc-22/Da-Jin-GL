package com.dajin.system.shift;

import com.dajin.system.common.*;
import com.dajin.system.config.SyncWebSocketHandler;
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

    public ShiftController(DbSupport db, ShiftService shifts, SyncWebSocketHandler ws) { this.db = db; this.shifts = shifts; this.ws = ws; }

    @GetMapping("/info")
    public ApiResponse<?> info(HttpServletRequest r) {
        long storeId = db.store(r);
        String shiftNo = shifts.current(storeId);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("shift", shiftNo);
        List<Map<String,Object>> lines = db.list("select pay_method,sum(amount) amount,count(*) count from finance_record where store_id=:s and type='INCOME' and shift_no=:shift group by pay_method", p);
        BigDecimal total = lines.stream().map(x -> new BigDecimal(String.valueOf(x.get("amount")))).reduce(BigDecimal.ZERO, BigDecimal::add);
        // 现金口径 = 现金收入 − 现金支出（如现金支付的回收款），否则现金实点必然出差异
        BigDecimal cashSystem = db.jdbc().queryForObject("select coalesce(sum(case when type='INCOME' then amount else -amount end),0) from finance_record where store_id=:s and shift_no=:shift and pay_method='CASH' and type in ('INCOME','EXPENSE')", p, BigDecimal.class);
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("shiftNo", shiftNo);
        result.put("lines", lines);
        result.put("total", total);
        result.put("cashSystem", cashSystem);
        return ApiResponse.ok(result);
    }

    @PostMapping("/confirm")
    @Transactional
    public ApiResponse<?> confirm(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        long storeId = db.store(r);
        String clientRequestId = q.get("clientRequestId") == null ? "" : String.valueOf(q.get("clientRequestId")).trim();
        // A cashier can submit the handover more than once while the response is in flight.
        // The unique operation-log key is the durable idempotency record; do not rotate again.
        if (!clientRequestId.isBlank()) {
            Integer previous = db.jdbc().queryForObject(
                    "select count(*) from operation_log where store_id=:s and module='SHIFT' and action='CONFIRM' and client_request_id=:client",
                    Map.of("s", storeId, "client", clientRequestId), Integer.class);
            if (previous != null && previous > 0) {
                String currentShift = shifts.current(storeId);
                return ApiResponse.ok(Map.of("confirmed", true, "idempotentReplay", true,
                        "nextShiftNo", currentShift, "currentShiftNo", currentShift));
            }
        }
        String endedShiftNo = shifts.current(storeId);
        BigDecimal systemCash = db.jdbc().queryForObject("select coalesce(sum(case when type='INCOME' then amount else -amount end),0) from finance_record where store_id=:s and shift_no=:shift and pay_method='CASH' and type in ('INCOME','EXPENSE')", Map.of("s", storeId, "shift", endedShiftNo), BigDecimal.class);
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
        String idempotencyKey = clientRequestId.isBlank() ? "SHIFT-" + System.currentTimeMillis() : clientRequestId;
        // The unique key on operation_log makes concurrent double-clicks atomic.
        // INSERT IGNORE lets the losing request return the new current shift instead of rotating twice.
        int claimed = db.jdbc().update("insert ignore into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'SHIFT','CONFIRM',:content,:client,'',now())", new MapSqlParameterSource().addValue("s", storeId).addValue("uid", userId(r)).addValue("content", content.toString()).addValue("client", idempotencyKey));
        if (claimed == 0) {
            // 另一个并发请求已抢到换班权：等它提交事务并轮转班次后再读，避免返回换班前的旧班次号
            String currentShift = shifts.current(storeId);
            for (int i = 0; i < 20 && currentShift.equals(endedShiftNo); i++) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
                currentShift = shifts.current(storeId);
            }
            return ApiResponse.ok(Map.of("confirmed", true, "idempotentReplay", true,
                    "nextShiftNo", currentShift, "currentShiftNo", currentShift));
        }
        String nextShiftNo = shifts.rotate(storeId);
        Map<String,Object> event = Map.of("storeId", storeId, "action", "CONFIRM", "previousShiftNo", endedShiftNo, "nextShiftNo", nextShiftNo);
        ws.broadcast("SHIFT_UPDATED", event);
        ws.broadcast("REPORT_UPDATED", event);
        return ApiResponse.ok(Map.of("confirmed", true, "previousShiftNo", endedShiftNo, "nextShiftNo", nextShiftNo, "cashSystem", systemCash, "cashActual", cashActual, "cashDifference", difference, "shiftEndedAt", LocalDateTime.now().toString()));
    }

    private long userId(HttpServletRequest r) { io.jsonwebtoken.Claims c = (io.jsonwebtoken.Claims) r.getAttribute("claims"); return c == null ? 0L : Long.parseLong(c.getSubject()); }
}
