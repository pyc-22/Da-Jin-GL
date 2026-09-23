package com.dajin.system.processing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/** 加工质保单（A4 横版双联）：文案与成品质保单同一套，数据取加工单。 */
final class ProcessingWarrantyHtml {
    private ProcessingWarrantyHtml() {}

    static String build(Map<String, Object> o, String storeName) {
        String billNo = esc(String.valueOf(o.get("order_no")));
        String date = LocalDate.now().toString().replace("-", "/");
        BigDecimal due = dec(o.get("due_amount"));
        BigDecimal paid = dec(o.get("paid_amount"));
        BigDecimal tail = due.subtract(paid).max(BigDecimal.ZERO);
        BigDecimal oldW = o.get("old_gold_weight") == null ? null : dec(o.get("old_gold_weight"));
        BigDecimal oldF = o.get("old_gold_fineness") == null ? null : dec(o.get("old_gold_fineness"));
        String laoliao = oldW == null ? "—" : grams(oldW) + (oldF == null ? "" : " · " + oldF.multiply(new BigDecimal("100")).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%");
        String chengpin = o.get("finished_weight") == null ? "—" : grams(dec(o.get("finished_weight")));
        BigDecimal storeW = o.get("store_gold_weight") == null ? BigDecimal.ZERO : dec(o.get("store_gold_weight"));
        String bujin = storeW.signum() <= 0 ? "—" : grams(storeW);
        String laoliaoZhe = oldW == null ? "" : oldW.multiply(oldF == null ? BigDecimal.ONE : oldF).toPlainString();
        String loss = o.get("loss_weight") == null ? "" : esc(dec(o.get("loss_weight")).toPlainString() + (o.get("loss_permille") != null ? "（" + dec(o.get("loss_permille")).toPlainString() + "‰）" : ""));
        java.util.function.UnaryOperator<String> copy = tag -> "<div class=\"half" + ("red".equals(tag) ? " red" : "") + "\"><span class=\"copy-tag\">"
                + ("red".equals(tag) ? "第二联 客户（红）" : "第一联 存根（白）") + "</span>"
                + "<h1>商品质保单</h1><p class=\"shop\">" + esc(storeName) + " · 黄金业务工作台</p>"
                + "<p class=\"biz\">主营业务：金银加工 ｜ 零损耗 ｜ 黄金回收 ｜ 私人定制 ｜ 珠宝零售</p>"
                + "<div class=\"meta\"><span>单据编号：<b>" + billNo + "</b></span><span>日期：<b>" + date + "</b></span>"
                + "<span>客户姓名：<b>" + esc(String.valueOf(o.getOrDefault("customer_name", "-"))) + "</b></span>"
                + "<span>客户电话：<b>" + esc(String.valueOf(o.getOrDefault("customer_phone", "-"))) + "</b></span></div>"
                + "<table><thead><tr><th>加工项目</th><th>来料</th><th>成品克重</th><th>补金</th><th class=\"num\">工费</th></tr></thead><tbody><tr><td>"
                + esc(String.valueOf(o.getOrDefault("item_name_snapshot", "-"))) + " × " + esc(String.valueOf(o.getOrDefault("quantity", "1")))
                + "</td><td>" + laoliao + "</td><td>" + chengpin + "</td><td>" + bujin + "</td><td class=\"num\">¥" + dec(o.get("labor_fee")).toPlainString() + "</td></tr></tbody></table>"
                + "<h2>费用</h2><div class=\"fee\"><div><span>加工工费</span><span>" + dec(o.get("labor_fee")).toPlainString() + "</span></div>"
                + "<div><span>旧料抵扣</span><span>-" + dec(o.get("residual_gold_deduction")).toPlainString() + "</span></div>"
                + "<div><span>应收金额</span><span>" + due.toPlainString() + "</span></div>"
                + "<div><span>已收定金</span><span>" + paid.toPlainString() + "</span></div>"
                + "<div><span>尾款待收</span><span>" + tail.toPlainString() + "</span></div></div>"
                + "<h2>称重记录（g）</h2><div class=\"weigh\">"
                + "<div><span>来料折重</span><b>" + laoliaoZhe + "</b></div>"
                + "<div><span>成品实重</span><b>" + (o.get("finished_weight") == null ? "" : esc(dec(o.get("finished_weight")).toPlainString())) + "</b></div>"
                + "<div><span>回收屑</span><b>" + (o.get("recovered_weight") == null ? "" : esc(dec(o.get("recovered_weight")).toPlainString())) + "</b></div>"
                + "<div><span>损耗</span><b>" + loss + "</b></div>"
                + "<div><span>余料（手写）</span><b></b></div><div><span>融后金重（手写）</span><b></b></div><div><span>加料（手写）</span><b></b></div></div>"
                + "<p class=\"rights\">客户权益：<span>☑ 终身免费清洗、焊接保养</span><span>□ 正品可复检 假赔十</span><span>△ 人为损坏维修收取工本费</span></p>"
                + "<p class=\"note\">备注：来料加工货品当面验收，离店概不负责。</p>"
                + "<div class=\"sign\"><span>客户签字：<span class=\"line\"></span></span><span>门店盖章：</span></div>"
                + "<p class=\"addr\">地址：河南省郑州市中原区绿东村街道华山路155-3号阳光商务二楼鑫铖金匠</p></div>";
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>加工质保单 " + billNo + "</title><style>"
                + "@page{size:A4 landscape;margin:8mm}body{font:11px/1.5 'Microsoft YaHei',sans-serif;color:#222;margin:0;padding:10px;background:#fff}"
                + ".pair{width:281mm;margin:0 auto;background:#fff;display:flex;box-sizing:border-box}.half{width:50%;padding:7mm 6mm;box-sizing:border-box;position:relative}"
                + ".half + .half{border-left:1px dashed #999}.half.red{background:#fdf1f0;color:#7a1f1a}"
                + ".copy-tag{position:absolute;top:4mm;right:5mm;font-size:10px;font-weight:700;letter-spacing:1px;border:1px solid currentColor;border-radius:4px;padding:0 6px;color:#666}.half.red .copy-tag{color:#c0392b}"
                + "h1{font-size:16px;text-align:center;margin:0 0 2px;letter-spacing:4px}.shop{text-align:center;font-size:10.5px;margin:0 0 4px}.biz{text-align:center;font-size:9.5px;margin:0 0 6px;color:#8a6a2f}.half.red .biz{color:#a04a44}"
                + ".meta{display:grid;grid-template-columns:1fr 1fr;gap:2px 10px;font-size:10.5px;border-top:1px solid #999;border-bottom:1px solid #999;padding:4px 0;margin-bottom:6px}"
                + "table{width:100%;border-collapse:collapse;font-size:10.5px;margin-bottom:6px}th,td{border:1px solid #999;padding:3px 4px;text-align:left}th{background:#f4efe6;font-weight:600}.half.red th{background:#f7e3e1}td.num,th.num{text-align:right}"
                + "h2{font-size:11px;border-left:3px solid #a66b2c;padding-left:5px;margin:7px 0 4px}.half.red h2{border-color:#c0392b}"
                + ".fee{border:1px solid #999}.fee div{display:flex;justify-content:space-between;padding:2px 5px;border-bottom:1px solid #ccc;font-size:10.5px}.fee div:last-child{border-bottom:0}"
                + ".weigh{border:1px solid #999}.weigh div{display:grid;grid-template-columns:26mm 1fr;border-bottom:1px solid #ccc;font-size:10.5px}.weigh div:last-child{border-bottom:0}.weigh span{background:#f4efe6;padding:2px 5px}.half.red .weigh span{background:#f7e3e1}.weigh b{padding:2px 5px;border-bottom:1px dashed #777;min-height:13px;font-weight:400}"
                + ".rights{font-size:10px;margin:6px 0 4px}.rights span{display:block}.note{font-size:10px;margin:0 0 8px}"
                + ".sign{display:flex;justify-content:space-between;align-items:flex-end;font-size:10.5px;margin-top:10px}.sign .line{display:inline-block;width:34mm;border-bottom:1px solid #555;height:18px}"
                + ".addr{margin-top:8px;font-size:9px;color:#777;text-align:center}.half.red .addr{color:#9a5a54}"
                + "@media print{body{padding:0}.pair{margin:0;width:auto}}"
                + "</style></head><body><section class=\"pair\">" + copy.apply("white") + copy.apply("red") + "</section></body></html>";
    }

    private static String esc(String s) { return s == null ? "-" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static BigDecimal dec(Object v) { return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v)); }
    private static String grams(BigDecimal v) { return v.stripTrailingZeros().toPlainString() + "g"; }
}
