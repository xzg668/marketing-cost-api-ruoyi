package com.sanhua.marketingcost.service.pricing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.impl.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 去掉文件格址和旧行情输入，仅比较决定未来月份价格的实际规则。 */
@Component
public final class OriginalRuleSignature {
  private final ObjectMapper json;
  private final OriginalRangeRuleCalculator ranges;
  public OriginalRuleSignature(ObjectMapper json, OriginalRangeRuleCalculator ranges) {
    this.json = json;
    this.ranges = ranges;
  }

  public String linked(OriginalLinkedWorkbookParser.Rule rule) {
    return hash(List.of("LINKED", rule.taxIncluded(), linkedExpression(rule)));
  }

  private String linkedExpression(OriginalLinkedWorkbookParser.Rule rule) {
    String expression = rule.compiled().expression();
    for (var entry : rule.factors().entrySet()) {
      var factor = entry.getValue();
      String token = "[" + factor.canonicalKey() + ":" + factor.unit() + "]";
      expression = expression.replaceAll("\\b" + Pattern.quote(entry.getKey()) + "\\b",
          java.util.regex.Matcher.quoteReplacement(token));
    }
    return expression;
  }

  public String describe(OriginalLinkedWorkbookParser.Rule rule) {
    return (rule.taxIncluded() ? "含税公式：" : "未税公式：") + linkedExpression(rule);
  }

  public String describe(OriginalRangeWorkbookParser.Rule rule) {
    StringBuilder description = new StringBuilder("因素：").append(rule.factorKey()).append("（")
        .append(rule.factorUnit()).append("），").append(rule.taxIncluded() ? "含税" : "未税");
    for (var band : rule.bands()) {
      var bounds = band.bounds();
      description.append("\n").append(bounds.lowerInclusive() ? "[" : "(")
          .append(bounds.low() == null ? "不限" : number(bounds.low())).append(", ")
          .append(bounds.high() == null ? "不限" : number(bounds.high()))
          .append(bounds.upperInclusive() ? "]" : ")").append(" → ")
          .append(band.problem() == null ? number(band.price()) : "待处理：" + band.problem());
    }
    var proof = ranges.extrapolation(rule);
    if (proof != null) description.append("\n外推：行情每变动").append(number(proof.factorStep()))
        .append("，单价变动").append(number(proof.priceStep()));
    if (proof != null && proof.rounding() != null) description.append("（舍入前），按原公式 ROUND(续算价, ")
        .append(proof.rounding().scale()).append(") 取整");
    return description.toString();
  }

  public String range(OriginalRangeWorkbookParser.Rule rule) {
    var proof = ranges.extrapolation(rule);
    Object extrapolation = proof == null ? List.of() : proof.rounding() == null ? Arrays.asList(
        band(proof.anchor()), number(proof.factorStep()), number(proof.priceStep()), proof.taxIncluded())
        : Arrays.asList(band(proof.anchor()), number(proof.factorStep()), number(proof.priceStep()), proof.taxIncluded(),
            number(proof.rounding().anchorBeforeRounding()), proof.rounding().scale());
    return hash(List.of("RANGE", rule.taxIncluded(), rule.factorKey(), rule.factorUnit(),
        rule.bands().stream().map(this::band).toList(), extrapolation));
  }

  private Object band(OriginalRangeWorkbookParser.Band band) {
    var b = band.bounds();
    // 诊断文字含上传路径/文件名/格址，不属于价格规则；仅保留该档不可用的状态。
    // 完整原因仍由原稿快照和 describe 展示，选中不可用档时计算器仍须拒绝取价。
    return Arrays.asList(number(b.low()), number(b.high()), b.lowerInclusive(), b.upperInclusive(),
        number(band.price()), band.problem() == null ? "" : "UNAVAILABLE");
  }

  private String number(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  public String hash(Object value) {
    try { return digest(json.writeValueAsBytes(value)); }
    catch (Exception e) { throw new IllegalArgumentException("无法生成规则核对摘要", e); }
  }

  public static String digest(byte[] bytes) {
    try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
