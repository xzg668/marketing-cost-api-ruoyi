package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

/** CMS 提供的是每产品科目金额，不是辅料采购单价。 */
public record TechnicalDataAuxiliaryCmsSource(String materialNo, String name, String model,
    String accountingMonth, String businessUnitType, String fingerprint, List<Item> items) {
  public record Item(Long sourceId, String subjectCode, String subjectName,
      String sourcePeriod, BigDecimal sourceAmount) {}
}
