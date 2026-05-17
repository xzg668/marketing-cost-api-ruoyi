package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorMonthlyPriceChangeLogDto {
  private Long id;
  private Long factorMonthlyPriceId;
  private Long factorIdentityId;
  private String priceMonth;
  private BigDecimal oldPrice;
  private BigDecimal newPrice;
  private String changeType;
  private Long sourceUploadBatchId;
  private Long adjustBatchId;
  private String sourceType;
  private String changedBy;
  private String remark;
  private LocalDateTime createdAt;

  public static FactorMonthlyPriceChangeLogDto fromEntity(FactorMonthlyPriceChangeLog e) {
    FactorMonthlyPriceChangeLogDto dto = new FactorMonthlyPriceChangeLogDto();
    if (e == null) {
      return dto;
    }
    dto.setId(e.getId());
    dto.setFactorMonthlyPriceId(e.getFactorMonthlyPriceId());
    dto.setFactorIdentityId(e.getFactorIdentityId());
    dto.setPriceMonth(e.getPriceMonth());
    dto.setOldPrice(e.getOldPrice());
    dto.setNewPrice(e.getNewPrice());
    dto.setChangeType(e.getChangeType());
    dto.setSourceUploadBatchId(e.getSourceUploadBatchId());
    dto.setAdjustBatchId(e.getAdjustBatchId());
    dto.setSourceType(e.getSourceType());
    dto.setChangedBy(e.getChangedBy());
    dto.setRemark(e.getRemark());
    dto.setCreatedAt(e.getCreatedAt());
    return dto;
  }
}
