package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    String materialCode = planItem == null ? null : trimToNull(planItem.getMaterialCode());
    if (materialCode == null) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_FAILED, GAP_TYPE_MISSING_PRICE, PriceResolveResult.SOURCE_ERROR,
          SOURCE_TABLE_PRICE_RESOLVER, "普通料号缺料号，无法取价");
    }
    LocalDate quoteDate = LocalDate.now();
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

    NormalMaterialPricePrepareResult ensureFailure =
        ensureLinkedPriceIfNeeded(oaNo, businessUnitType, periodMonth, materialCode, candidates);
    if (ensureFailure != null) {
      return ensureFailure;
    }

    CostRunPartItemDto resolveItem = toResolveItem(oaNo, planItem);
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
      PriceResolveResult result = resolver.resolve(oaNo, resolveItem, route);
      if (result != null && result.unitPrice() != null) {
        BigDecimal amount = quantity(planItem) == null ? null : result.unitPrice().multiply(quantity(planItem));
        return NormalMaterialPricePrepareResult.ready(
            result.unitPrice(),
            amount,
            result.priceSource(),
            resultRefType(route.priceType()),
            null,
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
      String materialCode,
      List<PriceTypeRoute> candidates) {
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
      LinkedPriceEnsureResult result =
          linkedPriceEnsureService.ensure(
              LinkedPriceEnsureRequest.quote(oaNo, businessUnitType, periodMonth, Set.of(materialCode)));
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
