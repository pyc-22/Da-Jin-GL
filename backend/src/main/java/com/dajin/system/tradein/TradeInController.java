package com.dajin.system.tradein;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.config.RequirePermission;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** Old clients must not book a quote as a settled inventory transaction. */
@RestController
@RequestMapping("/api/trade-in")
public class TradeInController {
    @PostMapping("/create")
    @RequirePermission("order:create")
    public ApiResponse<?> create(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        throw new BusinessException(409109, "请更新客户端，在商品开单中选择实际商品并录入旧料后结算；旧版换新报价不能直接成交");
    }
}
