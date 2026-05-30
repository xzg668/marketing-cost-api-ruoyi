package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustBatchDto {
  private Long id;
  private String adjustBatchNo;
  private String adjustType;
  private String pricingMonth;
  private String businessUnitType;
  private String usageScope;
  private String sourceType;
  private String sourceFileName;
  private String fileSha256;
  private String contentHash;
  private Integer totalCount;
  private Integer changedCount;
  private Integer noChangeCount;
  private Integer skippedCount;
  private Integer failedCount;
  private String status;
  private String uploadedBy;
  private LocalDateTime uploadedAt;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static FactorAdjustBatchDto fromEntity(FactorAdjustBatch e) {
    FactorAdjustBatchDto dto = new FactorAdjustBatchDto();
    if (e == null) {
      return dto;
    }
    dto.setId(e.getId());
    dto.setAdjustBatchNo(e.getAdjustBatchNo());
    dto.setAdjustType(e.getAdjustType());
    dto.setPricingMonth(e.getPricingMonth());
    dto.setBusinessUnitType(e.getBusinessUnitType());
    dto.setUsageScope(e.getUsageScope());
    dto.setSourceType(e.getSourceType());
    dto.setSourceFileName(e.getSourceFileName());
    dto.setFileSha256(e.getFileSha256());
    dto.setContentHash(e.getContentHash());
    dto.setTotalCount(e.getTotalCount());
    dto.setChangedCount(e.getChangedCount());
    dto.setNoChangeCount(e.getNoChangeCount());
    dto.setSkippedCount(e.getSkippedCount());
    dto.setFailedCount(e.getFailedCount());
    dto.setStatus(e.getStatus());
    dto.setUploadedBy(e.getUploadedBy());
    dto.setUploadedAt(e.getUploadedAt());
    dto.setRemark(e.getRemark());
    dto.setCreatedAt(e.getCreatedAt());
    dto.setUpdatedAt(e.getUpdatedAt());
    return dto;
  }
}
