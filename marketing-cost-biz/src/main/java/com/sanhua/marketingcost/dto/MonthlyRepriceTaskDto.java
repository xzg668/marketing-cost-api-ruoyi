package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.CostRunTask;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceTaskDto {
  private Long id;
  private String repriceNo;
  private String pricingMonth;
  private String businessUnitType;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private String normalizedCustomerName;
  private String calcObjectKey;
  private String sourceOaCalcStatus;
  private String status;
  private String workerId;
  private LocalDateTime lockedAt;
  private LocalDateTime lockExpireTime;
  private Integer retryCount;
  private String lastErrorCode;
  private String lastErrorMessage;
  private LocalDateTime startedAt;
  private LocalDateTime finishedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static MonthlyRepriceTaskDto fromCostRunTask(CostRunTask task) {
    MonthlyRepriceTaskDto dto = new MonthlyRepriceTaskDto();
    if (task == null) {
      return dto;
    }
    dto.setId(task.getId());
    dto.setRepriceNo(task.getSourceNo());
    dto.setPricingMonth(task.getPricingMonth());
    dto.setBusinessUnitType(task.getBusinessUnitType());
    dto.setOaNo(task.getOaNo());
    dto.setOaFormItemId(task.getOaFormItemId());
    dto.setProductCode(task.getProductCode());
    dto.setPackageMethod(task.getPackageMethod());
    dto.setCustomerName(task.getCustomerName());
    dto.setCalcObjectKey(task.getCalcObjectKey());
    dto.setStatus(task.getStatus());
    dto.setWorkerId(task.getWorkerId());
    dto.setLockedAt(task.getLockedAt());
    dto.setLockExpireTime(task.getLockExpireTime());
    dto.setRetryCount(task.getRetryCount());
    dto.setLastErrorMessage(task.getErrorMessage());
    dto.setStartedAt(task.getStartedAt());
    dto.setFinishedAt(task.getFinishedAt());
    dto.setCreatedAt(task.getCreatedAt());
    dto.setUpdatedAt(task.getUpdatedAt());
    return dto;
  }
}
