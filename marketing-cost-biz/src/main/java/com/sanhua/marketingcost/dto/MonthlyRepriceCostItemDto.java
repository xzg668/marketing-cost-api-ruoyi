package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceCostItemDto {
  private Long id;
  private String repriceNo;
  private String oaNo;
  private String calcObjectKey;
  private String productCode;
  private Integer lineNo;
  private String costItemCode;
  private String costItemName;
  private BigDecimal baseAmount;
  private BigDecimal rate;
  private BigDecimal amount;
  private String calcFormula;
  private String calcStatus;
  private String calcMessage;

  public static MonthlyRepriceCostItemDto fromEntity(MonthlyRepriceCostItem item) {
    MonthlyRepriceCostItemDto dto = new MonthlyRepriceCostItemDto();
    if (item == null) {
      return dto;
    }
    dto.setId(item.getId());
    dto.setRepriceNo(item.getRepriceNo());
    dto.setOaNo(item.getOaNo());
    dto.setCalcObjectKey(item.getCalcObjectKey());
    dto.setProductCode(item.getProductCode());
    dto.setLineNo(item.getLineNo());
    dto.setCostItemCode(item.getCostItemCode());
    dto.setCostItemName(item.getCostItemName());
    dto.setBaseAmount(item.getBaseAmount());
    dto.setRate(item.getRate());
    dto.setAmount(item.getAmount());
    dto.setCalcFormula(item.getCalcFormula());
    dto.setCalcStatus(item.getCalcStatus());
    dto.setCalcMessage(item.getCalcMessage());
    return dto;
  }
}
