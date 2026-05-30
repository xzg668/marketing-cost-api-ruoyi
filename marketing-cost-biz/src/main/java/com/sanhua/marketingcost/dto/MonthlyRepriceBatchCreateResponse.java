package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceBatchCreateResponse {
  private Long id;
  private String repriceNo;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String bomSourcePolicy;
  private String businessUnitType;
  private Long adjustBatchId;
  private String executionBackend;
  private String status;
  private Integer totalCount;
  private Integer successCount;
  private Integer failedCount;
  private Integer skippedCount;
  private String createdBy;
  private String createdName;
  private LocalDateTime createdAt;
  private String remark;

  public static MonthlyRepriceBatchCreateResponse fromEntity(MonthlyRepriceBatch batch) {
    MonthlyRepriceBatchCreateResponse response = new MonthlyRepriceBatchCreateResponse();
    if (batch == null) {
      return response;
    }
    response.setId(batch.getId());
    response.setRepriceNo(batch.getRepriceNo());
    response.setPricingMonth(batch.getPricingMonth());
    response.setPriceAsOfTime(batch.getPriceAsOfTime());
    response.setBomSourcePolicy(batch.getBomSourcePolicy());
    response.setBusinessUnitType(batch.getBusinessUnitType());
    response.setAdjustBatchId(batch.getAdjustBatchId());
    response.setExecutionBackend(batch.getExecutionBackend());
    response.setStatus(batch.getStatus());
    response.setTotalCount(batch.getTotalCount());
    response.setSuccessCount(batch.getSuccessCount());
    response.setFailedCount(batch.getFailedCount());
    response.setSkippedCount(batch.getSkippedCount());
    response.setCreatedBy(batch.getCreatedBy());
    response.setCreatedName(batch.getCreatedName());
    response.setCreatedAt(batch.getCreatedAt());
    response.setRemark(batch.getRemark());
    return response;
  }
}
