package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.service.LinkedPriceEnsureService;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartMaterialPriceResolveServiceImpl implements MakePartMaterialPriceResolveService {

  private final MaterialPriceRouterService materialPriceRouterService;
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;
  private final LinkedPriceEnsureService linkedPriceEnsureService;

  public MakePartMaterialPriceResolveServiceImpl(
      MaterialPriceRouterService materialPriceRouterService,
      List<PriceResolver> priceResolvers) {
    this(materialPriceRouterService, priceResolvers, null);
  }

  @Autowired
  public MakePartMaterialPriceResolveServiceImpl(
      MaterialPriceRouterService materialPriceRouterService,
      List<PriceResolver> priceResolvers,
      LinkedPriceEnsureService linkedPriceEnsureService) {
    this.materialPriceRouterService = materialPriceRouterService;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Map.copyOf(map);
    this.linkedPriceEnsureService = linkedPriceEnsureService;
  }

  /**
   * 制造件生成专用取价入口。
   *
   * <p>原材料 child_material_no 和回收废料 scrap_code 都必须先查 lp_material_price_type，
   * 再进入固定价/联动价/区间价 Resolver；废料回收单价不能再读取旧废料回收价表。
   */
  @Override
  public MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      String oaNo,
      String businessUnitType) {
    return resolveMaterialUnitPrice(materialCode, period, quoteDate, null, oaNo, businessUnitType);
  }

  @Override
  public MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType) {
    return resolveMaterialUnitPrice(
        materialCode,
        period,
        quoteDate,
        priceAsOfTime,
        oaNo,
        businessUnitType,
        null);
  }

  @Override
  public MakePartMaterialPriceResolveResult resolveMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext) {
    return execute(
        materialCode,
        period,
        quoteDate,
        priceAsOfTime,
        oaNo,
        businessUnitType,
        scenarioContext,
        true);
  }

  @Override
  public MakePartMaterialPriceResolveResult calculateMaterialUnitPrice(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext) {
    return execute(
        materialCode,
        period,
        quoteDate,
        priceAsOfTime,
        oaNo,
        businessUnitType,
        scenarioContext,
        false);
  }

  private MakePartMaterialPriceResolveResult execute(
      String materialCode,
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext,
      boolean persistLinkedPrice) {
    String code = trimToNull(materialCode);
    if (code == null) {
      return MakePartMaterialPriceResolveResult.miss(
          null, "INVALID_INPUT", "materialCode 为空", null);
    }
    List<PriceTypeRoute> candidates =
        materialPriceRouterService.listCandidates(code, trimToNull(period), quoteDate);
    if (candidates.isEmpty()) {
      return MakePartMaterialPriceResolveResult.missingRoute(
          code, "缺价格类型路由(material_code=" + code + ")");
    }

    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo(oaNo);
    item.setPartCode(code);
    item.setPartQty(BigDecimal.ONE);
    CostRunContext context =
        priceContext(
            period,
            quoteDate,
            priceAsOfTime,
            oaNo,
            businessUnitType,
            scenarioContext);

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
      if (route.priceType() == PriceTypeEnum.LINKED && !linkedPriceEnsured) {
        if (persistLinkedPrice) {
          lastMissReason = ensureLinkedPrice(
              code, period, oaNo, businessUnitType, context, scenarioContext);
        } else {
          PriceLinkedCalcItem calculated = calculateLinkedPrice(
              code, period, oaNo, businessUnitType, context, scenarioContext);
          if (calculated != null && calculated.getPartUnitPrice() != null) {
            return MakePartMaterialPriceResolveResult.ok(
                code,
                priceTypeText,
                calculated.getPartUnitPrice(),
                "联动价只读计算完成",
                String.join(" -> ", traceParts));
          }
          lastMissReason = calculated == null
              ? "联动价只读计算未返回结果(material_code=" + code + ")"
              : calculated.getCalcMessage();
        }
        linkedPriceEnsured = true;
      }
      if (!persistLinkedPrice && route.priceType() == PriceTypeEnum.LINKED) {
        continue;
      }
      PriceResolveResult resolved =
          resolver.resolve(oaNo, item, route, context);
      if (resolved.unitPrice() != null) {
        return MakePartMaterialPriceResolveResult.ok(
            code,
            priceTypeText,
            resolved.unitPrice(),
            resolved.remark(),
            String.join(" -> ", traceParts));
      }
      if (StringUtils.hasText(resolved.remark())) {
        lastMissReason = resolved.remark();
      }
    }

    String trace = String.join(" -> ", traceParts);
    String remark = lastMissReason == null
        ? "有价格类型路由但未取到有效价格(material_code=" + code + ")"
        : lastMissReason;
    String status = trace.contains("自制件")
        ? "UNSUPPORTED_MAKE"
        : "MISSING_PRICE";
    return MakePartMaterialPriceResolveResult.miss(code, status, remark, trace);
  }

  private String ensureLinkedPrice(
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
      request.setPriceAsOfTime(context == null ? null : context.getPriceAsOfTime());
      request.setPriceScenarioType(scenarioType(scenarioContext));
      request.setVariableOverrides(
          scenarioContext == null ? Map.of() : scenarioContext.variableOverrides());
      LinkedPriceEnsureResult result = linkedPriceEnsureService.ensure(request);
      if (result != null && result.getFailedCount() > 0) {
        return "联动价准备失败(material_code=" + materialCode + ")";
      }
      return null;
    } catch (RuntimeException ex) {
      return "联动价准备异常(material_code=" + materialCode + "): "
          + (StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName());
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
      request.setPriceAsOfTime(context == null ? null : context.getPriceAsOfTime());
      request.setPriceScenarioType(scenarioType(scenarioContext));
      request.setVariableOverrides(
          scenarioContext == null ? Map.of() : scenarioContext.variableOverrides());
      List<PriceLinkedCalcItem> calculated = linkedPriceEnsureService.calculate(request);
      return calculated == null || calculated.isEmpty() ? null : calculated.get(0);
    } catch (RuntimeException ex) {
      PriceLinkedCalcItem failed = new PriceLinkedCalcItem();
      failed.setItemCode(materialCode);
      failed.setCalcStatus("FAILED");
      failed.setCalcMessage(
          "联动价只读计算异常(material_code=" + materialCode + "): " + ex.getMessage());
      return failed;
    }
  }

  private String traceRoute(PriceTypeRoute route) {
    return priceTypeText(route)
        + "(priority=" + route.priority()
        + ",source=" + nullToDash(route.sourceSystem()) + ")";
  }

  private CostRunContext priceContext(
      String period,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      String oaNo,
      String businessUnitType,
      PricePrepareScenarioContext scenarioContext) {
    LocalDateTime resolvedPriceAsOfTime = priceAsOfTime == null && quoteDate != null
        ? quoteDate.atStartOfDay()
        : priceAsOfTime;
    CostRunContext context = CostRunContext.quote(
        oaNo,
        null,
        null,
        null,
        null,
        businessUnitType,
        trimToNull(period),
        resolvedPriceAsOfTime,
        null);
    context.setPriceScenarioType(scenarioType(scenarioContext).name());
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
