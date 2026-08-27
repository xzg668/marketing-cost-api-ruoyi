package com.sanhua.marketingcost.service.collaboration.scan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** U9 月度首次查询唯一键；客户和包装不参与，避免同料号同月重复查询。 */
public record U9MonthlySnapshotIdentity(
    String identityKey,
    String businessUnitType,
    String priceOrgCode,
    String materialOrganizationCode,
    String accountingMonth,
    String productCode,
    String bomPurpose) {

  private static final String VERSION = "QUOTE-U9-MONTHLY-V1";
  public static final String DEFAULT_BOM_PURPOSE = "主制造";

  public static U9MonthlySnapshotIdentity from(QuoteCollaborationScanContext context) {
    if (context == null) throw new IllegalArgumentException("U9月度快照上下文不能为空");
    return of(
        context.businessUnitType(), context.priceOrgCode(), context.materialOrganizationCode(),
        context.accountingMonth(), context.productCode());
  }

  public static U9MonthlySnapshotIdentity of(
      String businessUnitType,
      String priceOrgCode,
      String materialOrganizationCode,
      String accountingMonth,
      String productCode) {
    String businessUnit = required(businessUnitType, "U9月度快照缺少业务单元");
    String priceOrg = required(priceOrgCode, "U9月度快照缺少报价组织");
    String materialOrg = required(materialOrganizationCode, "U9月度快照缺少料品组织");
    String month = required(accountingMonth, "U9月度快照缺少核算月份");
    YearMonth.parse(month);
    String product = required(productCode, "U9月度快照缺少产品料号");
    String purpose = DEFAULT_BOM_PURPOSE;
    String canonical = String.join("\n",
        VERSION,
        "BUSINESS_UNIT=" + normalized(businessUnit),
        "PRICE_ORG=" + normalized(priceOrg),
        "MATERIAL_ORG=" + normalized(materialOrg),
        "MONTH=" + month,
        "PRODUCT=" + normalized(product),
        "PURPOSE=" + purpose);
    return new U9MonthlySnapshotIdentity(
        sha256(canonical), businessUnit.trim(), priceOrg.trim(), materialOrg.trim(), month,
        product.trim(), purpose);
  }

  private static String normalized(String value) {
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static String required(String value, String message) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(message);
    return value.trim();
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("当前JVM不支持SHA-256", exception);
    }
  }
}
