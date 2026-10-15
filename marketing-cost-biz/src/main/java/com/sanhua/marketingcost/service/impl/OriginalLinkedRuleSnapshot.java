package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser.Rule;
import org.springframework.stereotype.Component;

/** 原稿执行契约随规则版本保存，读取时核对身份，不能借损坏快照回落到旧算法。 */
@Component
public final class OriginalLinkedRuleSnapshot {
  public static final String VERSION = "ORIGINAL_V1";
  private final ObjectMapper json;

  public OriginalLinkedRuleSnapshot(ObjectMapper json) {
    // MySQL JSON会将小数数字存成double；用十进制文本保留原稿输入完整精度。
    this.json =
        json.copy()
            .registerModule(
                new com.fasterxml.jackson.databind.module.SimpleModule()
                    .addSerializer(
                        java.math.BigDecimal.class,
                        com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
  }

  public String write(java.util.List<Rule> sources, String inputVersionFingerprint) {
    if (sources.isEmpty()) throw new IllegalArgumentException("原稿来源不能为空");
    try {
      return json.writeValueAsString(
          new SavedRule(
              sources.getFirst(), sources.subList(1, sources.size()), inputVersionFingerprint));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("无法保存原稿重复来源", error);
    }
  }

  public record SavedRule(
      Rule rule, java.util.List<Rule> duplicateSources, String inputVersionFingerprint) {}

  public String inputVersionFingerprint(PriceLinkedItem item) {
    try {
      return json.readValue(item.getSourceInputSnapshotJson(), SavedRule.class)
          .inputVersionFingerprint();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("原稿预检版本快照损坏：" + item.getId(), error);
    }
  }

  public Rule read(PriceLinkedItem item) {
    if (!VERSION.equals(item.getExpressionVersion()))
      throw new IllegalArgumentException("不支持的原稿表达式版本：" + item.getExpressionVersion());
    try {
      Rule rule = json.readValue(item.getSourceInputSnapshotJson(), SavedRule.class).rule();
      var key = rule.identity();
      var storedKey =
          new com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey(
              item.getBusinessUnitType(),
              item.getOrgCode(),
              item.getMaterialCode(),
              item.getSupplierCode(),
              item.getSupplierName(),
              item.getSpecModel(),
              item.getUnit());
      if (!key.identityKey().equals(storedKey.identityKey())
          || !key.identityKey().equals(item.getRuleIdentityKey())
          || !key.businessUnitType().equals(item.getBusinessUnitType())
          || !key.orgCode().equals(item.getOrgCode())
          || !key.materialCode().equals(item.getMaterialCode())
          || !rule.compiled().expression().equals(item.getFormulaExpr())
          || !Integer.valueOf(rule.taxIncluded() ? 1 : 0).equals(item.getTaxIncluded()))
        throw new IllegalArgumentException("快照与规则身份/表达式/税价口径不一致");
      return rule;
    } catch (Exception error) {
      throw new IllegalArgumentException(
          "原稿规则快照不可用，规则=" + item.getId() + "：" + error.getMessage(), error);
    }
  }
}
