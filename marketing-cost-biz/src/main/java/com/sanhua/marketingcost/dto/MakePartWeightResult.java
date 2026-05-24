package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MakePartWeightResult {
  private String parentMaterialNo;
  private String childMaterialNo;
  private String itemProcessType;
  private BigDecimal grossWeightG;
  private BigDecimal netWeightG;
  private String status;
  private String remark;

  public static MakePartWeightResult of(
      String parentMaterialNo,
      String childMaterialNo,
      String itemProcessType,
      BigDecimal grossWeightG,
      BigDecimal netWeightG,
      String status,
      String remark) {
    MakePartWeightResult result = new MakePartWeightResult();
    result.setParentMaterialNo(parentMaterialNo);
    result.setChildMaterialNo(childMaterialNo);
    result.setItemProcessType(itemProcessType);
    result.setGrossWeightG(grossWeightG);
    result.setNetWeightG(netWeightG);
    result.setStatus(status);
    result.setRemark(remark);
    return result;
  }
}
