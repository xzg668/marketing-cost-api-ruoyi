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

  public static final String PRIMARY_SUPPLIER_PRICE_MISSING = "PRIMARY_SUPPLIER_PRICE_MISSING";
  public static final String SUPPLIER_RATIO_MISSING = "SUPPLIER_RATIO_MISSING";

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
      return selection(null, "", 0, null, null, null, "NONE", "", "");
    }
    T first = candidates.get(0);
    int candidateSupplierCount = distinctSupplierCount(
        candidates, supplierNameGetter, supplierCodeGetter);
    if (candidateSupplierCount == 0) {
      return selection(
          first,
          "价格源无供应商维度，按价格版本规则取价",
          0,
          null,
          null,
          null,
          "NO_SUPPLIER_DIMENSION",
          "",
          "");
    }
    if (candidateSupplierCount == 1) {
      T onlySupplierRow = candidates.stream()
          .filter(candidate -> StringUtils.hasText(
              supplierKey(candidate, supplierNameGetter, supplierCodeGetter)))
          .findFirst()
          .orElse(first);
      return selection(
          onlySupplierRow,
          "单一供应商价格，直接取该供应商最新已生效价格",
          1,
          supplierNameGetter.apply(onlySupplierRow),
          supplierCodeGetter.apply(onlySupplierRow),
          null,
          "SINGLE_SUPPLIER",
          "",
          "");
    }

    SupplierSupplyRatioResolveResult mainSupplier =
        resolveService.resolve(
            businessUnitType, materialCode, materialName, specModel, pricingDate);
    if (mainSupplier == null || !mainSupplier.isMatched()) {
      String message = "物料 " + materialCode + " 存在多个供应商价格，但未维护供货比例";
      return selection(
          null,
          message,
          candidateSupplierCount,
          null,
          null,
          null,
          "NONE",
          SUPPLIER_RATIO_MISSING,
          message);
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
            mainSupplier.getId(),
            matchMode,
            "",
            "",
            mainSupplier.getSupplyRatio());
      }
    }

    String message = "主供应商无价格：物料=" + materialCode
        + "，主供应商=" + displaySupplier(mainSupplier);
    return selection(
        null,
        message,
        candidateSupplierCount,
        mainSupplier.getSupplierName(),
        mainSupplier.getSupplierCode(),
        mainSupplier.getId(),
        "NONE",
        PRIMARY_SUPPLIER_PRICE_MISSING,
        message,
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
      Long supplyRatioRecordId,
      String matchMode,
      String failureCode,
      String failureMessage) {
    return selection(
        row,
        traceMessage,
        candidateSupplierCount,
        mainSupplierName,
        mainSupplierCode,
        supplyRatioRecordId,
        matchMode,
        failureCode,
        failureMessage,
        null);
  }

  private <T> SupplierPreferredPriceSelection<T> selection(
      T row,
      String traceMessage,
      int candidateSupplierCount,
      String mainSupplierName,
      String mainSupplierCode,
      Long supplyRatioRecordId,
      String matchMode,
      String failureCode,
      String failureMessage,
      java.math.BigDecimal supplyRatio) {
    return new SupplierPreferredPriceSelection<>(
        row,
        traceMessage,
        candidateSupplierCount,
        mainSupplierName,
        mainSupplierCode,
        supplyRatio,
        supplyRatioRecordId,
        matchMode,
        failureCode,
        failureMessage);
  }

  private String displaySupplier(SupplierSupplyRatioResolveResult supplier) {
    if (supplier == null) {
      return "";
    }
    String name = StringUtils.hasText(supplier.getSupplierName())
        ? supplier.getSupplierName().trim()
        : "";
    String code = StringUtils.hasText(supplier.getSupplierCode())
        ? supplier.getSupplierCode().trim()
        : "";
    if (StringUtils.hasText(name) && StringUtils.hasText(code)) {
      return name + "(" + code + ")";
    }
    return StringUtils.hasText(name) ? name : code;
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
