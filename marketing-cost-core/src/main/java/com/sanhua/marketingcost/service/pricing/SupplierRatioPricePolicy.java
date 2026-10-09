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
    if (candidates.isEmpty()) return null;
    if (candidates.size() == 1) return candidates.get(0);
    Map<String, SupplierSupplyRatio> bySupplier = index(ratios);
    List<SupplierPriceCandidate> known = candidates.stream()
        .filter(c -> bySupplier.containsKey(c.supplierCode())
            && bySupplier.get(c.supplierCode()).getSupplyRatio() != null).toList();
    if (known.isEmpty()) return null;
    Comparator<SupplierPriceCandidate> preference = Comparator
        .comparing((SupplierPriceCandidate c) -> bySupplier.get(c.supplierCode()).getSupplyRatio())
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
    return bySupplier.get(best.supplierCode()).getSupplyRatio().compareTo(remaining) > 0
        ? best : null;
  }

  public static SupplierPriceCandidate highest(List<SupplierPriceCandidate> candidates) {
    return candidates.stream().max(Comparator.comparing(SupplierPriceCandidate::unitPrice)
        .thenComparing(SupplierPriceCandidate::supplierCode)).orElse(null);
  }

  public static Map<String, SupplierSupplyRatio> index(List<SupplierSupplyRatio> ratios) {
    return ratios.stream().collect(Collectors.toMap(
        SupplierSupplyRatio::getSupplierCode, Function.identity(), (a, b) -> {
          throw new IllegalStateException("同一物料和供应商存在多条有效供货比率：" + a.getSupplierCode());
        }));
  }
}
