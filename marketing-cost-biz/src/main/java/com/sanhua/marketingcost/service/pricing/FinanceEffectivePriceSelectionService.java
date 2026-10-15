package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 公共原稿先完整读取各家有效报价，再复用固定采购价的选供与确认政策。 */
@Service
public class FinanceEffectivePriceSelectionService {
  private final FinanceEffectivePriceService prices;
  private final SupplierPriceSelectionService suppliers;

  public FinanceEffectivePriceSelectionService(
      FinanceEffectivePriceService prices, SupplierPriceSelectionService suppliers) {
    this.prices = prices;
    this.suppliers = suppliers;
  }

  /** 一批须已确定原稿版本、类型和同一财务方案；缺结果的规则不能被丢弃。 */
  public Map<Long, FinanceEffectivePrice> read(
      List<FinanceEffectivePriceService.SourceRule> rules, FactorPriceSnapshot financial) {
    if (rules.isEmpty()) return Map.of();
    String type = rules.getFirst().type();
    for (var rule : rules) {
      if (!type.equals(rule.type())
          || !financial.scope().businessUnitType().equals(rule.key().businessUnitType())
          || !financial.scope().priceOrgCode().equals(rule.key().orgCode()))
        throw new IllegalArgumentException("有效价批量读取的类型、业务单元或组织不一致");
    }
    var saved = prices.read(type, rules.stream().map(r -> r.id()).toList(), financial);
    var result = new LinkedHashMap<Long, FinanceEffectivePrice>();
    for (var rule : rules) result.put(rule.id(), prices.require(rule, saved));
    return result;
  }

  public record Selection(
      FinanceEffectivePrice price, SupplierPriceSelectionService.Selection supplier) {}

  public Selection select(
      SupplierPriceScope scope,
      Collection<FinanceEffectivePrice> candidates,
      SupplierPriceReviewContext reviews) {
    return select(scope, candidates, reviews, null);
  }

  /** preparedRatios为本次批量读取的有效比率；null表示单独查询，空集合表示确实未维护。 */
  public Selection select(
      SupplierPriceScope scope,
      Collection<FinanceEffectivePrice> candidates,
      SupplierPriceReviewContext reviews,
      List<SupplierSupplyRatio> preparedRatios) {
    var byKey = new LinkedHashMap<String, FinanceEffectivePrice>();
    var bySupplier = new LinkedHashMap<String, FinanceEffectivePrice>();
    FinanceEffectivePrice first = null;
    for (var row : candidates) {
      if (!Objects.equals(scope.businessUnitType(), row.getBusinessUnitType())
          || !Objects.equals(scope.orgCode(), row.getOrgCode())
          || !Objects.equals(scope.materialCode(), row.getMaterialCode())
          || !Objects.equals(scope.priceType(), row.getPriceType())
          || scope.periodMonth() == null
          || row.getEffectiveFromMonth() == null
          || row.getEffectiveFromMonth().compareTo(scope.periodMonth()) > 0)
        throw new IllegalArgumentException("财务有效价与选供范围不一致：" + scope.materialCode());
      if (row.getSupplierCode() == null
          || row.getSupplierCode().isBlank()
          || row.getUnitPrice() == null
          || row.getInputFingerprint() == null)
        throw new IllegalArgumentException("财务有效价缺少供应商、单价或来源身份：" + row.getId());
      if (first != null
          && (!Objects.equals(first.getFinanceBatchId(), row.getFinanceBatchId())
              || !Objects.equals(first.getSchemeCode(), row.getSchemeCode())
              || !Objects.equals(first.getConditionCode(), row.getConditionCode())
              || !Objects.equals(first.getOriginalMonth(), row.getOriginalMonth())))
        throw new IllegalArgumentException("候选有效价混入不同原稿月份或财务方案：" + scope.materialCode());
      first = row;
      if (bySupplier.putIfAbsent(row.getSupplierCode(), row) != null)
        throw new IllegalArgumentException(
            "料号="
                + scope.materialCode()
                + "，供应商="
                + row.getSupplierCode()
                + "存在多个规格、单位或版本，必须明确适用规则身份");
      byKey.put(row.getInputFingerprint(), row);
    }
    var values =
        byKey.values().stream()
            .map(
                row ->
                    new SupplierPriceCandidate(
                        row.getInputFingerprint(),
                        row.getSupplierCode(),
                        row.getSupplierName(),
                        row.getUnitPrice(),
                        null,
                        null))
            .toList();
    var selection =
        preparedRatios == null
            ? suppliers.select(scope, values)
            : suppliers.select(scope, values, preparedRatios);
    if (selection.review() != null && reviews != null) reviews.add(selection.review());
    return new Selection(
        selection.candidate() == null ? null : byKey.get(selection.candidate().key()), selection);
  }
}
