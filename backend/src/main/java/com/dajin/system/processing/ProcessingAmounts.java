package com.dajin.system.processing;

/** SQL expressions shared by processing-sales commission and reporting queries. */
public final class ProcessingAmounts {
    private ProcessingAmounts() { }

    /** Processing-sales commission base: labor fee less the labor share of discounts/refunds. */
    public static String laborBase(String alias) {
        String gross = "(coalesce(" + alias + ".labor_fee,0)+coalesce(" + alias + ".store_gold_amount,0))";
        String refunds = "(select coalesce(sum(case when fr.type='EXPENSE' and fr.category in ('PROCESSING_REFUND','REFUND') then fr.amount else 0 end),0) "
                + "from finance_record fr where fr.store_id=" + alias + ".store_id and fr.related_bill_no=" + alias + ".order_no)";
        String share = "case when " + gross + ">0 then coalesce(" + alias + ".labor_fee,0)/" + gross + " else 1 end";
        return "greatest(0,coalesce(" + alias + ".labor_fee,0)-round((coalesce(" + alias + ".promotion_discount,0)+" + refunds + ")*" + share + ",2))";
    }

    public static String commission(String alias, String rateExpression) {
        return "round((" + laborBase(alias) + ")*coalesce(" + alias
                + ".sales_commission_rate_snapshot," + rateExpression + "),2)";
    }

    public static String settledDate(String alias) {
        return "coalesce(" + alias + ".picked_up_time," + alias + ".completed_time," + alias + ".create_time)";
    }
}
