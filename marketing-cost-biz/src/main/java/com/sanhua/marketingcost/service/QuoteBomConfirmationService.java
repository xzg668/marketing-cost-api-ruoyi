package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;

public interface QuoteBomConfirmationService {

  boolean hasActiveConfirmation(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth);

  /** 同一月度方案可能被其他报价产品先确认，按最终构建编号判断是否已经正式冻结。 */
  default boolean hasActiveConfirmationForBuild(String effectiveBuildBatchId) {
    return false;
  }

  QuoteBomConfirmResponse confirm(String oaNo, Long oaFormItemId, QuoteBomConfirmRequest request);

  /** 确认由最终有效BOM生成的结算行，并锁定指定最终构建编号。 */
  QuoteBomConfirmResponse confirmEffective(
      String oaNo,
      Long oaFormItemId,
      String effectiveBuildBatchId,
      int replaceCount,
      QuoteBomConfirmRequest request);

  QuoteBomConfirmResponse cancelConfirm(
      String oaNo, Long oaFormItemId, QuoteBomCancelConfirmRequest request);
}
