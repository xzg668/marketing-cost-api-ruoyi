package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 技术公式按实际输入保存新结果，历史价格准备引用的旧结果不原位改写。 */
final class TechnicalFormulaCalculationEvidence {
  static final String VERSION = "TECH_FORMULA_V1";

  private TechnicalFormulaCalculationEvidence() {}

  static void capture(
      PriceLinkedItem rule,
      VariableContext context,
      Map<String, BigDecimal> inputs,
      PriceLinkedFormulaPreviewResponse response,
      ObjectMapper json) {
    if (context.getPriceFactors() == null) throw new IllegalStateException("技术公式缺少财务因素快照");
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("calculatorVersion", VERSION);
    evidence.put("ruleId", rule.getId());
    evidence.put("technicalVersionId", rule.getTechnicalVersionId());
    evidence.put("businessUnitType", rule.getBusinessUnitType());
    evidence.put("organization", rule.getOrgCode());
    evidence.put("materialCode", rule.getMaterialCode());
    evidence.put("expression", response.getNormalizedExpr());
    evidence.put("taxIncluded", rule.getTaxIncluded());
    evidence.put("context", context.getPriceFactors());
    evidence.put("inputs", inputs);
    evidence.put("primitiveInputs", context.getPrimitiveInputs());
    evidence.put("definitions", context.getUsedDefinitions());
    evidence.put("usedFinanceKeys", context.getUsedFinanceKeys().stream().sorted().toList());
    evidence.put("factorKeys", context.getResolvedFactorKeys());
    evidence.put("result", response.getResult());
    try {
      String serialized =
          json.writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(evidence);
      response.setInputSnapshotJson(serialized);
      response.setCalculatorVersion(VERSION);
      response.setCalcFingerprint(
          PriceLinkedImportFileDigest.sha256(serialized.getBytes(StandardCharsets.UTF_8)));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("技术公式输入依据无法保存：" + rule.getMaterialCode(), error);
    }
  }
}
