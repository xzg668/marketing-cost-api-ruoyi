package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;
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
  /** T25：取 OA.apply_date 推 period（解掉历史 LocalDate.now() 跨月失效 bug） */
  private final OaFormMapper oaFormMapper;
  /** T26：聚合视图判定焊料子件用 — 同步主档查 cost_element */
  private final MaterialMasterMapper materialMasterMapper;
  /** T26：聚合视图查包装组件父件用 — raw 主档（虚拟件 9830000026238 不在同步表） */
  private final MaterialMasterRawMapper materialMasterRawMapper;
  /** T26：聚合视图查 BOM 父子关系用（找包装组件父件下挂的子件） */
  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  /** 桶 → Resolver 的反查表（Spring 注入所有 PriceResolver Bean 后建索引） */
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;

  // ===== T26 聚合算法常量（与 CostRunCostItemServiceImpl 的 BUCKET 算法一致）=====
  /** T26：焊料子件判定 — 主档 cost_element 固定文本 */
  private static final String COST_ELEMENT_WELD = "主要材料-焊料";
  /** T26：包装父件判定 — raw 主档 main_category_name 固定文本 */
  private static final String MAIN_CATEGORY_PACKAGE = "包装组件";
  /** T26：包装算法系数 — 硬编码 1.05（业务来源待确认，TODO #T24.9） */
  private static final BigDecimal PACKAGE_COEFFICIENT = new BigDecimal("1.05");
  /** T26：包装数量 — MVP 阶段硬编码 12（OA-001 实测，TODO #T24.9） */
  private static final BigDecimal PACKAGE_COUNT = new BigDecimal("12");

  public CostRunPartItemServiceImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      MaterialPriceRouterService materialPriceRouterService,
      OaFormMapper oaFormMapper,
      MaterialMasterMapper materialMasterMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      BomRawHierarchyMapper bomRawHierarchyMapper,
      List<PriceResolver> priceResolvers) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.materialPriceRouterService = materialPriceRouterService;
    this.oaFormMapper = oaFormMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
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
      saveCostRunItems(oaNoValue, items);
      progress.accept(100);
      return items;
    }

    // T25：用 OA.apply_date 推 period（OA 报价单是历史单，路由匹配应按"申请那时点"，跟今天解耦）
    //   apply_date 为空时 fallback 到 LocalDate.now()，老用例零回归
    LocalDate quoteDate = resolveQuoteDate(oaNoValue);

    // 走 Router + 4 桶 Resolver 取价（v1.1 起唯一路径）
    // 同时收集胜出 PriceTypeRoute（T06.5：mapper SQL 不再 JOIN 路由表，路由字段在这里回填）
    // T16：resolveAll 内部按 part 索引上报进度（0-95%），剩 5% 给 applyResults+save
    Map<Integer, PriceTypeRoute> winningRoutes = new HashMap<>();
    Map<Integer, PriceResolveResult> results =
        resolveAll(oaNoValue, quoteDate, items, winningRoutes, p -> progress.accept(p * 95 / 100));
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

  // ============================ T26 见机表聚合视图 ============================

  /**
   * T26：聚合后的部品列表（焊料/包装合 1 行，其他原样）。
   *
   * <p>实现：
   * <ol>
   *   <li>拉 raw 部品 → filter by productCode</li>
   *   <li>查焊料子件集合（主档 cost_element=主要材料-焊料）</li>
   *   <li>查包装子件集合（BOM 父件 main_category=包装组件 → 取下挂子件）</li>
   *   <li>遍历部品：焊料归 weldSum，包装归 pkgChildSum，其他原样追加 result</li>
   *   <li>追加 1 行焊料汇总（amount=Σ）+ 1 行包装汇总（amount=Σ × 1.05 ÷ 12）</li>
   * </ol>
   */
  @Override
  public List<CostRunPartItemDto> listAggregatedByOaNo(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return Collections.emptyList();
    }
    String productCodeValue = productCode.trim();
    // 1) 拉 raw 部品并按 productCode 过滤
    List<CostRunPartItemDto> raw = listStoredByOaNo(oaNo);
    List<CostRunPartItemDto> filtered = new ArrayList<>();
    Set<String> partCodes = new LinkedHashSet<>();
    for (CostRunPartItemDto p : raw) {
      if (productCodeValue.equals(p.getProductCode())) {
        filtered.add(p);
        if (StringUtils.hasText(p.getPartCode())) {
          partCodes.add(p.getPartCode().trim());
        }
      }
    }
    if (filtered.isEmpty()) {
      return filtered;
    }
    // 2) 查焊料子件集合
    Set<String> weldCodes = lookupCodesByCostElement(partCodes, COST_ELEMENT_WELD);
    // 3) 查包装子件集合
    Set<String> packageChildCodes = lookupPackageChildCodes(productCodeValue);

    // 4) 分桶聚合
    List<CostRunPartItemDto> result = new ArrayList<>();
    BigDecimal weldSum = BigDecimal.ZERO;
    BigDecimal pkgChildSum = BigDecimal.ZERO;
    for (CostRunPartItemDto p : filtered) {
      String code = p.getPartCode() == null ? null : p.getPartCode().trim();
      BigDecimal amt = p.getAmount();
      if (code != null && weldCodes.contains(code)) {
        if (amt != null) {
          weldSum = weldSum.add(amt);
        }
      } else if (code != null && packageChildCodes.contains(code)) {
        if (amt != null) {
          pkgChildSum = pkgChildSum.add(amt);
        }
      } else {
        result.add(p); // 普通部品原样追加
      }
    }
    // 5) 追加聚合行（T26.1：partCode 留空，跟 Excel 见机表 r44/r45 显示一致）
    if (weldSum.signum() > 0) {
      result.add(buildAggregatedRow(
          oaNo, productCodeValue,
          "焊料",
          weldSum.setScale(6, RoundingMode.HALF_UP),
          "焊料汇总（cost_element=主要材料-焊料 子件 SUM）"));
    }
    if (pkgChildSum.signum() > 0) {
      BigDecimal pkgAmount = pkgChildSum
          .multiply(PACKAGE_COEFFICIENT)
          .divide(PACKAGE_COUNT, 6, RoundingMode.HALF_UP);
      result.add(buildAggregatedRow(
          oaNo, productCodeValue,
          "包装",
          pkgAmount,
          "包装汇总（BOM 父件 main_category=包装组件 子件 SUM × 1.05 ÷ 12）"));
    }
    return result;
  }

  /** T26：在给定 partCodes 集合里筛出 cost_element 命中的 material_code 子集 */
  private Set<String> lookupCodesByCostElement(Set<String> partCodes, String costElement) {
    if (partCodes == null || partCodes.isEmpty()) {
      return Collections.emptySet();
    }
    List<MaterialMaster> rows =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .in(MaterialMaster::getMaterialCode, partCodes)
                .eq(MaterialMaster::getCostElement, costElement));
    if (rows == null || rows.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (MaterialMaster m : rows) {
      if (StringUtils.hasText(m.getMaterialCode())) {
        result.add(m.getMaterialCode());
      }
    }
    return result;
  }

  /** T26：找产品 BOM 里 main_category=包装组件 父件下挂的所有子件 material_code */
  private Set<String> lookupPackageChildCodes(String productCode) {
    // 1) raw 主档拿到所有 main_category='包装组件' 的料号集合（虚拟父件候选）
    List<MaterialMasterRaw> parents =
        materialMasterRawMapper.selectList(
            Wrappers.lambdaQuery(MaterialMasterRaw.class)
                .eq(MaterialMasterRaw::getMainCategoryName, MAIN_CATEGORY_PACKAGE));
    if (parents == null || parents.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> parentCodes = new LinkedHashSet<>();
    for (MaterialMasterRaw m : parents) {
      if (StringUtils.hasText(m.getMaterialCode())) {
        parentCodes.add(m.getMaterialCode());
      }
    }
    if (parentCodes.isEmpty()) {
      return Collections.emptySet();
    }
    // 2) BOM 拿 top_product_code=本产品 + parent_code in 父件集合 的子件 material_code
    List<BomRawHierarchy> children =
        bomRawHierarchyMapper.selectList(
            Wrappers.lambdaQuery(BomRawHierarchy.class)
                .eq(BomRawHierarchy::getTopProductCode, productCode)
                .in(BomRawHierarchy::getParentCode, parentCodes));
    if (children == null || children.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (BomRawHierarchy c : children) {
      if (StringUtils.hasText(c.getMaterialCode())) {
        result.add(c.getMaterialCode());
      }
    }
    return result;
  }

  /** T26：构造 1 行聚合行 DTO（partCode 留空，跟 Excel 见机表 r44/r45 显示一致） */
  private CostRunPartItemDto buildAggregatedRow(
      String oaNo, String productCode, String name, BigDecimal amount, String remark) {
    CostRunPartItemDto dto = new CostRunPartItemDto();
    dto.setOaNo(oaNo);
    dto.setProductCode(productCode);
    // partCode 不设（null），前端"部品料号"列显示空白
    dto.setPartName(name);
    dto.setAmount(amount);
    dto.setPriceSource("汇总");
    dto.setRemark(remark);
    return dto;
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
      LocalDate quoteDate,
      List<CostRunPartItemDto> items,
      Map<Integer, PriceTypeRoute> winningRoutes,
      java.util.function.IntConsumer progress) {
    Map<Integer, PriceResolveResult> results = new HashMap<>();
    // T25：quoteDate 由调用方决定（OA.apply_date 优先 / fallback today），不再强行 LocalDate.now()
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

  /**
   * T25：决定取价用的 quoteDate（影响 period + effective 窗口）。
   *
   * <p>OA 报价单本质是"申请那一时刻的快照"，路由表/价格表/财务基价都按月份分账期，
   * 因此应该按 OA.apply_date 而不是"今天"去匹配 — 否则跨月就 NO_ROUTE。
   *
   * <p>fallback 顺序：apply_date → LocalDate.now()（保 OA 没填日期时不抛错）。
   *
   * <p>包私有以便单测覆盖。
   */
  LocalDate resolveQuoteDate(String oaNo) {
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo).last("LIMIT 1"));
    if (form != null && form.getApplyDate() != null) {
      return form.getApplyDate();
    }
    log.warn("T25 resolveQuoteDate: OA {} 缺 apply_date，fallback to today", oaNo);
    return LocalDate.now();
  }

  /** 用试算日推算 period（yyyy-MM）；未来可扩展按账期查找服务。 */
  private static String inferPeriod(LocalDate date) {
    return Optional.ofNullable(date).orElse(LocalDate.now())
        .toString().substring(0, 7);
  }

  private void saveCostRunItems(String oaNo, List<CostRunPartItemDto> items) {
    if (!StringUtils.hasText(oaNo) || items == null) {
      return;
    }
    costRunPartItemMapper.delete(
        Wrappers.lambdaQuery(CostRunPartItem.class).eq(CostRunPartItem::getOaNo, oaNo));
    if (items.isEmpty()) {
      return;
    }
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
