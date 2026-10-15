package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
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
  static final String STATUS_MISSING_PRICE = "MISSING_PRICE";
  static final String STATUS_FAILED = "FAILED";
  static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";
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
      String oaNo, String businessUnitType, String periodMonth, PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, LocalDateTime.now(BUSINESS_ZONE), planItem);
  }

  @Override
  public NormalMaterialPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, priceAsOfTime, null, planItem);
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
        oaNo, businessUnitType, periodMonth, priceAsOfTime, scenarioContext, planItem, true);
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
        oaNo, businessUnitType, periodMonth, priceAsOfTime, scenarioContext, planItem, false);
  }

  @Override
  public Map<PricePreparePlanItem, NormalMaterialPricePrepareResult> prepareBatch(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      List<PricePreparePlanItem> items,
      boolean persist) {
    if (items == null || items.isEmpty()) return Map.of();
    scenarioContext =
        scenarioContext == null
            ? PricePrepareScenarioContext.oaLocked(null, organization(items.getFirst()))
            : scenarioContext;
    priceAsOfTime = priceAsOfTime == null ? LocalDateTime.now(BUSINESS_ZONE) : priceAsOfTime;
    LocalDate quoteDate = priceAsOfTime.toLocalDate();
    var routes = new java.util.LinkedHashMap<MaterialScope, List<PriceTypeRoute>>();
    var groups = new java.util.LinkedHashMap<String, Set<String>>();
    for (var item : items) {
      String code = item.getMaterialCode().trim();
      var candidates =
          routes.computeIfAbsent(
              new MaterialScope(code, organization(item)),
              ignored -> persist
                  ? materialPriceRouterService.prepareCandidates(code, item.getMaterialName(), periodMonth,
                      quoteDate, organization(item), businessUnitType)
                  : materialPriceRouterService.listCandidates(code, periodMonth, quoteDate,
                      organization(item), businessUnitType));
      if (firstRouteIsPublicLinked(candidates))
        groups.computeIfAbsent(organization(item), ignored -> new java.util.TreeSet<>()).add(code);
    }
    var linked = new java.util.LinkedHashMap<String, Map<String, PriceLinkedCalcItem>>();
    for (var group : groups.entrySet()) {
      var request =
          LinkedPriceEnsureRequest.quote(oaNo, businessUnitType, periodMonth, group.getValue());
      request.setPriceAsOfTime(priceAsOfTime);
      applyScenario(request, scenarioContext.forOrganization(group.getKey()));
      request.setPriceOrgCode(group.getKey());
      var rows = new java.util.LinkedHashMap<String, PriceLinkedCalcItem>();
      try {
        if (persist) rows.putAll(linkedPriceEnsureService.ensure(request).getItems());
        else
          for (var row : linkedPriceEnsureService.calculate(request))
            rows.put(row.getItemCode(), row);
      } catch (org.springframework.dao.TransientDataAccessException retryable) {
        throw retryable;
      } catch (RuntimeException error) {
        for (String code : group.getValue()) {
          var failed = new PriceLinkedCalcItem();
          failed.setItemCode(code);
          failed.setFailureCode(LinkedPriceEnsureServiceImpl.LINKED_RULE_ERROR);
          failed.setCalcMessage("联动价批量准备失败：" + error.getMessage());
          rows.put(code, failed);
        }
      }
      linked.put(group.getKey(), rows);
    }
    var rangeRequests =
        new java.util.LinkedHashMap<
            PricePreparePlanItem, com.sanhua.marketingcost.service.pricing.PriceResolveRequest>();
    var rangeResolver = resolverMap.get(PriceTypeEnum.RANGE);
    if (rangeResolver != null)
      for (var item : items) {
        var row =
            linked.getOrDefault(organization(item), Map.of()).get(item.getMaterialCode().trim());
        if (row != null && (row.getPartUnitPrice() != null || row.getFailureCode() != null))
          continue;
        for (var route : routes.getOrDefault(new MaterialScope(item.getMaterialCode().trim(), organization(item)), List.of())) {
          if (route.priceType() == PriceTypeEnum.LINKED && !route.supplemental()) continue;
          if (route.priceType() == PriceTypeEnum.RANGE) {
            var context =
                toResolveContext(
                    oaNo,
                    businessUnitType,
                    periodMonth,
                    priceAsOfTime,
                    item,
                    scenarioContext.forOrganization(organization(item)));
            context.setPriceCheckOnly(!persist);
            rangeRequests.put(
                item,
                new com.sanhua.marketingcost.service.pricing.PriceResolveRequest(
                    toResolveItem(oaNo, item), route, context));
          }
          break;
        }
      }
    var rangeResults =
        rangeRequests.isEmpty()
            ? Map
                .<com.sanhua.marketingcost.service.pricing.PriceResolveRequest, PriceResolveResult>
                    of()
            : rangeResolver.resolveBatch(oaNo, List.copyOf(rangeRequests.values()));
    var results =
        new java.util.LinkedHashMap<PricePreparePlanItem, NormalMaterialPricePrepareResult>();
    for (var item : items) {
      var row =
          linked.getOrDefault(organization(item), Map.of()).get(item.getMaterialCode().trim());
      try {
        results.put(
            item,
            execute(
                oaNo,
                businessUnitType,
                periodMonth,
                priceAsOfTime,
                scenarioContext.forOrganization(organization(item)),
                item,
                persist,
                routes.get(new MaterialScope(item.getMaterialCode().trim(), organization(item))),
                row,
                (rangeRequests.containsKey(item)
                    ? rangeResults.get(rangeRequests.get(item))
                    : null),
                true));
      } catch (org.springframework.dao.TransientDataAccessException retryable) {
        throw retryable;
      } catch (RuntimeException error) {
        results.put(
            item,
            NormalMaterialPricePrepareResult.gap(
                STATUS_FAILED,
                GAP_TYPE_MISSING_PRICE,
                PriceResolveResult.SOURCE_ERROR,
                SOURCE_TABLE_PRICE_RESOLVER,
                "普通料号取价失败：" + error.getMessage()));
      }
    }
    return results;
  }

  private record MaterialScope(String materialCode, String priceOrgCode) {}

  private String organization(PricePreparePlanItem item) {
    String org = item.getBomRow() == null ? null : item.getBomRow().getPriceOrgCode();
    return org == null ? "" : org.trim();
  }

  private NormalMaterialPricePrepareResult execute(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem,
      boolean persistLinkedPrice) {
    return execute(
        oaNo,
        businessUnitType,
        periodMonth,
        priceAsOfTime,
        scenarioContext,
        planItem,
        persistLinkedPrice,
        null,
        null,
        null,
        false);
  }

  private NormalMaterialPricePrepareResult execute(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem,
      boolean persistLinkedPrice,
      List<PriceTypeRoute> preparedRoutes,
      PriceLinkedCalcItem preparedLinked,
      PriceResolveResult preparedRange,
      boolean batchPrepared) {
    String materialCode = planItem == null ? null : trimToNull(planItem.getMaterialCode());
    if (materialCode == null) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_FAILED,
          GAP_TYPE_MISSING_PRICE,
          PriceResolveResult.SOURCE_ERROR,
          SOURCE_TABLE_PRICE_RESOLVER,
          "普通料号缺料号，无法取价");
    }
    scenarioContext =
        scenarioContext == null
            ? PricePrepareScenarioContext.oaLocked(null, organization(planItem))
            : scenarioContext.forOrganization(organization(planItem));
    LocalDateTime resolvedPriceAsOfTime =
        priceAsOfTime == null ? LocalDateTime.now(BUSINESS_ZONE) : priceAsOfTime;
    LocalDate quoteDate = resolvedPriceAsOfTime.toLocalDate();
    List<PriceTypeRoute> candidates =
        batchPrepared
            ? preparedRoutes
            : persistLinkedPrice
                ? materialPriceRouterService.prepareCandidates(materialCode, planItem.getMaterialName(),
                    periodMonth, quoteDate, organization(planItem), businessUnitType)
                : materialPriceRouterService.listCandidates(materialCode, periodMonth, quoteDate,
                    organization(planItem), businessUnitType);
    if (candidates == null || candidates.isEmpty()) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_MISSING_PRICE,
          GAP_TYPE_MISSING_PRICE,
          PriceResolveResult.SOURCE_MISSING_PRICE,
          SOURCE_TABLE_PRICE_RESOLVER,
          "该月份和组织没有可用价格来源：" + materialCode);
    }

    PriceLinkedCalcItem calculatedLinkedPrice = preparedLinked;
    NormalMaterialPricePrepareResult ensureFailure = null;
    boolean linkedPrepared = batchPrepared && firstRouteIsPublicLinked(candidates);

    CostRunPartItemDto resolveItem = toResolveItem(oaNo, planItem);
    CostRunContext resolveContext =
        toResolveContext(
            oaNo, businessUnitType, periodMonth, resolvedPriceAsOfTime, planItem, scenarioContext);
    resolveContext.setPriceCheckOnly(!persistLinkedPrice);
    List<String> attemptedBuckets = new ArrayList<>(candidates.size());
    String lastMissReason = null;
    String lastMissCode = null;
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
      // 走到本类型才计算；财务优先的固定价成功时不产生低优先级公式计算及写入。
      if (route.priceType() == PriceTypeEnum.LINKED && !route.supplemental() && !linkedPrepared) {
        if (persistLinkedPrice) {
          ensureFailure = ensureLinkedPrice(oaNo, businessUnitType, periodMonth,
              resolvedPriceAsOfTime, materialCode, scenarioContext);
        } else {
          calculatedLinkedPrice = calculateLinkedPrice(oaNo, businessUnitType, periodMonth,
              resolvedPriceAsOfTime, materialCode, scenarioContext);
        }
        linkedPrepared = true;
      }
      // A missing public formula must not prevent a submitted technical formula from being tried.
      if (ensureFailure != null
          && route.priceType() == PriceTypeEnum.LINKED
          && !route.supplemental()) {
        if (PriceResolveResult.isBlockingFailure(ensureFailure.getReasonCode())) {
          ensureFailure.setPriceType(route.selectedSourceType());
          return ensureFailure;
        }
        continue;
      }
      if (((batchPrepared && firstRouteIsPublicLinked(candidates)) || !persistLinkedPrice)
          && route.priceType() == PriceTypeEnum.LINKED
          && !route.supplemental()) {
        if (calculatedLinkedPrice != null && calculatedLinkedPrice.getPartUnitPrice() != null) {
          BigDecimal unitPrice = calculatedLinkedPrice.getPartUnitPrice();
          BigDecimal amount =
              quantity(planItem) == null ? null : unitPrice.multiply(quantity(planItem));
          PriceResolveResult linkedResult = linkedResult(calculatedLinkedPrice);
          return readyResult(
              linkedResult,
              amount,
              route,
              resultRefType(route.priceType()),
              persistLinkedPrice ? "联动价批量准备完成" : "联动价只读计算完成");
        }
        if (calculatedLinkedPrice != null && PriceResolveResult.isBlockingFailure(calculatedLinkedPrice.getFailureCode())) {
          var gap = NormalMaterialPricePrepareResult.gap(
              STATUS_FAILED,
              GAP_TYPE_MISSING_PRICE,
              calculatedLinkedPrice.getFailureCode(),
              PriceResolveResult.SOURCE_ERROR,
              SOURCE_TABLE_PRICE_RESOLVER,
              calculatedLinkedPrice.getCalcMessage());
          gap.setPriceType(route.selectedSourceType());
          return gap;
        }
        lastMissReason =
            calculatedLinkedPrice == null ? "联动价只读计算未返回结果" : calculatedLinkedPrice.getCalcMessage();
        lastMissCode =
            calculatedLinkedPrice == null ? null : calculatedLinkedPrice.getFailureCode();
        continue;
      }
      PriceResolveResult result =
          route.priceType() == PriceTypeEnum.RANGE && preparedRange != null
              ? preparedRange
              : resolver.resolve(oaNo, resolveItem, route, resolveContext);
      if (result != null && result.unitPrice() != null) {
        BigDecimal amount =
            quantity(planItem) == null ? null : result.unitPrice().multiply(quantity(planItem));
        return readyResult(
            result, amount, route, resultRefType(route.priceType()), "普通料号价格准备完成");
      }
      if (result != null && result.hasBlockingFailure()) {
        var gap = NormalMaterialPricePrepareResult.gap(
            STATUS_FAILED,
            GAP_TYPE_MISSING_PRICE,
            result.failureCode(),
            PriceResolveResult.SOURCE_ERROR,
            SOURCE_TABLE_PRICE_RESOLVER,
            result.remark());
        gap.setPriceType(route.selectedSourceType());
        return gap;
      }
      if (result != null && StringUtils.hasText(result.remark())) {
        lastMissReason = result.remark();
        lastMissCode = result.failureCode();
      }
    }
    if (ensureFailure != null) {
      ensureFailure.setPriceType(PriceTypeEnum.LINKED.name());
      return ensureFailure;
    }
    String message =
        "路由="
            + attemptedBuckets
            + " 但桶内无该料号"
            + (lastMissReason == null ? "" : ": " + lastMissReason);
    var gap = NormalMaterialPricePrepareResult.gap(
        STATUS_MISSING_PRICE,
        GAP_TYPE_MISSING_PRICE,
        lastMissCode,
        PriceResolveResult.SOURCE_ERROR,
        SOURCE_TABLE_PRICE_RESOLVER,
        message);
    if (candidates.size() == 1) gap.setPriceType(candidates.getFirst().selectedSourceType());
    return gap;
  }

  private NormalMaterialPricePrepareResult readyResult(
      PriceResolveResult resolved,
      BigDecimal amount,
      PriceTypeRoute route,
      String resultRefType,
      String defaultMessage) {
    String message = StringUtils.hasText(resolved.remark()) ? resolved.remark() : defaultMessage;
    NormalMaterialPricePrepareResult result =
        NormalMaterialPricePrepareResult.ready(
            resolved.unitPrice(),
            amount,
            resolved.priceSource(),
            resultRefType,
            resolved.resultRefId(),
            message);
    PriceResolveEvidence evidence = resolved.evidence().withPriority(route);
    result.setPriceType(route.selectedSourceType());
    result.setSourcePriceRecordId(evidence.sourcePriceRecordId());
    result.setSourcePriceBatchNo(evidence.sourceBatchNo());
    result.setInputSnapshotJson(evidence.inputSnapshotJson());
    result.setSupplierName(evidence.supplierName());
    result.setSupplierCode(evidence.supplierCode());
    result.setSupplyRatio(evidence.supplyRatio());
    result.setSupplyRatioRecordId(evidence.supplyRatioRecordId());
    result.setSourceEffectiveFrom(evidence.effectiveFrom());
    result.setSourceEffectiveTo(evidence.effectiveTo());
    result.setCarriedForward(evidence.carriedForward());
    result.setWarningMessage(evidence.warningMessage());
    return result;
  }

  private PriceResolveResult linkedResult(PriceLinkedCalcItem item) {
    PriceResolveEvidence evidence =
        new PriceResolveEvidence(
            item.getSourcePriceRecordId(),
            item.getSourcePriceBatchNo(),
            item.getSupplierName(),
            item.getSupplierCode(),
            item.getSupplyRatio(),
            item.getSupplyRatioRecordId(),
            item.getSourceEffectiveFrom(),
            item.getSourceEffectiveTo(),
            Integer.valueOf(1).equals(item.getCarriedForward()),
            item.getWarningMessage(),
            item.getInputSnapshotJson());
    String message =
        StringUtils.hasText(item.getWarningMessage())
            ? "联动价只读计算完成；" + item.getWarningMessage()
            : "联动价只读计算完成";
    return PriceResolveResult.hit(item.getPartUnitPrice(), "联动价", message, item.getId(), evidence);
  }

  private NormalMaterialPricePrepareResult ensureLinkedPrice(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String materialCode,
      PricePrepareScenarioContext scenarioContext) {
    // 联动价生成是入口级准备动作，不能藏在 LinkedPriceResolver 里；Resolver 只读取已准备结果。
    try {
      LinkedPriceEnsureRequest request =
          LinkedPriceEnsureRequest.quote(oaNo, businessUnitType, periodMonth, Set.of(materialCode));
      request.setPriceAsOfTime(priceAsOfTime);
      applyScenario(request, scenarioContext);
      LinkedPriceEnsureResult result = linkedPriceEnsureService.ensure(request);
      if (result != null && result.getFailedCount() > 0) {
        return NormalMaterialPricePrepareResult.gap(
            STATUS_MISSING_PRICE,
            GAP_TYPE_MISSING_PRICE,
            firstFailureCode(result),
            PriceResolveResult.SOURCE_ERROR,
            SOURCE_TABLE_LINKED_ENSURE,
            formatEnsureFailures(result));
      }
      return null;
    } catch (org.springframework.dao.TransientDataAccessException retryable) {
      throw retryable;
    } catch (RuntimeException ex) {
      return NormalMaterialPricePrepareResult.gap(
          STATUS_FAILED,
          GAP_TYPE_MISSING_PRICE,
          LinkedPriceEnsureServiceImpl.LINKED_RULE_ERROR,
          PriceResolveResult.SOURCE_ERROR,
          SOURCE_TABLE_LINKED_ENSURE,
          "联动价按需确保失败：" + ex.getMessage());
    }
  }

  private boolean firstRouteIsPublicLinked(List<PriceTypeRoute> candidates) {
    if (candidates == null || candidates.isEmpty()) return false;
    var first = candidates.getFirst();
    return first.priceType() == PriceTypeEnum.LINKED && !first.supplemental();
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
          LinkedPriceEnsureRequest.quote(oaNo, businessUnitType, periodMonth, Set.of(materialCode));
      request.setPriceAsOfTime(priceAsOfTime);
      applyScenario(request, scenarioContext);
      List<PriceLinkedCalcItem> calculated = linkedPriceEnsureService.calculate(request);
      return calculated == null || calculated.isEmpty() ? null : calculated.get(0);
    } catch (org.springframework.dao.TransientDataAccessException retryable) {
      throw retryable;
    } catch (RuntimeException ex) {
      PriceLinkedCalcItem failed = new PriceLinkedCalcItem();
      failed.setItemCode(materialCode);
      failed.setCalcStatus("FAILED");
      failed.setFailureCode(LinkedPriceEnsureServiceImpl.LINKED_RULE_ERROR);
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
      String code =
          StringUtils.hasText(failedItem.getItemCode()) ? failedItem.getItemCode().trim() : "-";
      String reason =
          StringUtils.hasText(failedItem.getReason()) ? failedItem.getReason().trim() : "未知原因";
      messages.add(code + ": " + reason);
    }
    return messages.isEmpty() ? "联动价按需确保失败：存在联动价计算失败" : "联动价按需确保失败：" + String.join("; ", messages);
  }

  private String firstFailureCode(LinkedPriceEnsureResult result) {
    if (result == null || result.getFailedItems() == null) {
      return null;
    }
    return result.getFailedItems().stream()
        .map(LinkedPriceEnsureResult.FailedItem::getReasonCode)
        .filter(StringUtils::hasText)
        .findFirst()
        .orElse(null);
  }

  private CostRunPartItemDto toResolveItem(String oaNo, PricePreparePlanItem planItem) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo(oaNo);
    item.setProductCode(planItem.getTopProductCode());
    item.setPartCode(planItem.getMaterialCode());
    item.setPartName(planItem.getMaterialName());
    item.setPartQty(quantity(planItem));
    if (planItem.getBomRow() != null) {
      item.setPriceOrgCode(trimToNull(planItem.getBomRow().getPriceOrgCode()));
      item.setMaterialOrganizationCode(
          trimToNull(planItem.getBomRow().getMaterialOrganizationCode()));
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
    CostRunContext context =
        CostRunContext.quote(
            oaNo,
            null,
            planItem == null ? null : planItem.getTopProductCode(),
            null,
            null,
            businessUnitType,
            periodMonth,
            priceAsOfTime,
            "PRICE_PREPARE:" + (planItem == null ? "" : trimToNull(planItem.getMaterialCode())));
    if (planItem != null && planItem.getBomRow() != null) {
      context.setPriceOrgCode(trimToNull(planItem.getBomRow().getPriceOrgCode()));
      context.setMaterialOrganizationCode(
          trimToNull(planItem.getBomRow().getMaterialOrganizationCode()));
    }
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

  private void applyScenario(
      LinkedPriceEnsureRequest request, PricePrepareScenarioContext scenarioContext) {
    request.setSupplierPriceReviewContext(scenarioContext == null ? null : scenarioContext.supplierReviews());
    request.setPriceOrgCode(
        scenarioContext == null ? null : scenarioContext.supplierReviews().priceOrgCode());
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
    return planItem == null || planItem.getBomRow() == null
        ? null
        : planItem.getBomRow().getQtyPerTop();
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
