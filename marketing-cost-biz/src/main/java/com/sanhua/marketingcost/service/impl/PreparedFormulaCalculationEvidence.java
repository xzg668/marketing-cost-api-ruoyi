package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.*;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent evidence for a captured public P0; leaves the existing technical snapshot format intact. */
final class PreparedFormulaCalculationEvidence {
  private static final String VERSION="MONTHLY_LINKED_FORMULA_V1";
  private PreparedFormulaCalculationEvidence() {}
  static void capture(PriceLinkedItem rule,VariableContext context,Map<String,BigDecimal> inputs,
      PriceLinkedFormulaPreviewResponse response,ObjectMapper json){
    var prepared=Objects.requireNonNull(context.getPreparedInputs());
    var evidence=new LinkedHashMap<String,Object>();
    evidence.put("calculatorVersion",VERSION);evidence.put("rule",rule);
    evidence.put("expression",response.getNormalizedExpr());evidence.put("taxIncluded",rule.getTaxIncluded());
    evidence.put("pricingMonth",context.getPricingMonth());evidence.put("priceAsOfDate",context.getQuoteDate());
    var financial=context.getPriceFactors();
    evidence.put("context",financial==null?null:financial.evidenceForFactors(context.getUsedFinanceKeys()));
    evidence.put("inputs",inputs);evidence.put("primitiveInputs",context.getPrimitiveInputs());
    evidence.put("definitions",context.getUsedDefinitions());evidence.put("factorKeys",context.getResolvedFactorKeys());
    if(!context.getUsedPrivateFactors().isEmpty())evidence.put("usedPrivateFactors",context.getUsedPrivateFactors());
    if(!context.getUsedFactorIdentities().isEmpty())evidence.put("capturedFactorIdentities",context.getUsedFactorIdentities());
    if(!context.getUsedScrapReferences().isEmpty())evidence.put("usedScrapReferences",context.getUsedScrapReferences());
    evidence.put("capturedDefinitions",prepared.selectedVariables(context.getUsedDefinitions().keySet()));
    evidence.put("capturedBindings",prepared.bindings(rule.getId()));
    evidence.put("capturedPlaceholders",prepared.catalog().placeholders());
    if(!context.getScrapPriceInputs().isEmpty())evidence.put("scrapPriceInputs",context.getScrapPriceInputs());
    evidence.put("result",response.getResult());
    try{
      String serialized=json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(evidence);
      response.setInputSnapshotJson(serialized);response.setCalculatorVersion(VERSION);
      response.setCalcFingerprint(PriceLinkedImportFileDigest.sha256(serialized.getBytes(StandardCharsets.UTF_8)));
    }catch(JsonProcessingException invalid){throw new IllegalStateException("预取公式输入依据无法保存："+rule.getMaterialCode(),invalid);}
  }
}
