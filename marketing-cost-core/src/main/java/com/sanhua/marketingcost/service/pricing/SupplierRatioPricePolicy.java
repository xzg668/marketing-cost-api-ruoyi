package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 与价格来源无关的供应商选择规则，不写数据，也不替报价员确认。 */
public final class SupplierRatioPricePolicy {
  private SupplierRatioPricePolicy() {}

  public static SupplierPriceCandidate choose(
      List<SupplierPriceCandidate> candidates, List<SupplierSupplyRatio> ratios) {
    candidates = eligible(candidates, ratios);
    if (candidates.isEmpty()) return null;
    if (candidates.size() == 1) return candidates.get(0);
    Map<String, SupplierSupplyRatio> bySupplier = index(ratios);
    List<SupplierPriceCandidate> known = candidates.stream()
        .filter(c -> match(bySupplier, c) != null
            && match(bySupplier, c).getSupplyRatio() != null).toList();
    if (known.isEmpty()) return null;
    Comparator<SupplierPriceCandidate> preference = Comparator
        .comparing((SupplierPriceCandidate c) -> match(bySupplier, c).getSupplyRatio())
        .thenComparing(SupplierPriceCandidate::unitPrice)
        .thenComparing(SupplierPriceCandidate::supplierCode);
    SupplierPriceCandidate best = known.stream().max(preference).orElseThrow();
    if (known.size() == candidates.size()) return best;

    // 空值仍是空值。按物料总比例100%的业务前提，用未分配比例的上界判断
    // 空值是否可能改变最大供应商；不补造0，也不使用没有有效价格的供应商报价。
    BigDecimal total = ratios.stream().map(SupplierSupplyRatio::getSupplyRatio)
        .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (total.compareTo(BigDecimal.ONE) > 0) return null;
    BigDecimal remaining = BigDecimal.ONE.subtract(total);
    return match(bySupplier, best).getSupplyRatio().compareTo(remaining) > 0
        ? best : null;
  }

  /** Apply withdrawal before every shortcut or manual fallback; null/missing is not zero. */
  public static List<SupplierPriceCandidate> eligible(
      List<SupplierPriceCandidate> candidates, List<SupplierSupplyRatio> ratios) {
    index(ratios); // Reject ambiguous current versions even in single/same-price branches.
    return candidates.stream().filter(c -> !withdrawn(c.supplierCode(), c.supplierName(), ratios)).toList();
  }

  public static boolean withdrawn(String code, String name, List<SupplierSupplyRatio> ratios) {
    String normalizedCode = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(code);
    String normalizedName = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(name);
    return ratios.stream().filter(r -> r.getSupplyRatio() != null && r.getSupplyRatio().signum() == 0)
        .anyMatch(r -> {
          String rowCode = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(r.getSupplierCode());
          if (normalizedCode != null && rowCode != null) return normalizedCode.equals(rowCode);
          return normalizedName != null && normalizedName.equals(
              com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(r.getSupplierName()));
        });
  }

  public static SupplierPriceCandidate highest(List<SupplierPriceCandidate> candidates) {
    return candidates.stream().max(Comparator.comparing(SupplierPriceCandidate::unitPrice)
        .thenComparing(SupplierPriceCandidate::supplierCode)).orElse(null);
  }

  public static Map<String, SupplierSupplyRatio> index(List<SupplierSupplyRatio> ratios) {
    return ratios.stream().collect(Collectors.toMap(
        r -> com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.supplierIdentity(r.getSupplierCode(), r.getSupplierName()),
        Function.identity(), (a, b) -> {
          throw new IllegalStateException("同一物料和供应商存在多条有效供货比率：" + a.getSupplierName());
        }));
  }

  public static SupplierSupplyRatio match(Map<String, SupplierSupplyRatio> ratios, SupplierPriceCandidate candidate) {
    String code = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(candidate.supplierCode());
    SupplierSupplyRatio exact = code == null ? null : ratios.get("CODE:" + code);
    if (exact != null) return exact;
    String name = com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils.normalizeToNull(candidate.supplierName());
    return name == null ? null : ratios.get("NAME:" + name);
  }
}
