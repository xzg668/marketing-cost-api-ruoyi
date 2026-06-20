package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.time.LocalDate;

public interface FormalBomReadService {

  default FormalBomReadResult read(String productCode, String periodMonth, String bomPurpose) {
    return read(productCode, periodMonth, bomPurpose, null);
  }

  FormalBomReadResult read(
      String productCode, String periodMonth, String bomPurpose, LocalDate quoteDate);

  default FormalBomReadResult read(
      String productCode,
      String periodMonth,
      String bomPurpose,
      LocalDate quoteDate,
      String organizationCode) {
    return read(productCode, periodMonth, bomPurpose, quoteDate);
  }

  default FormalBomReadResult readForCommercial(
      String productCode, String periodMonth, String bomPurpose, LocalDate quoteDate) {
    return read(productCode, periodMonth, bomPurpose, quoteDate, MaterialOrganization.COMMERCIAL.getCode());
  }
}
