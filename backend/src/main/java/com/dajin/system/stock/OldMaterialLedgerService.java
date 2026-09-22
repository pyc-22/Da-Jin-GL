package com.dajin.system.stock;

import com.dajin.system.common.DbSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/** Activates old material and records an idempotent inventory-in audit entry. */
@Component
public class OldMaterialLedgerService {
    private final DbSupport db;

    public OldMaterialLedgerService(DbSupport db) { this.db = db; }

    public void activateAndRecord(long storeId, String source, long operatorId) {
        db.jdbc().update("update old_material set status=1,update_time=now(),version=version+1 where store_id=:s and source=:source and status=0", Map.of("s", storeId, "source", source));
        List<Map<String,Object>> materials = db.list("select material_id,weight,purity,value from old_material where store_id=:s and source=:source and status=1", Map.of("s", storeId, "source", source));
        for (Map<String,Object> material : materials) {
            recordMaterial(storeId, ((Number) material.get("material_id")).longValue(), source,
                    decimal(material.get("weight")), decimal(material.get("purity")),
                    decimal(material.get("value")), operatorId);
        }
    }

    public void recordMaterial(long storeId, long materialId, String source, BigDecimal weight,
                               BigDecimal purity, BigDecimal value, long operatorId) {
        db.jdbc().update("insert into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) "
                        + "values(:s,:no,'OLD_MATERIAL_IN',null,:qty,:cost,:uid,now()) "
                        + "on duplicate key update qty=values(qty),cost=values(cost),operator_id=values(operator_id)",
                parameters(storeId, materialId, source, weight, purity, value, operatorId));
    }

    public void returnAndRecord(long storeId, String source, long operatorId) {
        List<Map<String,Object>> materials = db.list("select * from old_material where store_id=:s and source=:source and status=1 order by material_type,material_id", Map.of("s",storeId,"source",source));
        for (Map<String,Object> material : materials) {
            var p = new MapSqlParameterSource().addValue("s",storeId).addValue("type",material.get("material_type"));
            var availableRows = db.list("select weight,purity,direction from old_material where store_id=:s and material_type=:type and status=1 order by material_id for update",p);
            BigDecimal available = availableRows.stream().map(row -> decimal(row.get("weight")).multiply(decimal(row.get("purity"))).multiply(decimal(row.get("direction")))).reduce(BigDecimal.ZERO,BigDecimal::add);
            BigDecimal weight = effectiveWeight(decimal(material.get("weight")),decimal(material.get("purity")));
            if (available.setScale(3,RoundingMode.HALF_UP).compareTo(weight)<0)
                throw new com.dajin.system.common.BusinessException(409111,"旧料已领用或库存不足，不能直接退还，请先核对旧料库存");
            p.addValue("id",material.get("material_id")).addValue("qty",weight).addValue("no","OMR-"+material.get("material_id")).addValue("uid",operatorId);
            db.jdbc().update("update old_material set status=2,version=version+1,update_time=now() where material_id=:id and store_id=:s and status=1",p);
            db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:no,'OLD_MATERIAL_OUT',null,:qty,'销售退款退还旧料',:uid,now())",p);
        }
    }

    public void synchronizeMaterial(long storeId, long materialId, String source, BigDecimal weight,
                                    BigDecimal purity, BigDecimal value, long operatorId) {
        MapSqlParameterSource parameters = parameters(storeId, materialId, source, weight, purity, value, operatorId);
        int changed = db.jdbc().update("update stock_in set qty=:qty,cost=:cost,operator_id=:uid "
                + "where store_id=:s and bill_no=:no and type='OLD_MATERIAL_IN'", parameters);
        if (changed == 0) recordMaterial(storeId, materialId, source, weight, purity, value, operatorId);
    }

    static BigDecimal effectiveWeight(BigDecimal weight, BigDecimal purity) {
        return weight.multiply(purity).setScale(3, RoundingMode.HALF_UP);
    }

    private MapSqlParameterSource parameters(long storeId, long materialId, String source, BigDecimal weight,
                                             BigDecimal purity, BigDecimal value, long operatorId) {
        return new MapSqlParameterSource()
                .addValue("s", storeId)
                .addValue("no", billNo(materialId, source))
                .addValue("qty", effectiveWeight(weight, purity))
                .addValue("cost", value)
                .addValue("uid", operatorId);
    }

    private String billNo(long materialId, String source) {
        if (source == null || source.isBlank() || source.startsWith("MANUAL:")) return "OMI-MANUAL-" + materialId;
        return "OMI-" + source.replace(':', '-') + "-" + materialId;
    }

    private BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(value));
    }
}
