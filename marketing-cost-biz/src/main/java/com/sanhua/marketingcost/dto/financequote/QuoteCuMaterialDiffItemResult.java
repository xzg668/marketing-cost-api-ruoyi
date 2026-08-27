package com.sanhua.marketingcost.dto.financequote;

import java.math.BigDecimal;
import lombok.Data;

/** 财务 Cu 基准与 OA 锁价的实时逐结算键材料费差异。 */
@Data
public class QuoteCuMaterialDiffItemResult {
  private Long id;
  private Long costRunVersionId;
  private String costRunNo;
  private Integer lineNo;
  private String settlementKey;
  private String parentSettlementKey;
  private String detailLevel;
  private Integer contributesToAdjustment;
  private Long bomRowId;
  private String topProductCode;
  private String parentMaterialCode;
  private String materialCode;
  private String materialName;
  private String itemType;
  private BigDecimal quantity;
  private Long financePrepareItemId;
  private Long oaPrepareItemId;
  private BigDecimal financeUnitPrice;
  private BigDecimal oaUnitPrice;
  private BigDecimal financeAmount;
  private BigDecimal oaAmount;
  private BigDecimal diffAmount;
  private Integer cuAffected;
  private String priceFormulaRefType;
  private Long priceFormulaRefId;
  private String traceJson;
  private String businessUnitType;
}
