package com.dajin.system.pay;

import com.dajin.system.common.BusinessException;
import com.dajin.system.common.DbSupport;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentChannelPolicyTests {
    @Test
    void groupChannelIsActiveOnlyForProcessingCollection() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(contains("from pay_channel"), anyMap(), eq(Integer.class))).thenReturn(1);

        assertEquals("DOUYIN_GROUP", PaymentChannelPolicy.requireActiveProcessingCollection(db, 1L, "douyin_group"));
        assertEquals(400310, assertThrows(BusinessException.class,
                () -> PaymentChannelPolicy.requireActiveCollection(db, 1L, "DOUYIN_GROUP")).getCode());
        assertEquals(400310, assertThrows(BusinessException.class,
                () -> PaymentChannelPolicy.requireActiveExternal(db, 1L, "MEITUAN_GROUP")).getCode());
    }

    @Test
    void disabledGroupChannelIsRejectedForProcessing() {
        DbSupport db = mock(DbSupport.class);
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(db.jdbc()).thenReturn(jdbc);
        when(jdbc.queryForObject(anyString(), anyMap(), eq(Integer.class))).thenReturn(0);
        assertEquals(400310, assertThrows(BusinessException.class,
                () -> PaymentChannelPolicy.requireActiveProcessingCollection(db, 1L, "DOUYIN_GROUP")).getCode());
    }
}
