package com.sanhua.marketingcost.service.costing;

import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

/** CMS 工资和辅料的核算年度从上一年 12 月开始。 */
public final class CmsSourcePeriod {
  private CmsSourcePeriod() {}

  public static List<String> months(int costYear) {
    YearMonth first = YearMonth.of(costYear - 1, 12);
    return IntStream.rangeClosed(0, 12).mapToObj(offset -> first.plusMonths(offset).toString()).toList();
  }

  public static boolean contains(int costYear, YearMonth month) {
    return month.getYear() == costYear || month.equals(YearMonth.of(costYear - 1, 12));
  }
}
