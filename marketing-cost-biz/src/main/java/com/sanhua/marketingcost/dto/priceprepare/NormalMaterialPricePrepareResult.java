package com.sanhua.marketingcost.dto.priceprepare;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

/** 普通料号价格准备结果，只承载沉淀到价格准备明细和缺口所需的字段。 */
@Getter
@Setter
public class NormalMaterialPricePrepareResult {
  private String status;
  private String gapType;
  private BigDecimal unitPrice;
  private BigDecimal amount;
  private String priceSource;
  private String resultRefType;
  private Long resultRefId;
  private String sourceTable;
  private String message;

  public static NormalMaterialPricePrepareResult ready(
      BigDecimal unitPrice,
      BigDecimal amount,
      String priceSource,
      String resultRefType,
      Long resultRefId,
      String message) {
    NormalMaterialPricePrepareResult result = new NormalMaterialPricePrepareResult();
    result.setStatus("READY");
    result.setUnitPrice(unitPrice);
    result.setAmount(amount);
    result.setPriceSource(priceSource);
    result.setResultRefType(resultRefType);
    result.setResultRefId(resultRefId);
    result.setMessage(message);
    return result;
  }

  public static NormalMaterialPricePrepareResult gap(
      String status, String gapType, String priceSource, String sourceTable, String message) {
    NormalMaterialPricePrepareResult result = new NormalMaterialPricePrepareResult();
    result.setStatus(status);
    result.setGapType(gapType);
    result.setPriceSource(priceSource);
    result.setSourceTable(sourceTable);
    result.setMessage(message);
    return result;
  }
}
