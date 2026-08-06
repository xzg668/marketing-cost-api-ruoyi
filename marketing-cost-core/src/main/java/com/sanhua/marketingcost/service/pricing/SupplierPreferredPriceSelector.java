package com.sanhua.marketingcost.service.pricing;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioCandidate;
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
      return selection(null, "", 0, null, null, "NONE", false, "");
    }
    T fallback = candidates.get(0);
    int candidateSupplierCount = distinctSupplierCount(
        candidates, supplierNameGetter, supplierCodeGetter);
    if (candidateSupplierCount == 0) {
      return selection(
          fallback,
          "候选价格缺少供应商身份，按历史价格排序取价",
          0,
          null,
          null,
          "DEFAULT_FALLBACK",
          true,
          "候选价格缺少供应商身份");
    }
    if (candidateSupplierCount == 1) {
      return selection(
          fallback,
          "",
          1,
          supplierNameGetter.apply(fallback),
          supplierCodeGetter.apply(fallback),
          "SINGLE_SUPPLIER",
          false,
          "");
    }

    SupplierSupplyRatioResolveResult mainSupplier =
        resolveService.resolveAmongSuppliers(
            businessUnitType,
            materialCode,
            materialName,
            specModel,
            pricingDate,
            supplierCandidates(candidates, supplierNameGetter, supplierCodeGetter));
    if (mainSupplier == null || !mainSupplier.isMatched()) {
      mainSupplier = resolveService.resolve(
          businessUnitType, materialCode, materialName, specModel, pricingDate);
    }
    if (mainSupplier == null || !mainSupplier.isMatched()) {
      // 供应关系缺失不能阻断报价；保留原价格源排序的第一条，并把原因写入 trace。
      return selection(
          fallback,
          "未维护主供应商供货比例，按默认价格取价",
          candidateSupplierCount,
          null,
          null,
          "DEFAULT_FALLBACK",
          true,
          "未维护主供应商供货比例");
    }

    for (T candidate : candidates) {
      String matchMode = supplierMatchMode(
          candidate, mainSupplier, supplierNameGetter, supplierCodeGetter);
      if (matchMode != null) {
        return selection(
            candidate,
            "按主供应商供货比例匹配价格",
            candidateSupplierCount,
            mainSupplier.getSupplierName(),
            mainSupplier.getSupplierCode(),
            matchMode,
            false,
            "",
            mainSupplier.getSupplyRatio());
      }
    }

    // 主供有维护但价格源没有对应供应商时，也不能阻断报价；仍按原排序兜底。
    return selection(
        fallback,
        "主供应商无价格记录，按默认价格取价",
        candidateSupplierCount,
        mainSupplier.getSupplierName(),
        mainSupplier.getSupplierCode(),
        "DEFAULT_FALLBACK",
        true,
        "主供应商无价格记录",
        mainSupplier.getSupplyRatio());
  }

  private <T> int distinctSupplierCount(
      List<T> candidates,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    return Math.toIntExact(candidates.stream()
        .map(candidate -> supplierKey(candidate, supplierNameGetter, supplierCodeGetter))
        .filter(StringUtils::hasText)
        .distinct()
        .count());
  }

  private <T> List<SupplierSupplyRatioCandidate> supplierCandidates(
      List<T> candidates,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    return candidates.stream()
        .map(candidate -> new SupplierSupplyRatioCandidate(
            supplierNameGetter.apply(candidate),
            supplierCodeGetter.apply(candidate)))
        .toList();
  }

  private <T> String supplierMatchMode(
      T candidate,
      SupplierSupplyRatioResolveResult mainSupplier,
      Function<T, String> supplierNameGetter,
      Function<T, String> supplierCodeGetter) {
    String candidateCode = normalized(supplierCodeGetter.apply(candidate));
    String candidateName = normalized(supplierNameGetter.apply(candidate));
    String mainCode = normalized(mainSupplier.getSupplierCode());
    String mainName = normalized(mainSupplier.getSupplierName());
    if (StringUtils.hasText(candidateCode) && StringUtils.hasText(mainCode)) {
      return Objects.equals(candidateCode, mainCode) ? "CODE" : null;
    }
    return StringUtils.hasText(candidateName)
        && StringUtils.hasText(mainName)
        && Objects.equals(candidateName, mainName)
        ? "NAME_FALLBACK"
        : null;
  }

  private <T> SupplierPreferredPriceSelection<T> selection(
      T row,
      String traceMessage,
      int candidateSupplierCount,
      String mainSupplierName,
      String mainSupplierCode,
      String matchMode,
      boolean fallback,
      String fallbackReason) {
    return selection(
        row,
        traceMessage,
        candidateSupplierCount,
        mainSupplierName,
        mainSupplierCode,
        matchMode,
        fallback,
        fallbackReason,
        null);
  }

  private <T> SupplierPreferredPriceSelection<T> selection(
      T row,
      String traceMessage,
      int candidateSupplierCount,
      String mainSupplierName,
      String mainSupplierCode,
      String matchMode,
      boolean fallback,
      String fallbackReason,
      java.math.BigDecimal supplyRatio) {
    return new SupplierPreferredPriceSelection<>(
        row,
        traceMessage,
        candidateSupplierCount,
        mainSupplierName,
        mainSupplierCode,
        supplyRatio,
        matchMode,
        fallback,
        fallbackReason);
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
