package com.sanhua.marketingcost.dto.ingest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 批量发起 BOM 补录 OA 任务请求。 */
public class BomSupplementBatchOaTaskRequest {
  private List<Long> quoteBomStatusIds = new ArrayList<>();
  private LocalDateTime dueAt;
  private String remark;

  public List<Long> getQuoteBomStatusIds() {
    return quoteBomStatusIds;
  }

  public void setQuoteBomStatusIds(List<Long> quoteBomStatusIds) {
    this.quoteBomStatusIds = quoteBomStatusIds;
  }

  public LocalDateTime getDueAt() {
    return dueAt;
  }

  public void setDueAt(LocalDateTime dueAt) {
    this.dueAt = dueAt;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
