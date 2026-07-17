package com.sanhua.marketingcost.dto.quotecosting;

import java.math.BigDecimal;
import lombok.Data;

/** 已持久化的单产品 Cu 材料费差异行。 */
@Data
public class QuoteCuMaterialDifferenceResponse {
  private Long id;
  private Long costRunVersionId;
  private String costRunNo;
  private Integer lineNo;
  private String settlementKey;
  private String parentSettlementKey;
  private String detailLevel;
  private boolean contributesToAdjustment;
  private Long bomRowId;
  private String topProductCode;
  private String parentMaterialCode;
  private String materialCode;
  private String materialName;
  private String itemType;
  private BigDecimal quantity;
  private BigDecimal financeUnitPrice;
  private BigDecimal oaUnitPrice;
  private BigDecimal financeAmount;
  private BigDecimal oaAmount;
  private BigDecimal diffAmount;
  private boolean cuAffected;
  private String priceFormulaRefType;
  private Long priceFormulaRefId;
  private String traceJson;
}
