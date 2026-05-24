package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartMaterialPriceResolveResult {
  private String materialCode;
  private String priceType;
  private BigDecimal unitPrice;
  private String status;
  private String remark;
  private String trace;

  public static MakePartMaterialPriceResolveResult ok(
      String materialCode, String priceType, BigDecimal unitPrice, String remark, String trace) {
    MakePartMaterialPriceResolveResult result = new MakePartMaterialPriceResolveResult();
    result.setMaterialCode(materialCode);
    result.setPriceType(priceType);
    result.setUnitPrice(unitPrice);
    result.setStatus("OK");
    result.setRemark(remark);
    result.setTrace(trace);
    return result;
  }

  public static MakePartMaterialPriceResolveResult missingRoute(String materialCode, String remark) {
    return miss(materialCode, "MISSING_ROUTE", remark, null);
  }

  public static MakePartMaterialPriceResolveResult miss(
      String materialCode, String status, String remark, String trace) {
    MakePartMaterialPriceResolveResult result = new MakePartMaterialPriceResolveResult();
    result.setMaterialCode(materialCode);
    result.setStatus(status);
    result.setRemark(remark);
    result.setTrace(trace);
    return result;
  }
}
