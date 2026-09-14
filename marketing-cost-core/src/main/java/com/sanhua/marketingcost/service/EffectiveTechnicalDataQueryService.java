package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;

/** The only costing read port for reviewed quote technical data. */
public interface EffectiveTechnicalDataQueryService {

  /**
   * Returns the exact effective version for the quote product and month, or {@code null} when the
   * product has no technical-data task and the existing CMS/standard-package rules must apply.
   */
  EffectiveTechnicalDataInput resolve(Long oaFormItemId, String accountingMonth);

  default String effectiveFingerprint(Long oaFormItemId, String accountingMonth) {
    EffectiveTechnicalDataInput input = resolve(oaFormItemId, accountingMonth);
    return input == null
        ? "NO_EFFECTIVE_TECHNICAL_DATA"
        : input.versionId() + ":" + input.contentFingerprint();
  }
}
