package com.dajin.system.gold;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/** Published exchange closures, supplemented by each store's gold_market_holidays config. */
final class GoldTradingCalendar {
    // SGE 2026 calendar: https://www.sge.com.cn/jjsnotice/10007108
    // National Day confirmation: https://www.sge.com.cn/jjsnotice/10007575
    private static final Set<LocalDate> HOLIDAYS = new HashSet<>();
    static {
        add("2026-01-01", "2026-01-03");
        add("2026-02-15", "2026-02-23");
        add("2026-04-04", "2026-04-06");
        add("2026-05-01", "2026-05-05");
        add("2026-06-19", "2026-06-21");
        add("2026-09-25", "2026-09-27");
        add("2026-10-01", "2026-10-07");
    }
    private static void add(String start, String end) {
        LocalDate last = LocalDate.parse(end);
        for (LocalDate date = LocalDate.parse(start); !date.isAfter(last); date = date.plusDays(1)) HOLIDAYS.add(date);
    }
    static Set<LocalDate> holidays() { return new HashSet<>(HOLIDAYS); }
    private GoldTradingCalendar() {}
}
