package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementWeights;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 一条已保存修订；维护只追加副本，禁止更新历史价格字段。 */
@Getter
@Setter
public class SettlementPriceRecord {
  private Long id;
  private String businessUnitType;
  private String materialCode;
  private String materialName;
  private String specModel;
  private SettlementPriceType priceType;
  private String priceMonth;
  private int revisionNo;
  private boolean active;
  private BigDecimal givenPrice;
  private BigDecimal plannedPrice;
  private BigDecimal markupRatio;
  private BigDecimal baseSettlePrice;
  private BigDecimal budgetCu;
  private BigDecimal budgetZn;
  private BigDecimal referenceCu;
  private BigDecimal referenceZn;
  private BigDecimal originalCopperWeightG;
  private BigDecimal originalZincWeightG;
  private BigDecimal copperWeightG;
  private BigDecimal zincWeightG;
  private boolean weightsConfirmed;
  private String weightConfirmationNote;
  private String weightsConfirmedBy;
  private LocalDateTime weightsConfirmedAt;
  private String expression;
  private String formulaVersion;
  private BigDecimal taxDivisor;
  private String materialUnit;
  private String unitBasis;
  private String sourceSystem;
  private String sourceFileName;
  private String sourceFileSha256;
  private String sourceFileRef;
  private String sourceSheet;
  private String sourceRowsJson;
  private String sourceFormula;
  private String remark;
  private String contentHash;
  private String createdBy;
  private LocalDateTime createdAt;

  public SettlementWeights weights() {
    return new SettlementWeights(originalCopperWeightG, originalZincWeightG, copperWeightG,
        zincWeightG, weightsConfirmed, weightConfirmationNote);
  }

  public SettlementPriceRecord copy() {
    var copy = new SettlementPriceRecord();
    org.springframework.beans.BeanUtils.copyProperties(this, copy);
    return copy;
  }
}
