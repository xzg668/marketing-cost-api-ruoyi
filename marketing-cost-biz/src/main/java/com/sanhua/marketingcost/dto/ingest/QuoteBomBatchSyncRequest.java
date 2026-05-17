package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

/** 报价单产品行批量同步 BOM 请求。 */
public class QuoteBomBatchSyncRequest {
  /** 前端勾选的报价单产品行 ID，后端据此回写对应 BOM 状态。 */
  private List<Long> oaFormItemIds = new ArrayList<>();

  public List<Long> getOaFormItemIds() {
    return oaFormItemIds;
  }

  public void setOaFormItemIds(List<Long> oaFormItemIds) {
    this.oaFormItemIds = oaFormItemIds;
  }
}
