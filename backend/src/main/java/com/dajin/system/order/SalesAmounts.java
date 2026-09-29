package com.dajin.system.order;

/** SQL expressions for sales settlement amounts shared by orders and reports. */
public final class SalesAmounts {
    private SalesAmounts() {}

    public static String actualPaid(String alias) {
        return "greatest(0,coalesce((select sum(case when fr.type='INCOME' and fr.category='SALE' then fr.amount "
                + "when fr.type='EXPENSE' and fr.category='SALE_REFUND' then -fr.amount else 0 end) "
                + "from finance_record fr where fr.store_id=" + alias + ".store_id and fr.related_bill_no=" + alias + ".order_no "
                + "and ((fr.type='INCOME' and fr.category='SALE') or (fr.type='EXPENSE' and fr.category='SALE_REFUND'))),"
                + "case when " + alias + ".status=5 then 0 else coalesce(" + alias + ".pay_amount,0) end))";
    }

    public static String originalDue(String alias) {
        return "greatest(0,coalesce(" + alias + ".total_amount,0)*coalesce(" + alias + ".discount,1)"
                + "+coalesce(" + alias + ".labor_fee,0)-coalesce(" + alias + ".old_material_deduct,0))";
    }

    public static String discountedDue(String alias) {
        return "greatest(0,(" + originalDue(alias) + ")-coalesce(" + alias + ".settlement_discount,0))";
    }

    public static String remainingDue(String alias) {
        return "greatest(0,(" + discountedDue(alias) + ")-(" + actualPaid(alias) + "))";
    }
}
