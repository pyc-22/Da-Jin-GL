package com.dajin.system.stock;

import com.dajin.system.common.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import java.math.BigDecimal;
import java.util.UUID;

/** Used inside a transaction after locking the goods row. */
public final class InventoryAvailability {
    private final DbSupport db;
    public InventoryAvailability(DbSupport db) { this.db = db; }

    public void requireAvailable(long store, Object goodsId, BigDecimal quantity) {
        var p = new MapSqlParameterSource().addValue("s",store).addValue("g",goodsId);
        BigDecimal stock = db.jdbc().queryForObject("select stock from goods where store_id=:s and goods_id=:g for update",p,BigDecimal.class);
        BigDecimal reserved = db.jdbc().queryForObject("select coalesce(sum(i.qty),0) from sales_order_item i join sales_order o on o.order_id=i.order_id and o.store_id=i.store_id where i.store_id=:s and i.goods_id=:g and o.status in (0,3)",p,BigDecimal.class);
        if (stock == null || stock.subtract(reserved == null ? BigDecimal.ZERO : reserved).compareTo(quantity)<0)
            throw new BusinessException(409103,"可用库存不足，请先处理占用库存的待收款订单");
    }

    public void removePieces(long store, Object goodsId, BigDecimal quantity, String pieceNo) {
        var p = new MapSqlParameterSource().addValue("s",store).addValue("g",goodsId);
        Integer tracked = db.jdbc().queryForObject("select count(*) from goods_piece where store_id=:s and goods_id=:g",p,Integer.class);
        if ((tracked == null || tracked == 0) && (pieceNo == null || pieceNo.isBlank())) return;
        int count;
        try { count = quantity.intValueExact(); } catch (ArithmeticException e) { throw new BusinessException(400233,"单件库存必须按整件调整"); }
        int changed;
        if (pieceNo != null && !pieceNo.isBlank()) {
            if(count!=1) throw new BusinessException(400233,"指定单件码时数量必须为1");
            changed=db.jdbc().update("update goods_piece set status=0,update_time=now() where store_id=:s and goods_id=:g and piece_no=:pieceNo and status=1 and sales_order_id is null",p.addValue("pieceNo",pieceNo));
        } else {
            changed=db.jdbc().update("update goods_piece set status=0,update_time=now() where piece_id in (select piece_id from (select piece_id from goods_piece where store_id=:s and goods_id=:g and status=1 and sales_order_id is null order by piece_id limit :qty) t)",p.addValue("qty",count));
        }
        if(changed!=count) throw new BusinessException(409115,"可用单件库存不足或已被占用，请核对库存");
    }

    public void adjustCountPieces(long store, long goodsId, BigDecimal difference) {
        if(difference.signum()<0) { removePieces(store,goodsId,difference.negate(),null); return; }
        var p = new MapSqlParameterSource().addValue("s",store).addValue("g",goodsId);
        Integer tracked = db.jdbc().queryForObject("select count(*) from goods_piece where store_id=:s and goods_id=:g",p,Integer.class);
        if(tracked==null || tracked==0) return;
        int count;
        try { count=difference.intValueExact(); } catch(ArithmeticException e) { throw new BusinessException(400233,"单件盘点差异必须为整数"); }
        for(int n=0;n<count;n++) db.jdbc().update("insert into goods_piece(store_id,goods_id,piece_no,status,create_time,update_time) values(:s,:g,:no,1,now(),now())",p.addValue("no","CHECK-"+UUID.randomUUID()));
    }
}
