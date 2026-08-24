package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.QuoteBomRuleFingerprintService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class QuoteBomRuleFingerprintServiceImpl implements QuoteBomRuleFingerprintService {

  private static final String VERSION = "QUOTE_BOM_RULE_V1";

  private final BomSettlementRuleQueryService settlementRuleService;
  private final BomByproductCostRuleQueryService byproductRuleService;

  public QuoteBomRuleFingerprintServiceImpl(
      BomSettlementRuleQueryService settlementRuleService,
      BomByproductCostRuleQueryService byproductRuleService) {
    this.settlementRuleService = settlementRuleService;
    this.byproductRuleService = byproductRuleService;
  }

  @Override
  public String currentFingerprint() {
    List<String> rules = new ArrayList<>();
    for (BomSettlementRule rule : safe(settlementRuleService.listEnabledCandidates())) {
      rules.add(
          canonical(
              "SETTLEMENT",
              rule.getId(),
              rule.getRuleCode(),
              rule.getRuleCategory(),
              rule.getSettlementAction(),
              rule.getSettlementRowType(),
              rule.getSubRefType(),
              rule.getMatchConditionJson(),
              rule.getMarkSubtreeCostRequired(),
              rule.getPriority(),
              rule.getBusinessUnitType(),
              rule.getBomPurpose(),
              rule.getEffectiveFrom(),
              rule.getEffectiveTo()));
    }
    for (BomByproductCostRule rule : safe(byproductRuleService.listEnabledCandidates())) {
      rules.add(
          canonical(
              "BYPRODUCT",
              rule.getId(),
              rule.getRuleCode(),
              rule.getRuleCategory(),
              rule.getAddConditionType(),
              rule.getSettlementRowType(),
              rule.getMatchConditionJson(),
              rule.getPriority(),
              rule.getBusinessUnitType(),
              rule.getBomPurpose(),
              rule.getEffectiveFrom(),
              rule.getEffectiveTo()));
    }
    rules.sort(Comparator.naturalOrder());
    return sha256(VERSION + "\n" + String.join("\n", rules));
  }

  private static <T> List<T> safe(List<T> values) {
    return values == null ? List.of() : values;
  }

  private static String canonical(Object... values) {
    StringBuilder result = new StringBuilder();
    for (Object value : values) {
      String text = value == null ? "" : value.toString().trim();
      result.append(text.length()).append(':').append(text).append('|');
    }
    return result.toString();
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
