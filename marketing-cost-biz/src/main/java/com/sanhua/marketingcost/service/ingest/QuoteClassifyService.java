package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteClassificationResult;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationWarning;
import com.sanhua.marketingcost.enums.QuoteClassificationStatus;
import com.sanhua.marketingcost.enums.QuoteScenario;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class QuoteClassifyService {
  public static final String BU_COMMERCIAL = "COMMERCIAL";
  public static final String BU_HOUSEHOLD = "HOUSEHOLD";
  public static final String BU_UNKNOWN = "UNKNOWN";
  public static final String CATEGORY_COMMERCIAL_DIRECT = "商用直销产品";
  public static final String CATEGORY_HOUSEHOLD_PROXY = "家代商代销产品";

  public QuoteClassificationResult classify(QuoteIngestRequest request) {
    String processCode = processCode(request);
    if ("FI-SC-020".equalsIgnoreCase(processCode)) {
      return classifyCommercialDirect(
          request, QuoteScenario.DIRECT_SALE.getCode(), "RULE_FI_SC_020");
    }
    if ("FI-SC-006".equalsIgnoreCase(processCode)) {
      return classifyCommercialDirect(
          request, QuoteScenario.STANDARD_BATCH.getCode(), "RULE_FI_SC_006");
    }
    if ("FI-SC-005".equalsIgnoreCase(processCode)) {
      return classifyCommercialDirect(
          request, QuoteScenario.NEW_PRODUCT.getCode(), "RULE_FI_SC_005");
    }
    if ("FI-SR-005".equalsIgnoreCase(processCode)) {
      return classifyFiSr005(request);
    }
    return pending("RULE_UNMATCHED", "未命中报价单自动分类规则");
  }

  private QuoteClassificationResult classifyCommercialDirect(
      QuoteIngestRequest request, String scenario, String ruleCode) {
    if (hasClassificationContext(request) && !matchesCommercialDirectContext(request)) {
      return pending("RULE_FI_SC_CONTEXT_CONFLICT", "FI-SC 流程与申请单位/费用产品口径不一致，需要人工确认");
    }
    return confirmed(BU_COMMERCIAL, CATEGORY_COMMERCIAL_DIRECT, scenario, ruleCode);
  }

  private QuoteClassificationResult classifyFiSr005(QuoteIngestRequest request) {
    if (hasClassificationContext(request) && !matchesHouseholdProxyContext(request)) {
      return pending("RULE_FI_SR_005_CONTEXT_CONFLICT", "FI-SR-005 流程与申请单位/费用产品口径不一致，需要人工确认");
    }

    Set<String> scenarios = new LinkedHashSet<>();
    if (request != null && request.getItems() != null) {
      for (QuoteIngestItemRequest item : request.getItems()) {
        String scenario = scenarioFromBusinessType(item == null ? null : item.getBusinessType());
        if (scenario != null) {
          scenarios.add(scenario);
        }
      }
    }

    if (scenarios.isEmpty()) {
      return pending("RULE_FI_SR_005_UNKNOWN", "FI-SR-005 未提供业务类型，需要人工确认新品/批量品/衍生品");
    }
    if (scenarios.size() > 1) {
      return pending("RULE_FI_SR_005_MIXED", "FI-SR-005 存在多个业务类型场景，需要人工确认后再报价");
    }

    String scenario = scenarios.iterator().next();
    String ruleCode;
    if (QuoteScenario.NEW_PRODUCT.getCode().equals(scenario)) {
      ruleCode = "RULE_FI_SR_005_NEW";
    } else if (QuoteScenario.MASS_PRODUCT.getCode().equals(scenario)) {
      ruleCode = "RULE_FI_SR_005_MASS";
    } else {
      ruleCode = "RULE_FI_SR_005_DERIVED";
    }
    // FI-SR-005 是家代商代销产品的商用特殊口径，不按普通家用 HOUSEHOLD 隔离。
    // 后续成本核算、价格准备、BOM 等链路统一按 businessUnitType=COMMERCIAL 取数。
    return confirmed(BU_COMMERCIAL, CATEGORY_HOUSEHOLD_PROXY, scenario, ruleCode);
  }

  private String scenarioFromBusinessType(String businessType) {
    String value = trimToNull(businessType);
    if (value == null) {
      return null;
    }
    if (value.contains("新品")) {
      return QuoteScenario.NEW_PRODUCT.getCode();
    }
    if (value.contains("批量")) {
      return QuoteScenario.MASS_PRODUCT.getCode();
    }
    if (value.contains("衍生")) {
      return QuoteScenario.DERIVED_PRODUCT.getCode();
    }
    return null;
  }

  private QuoteClassificationResult confirmed(
      String businessUnitType, String expenseProductCategory, String scenario, String ruleCode) {
    QuoteClassificationResult result = new QuoteClassificationResult();
    result.setBusinessUnitType(businessUnitType);
    result.setExpenseProductCategory(expenseProductCategory);
    result.setQuoteScenario(scenario);
    result.setClassificationStatus(QuoteClassificationStatus.CONFIRMED.getCode());
    result.setRuleCode(ruleCode);
    result.setConfidence(100);
    return result;
  }

  private QuoteClassificationResult pending(String ruleCode, String message) {
    QuoteClassificationResult result = new QuoteClassificationResult();
    result.setBusinessUnitType(BU_UNKNOWN);
    result.setQuoteScenario(QuoteScenario.UNKNOWN.getCode());
    result.setClassificationStatus(QuoteClassificationStatus.PENDING.getCode());
    result.setRuleCode(ruleCode);
    result.setConfidence(0);
    result.getWarnings().add(new QuoteValidationWarning("classification", ruleCode, message));
    return result;
  }

  private String processCode(QuoteIngestRequest request) {
    if (request == null) {
      return null;
    }
    QuoteIngestHeaderRequest header = request.getHeader();
    return QuoteProcessCodeResolver.resolve(
        header == null ? null : header.getProcessCode(),
        request.getOaNo(),
        request.getExternalFormNo());
  }

  private boolean hasClassificationContext(QuoteIngestRequest request) {
    QuoteIngestHeaderRequest header = request == null ? null : request.getHeader();
    return header != null
        && (trimToNull(header.getApplicantUnit()) != null
            || trimToNull(header.getSourceCompany()) != null
            || trimToNull(header.getSourceBusinessDivision()) != null
            || trimToNull(header.getExpenseProductCategory()) != null);
  }

  private boolean matchesCommercialDirectContext(QuoteIngestRequest request) {
    String context = classificationContext(request);
    if (context.contains("家代商") || context.contains("家代商代销")) {
      return false;
    }
    if (context.contains("家用") || context.contains("HOUSEHOLD")) {
      return false;
    }
    return context.contains("商用")
        || context.contains("商用制冷")
        || context.contains("商用直销")
        || context.contains("COMMERCIAL");
  }

  private boolean matchesHouseholdProxyContext(QuoteIngestRequest request) {
    String context = classificationContext(request);
    if (context.contains("家代商") || context.contains("家代商代销")) {
      return true;
    }
    if (context.contains("商用制冷") || context.contains("商用直销")) {
      return false;
    }
    if (context.contains("家用") || context.contains("HOUSEHOLD")) {
      return false;
    }
    return context.contains("COMMERCIAL") && !context.contains("商用直销");
  }

  private String classificationContext(QuoteIngestRequest request) {
    QuoteIngestHeaderRequest header = request == null ? null : request.getHeader();
    if (header == null) {
      return "";
    }
    return join(
        header.getApplicantUnit(),
        header.getSourceCompany(),
        header.getSourceBusinessDivision(),
        header.getExpenseProductCategory());
  }

  private String join(String... values) {
    StringBuilder builder = new StringBuilder();
    for (String value : values) {
      String trimmed = trimToNull(value);
      if (trimmed != null) {
        builder.append(' ').append(trimmed);
      }
    }
    return builder.toString().toUpperCase();
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
