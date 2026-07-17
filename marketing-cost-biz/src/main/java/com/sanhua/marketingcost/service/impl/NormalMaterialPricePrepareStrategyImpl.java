package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class NormalMaterialPricePrepareStrategyImpl implements NormalMaterialPricePrepareStrategy {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  static final String STATUS_READY = "READY";
  static final String STATUS_MISSING_PRICE_TYPE = "MISSING_PRICE_TYPE";
  static final String STATUS_MISSING_PRICE = "MISSING_PRICE";
  static final String STATUS_FAILED = "FAILED";
  static final String GAP_TYPE_MISSING_PRICE_TYPE = "MISSING_PRICE_TYPE";
  static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";
  static final String SOURCE_TABLE_MATERIAL_PRICE_TYPE = "lp_material_price_type";
  static final String SOURCE_TABLE_PRICE_RESOLVER = "PriceResolver";
  static final String SOURCE_TABLE_LINKED_ENSURE = "LinkedPriceEnsureService";

  private final MaterialPriceRouterService materialPriceRouterService;
  private final LinkedPriceEnsureService linkedPriceEnsureService;
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;

  public NormalMaterialPricePrepareStrategyImpl(
      MaterialPriceRouterService materialPriceRouterService,
      LinkedPriceEnsureService linkedPriceEnsureService,
      List<PriceResolver> priceResolvers) {
    this.materialPriceRouterService = materialPriceRouterService;
    this.linkedPriceEnsureService = linkedPriceEnsureService;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    if (priceResolvers != null) {
      for (PriceResolver resolver : priceResolvers) {
        if (resolver != null) {
          map.put(resolver.priceType(), resolver);
        }
      }
    }
    this.resolverMap = Collections.unmodifiableMap(map);
  }

  @Override
  public NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      PricePreparePlanItem planItem) {
    return prepare(
        oaNo,
        businessUnitType,
        periodMonth,
        LocalDateTime.now(BUSINESS_ZONE),
        planItem);
  }

  @Override
  public NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePreparePlanItem planItem) {
    return prepare(
        oaNo, businessUnitType, periodMonth, priceAsOfTime, null, planItem);
  }

  @Override
  public NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return execute(
        oaNo,
        businessUnitType,
        periodMonth,
        priceAsOfTime,
        scenarioContext,
        planItem,
        true);
  }

  @Override
  public NormalMaterialPricePrepareResult calculate(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return execute(
        oaNo,
        businessUnitType,
        periodMonth,
        priceAsOfTime,
        scenarioContext,
        planItem,
        false);
  }

  private NormalMaterialPricePrepareResult execute(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem,
      boolean persistLinkedPrice) {
    String materialCode = planItem == null ? null : trimToNull(planItem.getMaterialCode());
    if (materialCode == null) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_FAILED, GAP_TYPE_MISSING_PRICE, PriceResolveResult.SOURCE_ERROR,
          SOURCE_TABLE_PRICE_RESOLVER, "普通料号缺料号，无法取价");
    }
    LocalDateTime resolvedPriceAsOfTime = priceAsOfTime == null
        ? LocalDateTime.now(BUSINESS_ZONE)
        : priceAsOfTime;
    LocalDate quoteDate = resolvedPriceAsOfTime.toLocalDate();
    List<PriceTypeRoute> candidates =
        materialPriceRouterService.listCandidates(materialCode, periodMonth, quoteDate);
    if (candidates == null || candidates.isEmpty()) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_MISSING_PRICE_TYPE,
          GAP_TYPE_MISSING_PRICE_TYPE,
          PriceResolveResult.SOURCE_NO_ROUTE,
          SOURCE_TABLE_MATERIAL_PRICE_TYPE,
          "未配价格类型路由：去价格类型表录入 " + materialCode);
    }

    PriceLinkedCalcItem calculatedLinkedPrice = null;
    if (persistLinkedPrice) {
      NormalMaterialPricePrepareResult ensureFailure =
          ensureLinkedPriceIfNeeded(
              oaNo,
              businessUnitType,
              periodMonth,
              resolvedPriceAsOfTime,
              materialCode,
              candidates,
              scenarioContext);
      if (ensureFailure != null) {
        return ensureFailure;
      }
    } else if (hasLinkedRoute(candidates)) {
      calculatedLinkedPrice = calculateLinkedPrice(
          oaNo,
          businessUnitType,
          periodMonth,
          resolvedPriceAsOfTime,
          materialCode,
          scenarioContext);
    }

    CostRunPartItemDto resolveItem = toResolveItem(oaNo, planItem);
    CostRunContext resolveContext =
        toResolveContext(
            oaNo,
            businessUnitType,
            periodMonth,
            resolvedPriceAsOfTime,
            planItem,
            scenarioContext);
    List<String> attemptedBuckets = new ArrayList<>(candidates.size());
    String lastMissReason = null;
    for (PriceTypeRoute route : candidates) {
      if (route == null || route.priceType() == null) {
        continue;
      }
      PriceResolver resolver = resolverMap.get(route.priceType());
      if (resolver == null) {
        attemptedBuckets.add(route.priceType().name() + "(无 Resolver)");
        continue;
      }
      attemptedBuckets.add(route.priceType().name());
      if (!persistLinkedPrice && route.priceType() == PriceTypeEnum.LINKED) {
        if (calculatedLinkedPrice != null && calculatedLinkedPrice.getPartUnitPrice() != null) {
          BigDecimal unitPrice = calculatedLinkedPrice.getPartUnitPrice();
          BigDecimal amount = quantity(planItem) == null
              ? null
              : unitPrice.multiply(quantity(planItem));
          return NormalMaterialPricePrepareResult.ready(
              unitPrice,
              amount,
              "联动价",
              resultRefType(route.priceType()),
              null,
              "联动价只读计算完成");
        }
        lastMissReason = calculatedLinkedPrice == null
            ? "联动价只读计算未返回结果"
            : calculatedLinkedPrice.getCalcMessage();
        continue;
      }
      PriceResolveResult result = resolver.resolve(oaNo, resolveItem, route, resolveContext);
      if (result != null && result.unitPrice() != null) {
        BigDecimal amount = quantity(planItem) == null ? null : result.unitPrice().multiply(quantity(planItem));
        return NormalMaterialPricePrepareResult.ready(
            result.unitPrice(),
            amount,
            result.priceSource(),
            resultRefType(route.priceType()),
            result.resultRefId(),
            StringUtils.hasText(result.remark()) ? result.remark() : "普通料号价格准备完成");
      }
      if (result != null && StringUtils.hasText(result.remark())) {
        lastMissReason = result.remark();
      }
    }
    String message = "路由=" + attemptedBuckets + " 但桶内无该料号"
        + (lastMissReason == null ? "" : ": " + lastMissReason);
    return NormalMaterialPricePrepareResult.gap(
        STATUS_MISSING_PRICE,
        GAP_TYPE_MISSING_PRICE,
        PriceResolveResult.SOURCE_ERROR,
        SOURCE_TABLE_PRICE_RESOLVER,
        message);
  }

  private NormalMaterialPricePrepareResult ensureLinkedPriceIfNeeded(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String materialCode,
      List<PriceTypeRoute> candidates,
      PricePrepareScenarioContext scenarioContext) {
    boolean hasLinkedRoute = false;
    for (PriceTypeRoute route : candidates) {
      if (route != null && route.priceType() == PriceTypeEnum.LINKED) {
        hasLinkedRoute = true;
        break;
      }
    }
    if (!hasLinkedRoute) {
      return null;
    }
    // 联动价生成是入口级准备动作，不能藏在 LinkedPriceResolver 里；Resolver 只读取已准备结果。
    try {
      LinkedPriceEnsureRequest request =
          LinkedPriceEnsureRequest.quote(
              oaNo, businessUnitType, periodMonth, Set.of(materialCode));
      request.setPriceAsOfTime(priceAsOfTime);
      applyScenario(request, scenarioContext);
      LinkedPriceEnsureResult result =
          linkedPriceEnsureService.ensure(request);
      if (result != null && result.getFailedCount() > 0) {
        return NormalMaterialPricePrepareResult.gap(
            STATUS_MISSING_PRICE,
            GAP_TYPE_MISSING_PRICE,
            PriceResolveResult.SOURCE_ERROR,
            SOURCE_TABLE_LINKED_ENSURE,
            formatEnsureFailures(result));
      }
      return null;
    } catch (RuntimeException ex) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_FAILED,
          GAP_TYPE_MISSING_PRICE,
          PriceResolveResult.SOURCE_ERROR,
          SOURCE_TABLE_LINKED_ENSURE,
          "联动价按需确保失败：" + ex.getMessage());
    }
  }

  private boolean hasLinkedRoute(List<PriceTypeRoute> candidates) {
    if (candidates == null) {
      return false;
    }
    for (PriceTypeRoute route : candidates) {
      if (route != null && route.priceType() == PriceTypeEnum.LINKED) {
        return true;
      }
    }
    return false;
  }

  private PriceLinkedCalcItem calculateLinkedPrice(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String materialCode,
      PricePrepareScenarioContext scenarioContext) {
    try {
      LinkedPriceEnsureRequest request =
          LinkedPriceEnsureRequest.quote(
              oaNo, businessUnitType, periodMonth, Set.of(materialCode));
      request.setPriceAsOfTime(priceAsOfTime);
      applyScenario(request, scenarioContext);
      List<PriceLinkedCalcItem> calculated = linkedPriceEnsureService.calculate(request);
      return calculated == null || calculated.isEmpty() ? null : calculated.get(0);
    } catch (RuntimeException ex) {
      PriceLinkedCalcItem failed = new PriceLinkedCalcItem();
      failed.setItemCode(materialCode);
      failed.setCalcStatus("FAILED");
      failed.setCalcMessage("联动价只读计算失败：" + ex.getMessage());
      return failed;
    }
  }

  private String formatEnsureFailures(LinkedPriceEnsureResult result) {
    if (result.getFailedItems() == null || result.getFailedItems().isEmpty()) {
      return "联动价按需确保失败：存在联动价计算失败";
    }
    List<String> messages = new ArrayList<>();
    for (LinkedPriceEnsureResult.FailedItem failedItem : result.getFailedItems()) {
      if (failedItem == null) {
        continue;
      }
      String code = StringUtils.hasText(failedItem.getItemCode()) ? failedItem.getItemCode().trim() : "-";
      String reason = StringUtils.hasText(failedItem.getReason()) ? failedItem.getReason().trim() : "未知原因";
      messages.add(code + ": " + reason);
    }
    return messages.isEmpty()
        ? "联动价按需确保失败：存在联动价计算失败"
        : "联动价按需确保失败：" + String.join("; ", messages);
  }

  private CostRunPartItemDto toResolveItem(String oaNo, PricePreparePlanItem planItem) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo(oaNo);
    item.setProductCode(planItem.getTopProductCode());
    item.setPartCode(planItem.getMaterialCode());
    item.setPartName(planItem.getMaterialName());
    item.setPartQty(quantity(planItem));
    if (planItem.getBomRow() != null) {
      item.setShapeAttr(planItem.getBomRow().getShapeAttr());
      item.setMaterial(planItem.getBomRow().getMaterialSpec());
    }
    return item;
  }

  private CostRunContext toResolveContext(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePreparePlanItem planItem,
      PricePrepareScenarioContext scenarioContext) {
    CostRunContext context = CostRunContext.quote(
        oaNo,
        null,
        planItem == null ? null : planItem.getTopProductCode(),
        null,
        null,
        businessUnitType,
        periodMonth,
        priceAsOfTime,
        "PRICE_PREPARE:" + (planItem == null ? "" : trimToNull(planItem.getMaterialCode())));
    context.setPriceScenarioType(scenarioType(scenarioContext).name());
    return context;
  }

  private void applyScenario(
      LinkedPriceEnsureRequest request, PricePrepareScenarioContext scenarioContext) {
    request.setPriceScenarioType(scenarioType(scenarioContext));
    request.setVariableOverrides(
        scenarioContext == null ? Map.of() : scenarioContext.variableOverrides());
  }

  private QuotePriceScenarioType scenarioType(PricePrepareScenarioContext scenarioContext) {
    return scenarioContext == null || scenarioContext.scenarioType() == null
        ? QuotePriceScenarioType.OA_LOCKED
        : scenarioContext.scenarioType();
  }

  private BigDecimal quantity(PricePreparePlanItem planItem) {
    return planItem == null || planItem.getBomRow() == null ? null : planItem.getBomRow().getQtyPerTop();
  }

  private String resultRefType(PriceTypeEnum priceType) {
    return switch (priceType) {
      case FIXED -> "FIXED_PRICE";
      case LINKED -> "LINKED_PRICE";
      case RANGE -> "RANGE_PRICE";
      case MAKE -> "MAKE_PART_PRICE";
    };
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
