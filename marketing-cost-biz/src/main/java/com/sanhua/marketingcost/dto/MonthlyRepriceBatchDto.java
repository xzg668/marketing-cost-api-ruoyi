package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceBatchDto {
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
  private String costEngineVersion;
  private String priceVersion;
  private String ruleVersion;
  private String createdBy;
  private String createdName;
  private String confirmedBy;
  private String confirmedName;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime confirmedAt;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static MonthlyRepriceBatchDto fromEntity(MonthlyRepriceBatch batch) {
    MonthlyRepriceBatchDto dto = new MonthlyRepriceBatchDto();
    if (batch == null) {
      return dto;
    }
    dto.setId(batch.getId());
    dto.setRepriceNo(batch.getRepriceNo());
    dto.setPricingMonth(batch.getPricingMonth());
    dto.setPriceAsOfTime(batch.getPriceAsOfTime());
    dto.setBomSourcePolicy(batch.getBomSourcePolicy());
    dto.setBusinessUnitType(batch.getBusinessUnitType());
    dto.setAdjustBatchId(batch.getAdjustBatchId());
    dto.setExecutionBackend(batch.getExecutionBackend());
    dto.setStatus(batch.getStatus());
    dto.setTotalCount(batch.getTotalCount());
    dto.setSuccessCount(batch.getSuccessCount());
    dto.setFailedCount(batch.getFailedCount());
    dto.setSkippedCount(batch.getSkippedCount());
    dto.setCostEngineVersion(batch.getCostEngineVersion());
    dto.setPriceVersion(batch.getPriceVersion());
    dto.setRuleVersion(batch.getRuleVersion());
    dto.setCreatedBy(batch.getCreatedBy());
    dto.setCreatedName(batch.getCreatedName());
    dto.setConfirmedBy(batch.getConfirmedBy());
    dto.setConfirmedName(batch.getConfirmedName());
    dto.setStartedAt(batch.getStartedAt());
    dto.setFinishedAt(batch.getFinishedAt());
    dto.setConfirmedAt(batch.getConfirmedAt());
    dto.setRemark(batch.getRemark());
    dto.setCreatedAt(batch.getCreatedAt());
    dto.setUpdatedAt(batch.getUpdatedAt());
    return dto;
  }
}
