package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import com.sanhua.marketingcost.service.scrap.ScrapPriceSelectionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartMaterialPriceResolveServiceImpl
    implements MakePartMaterialPriceResolveService {

  private final MaterialPriceRouterService materialPriceRouterService;
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final ScrapPriceSelectionService scrapPrices;

  public MakePartMaterialPriceResolveServiceImpl(
      MaterialPriceRouterService materialPriceRouterService,
      List<PriceResolver> priceResolvers,
      LinkedPriceEnsureService linkedPriceEnsureService,
      ScrapPriceSelectionService scrapPrices) {
    this.materialPriceRouterService = materialPriceRouterService;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Map.copyOf(map);
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    this.scrapPrices = scrapPrices;
  }

  /** Resolve raw material and scrap prices using the complete request scope. */
  @Override
  public MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext,
      Purpose purpose) {
    return execute(
        materialCode,
        period,
        quoteDate,
        priceAsOfTime,
        oaNo,
        businessUnitType,
        scenarioContext,
        true,
        purpose);
  }

  @Override
  public MakePartMaterialPriceResolveResult calculateMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext,
      Purpose purpose) {
    return execute(
        materialCode,
        period,
        quoteDate,
        priceAsOfTime,
        oaNo,
        businessUnitType,
        scenarioContext,
        false,
        purpose);
  }

  private MakePartMaterialPriceResolveResult execute(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext,
      boolean persistLinkedPrice,
      Purpose purpose) {
    String code = trimToNull(materialCode);
    if (code == null) {
      return MakePartMaterialPriceResolveResult.miss(
          null, "INVALID_INPUT", "materialCode 为空", null);
    }
    String priceOrgCode = scenarioContext == null ? null : scenarioContext.supplierReviews().priceOrgCode();
    if (!StringUtils.hasText(priceOrgCode)) {
      throw new IllegalArgumentException("制造件材料取价缺少取价组织");
    }
    try {
      var scrap =
          scrapPrices.select(
              businessUnitType,
              period,
              code,
              priceOrgCode,
              oaNo,
              quoteDate,
              scenarioContext,
              persistLinkedPrice,
              purpose == Purpose.SCRAP);
      if (scrap.isPresent()) {
        var selected = scrap.get();
        var result =
            MakePartMaterialPriceResolveResult.ok(
                code,
                selected.priceType(),
                selected.unitPrice(),
                selected.message(),
                "SCRAP_PRICE");
        result.setEvidence(selected.evidence());
        return result;
      }
    } catch (IllegalArgumentException failure) {
      return MakePartMaterialPriceResolveResult.miss(
          code, "MISSING_PRICE", failure.getMessage(), "SCRAP_PRICE");
    }
    List<PriceTypeRoute> candidates = persistLinkedPrice
        ? materialPriceRouterService.prepareCandidates(code, null, trimToNull(period), quoteDate,
            priceOrgCode, businessUnitType)
        : materialPriceRouterService.listCandidates(code, trimToNull(period), quoteDate,
            priceOrgCode, businessUnitType);
    if (candidates.isEmpty()) {
      return MakePartMaterialPriceResolveResult.missingPrice(
          code, "当前月份和组织没有可用价格来源：" + code);
    }

    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo(oaNo);
    item.setPartCode(code);
    item.setPriceOrgCode(priceOrgCode);
    item.setPartQty(BigDecimal.ONE);
    CostRunContext context =
        priceContext(period, quoteDate, priceAsOfTime, oaNo, businessUnitType, scenarioContext);

    context.setPriceCheckOnly(!persistLinkedPrice);
    List<String> traceParts = new ArrayList<>();
    String lastMissReason = null;
    boolean linkedPriceEnsured = false;
    for (PriceTypeRoute route : candidates) {
      String priceTypeText = priceTypeText(route);
      traceParts.add(traceRoute(route));
      if (route.priceType() == PriceTypeEnum.MAKE) {
        // 第一版不做递归制造件取价，避免原材料/废料价格解析再次进入制造件生成链路。
        lastMissReason = "命中自制件价格类型，第一版不递归取价(material_code=" + code + ")";
        continue;
      }
      PriceResolver resolver = resolverMap.get(route.priceType());
      if (resolver == null) {
        lastMissReason = "价格类型无 Resolver(price_type=" + priceTypeText + ")";
        continue;
      }
      if (route.ordinaryLinked() && !linkedPriceEnsured) {
        if (persistLinkedPrice) {
          var preparationFailure =
              ensureLinkedPrice(code, period, oaNo, businessUnitType, context, scenarioContext);
          if (preparationFailure != null) {
            lastMissReason = preparationFailure.message();
            if (PriceResolveResult.isBlockingFailure(preparationFailure.code())) {
              return MakePartMaterialPriceResolveResult.miss(
                  code, "ERROR", lastMissReason, String.join(" -> ", traceParts));
            }
            // 本次已确认公共规则不可用，不能读取先前生成的缓存价格。
            continue;
          }
        } else {
          PriceLinkedCalcItem calculated =
              calculateLinkedPrice(code, period, oaNo, businessUnitType, context, scenarioContext);
          if (calculated != null && calculated.getPartUnitPrice() != null) {
            var result =
                MakePartMaterialPriceResolveResult.ok(
                    code,
                    priceTypeText,
                    calculated.getPartUnitPrice(),
                    "联动价只读计算完成",
                    String.join(" -> ", traceParts));
            result.setEvidence(
                new com.sanhua.marketingcost.service.pricing.PriceResolveEvidence(
                    calculated.getSourcePriceRecordId(),
                    calculated.getSourcePriceBatchNo(),
                    calculated.getSupplierName(),
                    calculated.getSupplierCode(),
                    calculated.getSupplyRatio(),
                    calculated.getSupplyRatioRecordId(),
                    calculated.getSourceEffectiveFrom(),
                    calculated.getSourceEffectiveTo(),
                    Integer.valueOf(1).equals(calculated.getCarriedForward()),
                    calculated.getWarningMessage(),
                    calculated.getInputSnapshotJson()).withPriority(route));
            return result;
          }
          if (calculated != null && PriceResolveResult.isBlockingFailure(calculated.getFailureCode())) {
            return MakePartMaterialPriceResolveResult.miss(
                code, "ERROR", calculated.getCalcMessage(), String.join(" -> ", traceParts));
          }
          lastMissReason =
              calculated == null
                  ? "联动价只读计算未返回结果(material_code=" + code + ")"
                  : calculated.getCalcMessage();
        }
        linkedPriceEnsured = true;
      }
      if (!persistLinkedPrice && route.ordinaryLinked()) {
        continue;
      }
      PriceResolveResult resolved = resolver.resolve(oaNo, item, route, context);
      if (resolved.unitPrice() != null) {
        var result =
            MakePartMaterialPriceResolveResult.ok(
                code,
                priceTypeText,
                resolved.unitPrice(),
                resolved.remark(),
                String.join(" -> ", traceParts));
        result.setEvidence(resolved.evidence().withPriority(route));
        return result;
      }
      if (resolved.hasBlockingFailure()) {
        var failure = MakePartMaterialPriceResolveResult.miss(
            code, "ERROR", resolved.remark(), String.join(" -> ", traceParts));
        failure.setPriceType(priceTypeText);
        failure.setEvidence(resolved.evidence().withPriority(route));
        return failure;
      }
      if (StringUtils.hasText(resolved.remark())) {
        lastMissReason = resolved.remark();
      }
    }

    String trace = String.join(" -> ", traceParts);
    String remark =
        lastMissReason == null ? "有价格类型路由但未取到有效价格(material_code=" + code + ")" : lastMissReason;
    String status = trace.contains("自制件") ? "UNSUPPORTED_MAKE" : "MISSING_PRICE";
    return MakePartMaterialPriceResolveResult.miss(code, status, remark, trace);
  }

  private record LinkedPreparationFailure(String code, String message) {}

  private LinkedPreparationFailure ensureLinkedPrice(
      String materialCode,
      String period,
      String oaNo,
      String businessUnitType,
      CostRunContext context,
      PricePrepareScenarioContext scenarioContext) {
    if (linkedPriceEnsureService == null
        || !StringUtils.hasText(oaNo)
        || !StringUtils.hasText(businessUnitType)
        || !StringUtils.hasText(period)) {
      return null;
    }
    try {
      LinkedPriceEnsureRequest request =
          LinkedPriceEnsureRequest.quote(
              oaNo.trim(), businessUnitType.trim(), period.trim(), Set.of(materialCode));
      request.setPriceOrgCode(context == null ? null : context.getPriceOrgCode());
      request.setPriceAsOfTime(context == null ? null : context.getPriceAsOfTime());
      request.setPriceScenarioType(scenarioType(scenarioContext));
      request.setSupplierPriceReviewContext(scenarioContext == null ? null : scenarioContext.supplierReviews());
      request.setVariableOverrides(
          scenarioContext == null ? Map.of() : scenarioContext.variableOverrides());
      LinkedPriceEnsureResult result = linkedPriceEnsureService.ensure(request);
      if (result != null && result.getFailedCount() > 0) {
        String failureCode =
            result.getFailedItems().stream()
                .map(LinkedPriceEnsureResult.FailedItem::getReasonCode)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        String detail =
            result.getFailedItems().stream()
                .map(LinkedPriceEnsureResult.FailedItem::getReason)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining("；"));
        return new LinkedPreparationFailure(
            failureCode, "联动价准备失败(material_code=" + materialCode + ")：" + detail);
      }
      return null;
    } catch (RuntimeException ex) {
      return new LinkedPreparationFailure(
          LinkedPriceEnsureServiceImpl.LINKED_RULE_ERROR,
          "联动价准备异常(material_code="
              + materialCode
              + "): "
              + (StringUtils.hasText(ex.getMessage())
                  ? ex.getMessage()
                  : ex.getClass().getSimpleName()));
    }
  }

  private PriceLinkedCalcItem calculateLinkedPrice(
      String materialCode,
      String period,
      String oaNo,
      String businessUnitType,
      CostRunContext context,
      PricePrepareScenarioContext scenarioContext) {
    if (linkedPriceEnsureService == null
        || !StringUtils.hasText(oaNo)
        || !StringUtils.hasText(businessUnitType)
        || !StringUtils.hasText(period)) {
      return null;
    }
    try {
      LinkedPriceEnsureRequest request =
          LinkedPriceEnsureRequest.quote(
              oaNo.trim(), businessUnitType.trim(), period.trim(), Set.of(materialCode));
      request.setPriceOrgCode(context == null ? null : context.getPriceOrgCode());
      request.setPriceAsOfTime(context == null ? null : context.getPriceAsOfTime());
      request.setPriceScenarioType(scenarioType(scenarioContext));
      request.setSupplierPriceReviewContext(scenarioContext == null ? null : scenarioContext.supplierReviews());
      request.setVariableOverrides(
          scenarioContext == null ? Map.of() : scenarioContext.variableOverrides());
      List<PriceLinkedCalcItem> calculated = linkedPriceEnsureService.calculate(request);
      return calculated == null || calculated.isEmpty() ? null : calculated.get(0);
    } catch (RuntimeException ex) {
      PriceLinkedCalcItem failed = new PriceLinkedCalcItem();
      failed.setItemCode(materialCode);
      failed.setCalcStatus("FAILED");
      failed.setFailureCode(LinkedPriceEnsureServiceImpl.LINKED_RULE_ERROR);
      failed.setCalcMessage("联动价只读计算异常(material_code=" + materialCode + "): " + ex.getMessage());
      return failed;
    }
  }

  private String traceRoute(PriceTypeRoute route) {
    return priceTypeText(route)
        + "(priority="
        + route.priority()
        + ",source="
        + nullToDash(route.sourceSystem())
        + ")";
  }

  private CostRunContext priceContext(
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext) {
    LocalDateTime resolvedPriceAsOfTime =
        priceAsOfTime == null && quoteDate != null ? quoteDate.atStartOfDay() : priceAsOfTime;
    CostRunContext context =
        CostRunContext.quote(
            oaNo,
            null,
            null,
            null,
            null,
            businessUnitType,
            trimToNull(period),
            resolvedPriceAsOfTime,
            null);
    if (scenarioContext != null) {
      context.setSupplierPriceReviewContext(scenarioContext.supplierReviews());
      context.setOaFormItemId(scenarioContext.supplierReviews().oaFormItemId());
      if (!StringUtils.hasText(context.getPriceOrgCode())) {
        context.setPriceOrgCode(scenarioContext.supplierReviews().priceOrgCode());
      }
    }
    context.setPriceScenarioType(scenarioType(scenarioContext).name());
    context.setPriceVariableOverrides(
        scenarioContext == null ? Map.of() : scenarioContext.variableOverrides());
    return context;
  }

  private QuotePriceScenarioType scenarioType(PricePrepareScenarioContext scenarioContext) {
    return scenarioContext == null || scenarioContext.scenarioType() == null
        ? QuotePriceScenarioType.OA_LOCKED
        : scenarioContext.scenarioType();
  }

  private String priceTypeText(PriceTypeRoute route) {
    if (route != null && StringUtils.hasText(route.rawPriceType())) {
      return route.rawPriceType().trim();
    }
    return route == null || route.priceType() == null ? null : route.priceType().getDbText();
  }

  private String nullToDash(String value) {
    return StringUtils.hasText(value) ? value.trim() : "-";
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
