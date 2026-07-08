package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.time.LocalDate;

public interface FormalBomReadService {

  default FormalBomReadResult read(String productCode, String periodMonth, String bomPurpose) {
    throw new IllegalArgumentException("读取正式 BOM 必须显式传入报价组织和料品组织");
  }

  FormalBomReadResult read(
      String productCode, String periodMonth, String bomPurpose, LocalDate quoteDate);

  FormalBomReadResult read(
      String productCode,
      String periodMonth,
      String bomPurpose,
      LocalDate quoteDate,
      QuoteDataOrganization organization);

  default FormalBomReadResult read(
      String productCode,
      String periodMonth,
      String bomPurpose,
      LocalDate quoteDate,
      String organizationCode) {
    return read(
        productCode,
        periodMonth,
        bomPurpose,
        quoteDate,
        MaterialOrganization.fromCode(organizationCode).toQuoteDataOrganization());
  }

  default FormalBomReadResult readForCommercial(
      String productCode, String periodMonth, String bomPurpose, LocalDate quoteDate) {
    return read(
        productCode,
        periodMonth,
        bomPurpose,
        quoteDate,
        MaterialOrganization.COMMERCIAL.toQuoteDataOrganization());
  }
}
