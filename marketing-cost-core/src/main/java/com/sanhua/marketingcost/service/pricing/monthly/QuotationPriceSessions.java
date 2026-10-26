package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext;
import java.time.LocalDateTime;

/** Application-owned quotation identity and source capture for existing core calculators. */
public interface QuotationPriceSessions {
  record Request(String oaNo, String businessUnit, String productCode, String month,
      LocalDateTime priceAsOfTime, SupplierPriceReviewContext supplierReviews,
      QuotationPriceSession.Scope scope) {}

  QuotationPriceSession open(Request request);
}
