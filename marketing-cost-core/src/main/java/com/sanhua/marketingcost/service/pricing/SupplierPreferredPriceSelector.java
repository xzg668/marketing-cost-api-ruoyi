package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SupplierPreferredPriceSelector {

  private final SupplierSupplyRatioResolveService resolveService;

  public SupplierPreferredPriceSelector(SupplierSupplyRatioResolveService resolveService) {
    this.resolveService = resolveService;
  }

  public <T> SupplierPreferredPriceSelection<T> select(
      List<T> candidates,
      String businessUnitType,
      String materialCode,
      String materialName,
      String specModel,
      LocalDate pricingDate,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    if (candidates == null || candidates.isEmpty()) {
      return new SupplierPreferredPriceSelection<>(null, "");
    }
    T fallback = candidates.get(0);
    if (distinctSupplierCount(candidates, supplierNameGetter, supplierCodeGetter) <= 1) {
      return new SupplierPreferredPriceSelection<>(fallback, "");
    }

    SupplierSupplyRatioResolveResult mainSupplier = resolveService.resolve(
        businessUnitType, materialCode, materialName, specModel, pricingDate);
    if (!mainSupplier.isMatched()) {
      // 供应关系缺失不能阻断报价；保留原价格源排序的第一条，并把原因写入 trace。
      return new SupplierPreferredPriceSelection<>(
          fallback,
          "未维护主供应商供货比例，按默认价格取价");
    }

    for (T candidate : candidates) {
      if (sameSupplier(candidate, mainSupplier, supplierNameGetter, supplierCodeGetter)) {
        return new SupplierPreferredPriceSelection<>(
            candidate,
            "按主供应商供货比例匹配价格");
      }
    }

    // 主供有维护但价格源没有对应供应商时，也不能阻断报价；仍按原排序兜底。
    return new SupplierPreferredPriceSelection<>(
        fallback,
        "主供应商无价格记录，按默认价格取价");
  }

  private <T> long distinctSupplierCount(
      List<T> candidates,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    return candidates.stream()
        .map(candidate -> supplierKey(candidate, supplierNameGetter, supplierCodeGetter))
        .filter(StringUtils::hasText)
        .distinct()
        .count();
  }

  private <T> boolean sameSupplier(
      T candidate,
      SupplierSupplyRatioResolveResult mainSupplier,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    String candidateCode = normalized(supplierCodeGetter.apply(candidate));
    String candidateName = normalized(supplierNameGetter.apply(candidate));
    String mainCode = normalized(mainSupplier.getSupplierCode());
    String mainName = normalized(mainSupplier.getSupplierName());
    return (StringUtils.hasText(mainCode) && Objects.equals(candidateCode, mainCode))
        || (StringUtils.hasText(mainName) && Objects.equals(candidateName, mainName));
  }

  private <T> String supplierKey(
      T candidate,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    String code = normalized(supplierCodeGetter.apply(candidate));
    if (StringUtils.hasText(code)) {
      return "CODE:" + code;
    }
    String name = normalized(supplierNameGetter.apply(candidate));
    return StringUtils.hasText(name) ? "NAME:" + name : "";
  }

  private String normalized(String value) {
    return SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(value);
  }
}
