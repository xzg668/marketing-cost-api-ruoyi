package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;

public interface QuoteBomStatusService {
  QuoteBomStatusResponse listByOaNo(String oaNo);

  QuoteBomStatusResponse checkByOaNo(String oaNo);

  QuoteBomStatusResponse checkForCostRun(String oaNo);

  /** 仅为当前报价产品行建立或复用月度原始 BOM，避免单产品发起核算影响同 OA 其他产品。 */
  QuoteBomStatusItemResponse checkItemForCostRun(String oaNo, Long oaFormItemId);

  /**
   * 按当前核算工作台已经确定的月份，为单个产品建立或复用月度原始 BOM。
   *
   * <p>历史 OA 的申请月份可能早于本次核算月份，不能用 OA 月份覆盖工作台月份。
   */
  QuoteBomStatusItemResponse checkItemForCostRun(
      String oaNo, Long oaFormItemId, String costPeriodMonth);
}
