package com.sanhua.marketingcost.service.ingest;

import java.util.Objects;

/** 报价单产品 BOM 当月沿用组合键，统一封装归一化规则，避免各服务各自拼接导致口径漂移。 */
public class QuoteBomReuseKey {
  private final String productCode;
  private final String customerCode;
  private final String packageMethod;
  private final String costPeriodMonth;

  private QuoteBomReuseKey(
      String productCode, String customerCode, String packageMethod, String costPeriodMonth) {
    this.productCode = productCode;
    this.customerCode = customerCode;
    this.packageMethod = packageMethod;
    this.costPeriodMonth = costPeriodMonth;
  }

  /** 业务复用键只能由已经统一解析并校验的上下文生成。 */
  public static QuoteBomReuseKey from(QuoteBomContext context) {
    if (context == null) {
      throw new QuoteIngestException("报价 BOM 上下文不能为空");
    }
    return new QuoteBomReuseKey(
        context.productCode(),
        context.customerKey(),
        context.packageMethod(),
        context.costPeriodMonth());
  }

  public String getProductCode() {
    return productCode;
  }

  public String getCustomerCode() {
    return customerCode;
  }

  public String getPackageMethod() {
    return packageMethod;
  }

  public String getCostPeriodMonth() {
    return costPeriodMonth;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof QuoteBomReuseKey that)) {
      return false;
    }
    return Objects.equals(productCode, that.productCode)
        && Objects.equals(customerCode, that.customerCode)
        && Objects.equals(packageMethod, that.packageMethod)
        && Objects.equals(costPeriodMonth, that.costPeriodMonth);
  }

  @Override
  public int hashCode() {
    return Objects.hash(productCode, customerCode, packageMethod, costPeriodMonth);
  }
}
