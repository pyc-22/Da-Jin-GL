package com.dajin.system.stock;

import com.dajin.system.common.*;
import com.dajin.system.config.RequirePermission;
import com.dajin.system.config.RequireRoles;
import com.dajin.system.config.SyncWebSocketHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

@RestController
@RequestMapping("/api/stock")
@Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
public class StockController {
    private final DbSupport db;
    private final SyncWebSocketHandler ws;
    private final ObjectMapper objectMapper;
    private final OldMaterialLedgerService oldMaterialLedger;

    public StockController(DbSupport db, SyncWebSocketHandler ws, ObjectMapper objectMapper,
                           OldMaterialLedgerService oldMaterialLedger) {
        this.db = db;
        this.ws = ws;
        this.objectMapper = objectMapper;
        this.oldMaterialLedger = oldMaterialLedger;
    }

    @PostMapping("/in") @RequirePermission("stock:transfer") public ApiResponse<?> in(@RequestBody Map<String,Object> q, HttpServletRequest r) { return move(q, r, "IN"); }
    @PostMapping("/out") @RequirePermission("stock:transfer") public ApiResponse<?> out(@RequestBody Map<String,Object> q, HttpServletRequest r) { return move(q, r, "OUT"); }
    @PostMapping("/process-in") @RequirePermission("stock:transfer") public ApiResponse<?> processIn(@RequestBody Map<String,Object> q,HttpServletRequest r) { Map<String,Object> body = new HashMap<>(q); body.put("reason", "PROCESS_IN"); return move(body, r, "IN"); }
    @PostMapping("/process-out") @RequirePermission("stock:transfer") public ApiResponse<?> processOut(@RequestBody Map<String,Object> q,HttpServletRequest r) { Map<String,Object> body = new HashMap<>(q); body.put("reason", "PROCESS_OUT"); return move(body, r, "OUT"); }

    /** Barcode lookup used by the mobile inbound workflow. */
    @GetMapping("/goods")
    @RequirePermission("goods:search")
    public ApiResponse<?> goodsByBarcode(@RequestParam(required = false) String barcode,
                                         @RequestParam(required = false) String pieceNo,
                                         @RequestParam(required = false) Long storeId,
                                         HttpServletRequest r) {
        long targetStore = resolveReadStore(storeId, r);
        String normalizedPieceNo = pieceNo == null ? "" : pieceNo.trim();
        if (!normalizedPieceNo.isBlank()) {
            if (normalizedPieceNo.length() > 80) throw new BusinessException(400233, "单件码不能超过80个字符");
            List<Map<String, Object>> registered = db.list(
                    "select gp.piece_id,gp.status,g.barcode,g.name from goods_piece gp join goods g on g.goods_id=gp.goods_id and g.store_id=gp.store_id where gp.store_id=:s and gp.piece_no=:pieceNo limit 1",
                    Map.of("s", targetStore, "pieceNo", normalizedPieceNo));
            if (!registered.isEmpty()) throw new BusinessException(409233, "该单件码已登记，不可重复入库");
        }
        String normalizedBarcode = barcode == null ? "" : barcode.trim();
        if (normalizedBarcode.isBlank()) throw new BusinessException(404001, "商品不存在");
        List<Map<String, Object>> rows = db.list("select g.*,c.name category,c.parent_id parent_category_id from goods g left join goods_category c on c.category_id=g.category_id and c.store_id=g.store_id where g.store_id=:s and g.barcode=:barcode limit 1",
                Map.of("s", targetStore, "barcode", normalizedBarcode));
        if (rows.isEmpty()) throw new BusinessException(404001, "商品不存在");
        return ApiResponse.ok(rows.get(0));
    }

    /** Resolves one physical item for inventory checks and other piece-level workflows. */
    @GetMapping("/piece")
    @RequirePermission("goods:search")
    public ApiResponse<?> goodsByPieceNo(@RequestParam String pieceNo, HttpServletRequest request) {
        String code = pieceNo == null ? "" : pieceNo.trim();
        if (code.isBlank() || code.length() > 80) throw new BusinessException(400233, "单件码不正确");
        List<Map<String,Object>> rows = db.list(
                "select g.*,greatest(g.stock-coalesce(rs.reserved_stock,0),0) available_stock,coalesce(rs.reserved_stock,0) reserved_stock,c.name category,c.parent_id parent_category_id,gp.piece_id,gp.piece_no,gp.status piece_status,gp.image piece_image "
                        + "from goods_piece gp join goods g on g.goods_id=gp.goods_id and g.store_id=gp.store_id "
                        + "left join goods_category c on c.category_id=g.category_id and c.store_id=g.store_id "
                        + "left join (select soi.store_id,soi.goods_id,sum(soi.qty) reserved_stock from sales_order_item soi join sales_order so on so.order_id=soi.order_id and so.store_id=soi.store_id where soi.store_id=:s and so.status in (0,3) group by soi.store_id,soi.goods_id) rs on rs.store_id=g.store_id and rs.goods_id=g.goods_id "
                        + "where gp.store_id=:s and gp.piece_no=:pieceNo limit 1",
                Map.of("s", db.store(request), "pieceNo", code));
        if (rows.isEmpty()) throw new BusinessException(404233, "未找到该单件码");
        if (((Number) rows.get(0).getOrDefault("piece_status", 0)).intValue() != 1)
            throw new BusinessException(409234, "该单件码当前不在库");
        return ApiResponse.ok(rows.get(0));
    }

    /** Active stores available to the inbound preparation selector. */
    @GetMapping("/inbound/stores")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> inboundStores(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select store_id,store_name,address from sys_store where status=1 order by store_id", Map.of()));
    }

    /** Active suppliers for purchase inbound. */
    @GetMapping("/inbound/suppliers")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> inboundSuppliers(@RequestParam(required = false) Long storeId, HttpServletRequest request) {
        long targetStore = resolveReadStore(storeId, request);
        return ApiResponse.ok(db.list("select supplier_id,supplier_name,supplier_code from stock_supplier where store_id=:s and status=1 order by supplier_name,supplier_id", Map.of("s", targetStore)));
    }

    /** Full supplier list for management (includes disabled). */
    @GetMapping("/suppliers")
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> suppliers(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select supplier_id,supplier_name,supplier_code,status from stock_supplier where store_id=:s order by status desc,supplier_name,supplier_id", Map.of("s", db.store(request))));
    }

    /** Create a supplier inline from the inbound flow or the admin console. */
    @PostMapping("/suppliers")
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> createSupplier(@RequestBody Map<String, Object> q, HttpServletRequest request) {
        String name = String.valueOf(q.getOrDefault("supplierName", "")).trim();
        if (name.isBlank()) throw new BusinessException(400240, "供应商名称不能为空");
        if (name.length() > 50) throw new BusinessException(400241, "供应商名称不能超过50个字符");
        if (db.jdbc().queryForObject("select count(*) from stock_supplier where store_id=:s and supplier_name=:n", new MapSqlParameterSource().addValue("s", db.store(request)).addValue("n", name), Integer.class) > 0)
            throw new BusinessException(409240, "供应商「" + name + "」已存在");
        String code = String.valueOf(q.getOrDefault("supplierCode", "")).trim();
        if (code.isBlank()) code = "SUP" + System.currentTimeMillis();
        if (code.length() > 50) throw new BusinessException(400241, "供应商编码不能超过50个字符");
        db.jdbc().update("insert into stock_supplier(store_id,supplier_name,supplier_code,status,create_time,update_time) values(:s,:n,:c,1,now(),now())",
                new MapSqlParameterSource().addValue("s", db.store(request)).addValue("n", name).addValue("c", code));
        ws.broadcast("SUPPLIERS_UPDATED", Map.of("storeId", db.store(request), "action", "CREATE"));
        return ApiResponse.ok(db.list("select supplier_id,supplier_name,supplier_code from stock_supplier where store_id=:s and status=1 order by supplier_name,supplier_id", Map.of("s", db.store(request))));
    }

    /** Rename or enable/disable a supplier. Disabled ones disappear from selectors but keep history. */
    @PutMapping("/suppliers/{id}")
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> updateSupplier(@PathVariable long id, @RequestBody Map<String, Object> q, HttpServletRequest request) {
        String name = q.get("supplierName") == null ? null : String.valueOf(q.get("supplierName")).trim();
        if (name != null && name.isBlank()) throw new BusinessException(400240, "供应商名称不能为空");
        if (name != null && db.jdbc().queryForObject("select count(*) from stock_supplier where store_id=:s and supplier_name=:n and supplier_id<>:id", new MapSqlParameterSource().addValue("s", db.store(request)).addValue("n", name).addValue("id", id), Integer.class) > 0)
            throw new BusinessException(409240, "供应商「" + name + "」已存在");
        Integer status = q.get("status") == null ? null : Integer.valueOf(String.valueOf(q.get("status")));
        if (status != null && status != 0 && status != 1) throw new BusinessException(400241, "状态不合法");
        int changed = db.jdbc().update("update stock_supplier set supplier_name=coalesce(:n,supplier_name),status=coalesce(:st,status),update_time=now() where supplier_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("n", name).addValue("st", status).addValue("id", id).addValue("s", db.store(request)));
        if (changed == 0) throw new BusinessException(404240, "供应商不存在");
        ws.broadcast("SUPPLIERS_UPDATED", Map.of("storeId", db.store(request), "supplierId", id, "action", "UPDATE"));
        return ApiResponse.ok();
    }

    /** Creates a complete multi-line inbound voucher atomically. */
    @PostMapping("/stock-in")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> createInbound(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        long storeId = resolveInboundStore(body, request);
        long operatorId = userId(request);
        String inboundType = String.valueOf(body.getOrDefault("inboundType", "purchase")).trim().toLowerCase(Locale.ROOT);
        if (!Set.of("purchase", "transfer", "return", "profit").contains(inboundType)) throw new BusinessException(400220, "入库类型不合法");
        Long sourceId = nullableInboundId(body.get("sourceId"));
        validateInboundSource(inboundType, sourceId, storeId);
        Object rawItems = body.get("items");
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) throw new BusinessException(400221, "入库清单不能为空");
        String clientRequestId = body.get("clientRequestId") == null ? null : String.valueOf(body.get("clientRequestId")).trim();
        if (clientRequestId != null && !clientRequestId.isBlank()) {
            List<Map<String, Object>> existing = db.list("select inbound_id from stock_inbound where store_id=:s and client_request_id=:client limit 1", Map.of("s", storeId, "client", clientRequestId));
            if (!existing.isEmpty()) return ApiResponse.ok(inboundDetail(((Number) existing.get(0).get("inbound_id")).longValue(), storeId));
        }
        String inboundNo = "RK" + System.currentTimeMillis() + String.format("%03d", new Random().nextInt(1000));
        BigDecimal totalQty = BigDecimal.ZERO, totalWeight = BigDecimal.ZERO, totalAmount = BigDecimal.ZERO;
        List<Map<String, Object>> normalized = new ArrayList<>();
        Set<String> requestPieceNos = new HashSet<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> source)) throw new BusinessException(400222, "入库明细格式不正确");
            Map<String, Object> item = new LinkedHashMap<>(); source.forEach((k, v) -> item.put(String.valueOf(k), v));
            BigDecimal quantity = inboundDecimal(item.get("quantity"), "数量", false).setScale(3, RoundingMode.HALF_UP);
            BigDecimal weight = item.get("goldWeight") == null ? BigDecimal.ZERO : inboundDecimal(item.get("goldWeight"), "金重", true).setScale(3, RoundingMode.HALF_UP);
            BigDecimal labelPrice = item.get("labelPrice") == null ? BigDecimal.ZERO : inboundDecimal(item.get("labelPrice"), "标签价", true).setScale(2, RoundingMode.HALF_UP);
            Object rawCost = item.containsKey("costPrice") ? item.get("costPrice") : item.get("cost");
            BigDecimal costPrice = rawCost == null || String.valueOf(rawCost).isBlank()
                    ? null : inboundDecimal(rawCost, "成本价", true).setScale(2, RoundingMode.HALF_UP);
            String barcode = String.valueOf(item.getOrDefault("barcode", "")).trim();
            List<String> pieceNos = inboundPieceNos(item);
            for (String pieceNo : pieceNos) {
                if (!requestPieceNos.add(pieceNo)) throw new BusinessException(409233, "入库清单中单件码重复: " + pieceNo);
            }
            if (!pieceNos.isEmpty()) {
                if (quantity.compareTo(BigDecimal.valueOf(pieceNos.size())) != 0)
                    throw new BusinessException(400234, "一物一码货品的数量必须与单件码数量一致");
            }
            String name = String.valueOf(item.getOrDefault("name", "")).trim();
            String certificateNo = item.get("certificateNo") == null ? "" : String.valueOf(item.get("certificateNo")).trim();
            if (name.isBlank()) throw new BusinessException(400223, "入库商品名称不能为空");
            item.put("quantity", quantity); item.put("goldWeight", weight); item.put("labelPrice", labelPrice); item.put("costPrice", costPrice); item.put("barcode", barcode); item.put("pieceNos", pieceNos); item.put("name", name); item.put("certificateNo", certificateNo.isBlank() ? null : certificateNo);
            item.put("images", inboundImageList(item.get("images")));
            item.put("pieceImages", inboundPieceImageList(item.get("pieceImages")));
            totalQty = totalQty.add(quantity); totalWeight = totalWeight.add(weight.multiply(quantity)); totalAmount = totalAmount.add(labelPrice.multiply(quantity)); normalized.add(item);
        }
        if (!requestPieceNos.isEmpty()) {
            MapSqlParameterSource pieceLookup = new MapSqlParameterSource().addValue("s", storeId).addValue("pieceNos", requestPieceNos);
            List<Map<String,Object>> registered = db.list("select piece_no from goods_piece where store_id=:s and piece_no in (:pieceNos) limit 1", pieceLookup);
            if (!registered.isEmpty()) throw new BusinessException(409233, "单件码已登记，不可重复入库: " + registered.get(0).get("piece_no"));
        }
        MapSqlParameterSource main = new MapSqlParameterSource().addValue("s", storeId).addValue("no", inboundNo).addValue("type", inboundType)
                .addValue("source", sourceId).addValue("remark", body.get("remark") == null ? null : String.valueOf(body.get("remark")).trim())
                .addValue("qty", totalQty).addValue("weight", totalWeight).addValue("amount", totalAmount).addValue("operator", operatorId).addValue("client", clientRequestId);
        db.jdbc().update("insert into stock_inbound(store_id,inbound_no,inbound_type,source_id,remark,status,total_quantity,total_weight,total_amount,operator_id,client_request_id,create_time,update_time) values(:s,:no,:type,:source,:remark,'COMPLETED',:qty,:weight,:amount,:operator,:client,now(),now())", main);
        long inboundId = db.jdbc().queryForObject("select inbound_id from stock_inbound where store_id=:s and inbound_no=:no", main, Long.class);
        int line = 0;
        for (Map<String, Object> item : normalized) {
            BigDecimal costPrice = item.get("costPrice") == null ? null : new BigDecimal(String.valueOf(item.get("costPrice")));
            Long goodsId = nullableInboundId(item.get("goodsId"));
            String requestedBarcode = String.valueOf(item.getOrDefault("barcode", "")).trim();
            if (requestedBarcode.length() > 32) throw new BusinessException(400232, "条码号不能超过32个字符");
            if (goodsId == null && !requestedBarcode.isBlank()) {
                // 同条码合并：扫码建档后再扫、或同一批重复条码，都挂到已有档案，避免一码裂成多档
                List<Map<String, Object>> existing = db.list("select goods_id from goods where store_id=:s and barcode=:b limit 1",
                        Map.of("s", storeId, "b", requestedBarcode));
                if (!existing.isEmpty()) {
                    goodsId = ((Number) existing.get(0).get("goods_id")).longValue();
                    item.put("goodsId", goodsId);
                    item.put("barcode", requestedBarcode);
                }
            }
            if (goodsId == null) {
                if (costPrice == null) throw new BusinessException(400240, "手动物料必须填写成本价");
                Long categoryId = nullableInboundId(item.get("categoryId"));
                if (categoryId == null) {
                    // QR labels from suppliers are not guaranteed to use our
                    // category IDs. When the label carries a category name,
                    // create/reuse a two-level goods_category entry and keep
                    // the newly scanned item inventory-only.
                    String categoryName = inboundText(item.get("categoryName"));
                    if (categoryName.isBlank()) categoryName = inboundText(item.get("category"));
                    String parentCategoryName = inboundText(item.get("parentCategoryName"));
                    if (categoryName.isBlank()) throw new BusinessException(400230, "手动物料必须选择二级品类，或提供扫码标签中的品类");
                    categoryId = ensureInboundCategory(storeId, categoryName, parentCategoryName, operatorId);
                    item.put("categoryId", categoryId);
                }
                Integer categoryCount = db.jdbc().queryForObject(
                        "select count(*) from goods_category where store_id=:s and category_id=:category and level=2 and status=1",
                        Map.of("s", storeId, "category", categoryId), Integer.class);
                if (categoryCount == null || categoryCount == 0) throw new BusinessException(400231, "商品分类不存在或已禁用");
                //走到这里说明该条码尚未建档（上方已查过），直接沿用标签条码；无条码才生成内部码
                String generatedBarcode = requestedBarcode.isBlank() ? "M" + UUID.randomUUID().toString().replace("-", "").substring(0, 31) : requestedBarcode;
                MapSqlParameterSource newGoods = new MapSqlParameterSource()
                        .addValue("s", storeId).addValue("barcode", generatedBarcode)
                        .addValue("name", item.get("name")).addValue("category", categoryId)
                        .addValue("weight", item.get("goldWeight")).addValue("cost", costPrice).addValue("sale", item.get("labelPrice"))
                        .addValue("certificateNo", item.get("certificateNo")).addValue("images", inboundImages(item.get("images"))).addValue("stock", BigDecimal.ZERO);
                // A manually discovered inbound item enters the complete inventory
                // first. It becomes a sellable product only after an explicit
                // "上架销售" action in 商品管理.
                db.jdbc().update("insert into goods(store_id,barcode,name,category_id,weight,cost_price,sale_price,price_type,gold_type,certificate_no,stock,status,create_time,update_time) "
                        + "values(:s,:barcode,:name,:category,:weight,:cost,:sale,2,'足金',:certificateNo,:stock,0,now(),now())", newGoods);
                goodsId = db.jdbc().queryForObject("select goods_id from goods where store_id=:s and barcode=:barcode", newGoods, Long.class);
                item.put("goodsId", goodsId);
                item.put("barcode", generatedBarcode);
            }
            if (costPrice == null) {
                costPrice = db.jdbc().queryForObject("select coalesce(cost_price,0) from goods where goods_id=:goods and store_id=:s", Map.of("goods", goodsId, "s", storeId), BigDecimal.class);
            }
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("inbound", inboundId).addValue("s", storeId).addValue("goods", goodsId)
                    .addValue("barcode", item.get("barcode")).addValue("name", item.get("name")).addValue("category", nullableInboundId(item.get("categoryId")))
                    .addValue("weight", item.get("goldWeight")).addValue("price", item.get("labelPrice")).addValue("certificateNo", item.get("certificateNo")).addValue("quantity", item.get("quantity"))
                     .addValue("images", inboundImages(item.get("images"))).addValue("pieceNos", inboundJson(item.get("pieceNos")));
            db.jdbc().update("insert into stock_inbound_item(inbound_id,store_id,goods_id,barcode,name,category_id,gold_weight,label_price,certificate_no,quantity,images,piece_nos,create_time) values(:inbound,:s,:goods,:barcode,:name,:category,:weight,:price,:certificateNo,:quantity,:images,:pieceNos,now())", p);
            if (goodsId != null) {
                // 一物一件：按数量生成件记录，每件一张实物图（无图件 image 为空）。
                String barcodeForPiece = String.valueOf(item.get("barcode"));
                List<?> pieceImages = item.get("pieceImages") instanceof List<?> pl ? pl : List.of();
                @SuppressWarnings("unchecked") List<String> pieceNos = (List<String>) item.getOrDefault("pieceNos", List.of());
                int pieceQty = item.get("quantity") == null ? 1 : (int) Math.round(new BigDecimal(String.valueOf(item.get("quantity"))).doubleValue());
                for (int i = 0; i < pieceQty; i++) {
                    String pieceImage = i < pieceImages.size() && pieceImages.get(i) != null ? String.valueOf(pieceImages.get(i)).trim() : "";
                    if (pieceImage.startsWith("data:")) pieceImage = "";
                    String pieceNo = i < pieceNos.size() ? pieceNos.get(i) : generatedPieceNo(barcodeForPiece, inboundId, i + 1);
                    try {
                        db.jdbc().update("insert into goods_piece(store_id,goods_id,piece_no,image,status,inbound_id,create_time) values(:s,:g,:no,:img,1,:inbound,now())",
                                new MapSqlParameterSource().addValue("s", storeId).addValue("g", goodsId)
                                        .addValue("no", pieceNo).addValue("img", pieceImage.isEmpty() ? null : pieceImage)
                                        .addValue("inbound", inboundId));
                    } catch (DuplicateKeyException e) {
                        throw new BusinessException(409233, "单件码已登记，不可重复入库: " + pieceNo);
                    }
                }
                int changed = db.jdbc().update("update goods set stock=stock+:quantity,version=version+1,update_time=now() where goods_id=:goods and store_id=:s", p);
                if (changed == 0) throw new BusinessException(404224, "库存货品不存在");
                if (!"[]".equals(String.valueOf(p.getValue("images")))) {
                    db.jdbc().update("update goods set images=:images,version=version+1,update_time=now() where goods_id=:goods and store_id=:s", p);
                }
                MapSqlParameterSource flow = new MapSqlParameterSource().addValue("s", storeId).addValue("no", inboundNo + "-" + (++line)).addValue("type", "INBOUND_" + inboundType).addValue("g", goodsId).addValue("qty", item.get("quantity")).addValue("cost", costPrice).addValue("operator", operatorId);
                db.jdbc().update("insert into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) values(:s,:no,:type,:g,:qty,:cost,:operator,now())", flow);
            }
        }
        logOperation(storeId, operatorId, "STOCK_INBOUND_CREATE", "入库单=" + inboundNo + ",件数=" + totalQty);
        Map<String, Object> result = inboundDetail(inboundId, storeId);
        result.put("storeId", storeId);
        ws.broadcast("STOCK_IN_COMPLETED", result);
        return ApiResponse.ok(result);
    }

    /** Recent inbound vouchers for the mobile workbench. The server enforces the three-month visibility window. */
    @GetMapping("/stock-in/history")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> inboundHistory(HttpServletRequest request) {
        long storeId = db.store(request);
        List<Map<String, Object>> rows = db.list(
                "select inbound_id,inbound_no,inbound_type,source_id,remark,status,total_quantity,total_weight,total_amount,operator_id,client_request_id,create_time,update_time "
                        + "from stock_inbound where store_id=:s and create_time>=date_sub(now(),interval 3 month) order by create_time desc,inbound_id desc limit 200",
                Map.of("s", storeId));
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("inbound_id")).longValue();
            row.put("items", db.list("select inbound_item_id,goods_id,barcode,name,category_id,gold_weight,label_price,certificate_no,quantity,images,piece_nos,create_time from stock_inbound_item where store_id=:s and inbound_id=:id order by inbound_item_id", Map.of("s", storeId, "id", id)));
        }
        return ApiResponse.ok(rows);
    }

    @GetMapping("/stock-in/{id}")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> inboundDetailEndpoint(@PathVariable long id, HttpServletRequest request) { return ApiResponse.ok(inboundDetail(id, db.store(request))); }

    @GetMapping("/stock-in/pending")
    @RequireRoles({"ADMIN", "MANAGER", "CASHIER", "SALES"})
    @RequirePermission("stock:inbound:create")
    public ApiResponse<?> inboundPending(HttpServletRequest request) {
        return ApiResponse.ok(db.list("select * from stock_inbound where store_id=:s and status not in ('COMPLETED','SYNCED') order by inbound_id desc limit 100", Map.of("s", db.store(request))));
    }

    private Map<String, Object> inboundDetail(long id, long storeId) {
        Map<String, Object> result;
        try { result = db.one("select * from stock_inbound where inbound_id=:id and store_id=:s", Map.of("id", id, "s", storeId)); }
        catch (Exception e) { throw new BusinessException(404225, "入库单不存在"); }
        result.put("items", db.list("select * from stock_inbound_item where inbound_id=:id and store_id=:s order by inbound_item_id", Map.of("id", id, "s", storeId)));
        Object sourceId = result.get("source_id");
        String inboundType = String.valueOf(result.get("inbound_type"));
        if (sourceId != null && "purchase".equals(inboundType)) {
            List<Map<String, Object>> source = db.list("select supplier_name source_name from stock_supplier where supplier_id=:id and store_id=:s", Map.of("id", sourceId, "s", storeId));
            if (!source.isEmpty()) result.put("source_name", source.get(0).get("source_name"));
        } else if (sourceId != null && "transfer".equals(inboundType)) {
            List<Map<String, Object>> source = db.list("select store_name source_name from sys_store where store_id=:id", Map.of("id", sourceId));
            if (!source.isEmpty()) result.put("source_name", source.get(0).get("source_name"));
        }
        return result;
    }

    /**
     * Resolve a category carried by a supplier QR label. Existing categories
     * are reused; otherwise a root and child are created atomically. The
     * fallback root keeps labels without a parent name usable while retaining
     * the original category as the searchable child value.
     */
    private Long ensureInboundCategory(long storeId, String categoryName, String parentName, long operatorId) {
        String childName = categoryName.trim();
        if (childName.length() > 100) childName = childName.substring(0, 100);
        String rootName = parentName == null ? "" : parentName.trim();
        if (rootName.length() > 100) rootName = rootName.substring(0, 100);
        if (rootName.isBlank()) {
            List<Map<String,Object>> existingChild = db.list(
                    "select category_id from goods_category where store_id=:s and level=2 and status=1 and name=:name order by category_id limit 1",
                    Map.of("s", storeId, "name", childName));
            if (!existingChild.isEmpty()) return ((Number) existingChild.get(0).get("category_id")).longValue();
            rootName = childName;
        }
        List<Map<String,Object>> roots = db.list(
                "select category_id from goods_category where store_id=:s and level=1 and name=:name order by category_id limit 1",
                Map.of("s", storeId, "name", rootName));
        long rootId;
        if (roots.isEmpty()) {
            String rootCode = autoCategoryCode(storeId, rootName, "ROOT");
            db.jdbc().update("insert into goods_category(store_id,name,category_code,parent_id,level,sort,status,create_time,update_time) values(:s,:name,:code,0,1,999,1,now(),now())",
                    new MapSqlParameterSource().addValue("s", storeId).addValue("name", rootName).addValue("code", rootCode));
            rootId = db.jdbc().queryForObject("select category_id from goods_category where store_id=:s and category_code=:code", Map.of("s", storeId, "code", rootCode), Long.class);
            logOperation(storeId, operatorId, "CATEGORY_AUTO_CREATE", "扫码标签自动创建一级分类=" + rootName);
        } else rootId = ((Number) roots.get(0).get("category_id")).longValue();

        List<Map<String,Object>> child = db.list(
                "select category_id from goods_category where store_id=:s and parent_id=:parent and level=2 and name=:name order by category_id limit 1",
                Map.of("s", storeId, "parent", rootId, "name", childName));
        if (!child.isEmpty()) return ((Number) child.get(0).get("category_id")).longValue();
        String childCode = autoCategoryCode(storeId, childName, "CHILD");
        db.jdbc().update("insert into goods_category(store_id,name,category_code,parent_id,level,sort,status,create_time,update_time) values(:s,:name,:code,:parent,2,999,1,now(),now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("name", childName).addValue("code", childCode).addValue("parent", rootId));
        Long id = db.jdbc().queryForObject("select category_id from goods_category where store_id=:s and category_code=:code", Map.of("s", storeId, "code", childCode), Long.class);
        logOperation(storeId, operatorId, "CATEGORY_AUTO_CREATE", "扫码标签自动创建二级分类=" + childName + ",父级=" + rootName);
        return id;
    }

    private String autoCategoryCode(long storeId, String name, String prefix) {
        String base = prefix + "_" + Integer.toUnsignedString(name.hashCode(), 36).toUpperCase(Locale.ROOT);
        String code = base;
        int suffix = 1;
        while (db.jdbc().queryForObject("select count(*) from goods_category where store_id=:s and category_code=:code", Map.of("s", storeId, "code", code), Integer.class) > 0) {
            code = base + "_" + suffix++;
        }
        return code.length() > 50 ? code.substring(0, 50) : code;
    }

    private String inboundText(Object value) { return value == null ? "" : String.valueOf(value).trim(); }

    private List<String> inboundPieceNos(Map<String,Object> item) {
        List<String> values = new ArrayList<>();
        Object raw = item.get("pieceNos");
        if (raw instanceof List<?> list) {
            for (Object entry : list) {
                String pieceNo = inboundText(entry);
                if (!pieceNo.isBlank()) values.add(pieceNo);
            }
        }
        if (values.isEmpty()) {
            String pieceNo = inboundText(item.get("pieceNo"));
            if (!pieceNo.isBlank()) values.add(pieceNo);
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String pieceNo : values) {
            if (pieceNo.length() > 80) throw new BusinessException(400233, "单件码不能超过80个字符");
            if (!unique.add(pieceNo)) throw new BusinessException(409233, "入库明细中单件码重复: " + pieceNo);
        }
        return new ArrayList<>(unique);
    }

    private String generatedPieceNo(String barcode, long inboundId, int sequence) {
        String suffix = "-" + inboundId + "-" + sequence;
        String prefix = inboundText(barcode);
        if (prefix.isBlank()) prefix = "PIECE";
        prefix = prefix.substring(0, Math.min(prefix.length(), 80 - suffix.length()));
        return prefix + suffix;
    }

    private String generatedManualPieceNo(String billNo, int sequence) {
        String suffix = "-" + sequence;
        String prefix = "MANUAL-" + inboundText(billNo);
        prefix = prefix.substring(0, Math.min(prefix.length(), 80 - suffix.length()));
        return prefix + suffix;
    }

    private String inboundJson(Object value) {
        try { return objectMapper.writeValueAsString(value == null ? List.of() : value); }
        catch (Exception e) { throw new BusinessException(400238, "入库明细格式不正确"); }
    }

    private void validateInboundSource(String inboundType, Long sourceId, long targetStore) {
        if ("purchase".equals(inboundType)) {
            if (sourceId == null) throw new BusinessException(400233, "采购入库必须选择供应商");
            Integer count = db.jdbc().queryForObject("select count(*) from stock_supplier where supplier_id=:id and store_id=:s and status=1", Map.of("id", sourceId, "s", targetStore), Integer.class);
            if (count == null || count == 0) throw new BusinessException(400234, "供应商不存在或已停用");
        }
        if ("transfer".equals(inboundType)) {
            if (sourceId == null) throw new BusinessException(400235, "调拨入库必须选择调出门店");
            if (sourceId == targetStore) throw new BusinessException(400236, "调出门店不能与入库门店相同");
            Integer count = db.jdbc().queryForObject("select count(*) from sys_store where store_id=:id and status=1", Map.of("id", sourceId), Integer.class);
            if (count == null || count == 0) throw new BusinessException(400237, "调出门店不存在或已停用");
        }
    }

    private BigDecimal inboundDecimal(Object value, String label, boolean allowZero) {
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(400226, label + "不能为空");
        try { BigDecimal n = new BigDecimal(String.valueOf(value)); if (n.signum() < 0 || (!allowZero && n.signum() <= 0)) throw new BusinessException(400227, label + "必须大于0"); return n; }
        catch (NumberFormatException e) { throw new BusinessException(400228, label + "格式不正确"); }
    }
    private Long nullableInboundId(Object value) { if (value == null || String.valueOf(value).isBlank()) return null; try { return Long.valueOf(String.valueOf(value)); } catch (NumberFormatException e) { throw new BusinessException(400229, "编号格式不正确"); } }
    private String inboundImages(Object value) {
        try { return objectMapper.writeValueAsString(inboundImageList(value)); }
        catch (Exception e) { throw new BusinessException(400238, "商品照片格式不正确"); }
    }

    private List<String> inboundImageList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > 4) throw new BusinessException(400238, "每件货品最多上传4张照片");
        List<String> images = new ArrayList<>();
        for (Object entry : list) {
            String image = entry == null ? "" : String.valueOf(entry).trim();
            if (image.isBlank() || image.length() > 1000) throw new BusinessException(400239, "商品照片地址不合法");
            images.add(image);
        }
        return images;
    }

    private List<String> inboundPieceImageList(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.size() > 4) throw new BusinessException(400238, "每件货品最多上传4张照片");
        List<String> images = new ArrayList<>();
        for (Object entry : list) {
            String image = entry == null ? "" : String.valueOf(entry).trim();
            if (image.length() > 1000) throw new BusinessException(400239, "商品照片地址不合法");
            images.add(image);
        }
        return images;
    }

    private long resolveInboundStore(Map<String, Object> body, HttpServletRequest request) {
        long currentStore = db.store(request);
        Long requested = nullableInboundId(body.get("storeId"));
        if (requested == null || requested == currentStore) return currentStore;
        String role = "";
        Object claims = request.getAttribute("claims");
        if (claims instanceof io.jsonwebtoken.Claims c) role = String.valueOf(c.get("role"));
        if (!("ADMIN".equals(role) || "MANAGER".equals(role))) throw new BusinessException(403224, "当前角色只能在所属门店入库");
        Integer active = db.jdbc().queryForObject("select count(*) from sys_store where store_id=:s and status=1", Map.of("s", requested), Integer.class);
        if (active == null || active == 0) throw new BusinessException(404226, "入库门店不存在或已停用");
        return requested;
    }

    private long resolveReadStore(Long requested, HttpServletRequest request) {
        long currentStore = db.store(request);
        if (requested == null || requested == currentStore) return currentStore;
        Object claims = request.getAttribute("claims");
        String role = claims instanceof io.jsonwebtoken.Claims c ? String.valueOf(c.get("role")) : "";
        if (!("ADMIN".equals(role) || "MANAGER".equals(role))) throw new BusinessException(403225, "当前角色只能查询所属门店货品");
        Integer active = db.jdbc().queryForObject("select count(*) from sys_store where store_id=:s and status=1", Map.of("s", requested), Integer.class);
        if (active == null || active == 0) throw new BusinessException(404227, "查询门店不存在或已停用");
        return requested;
    }

    private ApiResponse<?> move(Map<String,Object> q, HttpServletRequest r, String type) {
        Object rawQty = q.get("qty");
        if (rawQty == null) throw new BusinessException(400202, "库存数量必须大于0");
        BigDecimal qty = new BigDecimal(rawQty.toString());
        if (qty.signum() <= 0) throw new BusinessException(400202, "库存数量必须大于0");
        if (q.get("goodsId") == null) throw new BusinessException(400200, "请选择商品");
        String remark = String.valueOf(q.getOrDefault("remark", q.getOrDefault("reason", ""))).trim();
        if (type.equals("IN") && remark.isBlank()) throw new BusinessException(400203, "手动入库备注不能为空");
        long storeId = db.store(r); long operatorId = userId(r); String billNo = String.valueOf(q.getOrDefault("billNo", type + System.currentTimeMillis()));
        String pieceNo = inboundText(q.get("pieceNo"));
        if (!pieceNo.isBlank() && (pieceNo.length() > 80 || qty.compareTo(BigDecimal.ONE) != 0)) throw new BusinessException(400233, "指定单件码时数量必须为1，且单件码不超过80个字符");
        Object rawCost = q.containsKey("cost") ? q.get("cost") : q.get("costPrice");
        if (type.equals("IN") && (rawCost == null || String.valueOf(rawCost).isBlank())) throw new BusinessException(400240, "手动入库必须填写成本价");
        BigDecimal cost = rawCost == null || String.valueOf(rawCost).isBlank() ? BigDecimal.ZERO : decimal(rawCost, "成本价").setScale(2, RoundingMode.HALF_UP);
        if (cost.signum() < 0) throw new BusinessException(400241, "成本价不能小于0");
        List<String> inboundImages = type.equals("IN") ? inboundImageList(q.get("images")) : List.of();
        // expectedVersion 缺省时跳过乐观锁（兼容旧弹窗），传了才校验，避免"库存已变化"误拦
        boolean checkVersion = q.get("expectedVersion") != null && !String.valueOf(q.get("expectedVersion")).isBlank();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("no", billNo).addValue("type", q.getOrDefault("reason", type)).addValue("g", q.get("goodsId")).addValue("qty", qty).addValue("cost", cost).addValue("operator", operatorId).addValue("version", q.get("expectedVersion"));
        if (type.equals("OUT")) {
            BigDecimal value = db.jdbc().queryForObject("select coalesce(cost_price,0)*:qty from goods where goods_id=:g and store_id=:s", p, BigDecimal.class);
            BigDecimal limit = configNumber(storeId, "stock_out_approval_limit", BigDecimal.valueOf(10000));
            if (value.compareTo(limit) > 0) {
                Map<String,Object> approvalData = new LinkedHashMap<>(); approvalData.put("goodsId", q.get("goodsId")); approvalData.put("qty", qty); approvalData.put("pieceNo", pieceNo.isBlank() ? null : pieceNo); approvalData.put("billNo", billNo); approvalData.put("reason", remark); approvalData.put("expectedVersion", q.get("expectedVersion"));
                try { p.addValue("approvalReason", objectMapper.writeValueAsString(approvalData)); } catch (JsonProcessingException e) { throw new BusinessException(400204, "出库申请格式错误"); }
                db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) values(:s,'STOCK_OUT',:g,:operator,:amount,:approvalReason,1,now())", p.addValue("amount", value));
                logOperation(storeId, operatorId, "STOCK_OUT_APPROVAL", approvalData.toString());
                long approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type='STOCK_OUT' and biz_id=:g order by approval_id desc limit 1", p, Long.class);
                ws.broadcast("APPROVAL_CREATED", Map.of("storeId", storeId, "id", approvalId, "approvalId", approvalId, "type", "STOCK_OUT", "bizId", q.get("goodsId")));
                return ApiResponse.ok(Map.of("approvalRequired", true, "approvalId", approvalId, "amount", value));
            }
        }
        String versionClause = checkVersion ? " and version=:version" : "";
        if(type.equals("OUT")) new InventoryAvailability(db).requireAvailable(storeId,q.get("goodsId"),qty);
        int changed = db.jdbc().update(type.equals("IN") ? "update goods set stock=stock+:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s"+versionClause : "update goods set stock=stock-:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s and stock>=:qty"+versionClause, p);
        if (changed == 0) {
            BigDecimal currentStock = db.jdbc().queryForObject("select coalesce(stock,0) from goods where goods_id=:g and store_id=:s", p, BigDecimal.class);
            Number currentVersion = db.jdbc().queryForObject("select version from goods where goods_id=:g and store_id=:s", p, Number.class);
            if (type.equals("OUT") && (currentStock == null || currentStock.compareTo(qty) < 0)) throw new BusinessException(409202, "库存不足");
            throw new BusinessException(409201, "库存已变化，请刷新后重试");
        }
        if (!inboundImages.isEmpty()) {
            String currentImages = db.jdbc().queryForObject(
                    "select coalesce(images,'[]') from goods where goods_id=:g and store_id=:s for update", p, String.class);
            db.jdbc().update("update goods set images=:images,update_time=now() where goods_id=:g and store_id=:s",
                    p.addValue("images", mergeGoodsImages(currentImages, inboundImages)));
        }
        if (type.equals("OUT")) {
            new InventoryAvailability(db).removePieces(storeId,q.get("goodsId"),qty,pieceNo);
        } else if (!pieceNo.isBlank()) {
            try { db.jdbc().update("insert into goods_piece(store_id,goods_id,piece_no,status,create_time) values(:s,:g,:pieceNo,1,now())", p.addValue("pieceNo", pieceNo)); }
            catch (DuplicateKeyException e) { throw new BusinessException(409233, "单件码已登记，不可重复入库: " + pieceNo); }
        } else if (qty.stripTrailingZeros().scale() <= 0) {
            for (int i = 1; i <= qty.intValue(); i++) db.jdbc().update("insert into goods_piece(store_id,goods_id,piece_no,status,create_time) values(:s,:g,:pieceNo,1,now())", p.addValue("pieceNo", generatedManualPieceNo(billNo, i)));
        }
        db.jdbc().update(type.equals("IN") ? "insert into stock_in(store_id,bill_no,type,goods_id,qty,cost,operator_id,create_time) values(:s,:no,:type,:g,:qty,:cost,:operator,now())" : "insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:no,:type,:g,:qty,:type,:operator,now())", p);
        logOperation(storeId, operatorId, type.equals("IN") ? "STOCK_IN" : "STOCK_OUT", "商品=" + q.get("goodsId") + ",数量=" + qty + ",照片=" + inboundImages.size() + ",备注=" + remark);
        ws.broadcast("STOCK_UPDATED", Map.of("storeId", storeId, "goodsId", q.get("goodsId"), "action", type, "quantity", qty));
        return ApiResponse.ok(Map.of("versionConflict", false));
    }

    private String mergeGoodsImages(String currentJson, List<String> inboundImages) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(inboundImages);
        try {
            if (currentJson != null && !currentJson.isBlank()) {
                List<String> current = objectMapper.readValue(currentJson, new TypeReference<List<String>>() { });
                current.stream().filter(Objects::nonNull).map(String::trim).filter(value -> !value.isBlank()).forEach(merged::add);
            }
            return objectMapper.writeValueAsString(merged.stream().limit(9).toList());
        } catch (JsonProcessingException e) {
            throw new BusinessException(400238, "商品照片格式不正确");
        }
    }

    /** Returns the server-side stock snapshot for a complete inventory-check scope. */
    @GetMapping("/check/scope-goods")
    @RequirePermission("stock:check:create")
    public ApiResponse<?> checkScopeGoods(@RequestParam String scopeType,
                                          @RequestParam(required = false) Long scopeId,
                                          HttpServletRequest r) {
        String type = normalizeScopeType(scopeType, false);
        validateCheckScope(db.store(r), type, scopeId);
        return ApiResponse.ok(scopedGoods(db.store(r), type, scopeId));
    }

    @PostMapping("/check")
    @RequirePermission("stock:check:submit")
    public ApiResponse<?> check(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        long storeId = db.store(r);
        long operatorId = userId(r);
        List<Map<String,Object>> submitted = rows(q.get("rows"));
        if (submitted.isEmpty()) throw new BusinessException(400203, "盘点明细不能为空");

        String scopeType = normalizeScopeType(q.get("scopeType"), true);
        Long scopeId = nullableLong(q.get("scopeId"));
        if (!"CUSTOM".equals(scopeType)) validateCheckScope(storeId, scopeType, scopeId);
        String clientRequestId = nullableText(q.get("clientRequestId"), 64, "客户端请求号不能超过64个字符");
        if (clientRequestId != null) {
            List<Map<String,Object>> existing = db.list("select check_id from stock_check where store_id=:s and client_request_id=:client limit 1",
                    Map.of("s", storeId, "client", clientRequestId));
            if (!existing.isEmpty()) return ApiResponse.ok(stockCheckDetail(((Number) existing.get(0).get("check_id")).longValue(), r));
        }

        if (!"CUSTOM".equals(scopeType)) {
            MapSqlParameterSource lock = new MapSqlParameterSource().addValue("s", storeId).addValue("scope", scopeType).addValue("scopeId", scopeId);
            List<Map<String,Object>> active = db.list("select check_id,bill_no from stock_check where store_id=:s and status=1 and "
                    + "(:scope='STORE' or scope_type='STORE' or (scope_type=:scope and scope_id <=> :scopeId)) limit 1 for update", lock);
            if (!active.isEmpty()) throw new BusinessException(409210, "该盘点范围已有待审批盘点单：" + active.get(0).get("bill_no"));
        }

        List<Map<String,Object>> snapshot = buildCheckSnapshot(storeId, scopeType, scopeId, submitted);
        BigDecimal totalDiff = snapshot.stream().map(this::difference).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal approvalAmount = snapshot.stream()
                .map(row -> checkDecimal(row.get("differenceAmount"), "0").abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String detail;
        try { detail = objectMapper.writeValueAsString(snapshot); }
        catch (JsonProcessingException e) { throw new BusinessException(400204, "盘点明细格式错误"); }
        String billNo = nullableText(q.get("billNo"), 32, "盘点单号不能超过32个字符");
        if (billNo == null) billNo = "PD" + System.currentTimeMillis() + String.format("%03d", new Random().nextInt(1000));
        String remark = nullableText(q.get("remark"), 500, "盘点备注不能超过500个字符");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("no", billNo)
                .addValue("scope", scopeType).addValue("scopeId", scopeId).addValue("remark", remark)
                .addValue("client", clientRequestId).addValue("diff", totalDiff).addValue("detail", detail)
                .addValue("operator", operatorId);
        try {
            db.jdbc().update("insert into stock_check(store_id,bill_no,scope_type,scope_id,remark,client_request_id,status,total_diff,detail,operator_id,create_time,update_time) "
                    + "values(:s,:no,:scope,:scopeId,:remark,:client,1,:diff,cast(:detail as json),:operator,now(),now())", p);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (clientRequestId != null) {
                List<Map<String,Object>> existing = db.list("select check_id from stock_check where store_id=:s and client_request_id=:client limit 1", p);
                if (!existing.isEmpty()) return ApiResponse.ok(stockCheckDetail(((Number) existing.get(0).get("check_id")).longValue(), r));
            }
            throw new BusinessException(409211, "盘点单号已存在，请重新提交");
        }
        long checkId = db.jdbc().queryForObject("select check_id from stock_check where store_id=:s and bill_no=:no", p, Long.class);
        MapSqlParameterSource approval = new MapSqlParameterSource().addValue("s", storeId).addValue("id", checkId)
                .addValue("uid", operatorId).addValue("amount", approvalAmount)
                .addValue("reason", "库存盘点差异审批（" + scopeLabel(scopeType) + "）");
        db.jdbc().update("insert into approval(store_id,type,biz_id,applicant_id,amount,reason,status,create_time) values(:s,'STOCK_CHECK',:id,:uid,:amount,:reason,1,now())", approval);
        long approvalId = db.jdbc().queryForObject("select approval_id from approval where store_id=:s and type='STOCK_CHECK' and biz_id=:id order by approval_id desc limit 1", approval, Long.class);
        logOperation(storeId, operatorId, "STOCK_CHECK_SUBMIT", "盘点单=" + billNo + ",范围=" + scopeLabel(scopeType) + ",商品数=" + snapshot.size() + ",数量差异=" + totalDiff);
        Map<String,Object> event = new LinkedHashMap<>();
        event.put("storeId", storeId); event.put("id", approvalId); event.put("approvalId", approvalId);
        event.put("type", "STOCK_CHECK"); event.put("bizId", checkId); event.put("billNo", billNo);
        ws.broadcast("APPROVAL_CREATED", event);
        ws.broadcast("STOCK_CHECK_CREATED", event);
        return ApiResponse.ok(stockCheckDetail(checkId, r));
    }

    /** Mobile visibility is limited to three months; sales can only see checks they submitted. */
    @GetMapping("/check/history")
    @RequirePermission("stock:check:view")
    public ApiResponse<?> checkHistory(HttpServletRequest r) {
        long storeId = db.store(r);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("u", userId(r));
        String own = "SALES".equals(roleCode(r)) ? " and sc.operator_id=:u" : "";
        List<Map<String,Object>> result = db.list(stockCheckSelect()
                + " where sc.store_id=:s and sc.create_time>=date_sub(now(),interval 3 month)" + own
                + " order by sc.check_id desc limit 200", p);
        result.forEach(this::addCheckSummary);
        return ApiResponse.ok(result);
    }

    @GetMapping("/check/{id}")
    @RequirePermission("stock:check:view")
    public ApiResponse<?> checkDetail(@PathVariable long id, HttpServletRequest r) {
        return ApiResponse.ok(stockCheckDetail(id, r));
    }

    @GetMapping("/old-material")
    @RequirePermission("stock:view")
    public ApiResponse<?> old(HttpServletRequest r) {
        long storeId = db.store(r);
        List<Map<String, Object>> rows = db.list("select material_id,material_type,weight,purity,direction,weight*purity*coalesce(direction,1) effective_weight,source,value,status,create_time,update_time from old_material where store_id=:s and status=1 order by material_id desc", Map.of("s", storeId));
        List<Map<String, Object>> aggregated = OldMaterialAggregation.aggregate(rows);
        List<Map<String, Object>> types = db.list("select name,status from old_material_type where store_id=:s order by sort,type_id", Map.of("s", storeId));
        for (Map<String, Object> type : types) {
            String name = String.valueOf(type.get("name"));
            boolean present = aggregated.stream().anyMatch(row -> String.valueOf(row.get("material_type")).equals(name));
            if (present) continue;
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("material_type", name);
            empty.put("total_weight", new BigDecimal("0.000"));
            empty.put("total_gross_weight", new BigDecimal("0.000"));
            empty.put("average_purity", new BigDecimal("0.0000"));
            empty.put("total_value", new BigDecimal("0.00"));
            empty.put("inbound_count", 0);
            empty.put("outbound_count", 0);
            empty.put("details", new ArrayList<Map<String, Object>>());
            aggregated.add(empty);
        }
        return ApiResponse.ok(aggregated);
    }

    @PostMapping("/old-material")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> createOldMaterial(@RequestBody Map<String, Object> q, HttpServletRequest r) {
        OldMaterialValues values = oldMaterialValues(q);
        long storeId = db.store(r);
        long operatorId = userId(r);
        String source = "MANUAL:" + UUID.randomUUID();
        MapSqlParameterSource parameters = materialParameters(storeId, values).addValue("source", source);
        db.jdbc().update("insert into old_material(store_id,material_type,weight,purity,source,value,status,direction,create_time,update_time) "
                + "values(:s,:type,:weight,:purity,:source,:value,1,1,now(),now())", parameters);
        long materialId = db.jdbc().queryForObject(
                "select material_id from old_material where store_id=:s and source=:source order by material_id desc limit 1",
                parameters, Long.class);
        oldMaterialLedger.recordMaterial(storeId, materialId, source, values.weight(), values.purity(), values.value(), operatorId);
        recordOldMaterialOperation(storeId, operatorId, "CREATE", materialId, values);
        ws.broadcast("OLD_MATERIAL_UPDATED", Map.of("storeId", storeId, "materialId", materialId, "action", "CREATE"));
        return ApiResponse.ok(materialResult(materialId, source, values));
    }

    @PutMapping("/old-material/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> updateOldMaterial(@PathVariable long id, @RequestBody Map<String, Object> q,
                                            HttpServletRequest r) {
        OldMaterialValues values = oldMaterialValues(q);
        long storeId = db.store(r);
        List<Map<String, Object>> rows = db.list(
                "select material_id,source from old_material where material_id=:id and store_id=:s and status=1 for update",
                Map.of("id", id, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(404201, "旧料记录不存在或已停用");
        String source = rows.get(0).get("source") == null ? null : String.valueOf(rows.get(0).get("source"));
        MapSqlParameterSource parameters = materialParameters(storeId, values).addValue("id", id);
        db.jdbc().update("update old_material set material_type=:type,weight=:weight,purity=:purity,value=:value,"
                + "update_time=now(),version=version+1 where material_id=:id and store_id=:s and status=1", parameters);
        long operatorId = userId(r);
        oldMaterialLedger.synchronizeMaterial(storeId, id, source, values.weight(), values.purity(), values.value(), operatorId);
        recordOldMaterialOperation(storeId, operatorId, "UPDATE", id, values);
        ws.broadcast("OLD_MATERIAL_UPDATED", Map.of("storeId", storeId, "materialId", id, "action", "UPDATE"));
        return ApiResponse.ok(materialResult(id, source, values));
    }

    @GetMapping("/old-material/types")
    @RequirePermission(value = {"stock:view", "order:create", "processing:view", "recycle:view"}, anyOf = true)
    public ApiResponse<?> oldMaterialTypes(HttpServletRequest r) {
        return ApiResponse.ok(db.list("select type_id,name,sort,status,create_time,update_time from old_material_type where store_id=:s order by sort,type_id", Map.of("s", db.store(r))));
    }

    @PostMapping("/old-material/types")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> createOldMaterialType(@RequestBody Map<String, Object> q, HttpServletRequest r) {
        String name = oldMaterialTypeName(q);
        long storeId = db.store(r);
        ensureOldMaterialTypeNameFree(storeId, name, null);
        int sort = q.get("sort") == null
                ? db.jdbc().queryForObject("select coalesce(max(sort),0)+1 from old_material_type where store_id=:s", Map.of("s", storeId), Integer.class)
                : ((Number) q.get("sort")).intValue();
        db.jdbc().update("insert into old_material_type(store_id,name,sort) values(:s,:n,:sort)",
                new MapSqlParameterSource().addValue("s", storeId).addValue("n", name).addValue("sort", sort));
        ws.broadcast("OLD_MATERIAL_TYPES_UPDATED", Map.of("storeId", storeId, "action", "create", "name", name));
        return ApiResponse.ok(Map.of("name", name));
    }

    @PutMapping("/old-material/types/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> updateOldMaterialType(@PathVariable long id, @RequestBody Map<String, Object> q, HttpServletRequest r) {
        String name = oldMaterialTypeName(q);
        long storeId = db.store(r);
        ensureOldMaterialTypeNameFree(storeId, name, id);
        db.jdbc().update("update old_material_type set name=:n,update_time=now() where type_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("id", id).addValue("s", storeId).addValue("n", name));
        ws.broadcast("OLD_MATERIAL_TYPES_UPDATED", Map.of("storeId", storeId, "action", "update", "id", id));
        return ApiResponse.ok(Map.of("typeId", id, "name", name));
    }

    @PatchMapping("/old-material/types/{id}/status")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> oldMaterialTypeStatus(@PathVariable long id, @RequestParam int status, HttpServletRequest r) {
        db.jdbc().update("update old_material_type set status=:st,update_time=now() where type_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("id", id).addValue("s", db.store(r)).addValue("st", status == 0 ? 0 : 1));
        ws.broadcast("OLD_MATERIAL_TYPES_UPDATED", Map.of("storeId", db.store(r), "action", "status", "id", id));
        return ApiResponse.ok();
    }

    @DeleteMapping("/old-material/types/{id}")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> deleteOldMaterialType(@PathVariable long id, HttpServletRequest r) {
        long storeId = db.store(r);
        List<Map<String, Object>> rows = db.list("select name from old_material_type where type_id=:id and store_id=:s", Map.of("id", id, "s", storeId));
        if (rows.isEmpty()) throw new BusinessException(404202, "旧料类型不存在");
        String name = String.valueOf(rows.get(0).get("name"));
        Integer referenced = db.jdbc().queryForObject("select count(*) from old_material where store_id=:s and material_type=:n",
                Map.of("s", storeId, "n", name), Integer.class);
        if (referenced != null && referenced > 0) throw new BusinessException(400732, "该旧料类型存在库存或历史记录，只能禁用");
        db.jdbc().update("delete from old_material_type where type_id=:id and store_id=:s",
                new MapSqlParameterSource().addValue("id", id).addValue("s", storeId));
        ws.broadcast("OLD_MATERIAL_TYPES_UPDATED", Map.of("storeId", storeId, "action", "delete", "id", id));
        return ApiResponse.ok();
    }

    private String oldMaterialTypeName(Map<String, Object> q) {
        String name = q.get("name") == null ? "" : String.valueOf(q.get("name")).trim();
        if (name.isEmpty()) throw new BusinessException(400730, "请输入旧料类型名称");
        if (name.length() > 50) throw new BusinessException(400731, "旧料类型名称不能超过50个字符");
        return name;
    }

    private void ensureOldMaterialTypeNameFree(long storeId, String name, Long excludeId) {
        Integer dup = db.jdbc().queryForObject("select count(*) from old_material_type where store_id=:s and name=:n and type_id<>:id",
                new MapSqlParameterSource().addValue("s", storeId).addValue("n", name).addValue("id", excludeId == null ? -1L : excludeId), Integer.class);
        if (dup != null && dup > 0) throw new BusinessException(400733, "该旧料类型已存在");
    }

    @PostMapping("/old-material/in")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> manualOldIn(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        OldMaterialValues values = oldMaterialValues(q); long storeId = db.store(r); long operatorId = userId(r);
        String source = "MANUAL:OLD:" + UUID.randomUUID(); String billNo = String.valueOf(q.getOrDefault("billNo", "OMI-OLD-" + System.currentTimeMillis()));
        MapSqlParameterSource p = materialParameters(storeId, values).addValue("source", source).addValue("billNo", billNo).addValue("operator", operatorId);
        db.jdbc().update("insert into old_material(store_id,material_type,weight,purity,source,value,status,direction,create_time,update_time) values(:s,:type,:weight,:purity,:source,:value,1,1,now(),now())", p);
        long id = db.jdbc().queryForObject("select material_id from old_material where store_id=:s and source=:source order by material_id desc limit 1", p, Long.class);
        oldMaterialLedger.recordMaterial(storeId, id, source, values.weight(), values.purity(), values.value(), operatorId);
        logOperation(storeId, operatorId, "OLD_MATERIAL_IN", "类型=" + values.materialType() + ",克重=" + values.weight() + ",来源=" + q.getOrDefault("source", "其他") + ",备注=" + q.getOrDefault("remark", ""));
        ws.broadcast("OLD_MATERIAL_UPDATED", Map.of("storeId", storeId, "materialId", id, "action", "IN")); return ApiResponse.ok(materialResult(id, source, values));
    }

    @PostMapping("/old-material/out")
    @RequireRoles({"ADMIN", "MANAGER"})
    @RequirePermission("stock:transfer")
    public ApiResponse<?> manualOldOut(@RequestBody Map<String,Object> q, HttpServletRequest r) {
        String materialType = q.get("materialType") == null ? "" : String.valueOf(q.get("materialType")).trim();
        BigDecimal weight = decimal(q.get("weight"), "旧料克重").setScale(3, RoundingMode.HALF_UP);
        BigDecimal purity = decimal(q.getOrDefault("purity", 1), "旧料成色").setScale(4, RoundingMode.HALF_UP);
        if (materialType.isBlank() || weight.signum() <= 0 || purity.signum() <= 0 || purity.compareTo(BigDecimal.ONE) > 0) throw new BusinessException(400207, "旧料类型、克重和成色不合法");
        long storeId = db.store(r); long operatorId = userId(r);
        List<Map<String,Object>> materialRows = db.list("select material_id,weight,purity,value,direction from old_material where store_id=:s and status=1 and material_type=:type order by material_id for update", Map.of("s", storeId, "type", materialType));
        BigDecimal available = BigDecimal.ZERO;
        BigDecimal currentValue = BigDecimal.ZERO;
        for (Map<String,Object> row : materialRows) {
            BigDecimal direction = new BigDecimal(String.valueOf(row.getOrDefault("direction", 1)));
            BigDecimal rowWeight = new BigDecimal(String.valueOf(row.getOrDefault("weight", 0)));
            BigDecimal rowPurity = new BigDecimal(String.valueOf(row.getOrDefault("purity", 0)));
            BigDecimal rowValue = new BigDecimal(String.valueOf(row.getOrDefault("value", 0)));
            available = available.add(rowWeight.multiply(rowPurity).multiply(direction));
            currentValue = currentValue.add(rowValue.multiply(direction));
        }
        BigDecimal effective = OldMaterialLedgerService.effectiveWeight(weight, purity);
        if (available.compareTo(effective) < 0) throw new BusinessException(409203, "旧料库存不足");
        BigDecimal outboundValue = available.signum() == 0 ? BigDecimal.ZERO : currentValue.multiply(effective).divide(available, 2, RoundingMode.HALF_UP);
        String source = "MANUAL:OLD_OUT:" + UUID.randomUUID(); String billNo = String.valueOf(q.getOrDefault("billNo", "OMO-OLD-" + System.currentTimeMillis()));
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("type", materialType).addValue("weight", weight).addValue("purity", purity).addValue("source", source).addValue("value", outboundValue).addValue("billNo", billNo).addValue("qty", effective).addValue("operator", operatorId);
        db.jdbc().update("insert into old_material(store_id,material_type,weight,purity,source,value,status,direction,create_time,update_time) values(:s,:type,:weight,:purity,:source,:value,1,-1,now(),now())", p);
        db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:billNo,'OLD_MATERIAL_OUT',null,:qty,:reason,:operator,now())", p.addValue("reason", String.valueOf(q.getOrDefault("reason", "其他"))));
        logOperation(storeId, operatorId, "OLD_MATERIAL_OUT", "类型=" + materialType + ",有效克重=" + effective + ",原因=" + q.getOrDefault("reason", "其他") + ",备注=" + q.getOrDefault("remark", ""));
        ws.broadcast("OLD_MATERIAL_UPDATED", Map.of("storeId", storeId, "action", "OUT", "materialType", materialType)); return ApiResponse.ok(Map.of("materialType", materialType, "weight", weight, "purity", purity, "effectiveWeight", effective, "availableAfter", available.subtract(effective)));
    }

    private OldMaterialValues oldMaterialValues(Map<String, Object> q) {
        String materialType = q.get("materialType") == null ? "" : String.valueOf(q.get("materialType")).trim();
        if (materialType.isEmpty() || materialType.length() > 50) throw new BusinessException(400205, "旧料类型不能为空且不能超过50个字符");
        BigDecimal weight = decimal(q.get("weight"), "旧料克重").setScale(3, RoundingMode.HALF_UP);
        BigDecimal purity = decimal(q.get("purity"), "旧料成色").setScale(4, RoundingMode.HALF_UP);
        BigDecimal value = decimal(q.getOrDefault("value", BigDecimal.ZERO), "旧料估值").setScale(2, RoundingMode.HALF_UP);
        if (weight.signum() <= 0) throw new BusinessException(400206, "旧料克重必须大于0");
        if (purity.signum() <= 0 || purity.compareTo(BigDecimal.ONE) > 0) throw new BusinessException(400207, "旧料成色必须大于0且不超过100%");
        if (value.signum() < 0) throw new BusinessException(400208, "旧料估值不能小于0");
        return new OldMaterialValues(materialType, weight, purity, value);
    }

    private BigDecimal decimal(Object value, String field) {
        if (value == null || String.valueOf(value).isBlank()) throw new BusinessException(400209, field + "不能为空");
        try { return new BigDecimal(String.valueOf(value)); }
        catch (NumberFormatException e) { throw new BusinessException(400210, field + "格式错误"); }
    }

    private MapSqlParameterSource materialParameters(long storeId, OldMaterialValues values) {
        return new MapSqlParameterSource().addValue("s", storeId).addValue("type", values.materialType())
                .addValue("weight", values.weight()).addValue("purity", values.purity()).addValue("value", values.value());
    }

    private Map<String, Object> materialResult(long id, String source, OldMaterialValues values) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("materialId", id);
        result.put("materialType", values.materialType());
        result.put("weight", values.weight());
        result.put("purity", values.purity());
        result.put("effectiveWeight", OldMaterialLedgerService.effectiveWeight(values.weight(), values.purity()));
        result.put("value", values.value());
        result.put("source", source);
        return result;
    }

    private void recordOldMaterialOperation(long storeId, long operatorId, String action, long materialId,
                                            OldMaterialValues values) {
        String content;
        try { content = objectMapper.writeValueAsString(materialResult(materialId, null, values)); }
        catch (JsonProcessingException e) { content = "旧料ID=" + materialId; }
        db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) "
                        + "values(:s,:uid,'OLD_MATERIAL',:action,:content,'',now())",
                new MapSqlParameterSource().addValue("s", storeId).addValue("uid", operatorId)
                        .addValue("action", action).addValue("content", content));
    }

    private record OldMaterialValues(String materialType, BigDecimal weight, BigDecimal purity, BigDecimal value) { }

    private String normalizeScopeType(Object raw, boolean allowCustom) {
        String value = raw == null ? "" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if (value.isBlank() && allowCustom) return "CUSTOM";
        if (Set.of("STORE", "CATEGORY_L1", "CATEGORY_L2", "GOODS").contains(value)) return value;
        if (allowCustom && "CUSTOM".equals(value)) return value;
        throw new BusinessException(400205, "盘点范围不合法");
    }

    private void validateCheckScope(long storeId, String scopeType, Long scopeId) {
        if ("STORE".equals(scopeType)) {
            if (scopeId != null && scopeId != storeId) throw new BusinessException(400206, "只能盘点当前门店");
            return;
        }
        if (scopeId == null) throw new BusinessException(400207, "请选择盘点范围");
        String sql;
        if ("CATEGORY_L1".equals(scopeType)) {
            sql = "select count(*) from goods_category where store_id=:s and category_id=:id and level=1 and status=1";
        } else if ("CATEGORY_L2".equals(scopeType)) {
            sql = "select count(*) from goods_category where store_id=:s and category_id=:id and level=2 and status=1";
        } else {
            sql = "select count(*) from goods where store_id=:s and goods_id=:id";
        }
        Integer count = db.jdbc().queryForObject(sql, Map.of("s", storeId, "id", scopeId), Integer.class);
        if (count == null || count == 0) throw new BusinessException(404205, "盘点范围不存在或已禁用");
    }

    private List<Map<String,Object>> scopedGoods(long storeId, String scopeType, Long scopeId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("scope", scopeType).addValue("scopeId", scopeId);
        return db.list("select g.goods_id,g.barcode,g.name,c.category_id,c.name category,c.parent_id parent_category_id,"
                + "pc.name parent_category,g.stock,g.weight,g.sale_price,g.cost_price,g.images "
                + "from goods g left join goods_category c on c.category_id=g.category_id and c.store_id=g.store_id "
                + "left join goods_category pc on pc.category_id=c.parent_id and pc.store_id=c.store_id "
                + "where g.store_id=:s and ("
                + ":scope='STORE' or (:scope='CATEGORY_L1' and (c.parent_id=:scopeId or c.category_id=:scopeId)) "
                + "or (:scope='CATEGORY_L2' and c.category_id=:scopeId) or (:scope='GOODS' and g.goods_id=:scopeId)) "
                + "order by coalesce(pc.sort,c.sort),c.sort,g.goods_id", p);
    }

    private List<Map<String,Object>> buildCheckSnapshot(long storeId, String scopeType, Long scopeId,
                                                         List<Map<String,Object>> submitted) {
        Map<Long,BigDecimal> actualByGoods = new LinkedHashMap<>();
        for (Map<String,Object> row : submitted) {
            long goodsId = Long.parseLong(String.valueOf(row.getOrDefault("goodsId", row.get("id"))));
            BigDecimal actual = checkDecimal(row.get("actual"), null);
            if (actual == null || actual.signum() < 0) throw new BusinessException(400203, "实盘数量不能小于0");
            if (actualByGoods.putIfAbsent(goodsId, actual.setScale(3, RoundingMode.HALF_UP)) != null) {
                throw new BusinessException(400208, "盘点明细存在重复商品");
            }
        }

        List<Map<String,Object>> goods;
        if ("CUSTOM".equals(scopeType)) {
            MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", storeId).addValue("ids", actualByGoods.keySet());
            goods = db.list("select g.goods_id,g.barcode,g.name,c.category_id,c.name category,c.parent_id parent_category_id,"
                    + "pc.name parent_category,g.stock,g.cost_price from goods g "
                    + "left join goods_category c on c.category_id=g.category_id and c.store_id=g.store_id "
                    + "left join goods_category pc on pc.category_id=c.parent_id and pc.store_id=c.store_id "
                    + "where g.store_id=:s and g.goods_id in (:ids) order by g.goods_id", p);
        } else {
            goods = scopedGoods(storeId, scopeType, scopeId);
        }
        Set<Long> serverIds = new LinkedHashSet<>();
        for (Map<String,Object> row : goods) serverIds.add(((Number) row.get("goods_id")).longValue());
        if (!serverIds.equals(actualByGoods.keySet())) {
            throw new BusinessException(409209, "盘点范围商品已变化，请刷新后重新盘点");
        }

        List<Map<String,Object>> result = new ArrayList<>();
        for (Map<String,Object> goodsRow : goods) {
            long goodsId = ((Number) goodsRow.get("goods_id")).longValue();
            BigDecimal stock = checkDecimal(goodsRow.get("stock"), "0").setScale(3, RoundingMode.HALF_UP);
            BigDecimal actual = actualByGoods.get(goodsId);
            BigDecimal diff = actual.subtract(stock).setScale(3, RoundingMode.HALF_UP);
            BigDecimal cost = checkDecimal(goodsRow.get("cost_price"), "0").setScale(2, RoundingMode.HALF_UP);
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("goodsId", goodsId);
            row.put("barcode", goodsRow.get("barcode"));
            row.put("name", goodsRow.get("name"));
            row.put("categoryId", goodsRow.get("category_id"));
            row.put("categoryName", goodsRow.get("category"));
            row.put("parentCategoryName", goodsRow.get("parent_category"));
            row.put("stock", stock);
            row.put("stockSnapshot", stock);
            row.put("actual", actual);
            row.put("difference", diff);
            row.put("costPrice", cost);
            row.put("differenceAmount", diff.multiply(cost).setScale(2, RoundingMode.HALF_UP));
            result.add(row);
        }
        return result;
    }

    private String stockCheckSelect() {
        return "select sc.*,u.real_name operator_name,a.approval_id,a.status approval_status,"
                + "coalesce(sc.approve_remark,a.approve_remark) decision_remark,"
                + "case when sc.scope_type='STORE' then st.store_name when sc.scope_type in ('CATEGORY_L1','CATEGORY_L2') "
                + "then c.name when sc.scope_type='GOODS' then sg.name else '自定义商品' end scope_name "
                + "from stock_check sc left join sys_user u on u.user_id=sc.operator_id and u.store_id=sc.store_id "
                + "left join approval a on a.store_id=sc.store_id and a.type='STOCK_CHECK' and a.biz_id=sc.check_id "
                + "left join sys_store st on st.store_id=sc.store_id "
                + "left join goods_category c on c.category_id=sc.scope_id and c.store_id=sc.store_id "
                + "left join goods sg on sg.goods_id=sc.scope_id and sg.store_id=sc.store_id";
    }

    private Map<String,Object> stockCheckDetail(long checkId, HttpServletRequest r) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("s", db.store(r)).addValue("id", checkId).addValue("u", userId(r));
        String own = "SALES".equals(roleCode(r)) ? " and sc.operator_id=:u" : "";
        List<Map<String,Object>> rows = db.list(stockCheckSelect() + " where sc.store_id=:s and sc.check_id=:id" + own + " limit 1", p);
        if (rows.isEmpty()) throw new BusinessException(404206, "盘点单不存在");
        Map<String,Object> result = new LinkedHashMap<>(rows.get(0));
        addCheckSummary(result);
        result.put("items", detailRows(result.get("detail")));
        return result;
    }

    private void addCheckSummary(Map<String,Object> check) {
        List<Map<String,Object>> detail = detailRows(check.get("detail"));
        int profitCount = 0, lossCount = 0;
        BigDecimal profitAmount = BigDecimal.ZERO, lossAmount = BigDecimal.ZERO;
        for (Map<String,Object> row : detail) {
            BigDecimal diff = difference(row);
            BigDecimal amount = checkDecimal(row.get("differenceAmount"), "0");
            if (diff.signum() > 0) { profitCount++; profitAmount = profitAmount.add(amount.abs()); }
            if (diff.signum() < 0) { lossCount++; lossAmount = lossAmount.add(amount.abs()); }
        }
        check.put("item_count", detail.size());
        check.put("profit_count", profitCount);
        check.put("loss_count", lossCount);
        check.put("profit_amount", profitAmount.setScale(2, RoundingMode.HALF_UP));
        check.put("loss_amount", lossAmount.setScale(2, RoundingMode.HALF_UP));
    }

    private List<Map<String,Object>> detailRows(Object raw) {
        if (raw == null) return List.of();
        try {
            if (raw instanceof String text) return objectMapper.readValue(text, new TypeReference<List<Map<String,Object>>>() { });
            return objectMapper.convertValue(raw, new TypeReference<List<Map<String,Object>>>() { });
        } catch (Exception ignored) { return List.of(); }
    }

    private String roleCode(HttpServletRequest r) {
        io.jsonwebtoken.Claims claims = (io.jsonwebtoken.Claims) r.getAttribute("claims");
        return claims == null ? "" : String.valueOf(claims.get("role"));
    }

    private Long nullableLong(Object raw) {
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try { return Long.parseLong(String.valueOf(raw)); }
        catch (NumberFormatException e) { throw new BusinessException(400207, "盘点范围编号不合法"); }
    }

    private String nullableText(Object raw, int max, String message) {
        if (raw == null || String.valueOf(raw).trim().isBlank()) return null;
        String value = String.valueOf(raw).trim();
        if (value.length() > max) throw new BusinessException(400209, message);
        return value;
    }

    private BigDecimal checkDecimal(Object raw, String fallback) {
        if (raw == null && fallback == null) return null;
        try { return new BigDecimal(raw == null ? fallback : String.valueOf(raw)); }
        catch (NumberFormatException e) { throw new BusinessException(400203, "盘点数量格式不正确"); }
    }

    private String scopeLabel(String scopeType) {
        return Map.of("STORE", "全店", "CATEGORY_L1", "一级分类", "CATEGORY_L2", "二级分类", "GOODS", "指定商品", "CUSTOM", "自定义商品")
                .getOrDefault(scopeType, "盘点");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> rows(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object value : list) if (value instanceof Map<?,?> item) {
            Map<String,Object> row = new LinkedHashMap<>();
            item.forEach((k,v) -> row.put(String.valueOf(k), v));
            Object id = row.getOrDefault("goodsId", row.get("id"));
            if (id == null || row.get("actual") == null) throw new BusinessException(400203, "盘点明细缺少商品或实盘数量");
            BigDecimal actual = checkDecimal(row.get("actual"), null);
            if (actual.signum() < 0) throw new BusinessException(400203, "实盘数量不能小于0");
            result.add(row);
        }
        return result;
    }

    private BigDecimal difference(Map<String,Object> row) {
        if (row.get("difference") != null) return checkDecimal(row.get("difference"), "0");
        Object actual = row.get("actual");
        Object expected = row.getOrDefault("stockSnapshot", row.getOrDefault("stock", 0));
        return checkDecimal(actual, "0").subtract(checkDecimal(expected, "0"));
    }

    private long userId(HttpServletRequest r) { io.jsonwebtoken.Claims c = (io.jsonwebtoken.Claims) r.getAttribute("claims"); return c == null ? 0L : Long.parseLong(c.getSubject()); }
    private BigDecimal configNumber(long storeId, String key, BigDecimal fallback) { try { Map<String,Object> row = db.one("select config_value from sys_config where store_id=:s and config_key=:k and enabled=1", Map.of("s", storeId, "k", key)); return new BigDecimal(String.valueOf(row.get("config_value"))); } catch (Exception e) { return fallback; } }
    private void logOperation(long storeId, long operatorId, String action, String content) { db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,'STOCK',:a,:c,'',now())", Map.of("s", storeId, "u", operatorId, "a", action, "c", content)); }
}
