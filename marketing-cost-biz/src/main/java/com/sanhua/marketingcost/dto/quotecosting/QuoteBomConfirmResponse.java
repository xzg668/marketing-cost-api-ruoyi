package com.sanhua.marketingcost.dto.quotecosting;

import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class QuoteBomConfirmResponse {
  private Long id;
  private String confirmNo;
  private String oaNo;
  private Long oaFormItemId;
  private String topProductCode;
  private String periodMonth;
  private String confirmStatus;
  private Integer confirmVersion;
  private Integer rowCount;
  private Integer manualModifiedCount;
  private Integer replaceCount;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String confirmRemark;
  private String costingBuildBatchId;

  public static QuoteBomConfirmResponse from(QuoteBomConfirmation entity) {
    QuoteBomConfirmResponse response = new QuoteBomConfirmResponse();
    response.setId(entity.getId());
    response.setConfirmNo(entity.getConfirmNo());
    response.setOaNo(entity.getOaNo());
    response.setOaFormItemId(entity.getOaFormItemId());
    response.setTopProductCode(entity.getTopProductCode());
    response.setPeriodMonth(entity.getPeriodMonth());
    response.setConfirmStatus(entity.getConfirmStatus());
    response.setConfirmVersion(entity.getConfirmVersion());
    response.setRowCount(entity.getRowCount());
    response.setManualModifiedCount(entity.getManualModifiedCount());
    response.setReplaceCount(entity.getReplaceCount());
    response.setConfirmedBy(entity.getConfirmedBy());
    response.setConfirmedAt(entity.getConfirmedAt());
    response.setConfirmRemark(entity.getConfirmRemark());
    response.setCostingBuildBatchId(entity.getCostingBuildBatchId());
    return response;
  }
}
