package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class QuoteOaPdfTemplateResolver {
  public QuoteExcelTemplateType resolve(String fileName, String fullText) {
    String text = normalize(join(fileName, fullText));
    if (!StringUtils.hasText(text)) {
      return null;
    }
    if (containsAny(text, "FI-SC-020", "FISC020")) {
      return QuoteExcelTemplateType.FI_SC_020;
    }
    if (containsAny(text, "FI-SC-006", "FISC006")) {
      return QuoteExcelTemplateType.FI_SC_006;
    }
    if (containsAny(text, "FI-SC-005", "FISC005")) {
      return QuoteExcelTemplateType.FI_SC_005;
    }
    if (containsAny(text, "FI-SR-005", "FISR005") || text.contains("家代商") || text.contains("空白：")) {
      return resolveFiSr005(text);
    }
    return null;
  }

  private QuoteExcelTemplateType resolveFiSr005(String text) {
    String businessType = businessType(text);
    if ("衍生品".equals(businessType)) {
      return QuoteExcelTemplateType.FI_SR_005_DERIVED;
    }
    if ("批量品".equals(businessType)) {
      return QuoteExcelTemplateType.FI_SR_005_MASS;
    }
    if ("新品".equals(businessType)) {
      return QuoteExcelTemplateType.FI_SR_005_NEW;
    }
    return null;
  }

  private String businessType(String text) {
    int index = text.indexOf("业务类型");
    if (index >= 0) {
      String tail = text.substring(index, Math.min(text.length(), index + 40));
      String value = businessTypeByKeyword(tail);
      if (value != null) {
        return value;
      }
    }
    return businessTypeByKeyword(text);
  }

  private String businessTypeByKeyword(String text) {
    if (text.contains("衍生品") || text.contains("衍生")) {
      return "衍生品";
    }
    if (text.contains("批量品") || text.contains("批量")) {
      return "批量品";
    }
    if (text.contains("新品")) {
      return "新品";
    }
    return null;
  }

  private boolean containsAny(String text, String... values) {
    for (String value : values) {
      if (text.contains(value)) {
        return true;
      }
    }
    return false;
  }

  private String normalize(String value) {
    if (value == null) {
      return "";
    }
    return value
        .replace('\u00a0', ' ')
        .replace('－', '-')
        .replace('—', '-')
        .replace(" ", "")
        .toUpperCase(Locale.ROOT)
        .trim();
  }

  private String join(String fileName, String fullText) {
    return nullToEmpty(fileName) + "\n" + nullToEmpty(fullText);
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
