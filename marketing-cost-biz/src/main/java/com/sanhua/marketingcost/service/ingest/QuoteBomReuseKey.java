package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.util.StringUtils;

/** 报价单产品 BOM 当月沿用组合键，统一封装归一化规则，避免各服务各自拼接导致口径漂移。 */
public class QuoteBomReuseKey {
  private static final DateTimeFormatter PERIOD_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

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

  public static QuoteBomReuseKey from(OaForm form, OaFormItem item, Clock clock) {
    if (item == null) {
      throw new QuoteIngestException("报价单产品行不能为空");
    }
    String productCode = trimToNull(item.getMaterialNo());
    if (productCode == null) {
      throw new QuoteIngestException("产品料号不能为空，无法生成 BOM 沿用组合键");
    }
    // 客户维度优先使用产品行客户；产品行为空时使用表头客户；最终统一为空串，便于 SQL 等值匹配。
    String customerCode = normalizeEmpty(firstText(item.getCustomerCode(), form == null ? null : form.getCustomer()));
    String packageMethod = normalizeEmpty(item.getPackageMethod());
    String costPeriodMonth = PERIOD_FORMATTER.format(LocalDate.now(clock == null ? Clock.systemDefaultZone() : clock));
    return new QuoteBomReuseKey(productCode, customerCode, packageMethod, costPeriodMonth);
  }

  public static String normalizeEmpty(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null || "/".equals(trimmed)) {
      return "";
    }
    return trimmed;
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

  private static String firstText(String primary, String fallback) {
    String normalizedPrimary = trimToNull(primary);
    return normalizedPrimary == null ? trimToNull(fallback) : normalizedPrimary;
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
