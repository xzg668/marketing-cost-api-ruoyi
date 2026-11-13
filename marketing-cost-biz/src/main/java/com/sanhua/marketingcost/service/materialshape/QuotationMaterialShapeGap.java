package com.sanhua.marketingcost.service.materialshape;

/** A named business input gap, never a transient database/network failure. */
public class QuotationMaterialShapeGap extends RuntimeException {
  private final String materialCode;
  public QuotationMaterialShapeGap(String code,String name,String message) {
    super("料号"+code+"（"+java.util.Objects.toString(name,"")+"）："+message);
    materialCode=code;
  }
  public String materialCode() { return materialCode; }
}
