package com.dajin.system.pay;

import com.dajin.system.common.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class CombinationPaymentTests {
    private static final Function<String, String> resolve = code -> code;

    @Test void acceptsPartsWhoseAmountsAddUpToTheCollectedTotal() {
        List<CombinationPayment.Part> parts = CombinationPayment.parse(
                List.of(Map.of("payMethod", "CASH", "amount", "500"), Map.of("payMethod", "WECHAT", "amount", 500)),
                new BigDecimal("1000.00"), resolve);
        assertEquals(2, parts.size());
        assertEquals("CASH", parts.get(0).method());
        assertEquals(new BigDecimal("500.00"), parts.get(1).amount());
    }

    @Test void rejectsWhenThePartsDoNotAddUp() {
        var error = assertThrows(BusinessException.class, () -> CombinationPayment.parse(
                List.of(Map.of("payMethod", "CASH", "amount", "500"), Map.of("payMethod", "WECHAT", "amount", "400")),
                new BigDecimal("1000.00"), resolve));
        assertEquals("组合支付明细合计必须等于本次收款金额", error.getMessage());
    }

    @Test void rejectsDuplicateChannel() {
        var error = assertThrows(BusinessException.class, () -> CombinationPayment.parse(
                List.of(Map.of("payMethod", "CASH", "amount", "500"), Map.of("payMethod", "cash", "amount", "500")),
                new BigDecimal("1000.00"), resolve));
        assertEquals("组合支付里同一收款方式只能出现一次", error.getMessage());
    }

    @Test void rejectsBadShapeAmountsAndEmptyDetails() {
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(List.of(), BigDecimal.TEN, resolve));
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(List.of("现金"), BigDecimal.TEN, resolve));
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(List.of(Map.of("amount", "10")), BigDecimal.TEN, resolve));
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(List.of(Map.of("payMethod", "CASH", "amount", "0")), BigDecimal.TEN, resolve));
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(List.of(Map.of("payMethod", "CASH", "amount", "10.001")), BigDecimal.TEN, resolve));
    }

    @Test void surfacesChannelValidationErrorsAsIs() {
        var error = assertThrows(BusinessException.class, () -> CombinationPayment.parse(
                List.of(Map.of("payMethod", "DOUYIN_GROUP", "amount", "10")), BigDecimal.TEN,
                code -> { throw new BusinessException(400310, "支付方式未启用"); }));
        assertEquals("支付方式未启用", error.getMessage());
    }

    @Test void recognisesStoredValueChannelsEvenWhenTheStoreUsesItsOwnCode() {
        // 生产门店的储值 code 是 CHUZHI，本地种子是 BALANCE —— 两套都必须认出来
        assertTrue(PaymentChannelPolicy.isBalanceChannel(null, 1L, "BALANCE"));
        assertTrue(PaymentChannelPolicy.isBalanceChannel(null, 1L, "chuzhi"));
        assertFalse(PaymentChannelPolicy.isBalanceChannel(null, 1L, "SQBWX"));
        assertFalse(PaymentChannelPolicy.isBalanceChannel(null, 1L, "XIANJIN"));
        assertFalse(PaymentChannelPolicy.isBalanceChannel(null, 1L, ""));
    }

    @Test void describesTheSplitForTheReceipt() {
        String text = CombinationPayment.describe(
                List.of(new CombinationPayment.Part("CASH", new BigDecimal("500.00"), null), new CombinationPayment.Part("WECHAT", new BigDecimal("500.00"), null)),
                code -> "CASH".equals(code) ? "现金" : "微信");
        assertEquals("现金 500.00 + 微信 500.00", text);
        assertEquals("CASH 500.00", CombinationPayment.describe(List.of(new CombinationPayment.Part("CASH", new BigDecimal("500.00"), null)), null));
    }

    @Test void supportsOneGroupBuyVoucherTogetherWithOtherMethods() {
        List<CombinationPayment.Part> parts = CombinationPayment.parse(List.of(
                        Map.of("payMethod", "DOUYIN_GROUP", "amount", "500", "voucherNo", "DY-123"),
                        Map.of("payMethod", "CASH", "amount", "500")),
                new BigDecimal("1000.00"), resolve);
        var group = CombinationPayment.groupPart(parts);
        assertNotNull(group);
        assertEquals("DOUYIN_GROUP", group.method());
        assertEquals("DY-123", group.voucherNo());
    }

    @Test void rejectsTwoGroupVouchersNestedCombinationAndBalance() {
        var twoGroups = CombinationPayment.parse(List.of(
                Map.of("payMethod", "DOUYIN_GROUP", "amount", "500", "voucherNo", "A"),
                Map.of("payMethod", "MEITUAN_GROUP", "amount", "500", "voucherNo", "B")), new BigDecimal("1000.00"), resolve);
        assertEquals("组合支付里团购核销只能有一笔", assertThrows(BusinessException.class, () -> CombinationPayment.groupPart(twoGroups)).getMessage());
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(
                List.of(Map.of("payMethod", "COMBINATION", "amount", "10")), BigDecimal.TEN, resolve));
        assertThrows(BusinessException.class, () -> CombinationPayment.parse(
                List.of(Map.of("payMethod", "BALANCE", "amount", "10")), BigDecimal.TEN, resolve));
        var tooLong = assertThrows(BusinessException.class, () -> CombinationPayment.parse(
                List.of(Map.of("payMethod", "DOUYIN_GROUP", "amount", "10", "voucherNo", "x".repeat(101))), BigDecimal.TEN, resolve));
        assertEquals("团购核销单号不能超过100字", tooLong.getMessage());
    }
}
