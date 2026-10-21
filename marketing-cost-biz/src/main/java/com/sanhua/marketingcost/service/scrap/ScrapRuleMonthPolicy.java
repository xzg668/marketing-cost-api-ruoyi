package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 按料号/供应商沿用显式规则；月价副本不产生新的规则边界。 */
public final class ScrapRuleMonthPolicy {
  private static final ObjectMapper JSON = new ObjectMapper();

  private ScrapRuleMonthPolicy() {}

  public static Map<String, ScrapPrice> select(
      Collection<ScrapPrice> rows, String businessUnit, String month) {
    String requested = FactorPriceScope.month(month);
    var selected = new TreeMap<String, ScrapPrice>();
    var replacements = new TreeMap<String, String>();
    for (var row : rows) {
      if (businessUnit.equals(row.getBusinessUnitType())
          && row.getPriceMonth().compareTo(requested) <= 0
          && !"CARRY_FORWARD".equals(row.getOriginType())) {
        for (String key : supersededKeys(row))
          replacements.merge(
              key, row.getPriceMonth(), (left, right) -> left.compareTo(right) >= 0 ? left : right);
      }
    }
    for (var row : rows) {
      if (!businessUnit.equals(row.getBusinessUnitType())
          || row.getPriceMonth().compareTo(requested) > 0) continue;
      if ("CARRY_FORWARD".equals(row.getOriginType())) continue;
      if (!"IMPORT".equals(row.getOriginType()) && !"MANUAL".equals(row.getOriginType()))
        throw new IllegalArgumentException("废料规则来源无效：" + row.getId());
      String key = identity(row).materialSupplierKey();
      // 编辑供应商替换的是这一条规则。旧身份不能再从更早月份冒出来；
      // 同月或后续显式导入旧身份则表示另行维护，仍按多供应商规则处理。
      String replacedFrom = replacements.get(key);
      if (replacedFrom != null && row.getPriceMonth().compareTo(replacedFrom) < 0) continue;
      ScrapPrice previous = selected.get(key);
      if (previous == null || previous.getPriceMonth().compareTo(row.getPriceMonth()) < 0)
        selected.put(key, row);
      else if (previous.getPriceMonth().equals(row.getPriceMonth()))
        throw new IllegalArgumentException("同月废料规则身份重复：" + row.getMaterialCode());
    }
    return Collections.unmodifiableMap(selected);
  }

  public static void recordIdentityChange(ScrapPrice row, ScrapPriceIdentity replacement) {
    String previous = identity(row).materialSupplierKey();
    String current = replacement.materialSupplierKey();
    if (previous.equals(current)) return;
    var keys = new java.util.TreeSet<>(supersededKeys(row));
    keys.add(previous);
    keys.remove(current);
    try {
      row.setSupersededIdentityKeysJson(JSON.writeValueAsString(keys));
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("废料供应商变更依据无法保存", failure);
    }
  }

  private static java.util.List<String> supersededKeys(ScrapPrice row) {
    if (row.getSupersededIdentityKeysJson() == null) return java.util.List.of();
    try {
      return JSON.readValue(
          row.getSupersededIdentityKeysJson(), new TypeReference<java.util.List<String>>() {});
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("废料供应商变更依据无效：" + row.getId(), failure);
    }
  }

  public static ScrapPriceIdentity identity(ScrapPrice row) {
    return new ScrapPriceIdentity(
        row.getBusinessUnitType(),
        row.getPriceMonth(),
        row.getMaterialCode(),
        row.getSupplierCode(),
        row.getSupplierName());
  }
}
