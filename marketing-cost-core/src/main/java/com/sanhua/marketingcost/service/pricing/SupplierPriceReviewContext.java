package com.sanhua.marketingcost.service.pricing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单次计算内收集待确认事项；只读预检查不因此写入数据库。 */
public final class SupplierPriceReviewContext {
  private final Long oaFormItemId;
  private final String priceOrgCode;
  private final Map<String, SupplierPriceReview> reviews;

  public SupplierPriceReviewContext(Long oaFormItemId) {
    this(oaFormItemId, null, new LinkedHashMap<>());
  }

  private SupplierPriceReviewContext(Long itemId, String orgCode,
      Map<String, SupplierPriceReview> reviews) {
    this.oaFormItemId = itemId;
    this.priceOrgCode = orgCode;
    this.reviews = reviews;
  }

  public SupplierPriceReviewContext forOrganization(String orgCode) {
    return new SupplierPriceReviewContext(oaFormItemId, orgCode, reviews);
  }

  public Long oaFormItemId() { return oaFormItemId; }
  public String priceOrgCode() { return priceOrgCode; }
  public void add(SupplierPriceReview review) { reviews.put(review.scopeKey(), review); }
  public List<SupplierPriceReview> reviews() { return List.copyOf(reviews.values()); }
}
