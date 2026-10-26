package com.sanhua.marketingcost.service.pricing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 单次计算内收集待确认事项；只读预检查不因此写入数据库。 */
public final class SupplierPriceReviewContext {
  private final Long oaFormItemId;
  private final String priceOrgCode;
  private final Map<String, SupplierPriceReview> reviews;
  public record SuccessfulReview(SupplierPriceReview review, String evidenceSha256) {}
  private record ReviewEvidenceKey(String scopeKey, String evidenceSha256) {}
  private final Map<ReviewEvidenceKey, SuccessfulReview> successfulReviews;

  public SupplierPriceReviewContext(Long oaFormItemId) {
    this(oaFormItemId, null, new LinkedHashMap<>(), new LinkedHashMap<>());
  }

  private SupplierPriceReviewContext(Long itemId, String orgCode,
      Map<String, SupplierPriceReview> reviews, Map<ReviewEvidenceKey, SuccessfulReview> successfulReviews) {
    this.oaFormItemId = itemId;
    this.priceOrgCode = orgCode;
    this.reviews = reviews;
    this.successfulReviews = successfulReviews;
  }

  public SupplierPriceReviewContext forOrganization(String orgCode) {
    return new SupplierPriceReviewContext(oaFormItemId, orgCode, reviews, successfulReviews);
  }

  public Long oaFormItemId() { return oaFormItemId; }
  public String priceOrgCode() { return priceOrgCode; }
  public void add(SupplierPriceReview review) {
    reviews.put(review.scopeKey(), review);
  }
  /** Binds a successful consumer decision to its precise public candidate, without publishing the decision. */
  public void add(SupplierPriceReview review, String evidenceSha256) {
    if (evidenceSha256 == null || !evidenceSha256.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("Price evidence SHA-256 is required");
    add(review);
    successfulReviews.put(new ReviewEvidenceKey(review.scopeKey(), evidenceSha256),
        new SuccessfulReview(review, evidenceSha256));
  }
  /** Successful choices remain available if the same scope is read again in this plan. */
  public List<SuccessfulReview> successfulReviews() { return List.copyOf(successfulReviews.values()); }
  /** Start another candidate plan in the same operation without retaining a losing selection. */
  public void clear() { reviews.clear(); successfulReviews.clear(); }
  public List<SupplierPriceReview> reviews() { return List.copyOf(reviews.values()); }
}
