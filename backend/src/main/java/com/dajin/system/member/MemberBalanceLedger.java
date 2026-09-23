package com.dajin.system.member;

import com.dajin.system.common.DbSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.math.BigDecimal;

/** Records the post-change balance while the caller still holds the member row lock. */
public final class MemberBalanceLedger {
    private final DbSupport db;
    public MemberBalanceLedger(DbSupport db) { this.db = db; }

    public void record(long store, Object member, BigDecimal delta, String type, String reference, long operator) {
        db.jdbc().update("insert into member_balance_record(store_id,member_id,amount,balance_after,type,reference_no,operator_id,create_time) select :s,member_id,:amount,balance,:type,:ref,:uid,now() from member where store_id=:s and member_id=:m",
                new MapSqlParameterSource().addValue("s",store).addValue("m",member).addValue("amount",delta)
                        .addValue("type",type).addValue("ref",reference).addValue("uid",operator));
    }
}
