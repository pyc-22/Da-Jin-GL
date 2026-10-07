package com.dajin.system.member;

import com.dajin.system.common.ApiResponse;
import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import java.time.LocalDate;
import java.util.Map;

/** Anonymous member registration reached from a salesperson QR code. */
@RestController
@RequestMapping("/api/public/member")
public class PublicMemberRegisterController {
    private final DbSupport db;

    public PublicMemberRegisterController(DbSupport db) { this.db = db; }

    public record Req(@NotBlank String name,
                      @NotBlank @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不合法") String phone,
                      String gender, String birthday, Long salesId, Long storeId) {}

    @PostMapping("/register")
    public ApiResponse<?> register(@Valid @RequestBody Req q) {
        if (q.salesId() == null || q.storeId() == null) throw new BusinessException(400401, "登记链接无效");
        if (q.birthday() != null && !q.birthday().isBlank()) {
            try { LocalDate.parse(q.birthday()); } catch (Exception e) { throw new BusinessException(400303, "生日必须是YYYY-MM-DD格式"); }
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("sales", q.salesId()).addValue("store", q.storeId()).addValue("phone", q.phone());
        // 归属人可以是导购，也可以是店长/管理员（老板自己用二维码拉会员），但必须是本店在职账号
        Integer validSales = db.jdbc().queryForObject(
                "select count(*) from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id " +
                        "where u.user_id=:sales and u.store_id=:store and u.status=1 and r.status=1 and r.role_code in ('SALES','MANAGER','ADMIN')", p, Integer.class);
        if (validSales == null || validSales != 1) throw new BusinessException(403401, "归属人与门店不匹配");
        Integer duplicate = db.jdbc().queryForObject("select count(*) from member where store_id=:store and phone=:phone", p, Integer.class);
        if (duplicate != null && duplicate > 0) throw new BusinessException(409401, "手机号已登记");
        db.jdbc().update("insert into member(store_id,name,phone,balance,points,total_consume,source,sales_id,birthday,gender,create_time,update_time) " +
                "values(:store,:name,:phone,0,0,0,'QR_REGISTER',:sales,:birthday,:gender,now(),now())",
                p.addValue("name", q.name().trim()).addValue("birthday", blankToNull(q.birthday())).addValue("gender", blankToNull(q.gender())));
        return ApiResponse.ok(Map.of("registered", true));
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
