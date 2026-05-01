package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 部品试算服务 —— v1.1 (T04) 起仅走 Router + 4 桶 Resolver。
 *
 * <p>历史背景：原本是 Strangler 双跑（legacy 老 2 桶 / dual / new 4 桶 三种模式），
 * 但老结果都是测试期造的数据，没有真实业务价值，v1.1 起**彻底删除 legacy/dual 路径**，
 * 仅保留 new 路径作为唯一实现。
 *
 * <p>取价流程：
 * <ol>
 *   <li>查 BOM 拍平结算行（按 oa_no）</li>
 *   <li>对每行：MaterialPriceRouterService.listCandidates 给出按 priority 升序的候选路由</li>
 *   <li>逐个候选尝试对应桶的 PriceResolver；首个 unitPrice != null 命中</li>
 *   <li>全部 fallthrough 仍未命中 → priceSource 标 ERROR / NO_ROUTE，remark 写具体原因</li>
 *   <li>写 lp_cost_run_part_item</li>
 * </ol>
 */
@Service
public class CostRunPartItemServiceImpl implements CostRunPartItemService {
  private static final Logger log = LoggerFactory.getLogger(CostRunPartItemServiceImpl.class);

  private final CostRunPartItemMapper costRunPartItemMapper;
  private final MaterialPriceRouterService materialPriceRouterService;
  /** 桶 → Resolver 的反查表（Spring 注入所有 PriceResolver Bean 后建索引） */
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;

  public CostRunPartItemServiceImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      MaterialPriceRouterService materialPriceRouterService,
      List<PriceResolver> priceResolvers) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.materialPriceRouterService = materialPriceRouterService;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Collections.unmodifiableMap(map);
  }

  @Override
  public List<CostRunPartItemDto> listByOaNo(String oaNo, java.util.function.IntConsumer progress) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<CostRunPartItemDto> items = costRunPartItemMapper.selectBaseByOaNo(oaNoValue);
    if (items.isEmpty()) {
      progress.accept(100);
      return items;
    }

    // 走 Router + 4 桶 Resolver 取价（v1.1 起唯一路径）
    // 同时收集胜出 PriceTypeRoute（T06.5：mapper SQL 不再 JOIN 路由表，路由字段在这里回填）
    // T16：resolveAll 内部按 part 索引上报进度（0-95%），剩 5% 给 applyResults+save
    Map<Integer, PriceTypeRoute> winningRoutes = new HashMap<>();
    Map<Integer, PriceResolveResult> results =
        resolveAll(oaNoValue, items, winningRoutes, p -> progress.accept(p * 95 / 100));
    applyResults(items, results, winningRoutes);
    saveCostRunItems(oaNoValue, items);
    progress.accept(100);
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

  // ============================ Router + Resolver 取价 ============================

  /**
   * 用 Router + 4 桶 Resolver 算出每行的取价结果。
   *
   * @param winningRoutes 出参：行索引 → 实际命中的 PriceTypeRoute（HIT 时填，缺路由 / 全 miss
   *                      不填）。供 applyResults 回填 priceType / priority / 生效期等字段，
   *                      替代 mapper SQL 原本的 LEFT JOIN（T06.5 重构）。
   * @return 行索引 → 取价结果。未命中行 result.unitPrice() = null + remark 标具体原因。
   */
  private Map<Integer, PriceResolveResult> resolveAll(
      String oaNoValue,
      List<CostRunPartItemDto> items,
      Map<Integer, PriceTypeRoute> winningRoutes,
      java.util.function.IntConsumer progress) {
    Map<Integer, PriceResolveResult> results = new HashMap<>();
    LocalDate quoteDate = LocalDate.now(); // 试算日，用于 effective 窗口
    String period = inferPeriod(quoteDate);
    int total = Math.max(1, items.size());
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      String code = item.getPartCode();
      // partCode 缺失：直接标 ERROR，不查 Router 也不抛异常（继续下一行）
      if (!StringUtils.hasText(code)) {
        results.put(i, PriceResolveResult.error("partCode 为空"));
        continue;
      }
      // Router 给出全部候选；按 priority 升序逐桶尝试，直到首个 Resolver 成功
      List<PriceTypeRoute> candidates =
          materialPriceRouterService.listCandidates(code, period, quoteDate);
      if (candidates.isEmpty()) {
        // 缺路由：业务侧需补价格类型表配置
        results.put(i, PriceResolveResult.noRoute(code));
        continue;
      }
      // 收集尝试过的桶名 + 最后一次 miss 原因，全 fallthrough 时拼成 ERROR remark
      PriceResolveResult hit = null;
      PriceTypeRoute hitRoute = null;
      List<String> attemptedBuckets = new ArrayList<>(candidates.size());
      String lastMissReason = null;
      for (PriceTypeRoute route : candidates) {
        PriceResolver resolver = resolverMap.get(route.priceType());
        if (resolver == null) {
          // 路由桶 X 没注册 Resolver（理论不该发生，PriceTypeEnum 只有 4 桶）
          attemptedBuckets.add(route.priceType().name() + "(无 Resolver)");
          continue;
        }
        attemptedBuckets.add(route.priceType().name());
        PriceResolveResult result = resolver.resolve(oaNoValue, item, route);
        if (result.unitPrice() != null) {
          hit = result;
          hitRoute = route;
          break;
        }
        if (StringUtils.hasText(result.remark())) {
          lastMissReason = result.remark();
        }
      }
      if (hit != null) {
        results.put(i, hit);
        winningRoutes.put(i, hitRoute);
      } else {
        String summary = "路由=" + attemptedBuckets + " 但桶内无该料号"
            + (lastMissReason == null ? "" : ": " + lastMissReason);
        results.put(i, PriceResolveResult.error(summary));
      }
      // T16：每完成 1 部品上报一次进度
      progress.accept((i + 1) * 100 / total);
    }
    return results;
  }

  /**
   * 把取价结果覆盖回 items（含缺价时的 priceSource + remark）；命中行还回填 6 个路由字段
   * （priceType / materialShape / priority / effectiveFrom / effectiveTo / sourceSystem），
   * 替代 mapper SQL 原本的 LEFT JOIN（T06.5）。
   */
  private void applyResults(
      List<CostRunPartItemDto> items,
      Map<Integer, PriceResolveResult> results,
      Map<Integer, PriceTypeRoute> winningRoutes) {
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
      // 命中时回填路由审计字段（JJB 导出的"价格类型"列依赖 priceType）
      PriceTypeRoute route = winningRoutes.get(i);
      if (route != null) {
        item.setPriceType(route.priceType().getDbText());
        item.setMaterialShape(route.formAttr() == null ? null : route.formAttr().getDbText());
        item.setPriority(route.priority());
        item.setEffectiveFrom(route.effectiveFrom());
        item.setEffectiveTo(route.effectiveTo());
        item.setSourceSystem(route.sourceSystem());
      }
    }
  }

  // ============================ 工具 / 持久化 ============================

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
