package com.sanhua.marketingcost.service.jjb;

/** The URL cannot identify a unique saved product version. */
public class CostVersionRequiredException extends IllegalArgumentException {
  public CostVersionRequiredException() {
    super("COST_VERSION_REQUIRED：同料号存在多个报价产品行，请指定成本版本");
  }
}
