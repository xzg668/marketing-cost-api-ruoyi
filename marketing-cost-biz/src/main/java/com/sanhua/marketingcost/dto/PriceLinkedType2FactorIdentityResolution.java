package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.PriceLinkedType2FactorIdentityResolutionStatus;
import java.math.BigDecimal;
import java.util.List;

/** 单条类型 2 影响因素的统一身份解析结果；本对象只描述决策，不执行写入。 */
public final class PriceLinkedType2FactorIdentityResolution {

  private final PriceLinkedType2FactorRow sourceRow;
  private final String businessUnitType;
  private final String priceMonth;
  private final String canonicalFactorKey;
  private final PriceLinkedType2FactorIdentityResolutionStatus status;
  private final Long selectedFactorIdentityId;
  private final Long selectedCanonicalFactorIdentityId;
  private final BigDecimal selectedTargetMonthPrice;
  private final Long recommendedFactorIdentityId;
  private final Long recommendedCanonicalFactorIdentityId;
  private final boolean overwriteAllowed;
  private final List<Long> canonicalMetadataRequiredIdentityIds;
  private final List<PriceLinkedType2FactorIdentityCandidate> candidates;
  private final String message;

  public PriceLinkedType2FactorIdentityResolution(
      PriceLinkedType2FactorRow sourceRow,
      String businessUnitType,
      String priceMonth,
      String canonicalFactorKey,
      PriceLinkedType2FactorIdentityResolutionStatus status,
      Long selectedFactorIdentityId,
      Long selectedCanonicalFactorIdentityId,
      BigDecimal selectedTargetMonthPrice,
      Long recommendedFactorIdentityId,
      Long recommendedCanonicalFactorIdentityId,
      boolean overwriteAllowed,
      List<Long> canonicalMetadataRequiredIdentityIds,
      List<PriceLinkedType2FactorIdentityCandidate> candidates,
      String message) {
    this.sourceRow = sourceRow;
    this.businessUnitType = businessUnitType;
    this.priceMonth = priceMonth;
    this.canonicalFactorKey = canonicalFactorKey;
    this.status = status;
    this.selectedFactorIdentityId = selectedFactorIdentityId;
    this.selectedCanonicalFactorIdentityId = selectedCanonicalFactorIdentityId;
    this.selectedTargetMonthPrice = selectedTargetMonthPrice;
    this.recommendedFactorIdentityId = recommendedFactorIdentityId;
    this.recommendedCanonicalFactorIdentityId = recommendedCanonicalFactorIdentityId;
    this.overwriteAllowed = overwriteAllowed;
    this.canonicalMetadataRequiredIdentityIds =
        List.copyOf(canonicalMetadataRequiredIdentityIds);
    this.candidates = List.copyOf(candidates);
    this.message = message;
  }

  public PriceLinkedType2FactorRow getSourceRow() {
    return sourceRow;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public String getPriceMonth() {
    return priceMonth;
  }

  public String getCanonicalFactorKey() {
    return canonicalFactorKey;
  }

  public PriceLinkedType2FactorIdentityResolutionStatus getStatus() {
    return status;
  }

  public Long getSelectedFactorIdentityId() {
    return selectedFactorIdentityId;
  }

  public Long getSelectedCanonicalFactorIdentityId() {
    return selectedCanonicalFactorIdentityId;
  }

  public BigDecimal getSelectedTargetMonthPrice() {
    return selectedTargetMonthPrice;
  }

  /**
   * 价格冲突时按统一身份规则计算出的候选，仅供用户明确选择覆盖时使用。
   *
   * <p>默认冲突下 {@link #getSelectedFactorIdentityId()} 仍为空。
   */
  public Long getRecommendedFactorIdentityId() {
    return recommendedFactorIdentityId;
  }

  public Long getRecommendedCanonicalFactorIdentityId() {
    return recommendedCanonicalFactorIdentityId;
  }

  public boolean isOverwriteAllowed() {
    return overwriteAllowed;
  }

  public List<Long> getCanonicalMetadataRequiredIdentityIds() {
    return canonicalMetadataRequiredIdentityIds;
  }

  public List<PriceLinkedType2FactorIdentityCandidate> getCandidates() {
    return candidates;
  }

  public String getMessage() {
    return message;
  }

  public boolean isResolved() {
    return status == PriceLinkedType2FactorIdentityResolutionStatus.EXACT_MATCH
        || status == PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MATCH;
  }

  public boolean isBlocked() {
    return status == PriceLinkedType2FactorIdentityResolutionStatus.PRICE_CONFLICT
        || status
            == PriceLinkedType2FactorIdentityResolutionStatus.CANONICAL_MASTER_CONFLICT
        || status == PriceLinkedType2FactorIdentityResolutionStatus.INVALID_REQUEST;
  }
}
