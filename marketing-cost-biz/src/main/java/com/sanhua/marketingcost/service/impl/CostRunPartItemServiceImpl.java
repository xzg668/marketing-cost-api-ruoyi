package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 部品试算服务 —— Strangler 双跑实现。
 *
 * <p>三种模式（cost.router.mode）：
 * <ul>
 *   <li>legacy：仅老 2 桶（FIXED/LINKED），等价于改造前行为，回滚保险</li>
 *   <li>dual：  两套并行算 → diff > warnThreshold 写 WARN + 写入 legacy 结果（默认）</li>
 *   <li>new：   仅新 6 桶 Router 路径；金标全绿后切换</li>
 * </ul>
 *
 * <p>新路径总览：MaterialPriceRouterService 给出 (formAttr, priceType) 路由 → resolverMap 按桶分发
 * → 6 个 Resolver 中的对应实现给出 unitPrice + priceSource。Resolver 拿不到值时按 Router 候选
 * 列表 priority 升序回退；全部 fallthrough 仍未命中则标红。
 */
@Service
public class CostRunPartItemServiceImpl implements CostRunPartItemService {
  private static final Logger log = LoggerFactory.getLogger(CostRunPartItemServiceImpl.class);

  /** 老 2 桶常量（legacy 路径与 dual 模式 baseline 共用） */
  private static final String FIXED_PRICE_TYPE = "固定价";

  private static final String LINKED_PRICE_TYPE = "联动价";

  private final CostRunPartItemMapper costRunPartItemMapper;
  private final PriceFixedItemMapper priceFixedItemMapper;
  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final MaterialPriceRouterService materialPriceRouterService;
  /** 桶 → Resolver 的反查表（Spring 注入所有 PriceResolver Bean 后建索引） */
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;

  /** Strangler 模式：legacy / dual / new */
  private final String routerMode;

  /** 双跑 diff 告警阈值（元/件） */
  private final BigDecimal dualWarnThreshold;

  public CostRunPartItemServiceImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      PriceFixedItemMapper priceFixedItemMapper,
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      MaterialPriceRouterService materialPriceRouterService,
      List<PriceResolver> priceResolvers,
      @Value("${cost.router.mode:dual}") String routerMode,
      @Value("${cost.router.dual.warnThreshold:0.01}") BigDecimal dualWarnThreshold) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.priceFixedItemMapper = priceFixedItemMapper;
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.materialPriceRouterService = materialPriceRouterService;
    this.routerMode = normalizeMode(routerMode);
    this.dualWarnThreshold = dualWarnThreshold == null ? new BigDecimal("0.01") : dualWarnThreshold;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Collections.unmodifiableMap(map);
  }

  @Override
  public List<CostRunPartItemDto> listByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<CostRunPartItemDto> items = costRunPartItemMapper.selectBaseByOaNo(oaNoValue);
    if (items.isEmpty()) {
      return items;
    }

    // 始终先算 legacy（dual 模式 baseline 也用它写库），new 模式再覆盖
    legacyResolve(oaNoValue, items);
    if ("dual".equals(routerMode) || "new".equals(routerMode)) {
      // 拷贝出新算的结果，dual 模式不替换字段，仅 diff 比对；new 模式才覆盖到 items
      Map<Integer, PriceResolveResult> newResults = newResolveAll(oaNoValue, items);
      if ("dual".equals(routerMode)) {
        logDualDiff(oaNoValue, items, newResults);
      } else {
        applyNewResults(items, newResults);
      }
    }

    saveCostRunItems(oaNoValue, items);
    return items;
  }

  @Override
  public List<CostRunPartItemDto> listStoredByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<CostRunPartItem> stored =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class).eq(CostRunPartItem::getOaNo, oaNoValue));
    if (stored.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunPartItemDto> items = new ArrayList<>();
    for (CostRunPartItem item : stored) {
      CostRunPartItemDto dto = new CostRunPartItemDto();
      dto.setOaNo(item.getOaNo());
      dto.setProductCode(item.getProductCode());
      dto.setPartCode(item.getPartCode());
      dto.setPartName(item.getPartName());
      dto.setPartDrawingNo(item.getPartDrawingNo());
      dto.setPartQty(item.getQty());
      dto.setMaterial(item.getMaterial());
      dto.setShapeAttr(item.getShapeAttr());
      dto.setPriceSource(item.getPriceSource());
      dto.setUnitPrice(item.getUnitPrice());
      dto.setAmount(item.getAmount());
      dto.setRemark(item.getRemark());
      items.add(dto);
    }
    return items;
  }

  // ============================ Legacy 路径（FIXED + LINKED） ============================

  /** 旧 2 桶取价 —— 直接在 items 上写入 unitPrice / amount / priceSource。 */
  private void legacyResolve(String oaNoValue, List<CostRunPartItemDto> items) {
    Set<String> fixedCodes = new LinkedHashSet<>();
    Set<String> linkedCodes = new LinkedHashSet<>();
    for (CostRunPartItemDto item : items) {
      String code = item.getPartCode();
      if (!StringUtils.hasText(code)) {
        continue;
      }
      String priceType = StringUtils.hasText(item.getPriceType())
          ? item.getPriceType().trim()
          : "";
      if (FIXED_PRICE_TYPE.equals(priceType)) {
        fixedCodes.add(code);
      } else if (LINKED_PRICE_TYPE.equals(priceType)) {
        linkedCodes.add(code);
      }
    }

    Map<String, BigDecimal> fixedPriceMap = new HashMap<>();
    if (!fixedCodes.isEmpty()) {
      List<PriceFixedItem> fixedItems =
          priceFixedItemMapper.selectList(
              Wrappers.lambdaQuery(PriceFixedItem.class)
                  .in(PriceFixedItem::getMaterialCode, fixedCodes)
                  .orderByDesc(PriceFixedItem::getEffectiveFrom)
                  .orderByDesc(PriceFixedItem::getId));
      for (PriceFixedItem item : fixedItems) {
        String code = item.getMaterialCode();
        if (!fixedPriceMap.containsKey(code)) {
          fixedPriceMap.put(code, item.getFixedPrice());
        }
      }
    }

    Map<String, BigDecimal> linkedPriceMap = new HashMap<>();
    if (!linkedCodes.isEmpty()) {
      List<PriceLinkedCalcItem> linkedItems =
          priceLinkedCalcItemMapper.selectList(
              Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
                  .eq(PriceLinkedCalcItem::getOaNo, oaNoValue)
                  .in(PriceLinkedCalcItem::getItemCode, linkedCodes)
                  .orderByDesc(PriceLinkedCalcItem::getId));
      for (PriceLinkedCalcItem item : linkedItems) {
        String code = item.getItemCode();
        if (!linkedPriceMap.containsKey(code)) {
          linkedPriceMap.put(code, item.getPartUnitPrice());
        }
      }
    }

    for (CostRunPartItemDto item : items) {
      item.setPriceSource("");
      item.setRemark("");
      String code = item.getPartCode();
      String priceType = StringUtils.hasText(item.getPriceType())
          ? item.getPriceType().trim()
          : "";
      BigDecimal unitPrice = null;
      if (StringUtils.hasText(code)) {
        if (FIXED_PRICE_TYPE.equals(priceType)) {
          unitPrice = fixedPriceMap.get(code);
        } else if (LINKED_PRICE_TYPE.equals(priceType)) {
          unitPrice = linkedPriceMap.get(code);
        }
      }
      item.setUnitPrice(unitPrice);
      if (unitPrice != null && item.getPartQty() != null) {
        item.setAmount(unitPrice.multiply(item.getPartQty()));
      }
    }
  }

  // ============================ New 路径（6 桶 Router） ============================

  /**
   * 用 Router + 6 桶 Resolver 算出每行的新结果。
   *
   * <p>不直接写到 items，让 dual 模式下保留 legacy 结果做 baseline 比对。
   *
   * @return 行索引 → 新算结果。未命中行 result.unitPrice() = null + remark 标红。
   */
  private Map<Integer, PriceResolveResult> newResolveAll(
      String oaNoValue, List<CostRunPartItemDto> items) {
    Map<Integer, PriceResolveResult> results = new HashMap<>();
    LocalDate quoteDate = LocalDate.now(); // 试算日，用于 effective 窗口
    String period = inferPeriod(quoteDate);
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      String code = item.getPartCode();
      if (!StringUtils.hasText(code)) {
        results.put(i, PriceResolveResult.miss("partCode 为空"));
        continue;
      }
      // Router 给出全部候选；按 priority 升序逐桶尝试，直到首个 Resolver 成功
      List<PriceTypeRoute> candidates =
          materialPriceRouterService.listCandidates(code, period, quoteDate);
      if (candidates.isEmpty()) {
        results.put(i, PriceResolveResult.miss("Router 无候选: " + code));
        continue;
      }
      PriceResolveResult finalResult = null;
      for (PriceTypeRoute route : candidates) {
        PriceResolver resolver = resolverMap.get(route.priceType());
        if (resolver == null) {
          continue;
        }
        PriceResolveResult result = resolver.resolve(oaNoValue, item, route);
        if (result.unitPrice() != null) {
          finalResult = result;
          break;
        }
        // 记下最后一次 miss 原因，便于全 fallthrough 时输出
        finalResult = result;
      }
      results.put(i, finalResult == null ? PriceResolveResult.miss("无可用 Resolver") : finalResult);
    }
    return results;
  }

  /** new 模式：把新结果覆盖回 items（dual 模式不调用此方法）。 */
  private void applyNewResults(
      List<CostRunPartItemDto> items, Map<Integer, PriceResolveResult> results) {
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      PriceResolveResult result = results.get(i);
      if (result == null) {
        continue;
      }
      item.setUnitPrice(result.unitPrice());
      item.setPriceSource(result.priceSource());
      item.setRemark(result.remark());
      if (result.unitPrice() != null && item.getPartQty() != null) {
        item.setAmount(result.unitPrice().multiply(item.getPartQty()));
      } else {
        item.setAmount(null);
      }
    }
  }

  /** dual 模式 diff：两边都有值且差异 > 阈值 → WARN；只一边有值 → INFO 提示。 */
  private void logDualDiff(
      String oaNoValue, List<CostRunPartItemDto> items, Map<Integer, PriceResolveResult> newResults) {
    int totalDiff = 0;
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      PriceResolveResult newResult = newResults.get(i);
      if (newResult == null) {
        continue;
      }
      BigDecimal legacyPrice = item.getUnitPrice();
      BigDecimal newPrice = newResult.unitPrice();
      if (legacyPrice == null && newPrice == null) {
        continue;
      }
      if (legacyPrice == null || newPrice == null) {
        log.info(
            "[router-dual] oa={} part={} 单边命中: legacy={} new={} ({})",
            oaNoValue, item.getPartCode(), legacyPrice, newPrice, newResult.remark());
        continue;
      }
      BigDecimal diff = legacyPrice.subtract(newPrice).abs();
      if (diff.compareTo(dualWarnThreshold) > 0) {
        totalDiff++;
        log.warn(
            "[router-dual] oa={} part={} 价差 {} > 阈值 {}: legacy={} new={}",
            oaNoValue, item.getPartCode(), diff, dualWarnThreshold, legacyPrice, newPrice);
      }
    }
    if (totalDiff > 0) {
      log.warn("[router-dual] oa={} 双跑发现 {} 行价差超阈值，请人工核对", oaNoValue, totalDiff);
    }
  }

  // ============================ 工具 / 持久化 ============================

  private static String normalizeMode(String mode) {
    if (mode == null) {
      return "dual";
    }
    String trimmed = mode.trim().toLowerCase();
    return switch (trimmed) {
      case "legacy", "dual", "new" -> trimmed;
      default -> "dual";
    };
  }

  /** 用试算日推算 period（yyyy-MM）；未来可扩展按账期查找服务。 */
  private static String inferPeriod(LocalDate date) {
    return Optional.ofNullable(date).orElse(LocalDate.now())
        .toString().substring(0, 7);
  }

  private void saveCostRunItems(String oaNo, List<CostRunPartItemDto> items) {
    if (!StringUtils.hasText(oaNo) || items == null || items.isEmpty()) {
      return;
    }
    costRunPartItemMapper.delete(
        Wrappers.lambdaQuery(CostRunPartItem.class).eq(CostRunPartItem::getOaNo, oaNo));
    List<CostRunPartItem> entities = new ArrayList<>(items.size());
    for (CostRunPartItemDto item : items) {
      CostRunPartItem entity = new CostRunPartItem();
      entity.setOaNo(oaNo);
      entity.setProductCode(item.getProductCode());
      entity.setPartCode(item.getPartCode());
      entity.setPartName(item.getPartName());
      entity.setPartDrawingNo(item.getPartDrawingNo());
      entity.setQty(item.getPartQty());
      entity.setMaterial(item.getMaterial());
      entity.setShapeAttr(item.getShapeAttr());
      entity.setPriceSource(item.getPriceSource());
      entity.setUnitPrice(item.getUnitPrice());
      entity.setAmount(item.getAmount());
      entity.setRemark(item.getRemark());
      entities.add(entity);
    }
    batchInsert(entities);
  }

  private void batchInsert(List<CostRunPartItem> entities) {
    if (entities.isEmpty()) {
      return;
    }
    for (CostRunPartItem entity : entities) {
      costRunPartItemMapper.insert(entity);
    }
  }
}
