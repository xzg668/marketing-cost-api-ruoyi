package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceResultDto {
  private Long id;
  private String repriceNo;
  private String pricingMonth;
  private String businessUnitType;
  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private String calcObjectKey;
  private BigDecimal totalCost;
  private BigDecimal materialCost;
  private BigDecimal laborCost;
  private BigDecimal auxiliaryCost;
  private BigDecimal manufacturingCost;
  private BigDecimal managementCost;
  private BigDecimal salesCost;
  private BigDecimal financeCost;
  private String costEngineVersion;
  private String priceVersion;
  private String ruleVersion;
  private Long sourceCostResultId;
  private String calcStatus;
  private String calcMessage;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static MonthlyRepriceResultDto fromEntity(MonthlyRepriceResult result) {
    MonthlyRepriceResultDto dto = new MonthlyRepriceResultDto();
    if (result == null) {
      return dto;
    }
    dto.setId(result.getId());
    dto.setRepriceNo(result.getRepriceNo());
    dto.setPricingMonth(result.getPricingMonth());
    dto.setBusinessUnitType(result.getBusinessUnitType());
    dto.setOaNo(result.getOaNo());
    dto.setOaFormItemId(result.getOaFormItemId());
    dto.setProductCode(result.getProductCode());
    dto.setPackageMethod(result.getPackageMethod());
    dto.setCustomerName(result.getCustomerName());
    dto.setCalcObjectKey(result.getCalcObjectKey());
    dto.setTotalCost(result.getTotalCost());
    dto.setMaterialCost(result.getMaterialCost());
    dto.setLaborCost(result.getLaborCost());
    dto.setAuxiliaryCost(result.getAuxiliaryCost());
    dto.setManufacturingCost(result.getManufacturingCost());
    dto.setManagementCost(result.getManagementCost());
    dto.setSalesCost(result.getSalesCost());
    dto.setFinanceCost(result.getFinanceCost());
    dto.setCostEngineVersion(result.getCostEngineVersion());
    dto.setPriceVersion(result.getPriceVersion());
    dto.setRuleVersion(result.getRuleVersion());
    dto.setSourceCostResultId(result.getSourceCostResultId());
    dto.setCalcStatus(result.getCalcStatus());
    dto.setCalcMessage(result.getCalcMessage());
    dto.setCreatedAt(result.getCreatedAt());
    dto.setUpdatedAt(result.getUpdatedAt());
    return dto;
  }
}
