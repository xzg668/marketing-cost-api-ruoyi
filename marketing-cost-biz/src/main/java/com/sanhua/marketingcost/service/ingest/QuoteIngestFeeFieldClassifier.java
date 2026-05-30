package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.enums.QuoteExtraFeeCategory;
import java.util.Locale;
import org.springframework.util.StringUtils;

final class QuoteIngestFeeFieldClassifier {
  private QuoteIngestFeeFieldClassifier() {}

  static boolean isFeeField(String fieldCode, String fieldName) {
    String code = nullToEmpty(fieldCode).toLowerCase(Locale.ROOT);
    String name = nullToEmpty(fieldName);
    if (code.contains("bearer") || name.contains("承担")) {
      return false;
    }
    return code.contains("fixture")
        || code.contains("mold")
        || code.contains("toolcost")
        || code.contains("tooling")
        || code.contains("certificationfee")
        || code.contains("equipmentfee")
        || name.contains("工装")
        || name.contains("模具")
        || name.contains("刀具")
        || name.contains("认证费")
        || name.contains("设备费");
  }

  static String feeCategory(String fieldCode, String fieldName) {
    String text = nullToEmpty(fieldCode) + " " + nullToEmpty(fieldName);
    String lower = text.toLowerCase(Locale.ROOT);
    if (text.contains("认证")) {
      return QuoteExtraFeeCategory.CERTIFICATION.getCode();
    }
    if (text.contains("设备")) {
      return QuoteExtraFeeCategory.EQUIPMENT.getCode();
    }
    if (text.contains("刀具") || lower.contains("toolcost")) {
      return QuoteExtraFeeCategory.CUTTER.getCode();
    }
    if (text.contains("模具") || lower.contains("mold")) {
      return QuoteExtraFeeCategory.MOLD.getCode();
    }
    if (text.contains("工装") || lower.contains("fixture") || lower.contains("tooling")) {
      return QuoteExtraFeeCategory.TOOLING.getCode();
    }
    return QuoteExtraFeeCategory.OTHER.getCode();
  }

  static String unit(String fieldName) {
    if (StringUtils.hasText(fieldName) && fieldName.contains("万元")) {
      return "万元";
    }
    if (StringUtils.hasText(fieldName) && fieldName.contains("元/只")) {
      return "元/只";
    }
    return "元";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
