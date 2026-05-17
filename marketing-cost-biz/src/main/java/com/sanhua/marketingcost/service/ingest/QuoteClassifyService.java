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

  public QuoteClassificationResult classify(QuoteIngestRequest request) {
    String processCode = processCode(request);
    if ("FI-SC-020".equalsIgnoreCase(processCode)) {
      return confirmed(BU_COMMERCIAL, QuoteScenario.DIRECT_SALE.getCode(), "RULE_FI_SC_020");
    }
    if ("FI-SC-006".equalsIgnoreCase(processCode)) {
      return confirmed(BU_COMMERCIAL, QuoteScenario.STANDARD_BATCH.getCode(), "RULE_FI_SC_006");
    }
    if ("FI-SC-005".equalsIgnoreCase(processCode)) {
      return confirmed(BU_COMMERCIAL, QuoteScenario.NEW_PRODUCT.getCode(), "RULE_FI_SC_005");
    }
    if ("FI-SR-005".equalsIgnoreCase(processCode)) {
      return classifyFiSr005(request);
    }
    return pending("RULE_UNMATCHED", "未命中报价单自动分类规则");
  }

  private QuoteClassificationResult classifyFiSr005(QuoteIngestRequest request) {
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
    return confirmed(BU_HOUSEHOLD, scenario, ruleCode);
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

  private QuoteClassificationResult confirmed(String businessUnitType, String scenario, String ruleCode) {
    QuoteClassificationResult result = new QuoteClassificationResult();
    result.setBusinessUnitType(businessUnitType);
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
    return header == null ? null : trimToNull(header.getProcessCode());
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
