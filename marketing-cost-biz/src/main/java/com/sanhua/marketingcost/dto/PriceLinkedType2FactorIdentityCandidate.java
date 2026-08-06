package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/** 统一因素身份解析中的只读候选快照。 */
public final class PriceLinkedType2FactorIdentityCandidate {

  private final Long factorIdentityId;
  private final Long canonicalFactorIdentityId;
  private final String factorName;
  private final String shortName;
  private final String priceSource;
  private final String canonicalFactorKey;
  private final BigDecimal targetMonthPrice;
  private final long activeBindingCount;
  private final boolean exactMatch;

  public PriceLinkedType2FactorIdentityCandidate(
      Long factorIdentityId,
      Long canonicalFactorIdentityId,
      String factorName,
      String shortName,
      String priceSource,
      String canonicalFactorKey,
      BigDecimal targetMonthPrice,
      long activeBindingCount,
      boolean exactMatch) {
    this.factorIdentityId = factorIdentityId;
    this.canonicalFactorIdentityId = canonicalFactorIdentityId;
    this.factorName = factorName;
    this.shortName = shortName;
    this.priceSource = priceSource;
    this.canonicalFactorKey = canonicalFactorKey;
    this.targetMonthPrice = targetMonthPrice;
    this.activeBindingCount = activeBindingCount;
    this.exactMatch = exactMatch;
  }

  public Long getFactorIdentityId() {
    return factorIdentityId;
  }

  public Long getCanonicalFactorIdentityId() {
    return canonicalFactorIdentityId;
  }

  public String getFactorName() {
    return factorName;
  }

  public String getShortName() {
    return shortName;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public String getCanonicalFactorKey() {
    return canonicalFactorKey;
  }

  public BigDecimal getTargetMonthPrice() {
    return targetMonthPrice;
  }

  public long getActiveBindingCount() {
    return activeBindingCount;
  }

  public boolean isExactMatch() {
    return exactMatch;
  }
}
