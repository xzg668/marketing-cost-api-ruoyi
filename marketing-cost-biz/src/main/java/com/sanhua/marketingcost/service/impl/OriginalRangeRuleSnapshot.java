package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser.Rule;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class OriginalRangeRuleSnapshot {
  public static final String VERSION = "RANGE_ORIGINAL_V1";
  private final ObjectMapper json;

  public OriginalRangeRuleSnapshot(ObjectMapper json) {
    // 与联动原稿一致：JSON中的十进制文本避免数据库double转换截断档位单价。
    this.json =
        json.copy()
            .registerModule(
                new com.fasterxml.jackson.databind.module.SimpleModule()
                    .addSerializer(
                        java.math.BigDecimal.class,
                        com.fasterxml.jackson.databind.ser.std.ToStringSerializer.instance));
  }

  public static boolean isOriginal(PriceRangeFactorRule rule) {
    return rule != null
        && rule.getSourceUploadBatchId() != null
        && "PUBLISHED".equals(rule.getRuleStatus());
  }

  public String write(List<Rule> sources, String inputFingerprint) {
    if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("区间来源不能为空");
    try {
      return json.writeValueAsString(
          new SavedRule(VERSION, List.copyOf(sources), inputFingerprint));
    } catch (Exception error) {
      throw new IllegalStateException("无法保存区间原稿快照", error);
    }
  }

  public SavedRule read(PriceRangeFactorRule item) {
    try {
      SavedRule saved = json.readValue(item.getSourceInputSnapshotJson(), SavedRule.class);
      if (!VERSION.equals(saved.version()) || saved.sources() == null || saved.sources().isEmpty())
        throw new IllegalArgumentException("区间快照版本或来源无效");
      var stored =
          new MaterialPriceRuleKey(
              item.getBusinessUnitType(),
              item.getOrgCode(),
              item.getMaterialCode(),
              item.getSupplierCode(),
              item.getSupplierName(),
              item.getSpecModel(),
              item.getPriceUnit());
      for (Rule source : saved.sources())
        if (!source.identity().identityKey().equals(stored.identityKey())
            || !source.identity().identityKey().equals(item.getRuleIdentityKey())
            || !source.factorKey().equals(item.getFactorCode())
            || !source.factorUnit().name().equals(item.getFactorUnit()))
          throw new IllegalArgumentException("区间快照与规则身份/因素/单位不一致");
      return saved;
    } catch (Exception error) {
      throw new IllegalArgumentException(
          "区间原稿快照不可用，规则=" + item.getId() + "：" + error.getMessage(), error);
    }
  }

  public record SavedRule(String version, List<Rule> sources, String inputFingerprint) {
    public Rule rule() {
      return sources.getFirst();
    }
  }
}
