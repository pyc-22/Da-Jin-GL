package com.dajin.system.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SalesAmountsTests {
    @Test
    void netPaymentExcludesUnrelatedFinanceAndKeepsLegacyFallback() {
        String sql = SalesAmounts.actualPaid("o");
        assertTrue(sql.contains("fr.category='SALE'"));
        assertTrue(sql.contains("fr.category='SALE_REFUND' then -fr.amount"));
        assertTrue(sql.contains("fr.store_id=o.store_id"));
        assertTrue(sql.contains("fr.related_bill_no=o.order_no"));
        assertTrue(sql.contains("case when o.status=5 then 0 else coalesce(o.pay_amount,0) end"));
    }

    @Test
    void discountedAndRemainingAmountsUseNetPayment() {
        assertTrue(SalesAmounts.discountedDue("o").contains("o.settlement_discount"));
        assertTrue(SalesAmounts.remainingDue("o").contains("SALE_REFUND"));
    }
}
