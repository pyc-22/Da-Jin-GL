package com.dajin.system.commission;

import com.dajin.system.common.*;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/api/commission")
@RequireRoles({"ADMIN","MANAGER"})
public class CommissionController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    public CommissionController(DbSupport db,SyncWebSocketHandler ws) { this.db=db; this.ws=ws; }
    @PostMapping("/calculate")
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ApiResponse<?> calculate(@RequestBody(required=false) Map<String,Object> body,HttpServletRequest request) {
        String month=body==null || body.get("month")==null?java.time.LocalDate.now().toString().substring(0,7):String.valueOf(body.get("month"));
        long store=db.store(request); var ledger=new CommissionLedger(db); int users=ledger.rebuild(store,month);
        var event=Map.of("storeId",store,"action","RECALCULATE","month",month,"users",users);
        ws.broadcast("COMMISSION_UPDATED",event); ws.broadcast("REPORT_UPDATED",event);
        return ApiResponse.ok(Map.of("month",month,"rate",ledger.rate(store),"users",users));
    }
}
