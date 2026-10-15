package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.enums.MaterialOrganization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** 联动与行情区间共享业务身份；供应商代码缺失时显式采用完整名称，不做模糊匹配。 */
public record MaterialPriceRuleKey(
    String businessUnitType, String orgCode, String materialCode,
    String supplierCode, String supplierName, String specification, String unit) {

  public MaterialPriceRuleKey {
    businessUnitType = FactorPriceScope.required(businessUnitType, "业务单元", 32)
        .toUpperCase(Locale.ROOT);
    if (!businessUnitType.equals("COMMERCIAL") && !businessUnitType.equals("HOUSEHOLD")) {
      throw new IllegalArgumentException("业务单元仅支持 COMMERCIAL/HOUSEHOLD");
    }
    orgCode = MaterialOrganization.fromPriceOrgCode(orgCode).getPriceOrgCode();
    materialCode = FactorPriceScope.required(materialCode, "料号", 64);
    supplierCode = clean(supplierCode);
    supplierName = clean(supplierName);
    if (supplierCode.isEmpty() && supplierName.isEmpty()) {
      throw new IllegalArgumentException("规则必须明确供应商代码或完整名称");
    }
    specification = clean(specification);
    unit = FactorPriceScope.required(unit, "计价单位", 32);
  }

  public String identityKey() {
    String supplier = supplierCode.isEmpty() ? "NAME:" + supplierName : "CODE:" + supplierCode;
    // 长度前缀消除字段内分隔符歧义；名称/规格/单位不猜别名或忽略大小写。
    StringBuilder input = new StringBuilder("PRICE_RULE_V1");
    for (String part : new String[] {businessUnitType, orgCode, materialCode, supplier, specification, unit}) {
      input.append('|').append(part.length()).append(':').append(part);
    }
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(input.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM不支持SHA-256", impossible);
    }
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
