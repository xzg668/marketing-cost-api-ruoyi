package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustPriceDto {
  private Long id;
  private Long adjustBatchId;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private String factorSeqNo;
  private String factorName;
  private String shortName;
  private String priceSource;
  private String unit;
  private BigDecimal originalPrice;
  private BigDecimal adjustedPrice;
  private BigDecimal priceDelta;
  private BigDecimal changeRate;
  private String matchMethod;
  private Integer applyToDaily;
  private String status;
  private String failReason;
  private String sourceSheetName;
  private Integer sourceRowNumber;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static FactorAdjustPriceDto fromEntity(FactorAdjustPrice e) {
    FactorAdjustPriceDto dto = new FactorAdjustPriceDto();
    if (e == null) {
      return dto;
    }
    dto.setId(e.getId());
    dto.setAdjustBatchId(e.getAdjustBatchId());
    dto.setFactorIdentityId(e.getFactorIdentityId());
    dto.setFactorMonthlyPriceId(e.getFactorMonthlyPriceId());
    dto.setFactorSeqNo(e.getFactorSeqNo());
    dto.setFactorName(e.getFactorName());
    dto.setShortName(e.getShortName());
    dto.setPriceSource(e.getPriceSource());
    dto.setUnit(e.getUnit());
    dto.setOriginalPrice(e.getOriginalPrice());
    dto.setAdjustedPrice(e.getAdjustedPrice());
    dto.setPriceDelta(e.getPriceDelta());
    dto.setChangeRate(e.getChangeRate());
    dto.setMatchMethod(e.getMatchMethod());
    dto.setApplyToDaily(e.getApplyToDaily());
    dto.setStatus(e.getStatus());
    dto.setFailReason(e.getFailReason());
    dto.setSourceSheetName(e.getSourceSheetName());
    dto.setSourceRowNumber(e.getSourceRowNumber());
    dto.setCreatedAt(e.getCreatedAt());
    dto.setUpdatedAt(e.getUpdatedAt());
    return dto;
  }
}
