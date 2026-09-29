package com.dajin.system.pay;
import com.dajin.system.common.*; import com.dajin.system.config.RequirePermission; import com.dajin.system.config.RequireRoles; import com.dajin.system.config.SyncWebSocketHandler; import com.dajin.system.shift.ShiftService; import com.dajin.system.stock.OldMaterialLedgerService; import com.fasterxml.jackson.core.type.TypeReference; import com.fasterxml.jackson.databind.ObjectMapper; import org.springframework.dao.DuplicateKeyException; import org.springframework.jdbc.core.namedparam.*; import org.springframework.transaction.annotation.Transactional; import org.springframework.web.bind.annotation.*; import javax.servlet.http.*; import java.math.*; import java.util.*;
@RestController @RequestMapping("/api/pay") public class PayController {private final DbSupport db; private final SyncWebSocketHandler ws; private final ShiftService shifts; private final OldMaterialLedgerService oldMaterials; private final ObjectMapper objectMapper; public PayController(DbSupport db,SyncWebSocketHandler ws,ShiftService shifts,OldMaterialLedgerService oldMaterials,ObjectMapper objectMapper){this.db=db;this.ws=ws;this.shifts=shifts;this.oldMaterials=oldMaterials;this.objectMapper=objectMapper;}
 @PostMapping("/pay") @RequirePermission("order:checkout") @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED) public ApiResponse<?> pay(@RequestBody Map<String,Object> body,HttpServletRequest r){
  String client=String.valueOf(body.getOrDefault("clientRequestId",""));
  if(client.isBlank())throw new BusinessException(400101,"clientRequestId不能为空");
  Long orderId=null; Object rawOrderId=body.get("orderId");
  if(rawOrderId instanceof Number n) orderId=n.longValue();
  if(orderId==null && body.get("orderClientRequestId")!=null){
   List<Map<String,Object>> orders=db.list("select order_id from sales_order where store_id=:s and client_request_id=:c limit 1",Map.of("s",db.store(r),"c",String.valueOf(body.get("orderClientRequestId"))));
   if(!orders.isEmpty()) orderId=((Number)orders.get(0).get("order_id")).longValue();
  }
  if(orderId==null) throw new BusinessException(409104,"订单尚未同步，稍后重试支付");
  long storeId=db.store(r); Map<String,Object> order=db.one("select * from sales_order where order_id=:o and store_id=:s for update",Map.of("o",orderId,"s",storeId));
  List<Map<String,Object>> done=db.list("select log_id,content from operation_log where store_id=:s and module='PAY' and client_request_id=:c",Map.of("s",storeId,"c",client));
  if(!done.isEmpty()){
   BigDecimal due=new BigDecimal(order.get("total_amount").toString()).multiply(new BigDecimal(order.get("discount").toString())).add(new BigDecimal(order.get("labor_fee").toString())).subtract(new BigDecimal(order.get("old_material_deduct").toString())).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
   BigDecimal paid=decimal(order.get("pay_amount")); BigDecimal discount=decimal(order.get("settlement_discount"));
   return ApiResponse.ok(Map.of("orderId",orderId,"recorded",true,"idempotentReplay",true,"thirdPartyCalled",false,"status",order.get("status"),"actualPaid",paid,"originalDue",due,"settlementDiscount",discount,"remaining",due.subtract(paid).subtract(discount).max(BigDecimal.ZERO)));
  }
  int status=((Number)order.get("status")).intValue();
  if(status==3)throw new BusinessException(409101,"订单仍在等待审批");
  if(status==1)throw new BusinessException(409102,"订单已经结算");
  if(status==4)throw new BusinessException(409105,"订单已被驳回，不能收款");
  if(status!=0)throw new BusinessException(409107,"只有待收款订单可以支付");
  BigDecimal expected=new BigDecimal(order.get("total_amount").toString()).multiply(new BigDecimal(order.get("discount").toString())).add(new BigDecimal(order.get("labor_fee").toString())).subtract(new BigDecimal(order.get("old_material_deduct").toString())).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
  BigDecimal alreadyPaid=decimal(order.get("pay_amount")); BigDecimal remaining=expected.subtract(alreadyPaid).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
  BigDecimal amount=new BigDecimal(String.valueOf(body.getOrDefault("amount",0))).setScale(2,RoundingMode.HALF_UP);
  if(amount.signum()<0||amount.compareTo(remaining)>0)throw new BusinessException(409707,"收款金额超过订单未收金额");
  String settlementMode=String.valueOf(body.getOrDefault("settlementMode","FULL")).trim().toUpperCase(Locale.ROOT);
  if(!Set.of("FULL","PARTIAL","DISCOUNT").contains(settlementMode))throw new BusinessException(400102,"结算方式不合法");
  boolean settled=amount.compareTo(remaining)==0;
  if("FULL".equals(settlementMode)&&!settled)throw new BusinessException(400102,"收款金额与订单未收金额不一致");
  if("PARTIAL".equals(settlementMode)&&(amount.signum()<=0||settled))throw new BusinessException(400102,"部分收款金额必须小于未收金额");
   BigDecimal settlementDiscount=BigDecimal.ZERO; String discountReason=null;
   BigDecimal existingDiscount=decimal(order.get("settlement_discount"));
   String existingDiscountReason=order.get("settlement_discount_reason") == null ? null : String.valueOf(order.get("settlement_discount_reason"));
  if("DISCOUNT".equals(settlementMode)){
   if(amount.signum()<=0||settled)throw new BusinessException(400102,"优惠结清必须实际收款且小于未收金额");
   discountReason=String.valueOf(body.getOrDefault("settlementDiscountReason","")).trim();
   if(discountReason.isBlank()||discountReason.length()>200)throw new BusinessException(400108,"优惠结清必须填写原因");
    settlementDiscount=remaining.subtract(amount).setScale(2,RoundingMode.HALF_UP); settled=true;
  }
   BigDecimal finalPaid=alreadyPaid.add(amount).setScale(2,RoundingMode.HALF_UP);
   BigDecimal totalSettlementDiscount=existingDiscount.add(settlementDiscount).setScale(2,RoundingMode.HALF_UP);
   String totalDiscountReason=discountReason == null ? existingDiscountReason : discountReason;
  List<PaymentLine> lines=parseLines(body,amount);
  for(PaymentLine line:lines)PaymentChannelPolicy.requireActiveCollection(db,storeId,line.method());
  BigDecimal balanceLine=lines.stream().filter(x->"BALANCE".equalsIgnoreCase(x.method())).map(PaymentLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
  if(balanceLine.signum()>0){if(order.get("member_id")==null)throw new BusinessException(400106,"储值支付必须关联会员");int balanceChanged=db.jdbc().update("update member set balance=balance-:b,update_time=now() where member_id=:m and store_id=:s and balance>=:b",new MapSqlParameterSource().addValue("b",balanceLine).addValue("m",order.get("member_id")).addValue("s",storeId));if(balanceChanged==0)throw new BusinessException(409106,"会员储值余额不足");}
  String method=lines.isEmpty()?"NO_PAYMENT":lines.stream().map(x->x.method).collect(java.util.stream.Collectors.joining("+"));
  if(balanceLine.signum()>0)new com.dajin.system.member.MemberBalanceLedger(db).record(storeId,order.get("member_id"),balanceLine.negate(),"SALE",String.valueOf(orderId)+":"+client,userId(r));
  String shiftNo=shifts.current(storeId); String oldMaterialPayoutMethod=resolveOldMaterialPayoutMethod(order,body,r);
  BigDecimal oldExcess=decimal(order.get("old_material_excess"));
  if(!settled&&oldExcess.signum()>0)throw new BusinessException(400109,"含旧金超额返款的订单必须最终结算");
  db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'PAY','RECORD',:content,:client,'',now())",new MapSqlParameterSource().addValue("s",storeId).addValue("uid",userId(r)).addValue("content",body.toString()).addValue("client",client));
   for(PaymentLine line:lines)db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'INCOME','SALE',:a,:m,:no,:uid,:remark,:shift,now())",new MapSqlParameterSource().addValue("s",storeId).addValue("no",order.get("order_no")).addValue("m",line.method).addValue("a",line.amount).addValue("uid",userId(r)).addValue("remark",settlementDiscount.signum()>0?"销售优惠结清："+discountReason:"内部收款记录").addValue("shift",shiftNo));
  if(settled){recordOldMaterialExcess(order,oldMaterialPayoutMethod,shiftNo,r);consumeOrderInventory(orderId,r);}
   int changed=db.jdbc().update("update sales_order set status=:status,pay_amount=:paid,pay_method=:m,settlement_discount=:discount,settlement_discount_reason=:reason,old_material_payout_method=coalesce(:payoutMethod,old_material_payout_method),shift_no=:shift,paid_time=case when :status=1 then now() else paid_time end,update_time=now(),version=version+1 where order_id=:o and store_id=:s and status=0",new MapSqlParameterSource().addValue("status",settled?1:0).addValue("paid",finalPaid).addValue("m",method).addValue("discount",totalSettlementDiscount).addValue("reason",totalDiscountReason).addValue("payoutMethod",oldMaterialPayoutMethod).addValue("shift",shiftNo).addValue("o",orderId).addValue("s",storeId));
  if(changed!=1)throw new BusinessException(409107,"订单状态已变化，请刷新后重试");
  if(settlementDiscount.signum()>0)notifyNegotiatedSale(storeId,orderId,String.valueOf(order.get("order_no")),expected,finalPaid,settlementDiscount,discountReason);
  if(settled){createPurchaseVisit(orderId,order,finalPaid,r);oldMaterials.activateAndRecord(storeId,"ORDER:"+orderId,userId(r));if(order.get("sales_id")!=null)new com.dajin.system.commission.CommissionLedger(db).recordSale(storeId,orderId);if(order.get("member_id")!=null){db.jdbc().update("insert into member_consume(store_id,member_id,order_id,amount,consume_time) values(:s,:m,:o,:a,now())",new MapSqlParameterSource().addValue("s",storeId).addValue("m",order.get("member_id")).addValue("o",orderId).addValue("a",finalPaid));db.jdbc().update("update member set total_consume=total_consume+:a,update_time=now() where member_id=:m and store_id=:s",new MapSqlParameterSource().addValue("s",storeId).addValue("m",order.get("member_id")).addValue("a",finalPaid));}wsBroadcast(orderId,finalPaid,order,r);}else{ws.broadcast("ORDER_UPDATED",Map.of("storeId",storeId,"orderId",orderId,"amount",amount));ws.broadcast("REPORT_UPDATED",Map.of("storeId",storeId,"orderId",orderId,"action","PARTIAL_PAYMENT"));}
   Map<String,Object> result=new LinkedHashMap<>();result.put("orderId",orderId);result.put("recorded",true);result.put("idempotentReplay",false);result.put("thirdPartyCalled",false);result.put("paymentLines",lines);result.put("shiftNo",shiftNo);result.put("status",settled?1:0);result.put("actualPaid",finalPaid);result.put("originalDue",expected);result.put("settlementDiscount",totalSettlementDiscount);result.put("remaining",settled?BigDecimal.ZERO:expected.subtract(finalPaid).subtract(totalSettlementDiscount).max(BigDecimal.ZERO));result.put("oldMaterialExcess",oldExcess);if(oldMaterialPayoutMethod!=null)result.put("oldMaterialPayoutMethod",oldMaterialPayoutMethod);return ApiResponse.ok(result);
 }

 private void notifyNegotiatedSale(long storeId,long orderId,String orderNo,BigDecimal originalDue,BigDecimal actualPaid,BigDecimal discount,String reason){
  List<Map<String,Object>> receivers=db.list("select u.user_id from sys_user u join sys_role r on r.role_id=u.role_id and r.store_id=u.store_id where u.store_id=:s and u.status=1 and r.status=1 and r.role_code in ('ADMIN','MANAGER')",Map.of("s",storeId));
  String content="议价成交：订单"+orderNo+"，原应收 ¥"+originalDue+"，实收 ¥"+actualPaid+"，议价优惠 ¥"+discount+"；原因："+reason;
  for(Map<String,Object> receiver:receivers){
   long uid=((Number)receiver.get("user_id")).longValue();
   db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,client_request_id,ip,create_time) values(:s,:uid,'NOTIFICATION','BARGAIN',:content,:client,'',now())",new MapSqlParameterSource().addValue("s",storeId).addValue("uid",uid).addValue("content",content).addValue("client","BARGAIN-"+orderId+"-"+uid));
  }
  ws.broadcast("BARGAIN_RECORDED",Map.of("storeId",storeId,"orderId",orderId));
 }

 void consumeOrderInventory(long orderId,HttpServletRequest r){
  long storeId=db.store(r);
  List<Map<String,Object>> items=db.list("select goods_id,qty,piece_nos,order_item_id from sales_order_item where order_id=:o and store_id=:s and goods_id is not null order by goods_id,order_item_id",Map.of("o",orderId,"s",storeId));
  for(Map<String,Object> item:items){
   MapSqlParameterSource p=new MapSqlParameterSource().addValue("qty",item.get("qty")).addValue("g",item.get("goods_id")).addValue("s",storeId).addValue("o",orderId);
   int changed=db.jdbc().update("update goods set stock=stock-:qty,version=version+1,update_time=now() where goods_id=:g and store_id=:s and stock>=:qty",p);
   if(changed==0)throw new BusinessException(409103,"商品库存不足或版本已变化: "+item.get("goods_id"));
   List<String> pieceNos=pieceNos(item.get("piece_nos"));
   int pieceQty=(int)Math.round(new BigDecimal(item.get("qty").toString()).doubleValue());
    if(!pieceNos.isEmpty()){
     if(pieceQty!=pieceNos.size())throw new BusinessException(409114,"订单数量与单件码数量不一致");
     int pieceChanged=db.jdbc().update("update goods_piece set status=0,sales_order_id=:o,update_time=now() where store_id=:s and goods_id=:g and piece_no in (:pieceNos) and ((status=2 and sales_order_id=:o) or (status=1 and sales_order_id is null))",p.addValue("pieceNos",pieceNos));
     if(pieceChanged!=pieceNos.size())throw new BusinessException(409115,"所选单件码已出库或被其他订单占用，请重新扫码开单");
    }else if(pieceQty>0 && !Integer.valueOf(0).equals(db.jdbc().queryForObject("select count(*) from goods_piece where store_id=:s and goods_id=:g",p,Integer.class))){
     int reservedChanged=db.jdbc().update("update goods_piece set status=0,update_time=now() where piece_id in (select piece_id from (select piece_id from goods_piece where store_id=:s and goods_id=:g and status=2 and sales_order_id=:o order by piece_id limit :pieceLimit) t)",p.addValue("pieceLimit",pieceQty));
     int remaining=pieceQty-reservedChanged;
      if(remaining>0){
       int availableChanged=db.jdbc().update("update goods_piece set status=0,sales_order_id=:o,update_time=now() where piece_id in (select piece_id from (select piece_id from goods_piece where store_id=:s and goods_id=:g and status=1 and sales_order_id is null order by piece_id limit :pieceLimit) t)",p.addValue("pieceLimit",remaining));
       if(availableChanged!=remaining)throw new BusinessException(409115,"单件库存与订单数量不一致，请重新开单");
      }
    }
    db.jdbc().update("insert into stock_out(store_id,bill_no,type,goods_id,qty,reason,operator_id,create_time) values(:s,:no,'SALE',:g,:qty,:reason,:uid,now())",
      p.addValue("no","SALE-"+item.get("order_item_id")).addValue("reason","销售订单:"+orderId).addValue("uid",userId(r)));
  }
 }

 private List<String> pieceNos(Object raw){
  if(raw==null||String.valueOf(raw).isBlank())return List.of();
  try{return objectMapper.readValue(String.valueOf(raw),new TypeReference<List<String>>(){});}
  catch(Exception e){throw new BusinessException(409114,"订单单件码格式不正确");}
 }
 private void createPurchaseVisit(long orderId, Map<String,Object> order, BigDecimal amount, HttpServletRequest r){
  if(order.get("member_id")==null)return;
  MapSqlParameterSource p=new MapSqlParameterSource().addValue("s",db.store(r)).addValue("order",orderId).addValue("m",order.get("member_id")).addValue("sales",order.get("sales_id")).addValue("no",order.get("order_no")).addValue("amount",amount);
  db.jdbc().update("insert ignore into visit_task(store_id,member_id,sales_id,order_id,order_no_snapshot,amount_snapshot,purchase_time_snapshot,phone_snapshot,gender_snapshot,visit_type,plan_time,status,create_time,update_time) select :s,:m,coalesce(:sales,0),:order,:no,:amount,now(),phone,gender,'PURCHASE_3D',date_add(now(),interval 3 day),1,now(),now() from member where member_id=:m and store_id=:s",p);
 }  @GetMapping("/methods") public ApiResponse<?> methods(HttpServletRequest r){return ApiResponse.ok(db.list("select channel_id,channel_name,channel_code,sort,status,icon from pay_channel where store_id=:s and status=1 order by sort,channel_id",Map.of("s",db.store(r))));}
 @GetMapping("/channels") @RequireRoles({"ADMIN","MANAGER"}) public ApiResponse<?> channels(HttpServletRequest r){return ApiResponse.ok(db.list("select channel_id,channel_name,channel_code,sort,status,icon,create_time from pay_channel where store_id=:s order by sort,channel_id",Map.of("s",db.store(r))));}
   @PostMapping("/channels") @RequireRoles({"ADMIN","MANAGER"}) @Transactional public ApiResponse<?> createChannel(@RequestBody Map<String,Object> q,HttpServletRequest r){
    long storeId=db.store(r); ChannelInput input=channelInput(q,null);
    if(channelCount(storeId,input.code())>0)throw new BusinessException(409305,"支付渠道编码已存在");
    try{db.jdbc().update("insert into pay_channel(store_id,channel_name,channel_code,sort,status,icon) values(:s,:n,:c,:sort,:st,:icon)",
      new MapSqlParameterSource().addValue("s",storeId).addValue("n",input.name()).addValue("c",input.code()).addValue("sort",input.sort()).addValue("st",input.status()).addValue("icon",input.icon()));}
    catch(DuplicateKeyException e){throw new BusinessException(409305,"支付渠道编码已存在");}
    logChannel(storeId,userId(r),"CREATE",input.name()+"（"+input.code()+"）");
    ws.broadcast("PAY_CHANNELS_UPDATED",Map.of("storeId",storeId,"action","CREATE","channelCode",input.code()));return ApiResponse.ok();
   }
   @PutMapping("/channels/{id}") @RequireRoles({"ADMIN","MANAGER"}) @Transactional public ApiResponse<?> updateChannel(@PathVariable long id,@RequestBody Map<String,Object> q,HttpServletRequest r){
    long storeId=db.store(r); Map<String,Object> current=channel(storeId,id); ChannelInput input=channelInput(q,current);
    if(channelCount(storeId,input.code(),id)>0)throw new BusinessException(409305,"支付渠道编码已存在");
    try{db.jdbc().update("update pay_channel set channel_name=:n,channel_code=:c,sort=:sort,status=:st,icon=:icon where channel_id=:id and store_id=:s",
      new MapSqlParameterSource().addValue("id",id).addValue("s",storeId).addValue("n",input.name()).addValue("c",input.code()).addValue("sort",input.sort()).addValue("st",input.status()).addValue("icon",input.icon()));}
    catch(DuplicateKeyException e){throw new BusinessException(409305,"支付渠道编码已存在");}
    String action=input.status()!=number(current.get("status"))?(input.status()==1?"ENABLE":"DISABLE"):"UPDATE";
    logChannel(storeId,userId(r),action,input.name()+"（"+input.code()+"）");
    ws.broadcast("PAY_CHANNELS_UPDATED",Map.of("storeId",storeId,"action",action,"channelId",id));return ApiResponse.ok();
   }
   @DeleteMapping("/channels/{id}") @RequireRoles({"ADMIN","MANAGER"}) @Transactional public ApiResponse<?> deleteChannel(@PathVariable long id,HttpServletRequest r){
    long storeId=db.store(r); Map<String,Object> current=channel(storeId,id);
    int changed=db.jdbc().update("delete from pay_channel where channel_id=:id and store_id=:s",Map.of("id",id,"s",storeId));
    if(changed==0)throw new BusinessException(404305,"支付渠道不存在");
    logChannel(storeId,userId(r),"DELETE",String.valueOf(current.get("channel_name"))+"（"+current.get("channel_code")+"）");
    ws.broadcast("PAY_CHANNELS_UPDATED",Map.of("storeId",storeId,"action","DELETE","channelId",id));return ApiResponse.ok();
   }
   private record ChannelInput(String name,String code,int sort,int status,String icon){}
   private ChannelInput channelInput(Map<String,Object> q,Map<String,Object> current){
    String name=text(q,"channelName","channel_name",current==null?null:current.get("channel_name")).trim();
    String code=text(q,"channelCode","channel_code",current==null?null:current.get("channel_code")).trim().toUpperCase(Locale.ROOT);
    if(name.isBlank()||name.length()>50)throw new BusinessException(400305,"支付渠道名称不能为空且不能超过50个字符");
    if(!code.matches("[A-Z][A-Z0-9_]{1,49}"))throw new BusinessException(400306,"支付渠道编码须为2-50位大写字母、数字或下划线，且以字母开头");
    int sort=intValue(q,"sort",current==null?0:number(current.get("sort"))); if(sort<0||sort>9999)throw new BusinessException(400307,"支付渠道排序必须在0到9999之间");
    int status=intValue(q,"status",current==null?1:number(current.get("status"))); if(status!=0&&status!=1)throw new BusinessException(400308,"支付渠道状态不合法");
    String icon=text(q,"icon",null,current==null?null:current.get("icon")); if(icon.length()>255)throw new BusinessException(400309,"支付渠道图标标识不能超过255个字符");
    return new ChannelInput(name,code,sort,status,icon.isBlank()?null:icon);
   }
   private Map<String,Object> channel(long storeId,long id){List<Map<String,Object>> rows=db.list("select channel_id,channel_name,channel_code,sort,status,icon from pay_channel where channel_id=:id and store_id=:s",Map.of("id",id,"s",storeId));if(rows.isEmpty())throw new BusinessException(404305,"支付渠道不存在");return rows.get(0);}
   private int channelCount(long storeId,String code){return db.jdbc().queryForObject("select count(*) from pay_channel where store_id=:s and channel_code=:c",Map.of("s",storeId,"c",code),Integer.class);}
   private int channelCount(long storeId,String code,long exclude){return db.jdbc().queryForObject("select count(*) from pay_channel where store_id=:s and channel_code=:c and channel_id<>:id",Map.of("s",storeId,"c",code,"id",exclude),Integer.class);}
   private void logChannel(long storeId,long operatorId,String action,String content){db.jdbc().update("insert into operation_log(store_id,user_id,module,action,content,ip,create_time) values(:s,:u,'PAY',:a,:c,'',now())",Map.of("s",storeId,"u",operatorId,"a",action,"c",content));}
   private static String text(Map<String,Object> q,String primary,String alternate,Object fallback){Object value=q.containsKey(primary)?q.get(primary):alternate!=null&&q.containsKey(alternate)?q.get(alternate):fallback;return value==null?"":String.valueOf(value);}
   private static int intValue(Map<String,Object> q,String key,int fallback){Object value=q.containsKey(key)?q.get(key):fallback;try{return value==null?fallback:new BigDecimal(String.valueOf(value)).intValueExact();}catch(Exception e){throw new BusinessException(400307,"支付渠道排序或状态格式不正确");}}
   private static int number(Object value){try{return value==null?0:new BigDecimal(String.valueOf(value)).intValue();}catch(Exception e){return 0;}}
 record PaymentLine(String method, BigDecimal amount) {}
 @SuppressWarnings("unchecked") List<PaymentLine> parseLines(Map<String,Object> body,BigDecimal expected){List<PaymentLine> lines=new ArrayList<>();Object raw=body.get("paymentDetails");if(raw instanceof List<?> list){for(Object value:list){if(!(value instanceof Map<?,?> m))continue;Object method=m.get("method");if(method==null)method=m.get("payMethod");if(method==null)method=m.get("channelCode");Object amount=m.get("amount");if(method==null||amount==null)continue;BigDecimal lineAmount=new BigDecimal(String.valueOf(amount)).setScale(2,RoundingMode.HALF_UP);if(lineAmount.signum()<=0)throw new BusinessException(400103,"支付明细金额必须大于0");lines.add(new PaymentLine(String.valueOf(method).trim().toUpperCase(Locale.ROOT),lineAmount));}}if(lines.isEmpty()&&expected.signum()>0)lines.add(new PaymentLine(String.valueOf(body.getOrDefault("payMethod","UNKNOWN")).trim().toUpperCase(Locale.ROOT),expected));BigDecimal total=lines.stream().map(PaymentLine::amount).reduce(BigDecimal.ZERO,BigDecimal::add).setScale(2,RoundingMode.HALF_UP);if(total.compareTo(expected)!=0)throw new BusinessException(400102,"支付明细合计与订单应收金额不一致");return lines;}
 private String resolveOldMaterialPayoutMethod(Map<String,Object> order,Map<String,Object> body,HttpServletRequest r){BigDecimal excess=decimal(order.get("old_material_excess"));if(excess.signum()<=0)return null;Object requested=body.get("oldMaterialPayoutMethod");String method=String.valueOf(requested==null?order.get("old_material_payout_method"):requested).trim().toUpperCase(Locale.ROOT);if(method.isBlank()||"NULL".equals(method)||"BALANCE".equals(method)||"COMBINATION".equals(method)||PaymentChannelPolicy.isGroupChannel(method))throw new BusinessException(400107,"请选择现金、微信、支付宝或银行卡作为超额旧金返款方式");Integer count=db.jdbc().queryForObject("select count(*) from pay_channel where store_id=:s and channel_code=:code and status=1",Map.of("s",db.store(r),"code",method),Integer.class);if(count==null||count==0)throw new BusinessException(400107,"超额旧金返款方式未启用");return method;}
 void recordOldMaterialExcess(Map<String,Object> order,String payoutMethod,String shiftNo,HttpServletRequest r){BigDecimal excess=decimal(order.get("old_material_excess"));if(excess.signum()<=0)return;db.jdbc().update("insert into finance_record(store_id,type,category,amount,pay_method,related_bill_no,operator_id,remark,shift_no,create_time) values(:s,'EXPENSE','RECYCLE',:amount,:pay,:no,:uid,'销售旧金超额回收付款',:shift,now())",new MapSqlParameterSource().addValue("s",db.store(r)).addValue("amount",excess).addValue("pay",payoutMethod).addValue("no",order.get("order_no")).addValue("uid",userId(r)).addValue("shift",shiftNo));}
 private BigDecimal decimal(Object value){return value==null?BigDecimal.ZERO.setScale(2,RoundingMode.HALF_UP):new BigDecimal(String.valueOf(value)).setScale(2,RoundingMode.HALF_UP);}
 private long userId(HttpServletRequest r){io.jsonwebtoken.Claims c=(io.jsonwebtoken.Claims)r.getAttribute("claims");return c==null?0L:Long.parseLong(c.getSubject());}
 private void wsBroadcast(long orderId,BigDecimal amount,Map<String,Object> order,HttpServletRequest r){Map<String,Object> data=new HashMap<>();data.put("storeId",order.get("store_id"));data.put("orderId",orderId);data.put("amount",amount);data.put("salesId",order.get("sales_id"));if(order.get("member_id")!=null)data.put("memberId",order.get("member_id"));ws.broadcast("ORDER_COMPLETED",data);ws.broadcast("STOCK_UPDATED",data);ws.broadcast("REPORT_UPDATED",data);ws.broadcast("COMMISSION_UPDATED",data);if(order.get("member_id")!=null){ws.broadcast("MEMBER_UPDATED",data);ws.broadcast("VISIT_TASK_UPDATED",data);}}
}
