package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepriceAuditLog;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceAuditLogDto {
  private Long id;
  private String repriceNo;
  private String pricingMonth;
  private String businessUnitType;
  private String operationType;
  private String operationName;
  private String operatorId;
  private String operatorName;
  private String operatorRole;
  private LocalDateTime operationTime;
  private String targetType;
  private String targetId;
  private String targetKey;
  private String beforeJson;
  private String afterJson;
  private String changeSummary;
  private String requestIp;
  private String requestUserAgent;
  private String requestId;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static MonthlyRepriceAuditLogDto fromEntity(MonthlyRepriceAuditLog log) {
    MonthlyRepriceAuditLogDto dto = new MonthlyRepriceAuditLogDto();
    if (log == null) {
      return dto;
    }
    dto.setId(log.getId());
    dto.setRepriceNo(log.getRepriceNo());
    dto.setPricingMonth(log.getPricingMonth());
    dto.setBusinessUnitType(log.getBusinessUnitType());
    dto.setOperationType(log.getOperationType());
    dto.setOperationName(log.getOperationName());
    dto.setOperatorId(log.getOperatorId());
    dto.setOperatorName(log.getOperatorName());
    dto.setOperatorRole(log.getOperatorRole());
    dto.setOperationTime(log.getOperationTime());
    dto.setTargetType(log.getTargetType());
    dto.setTargetId(log.getTargetId());
    dto.setTargetKey(log.getTargetKey());
    dto.setBeforeJson(log.getBeforeJson());
    dto.setAfterJson(log.getAfterJson());
    dto.setChangeSummary(log.getChangeSummary());
    dto.setRequestIp(log.getRequestIp());
    dto.setRequestUserAgent(log.getRequestUserAgent());
    dto.setRequestId(log.getRequestId());
    dto.setRemark(log.getRemark());
    dto.setCreatedAt(log.getCreatedAt());
    dto.setUpdatedAt(log.getUpdatedAt());
    return dto;
  }
}
