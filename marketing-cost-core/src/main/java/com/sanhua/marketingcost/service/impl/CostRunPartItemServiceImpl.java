package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.enums.MaterialFormAttrEnum;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
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
  private final PackageComponentIdentifyService packageComponentIdentifyService;
  private final PackageComponentPriceService packageComponentPriceService;
  /** 保留依赖用于历史构造兼容；成本试算取价月份不再读取 OA.apply_date。 */
  private final OaFormMapper oaFormMapper;
  /** T26：聚合视图判定焊料子件用 — 同步主档查 cost_element */
  private final MaterialMasterMapper materialMasterMapper;
  /** T26：聚合视图查包装组件父件用 — raw 主档（虚拟件 9830000026238 不在同步表） */
  private final MaterialMasterRawMapper materialMasterRawMapper;
  /** 桶 → Resolver 的反查表（Spring 注入所有 PriceResolver Bean 后建索引） */
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;

  // ===== T26 聚合算法常量（与 CostRunCostItemServiceImpl 的 BUCKET 算法一致）=====
  /** T26：焊料子件判定 — 主档 cost_element 固定文本 */
  private static final String COST_ELEMENT_WELD = "主要材料-焊料";
  /** T26：包装父件判定 — raw 主档 main_category_name 固定文本 */
  private static final String MAIN_CATEGORY_PACKAGE = "包装组件";
  /** T26：包装算法系数 — 硬编码 1.05（业务来源待确认，TODO #T24.9） */
  private static final BigDecimal PACKAGE_COEFFICIENT = new BigDecimal("1.05");
  private static final String SOURCE_TYPE_U9 = "U9";
  private static final String PRICE_SOURCE_PACKAGE_COMPONENT = "包装组件价格";

  public CostRunPartItemServiceImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      MaterialPriceRouterService materialPriceRouterService,
      PackageComponentIdentifyService packageComponentIdentifyService,
      PackageComponentPriceService packageComponentPriceService,
      OaFormMapper oaFormMapper,
      MaterialMasterMapper materialMasterMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      com.sanhua.marketingcost.mapper.BomRawHierarchyMapper bomRawHierarchyMapper,
      List<PriceResolver> priceResolvers) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.materialPriceRouterService = materialPriceRouterService;
    this.packageComponentIdentifyService = packageComponentIdentifyService;
    this.packageComponentPriceService = packageComponentPriceService;
    this.oaFormMapper = oaFormMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Collections.unmodifiableMap(map);
  }

  @Override
  public List<CostRunPartItemDto> listByOaNo(String oaNo, java.util.function.IntConsumer progress) {
    return listByOaNo(oaNo, resolveQuoteDate(oaNo), progress);
  }

  @Override
  public List<CostRunPartItemDto> listByOaNo(
      String oaNo, LocalDate quoteDate, java.util.function.IntConsumer progress) {
    return listByOaNo(oaNo, quoteDate, null, true, progress);
  }

  @Override
  public List<CostRunPartItemDto> listByOaNo(
      String oaNo,
      LocalDate quoteDate,
      CostRunContext context,
      boolean persistDailyResult,
      java.util.function.IntConsumer progress) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    List<CostRunPartItemDto> items = selectBaseItems(oaNoValue, context);
    if (items.isEmpty()) {
      if (persistDailyResult) {
        saveCostRunItems(oaNoValue, items);
      }
      progress.accept(100);
      return items;
    }

    // 普通 OA 默认按当前月份取价；月度调价由 CostRunEngine 传入 pricing_month 对应的取价日。
    LocalDate priceDate = quoteDate == null ? resolveQuoteDate(oaNoValue) : quoteDate;

    // 走 Router + 4 桶 Resolver 取价（v1.1 起唯一路径）
    // 同时收集胜出 PriceTypeRoute（T06.5：mapper SQL 不再 JOIN 路由表，路由字段在这里回填）
    // T16：resolveAll 内部按 part 索引上报进度（0-95%），剩 5% 给 applyResults+save
    Map<Integer, PriceTypeRoute> winningRoutes = new HashMap<>();
    String organizationCode = resolveMaterialOrganization(oaNoValue);
    Map<Integer, PriceResolveResult> results =
        resolveAll(
            oaNoValue,
            priceDate,
            items,
            context,
            organizationCode,
            winningRoutes,
            p -> progress.accept(p * 95 / 100));
    applyResults(items, results, winningRoutes);
    if (persistDailyResult) {
      saveCostRunItems(oaNoValue, items);
    }
    progress.accept(100);
    return items;
  }

  private List<CostRunPartItemDto> selectBaseItems(String oaNo, CostRunContext context) {
    if (context != null
        && CostRunContext.SCENE_QUOTE.equals(context.getScene())
        && context.getOaFormItemId() != null
        && StringUtils.hasText(context.getProductCode())
        && StringUtils.hasText(context.getPricingMonth())) {
      return costRunPartItemMapper.selectBaseByQuoteScope(
          oaNo,
          context.getOaFormItemId(),
          context.getProductCode().trim(),
          context.getPricingMonth().trim());
    }
    return costRunPartItemMapper.selectBaseByOaNo(oaNo);
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
    return toDtos(stored);
  }

  @Override
  public List<CostRunPartItemDto> listStoredByCostRunNo(String costRunNo) {
    if (!StringUtils.hasText(costRunNo)) {
      return Collections.emptyList();
    }
    List<CostRunPartItem> stored =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class)
                .eq(CostRunPartItem::getCostRunNo, costRunNo.trim())
                .orderByAsc(CostRunPartItem::getId));
    return toDtos(stored);
  }

  private List<CostRunPartItemDto> toDtos(List<CostRunPartItem> stored) {
    if (stored.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunPartItemDto> items = new ArrayList<>();
    for (CostRunPartItem item : stored) {
      CostRunPartItemDto dto = new CostRunPartItemDto();
      dto.setId(item.getId());
      dto.setBomRowId(item.getBomRowId());
      dto.setPricePrepareItemId(item.getPricePrepareItemId());
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
   *   <li>查包装组件父件集合</li>
   *   <li>遍历部品：焊料归 weldSum，包装父件归 packageParentSum，其他原样追加 result</li>
   *   <li>追加 1 行焊料汇总（amount=Σ）+ 1 行包装汇总（amount=Σ包装父件 × 1.05）</li>
   * </ol>
   */
  @Override
  public List<CostRunPartItemDto> listAggregatedByOaNo(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return Collections.emptyList();
    }
    return aggregateStoredRows(oaNo.trim(), productCode.trim(), listStoredByOaNo(oaNo));
  }

  @Override
  public List<CostRunPartItemDto> listAggregatedByCostRunNo(String costRunNo, String productCode) {
    if (!StringUtils.hasText(costRunNo) || !StringUtils.hasText(productCode)) {
      return Collections.emptyList();
    }
    List<CostRunPartItemDto> storedRows = listStoredByCostRunNo(costRunNo);
    String oaNo = storedRows.isEmpty() ? null : storedRows.get(0).getOaNo();
    return aggregateStoredRows(oaNo, productCode.trim(), storedRows);
  }

  private List<CostRunPartItemDto> aggregateStoredRows(
      String oaNo, String productCode, List<CostRunPartItemDto> raw) {
    String productCodeValue = productCode.trim();
    // 1) 拉 raw 部品并按 productCode 过滤
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
    // 3) 查包装组件父件集合
    Set<String> packageParentCodes =
        lookupPackageParentCodes(partCodes, resolveMaterialOrganization(oaNo));

    // 4) 分桶聚合
    List<CostRunPartItemDto> result = new ArrayList<>();
    BigDecimal weldSum = BigDecimal.ZERO;
    BigDecimal packageParentSum = BigDecimal.ZERO;
    List<CostRunPartItemDto> packageRows = new ArrayList<>();
    for (CostRunPartItemDto p : filtered) {
      String code = p.getPartCode() == null ? null : p.getPartCode().trim();
      BigDecimal amt = p.getAmount();
      if (code != null && weldCodes.contains(code)) {
        if (amt != null) {
          weldSum = weldSum.add(amt);
        }
      } else if (code != null && packageParentCodes.contains(code)) {
        if (amt != null) {
          packageParentSum = packageParentSum.add(amt);
        }
        packageRows.add(p);
      } else {
        result.add(p); // 普通部品原样追加
      }
    }
    // 5) 追加聚合行（焊料仍按 Excel 见机表 r44/r45 留空料号；包装需保留父件信息便于追溯）
    if (weldSum.signum() > 0) {
      result.add(buildAggregatedRow(
          oaNo, productCodeValue,
          "焊料",
          weldSum.setScale(6, RoundingMode.HALF_UP),
          "焊料汇总（cost_element=主要材料-焊料 子件 SUM）"));
    }
    if (packageParentSum.signum() > 0) {
      BigDecimal pkgAmount = packageParentSum
          .multiply(PACKAGE_COEFFICIENT)
          .setScale(6, RoundingMode.HALF_UP);
      result.add(buildPackageAggregatedRow(
          oaNo, productCodeValue, packageRows, pkgAmount,
          "包装汇总（包装组件父件金额 × 1.05）"));
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

  /** T26：找当前部品列表中的包装组件父件 material_code。 */
  private Set<String> lookupPackageParentCodes(Set<String> partCodes, String organizationCode) {
    if (partCodes == null || partCodes.isEmpty()) {
      return Collections.emptySet();
    }
    List<MaterialMasterRaw> parents = selectPackageComponentParents(organizationCode);
    if (parents == null || parents.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> parentCodes = new LinkedHashSet<>();
    for (MaterialMasterRaw m : parents) {
      String code = m.getMaterialCode() == null ? null : m.getMaterialCode().trim();
      if (code != null && partCodes.contains(code)) {
        parentCodes.add(code);
      }
    }
    return parentCodes;
  }

  private List<MaterialMasterRaw> selectPackageComponentParents(String organizationCode) {
    String organization = MaterialOrganization.normalize(organizationCode);
    if (MaterialOrganization.COMMERCIAL.getCode().equals(organization)) {
      return materialMasterRawMapper.selectPackageComponentParentsByLatestBatch(MAIN_CATEGORY_PACKAGE, null);
    }
    return materialMasterRawMapper.selectPackageComponentParentsByLatestBatch(
        MAIN_CATEGORY_PACKAGE, null, organization);
  }

  private String resolveMaterialOrganization(String oaNo) {
    String normalizedOaNo = normalizeBlankToNull(oaNo);
    if (normalizedOaNo == null) {
      return MaterialOrganization.COMMERCIAL.getCode();
    }
    return MaterialOrganization.forQuoteProcess(null, normalizedOaNo);
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

  /** T26：包装汇总行保留包装父件料号/图号/单价；金额仍为包装父件金额 × 1.05。 */
  private CostRunPartItemDto buildPackageAggregatedRow(
      String oaNo,
      String productCode,
      List<CostRunPartItemDto> packageRows,
      BigDecimal amount,
      String remark) {
    CostRunPartItemDto dto = buildAggregatedRow(oaNo, productCode, "包装", amount, remark);
    if (packageRows == null || packageRows.isEmpty()) {
      return dto;
    }
    CostRunPartItemDto first = packageRows.get(0);
    dto.setPartCode(first.getPartCode());
    dto.setPartDrawingNo(first.getPartDrawingNo());
    dto.setPartQty(first.getPartQty());
    dto.setShapeAttr(first.getShapeAttr());
    dto.setMaterial(first.getMaterial());
    dto.setPriceType(first.getPriceType());
    dto.setSourceSystem(first.getSourceSystem());
    dto.setCostElement(first.getCostElement());
    dto.setUnitPrice(calculateDisplayUnitPrice(first, amount));
    return dto;
  }

  private BigDecimal calculateDisplayUnitPrice(CostRunPartItemDto row, BigDecimal amount) {
    if (row == null || amount == null) {
      return null;
    }
    BigDecimal qty = row.getPartQty();
    if (qty != null && qty.signum() != 0) {
      return amount.divide(qty, 6, RoundingMode.HALF_UP);
    }
    BigDecimal unitPrice = row.getUnitPrice();
    return unitPrice == null
        ? null
        : unitPrice.multiply(PACKAGE_COEFFICIENT).setScale(6, RoundingMode.HALF_UP);
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
      CostRunContext context,
      String organizationCode,
      Map<Integer, PriceTypeRoute> winningRoutes,
      java.util.function.IntConsumer progress) {
    Map<Integer, PriceResolveResult> results = new HashMap<>();
    // 月度调价必须使用批次 pricing_month；普通报价未传 context 时才从取价日推导月份。
    String period = resolvePricingMonth(context, quoteDate);
    Map<String, Boolean> packageFlags = identifyPackageComponents(items, organizationCode);
    int total = Math.max(1, items.size());
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      String code = item.getPartCode();
      // partCode 缺失：直接标 ERROR，不查 Router 也不抛异常（继续下一行）
      if (!StringUtils.hasText(code)) {
        results.put(i, PriceResolveResult.error("partCode 为空"));
        continue;
      }
      if (Boolean.TRUE.equals(packageFlags.get(code.trim()))) {
        results.put(i, resolvePackageComponentPrice(oaNoValue, quoteDate, period, context, item));
        progress.accept((i + 1) * 100 / total);
        continue;
      }
      // Router 给出全部候选；按 priority 升序逐桶尝试，直到首个 Resolver 成功
      List<PriceTypeRoute> candidates =
          materialPriceRouterService.listCandidates(code, period, quoteDate);
      if (candidates.isEmpty()) {
        if (isManufacturedItem(item)) {
          // 制造件价格源来自“制造件价格生成”结果表；缺价格类型路由不能阻断制造件取价。
          // 成本试算只消费已生成结果，不在这里触发制造件价格生成。
          candidates = List.of(syntheticMakeRoute(code));
        } else {
          // 缺路由：非制造件仍要求业务侧补价格类型表配置
          results.put(i, PriceResolveResult.noRoute(code));
          continue;
        }
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
        PriceResolveResult result = resolver.resolve(oaNoValue, item, route, context);
        if (result == null) {
          result = resolver.resolve(oaNoValue, item, route);
        }
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

  private Map<String, Boolean> identifyPackageComponents(
      List<CostRunPartItemDto> items, String organizationCode) {
    Set<String> codes = new LinkedHashSet<>();
    for (CostRunPartItemDto item : items) {
      if (item != null && StringUtils.hasText(item.getPartCode())) {
        codes.add(item.getPartCode().trim());
      }
    }
    if (codes.isEmpty()) {
      return Collections.emptyMap();
    }
    String organization = MaterialOrganization.normalize(organizationCode);
    Map<String, Boolean> flags =
        MaterialOrganization.COMMERCIAL.getCode().equals(organization)
            ? packageComponentIdentifyService.batchIdentify(codes)
            : packageComponentIdentifyService.batchIdentify(codes, organization);
    return flags == null ? Collections.emptyMap() : flags;
  }

  private PriceResolveResult resolvePackageComponentPrice(
      String oaNoValue,
      LocalDate quoteDate,
      String period,
      CostRunContext context,
      CostRunPartItemDto item) {
    PackagePriceRequest request = new PackagePriceRequest();
    request.setPackageMaterialCode(item.getPartCode().trim());
    request.setPeriodMonth(period);
    request.setOaNo(oaNoValue);
    request.setTopProductCode(normalizeBlankToNull(item.getProductCode()));
    request.setSourceType(SOURCE_TYPE_U9);
    request.setAsOfDate(quoteDate);
    request.setPriceAsOfTime(context == null ? null : context.getPriceAsOfTime());

    PackagePriceResult packageResult = packageComponentPriceService.ensurePrice(request);
    if (packageResult != null
        && packageResult.isComplete()
        && packageResult.getPrice() != null
        && packageResult.getPrice().getTotalPrice() != null) {
      return new PriceResolveResult(
          packageResult.getPrice().getTotalPrice(), PRICE_SOURCE_PACKAGE_COMPONENT, "");
    }
    return PriceResolveResult.error(buildPackageComponentMissingRemark(packageResult));
  }

  private String resolvePricingMonth(CostRunContext context, LocalDate quoteDate) {
    if (context != null && StringUtils.hasText(context.getPricingMonth())) {
      return context.getPricingMonth().trim();
    }
    return inferPeriod(quoteDate);
  }

  private String buildPackageComponentMissingRemark(PackagePriceResult packageResult) {
    if (packageResult == null) {
      return "包装组件价格生成失败：无返回结果，当前阶段不阻断";
    }
    String status = StringUtils.hasText(packageResult.getStatus()) ? packageResult.getStatus() : "UNKNOWN";
    String warningText =
        packageResult.getWarnings() == null || packageResult.getWarnings().isEmpty()
            ? ""
            : "，" + String.join("；", packageResult.getWarnings());
    return "包装组件价格未完整：status=" + status + warningText + "，当前阶段不阻断";
  }

  private static String normalizeBlankToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private boolean isManufacturedItem(CostRunPartItemDto item) {
    return isManufacturedText(item.getShapeAttr()) || isManufacturedText(item.getMaterialShape());
  }

  private boolean isManufacturedText(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    String normalized = value.trim();
    return MaterialFormAttrEnum.fromDbText(normalized)
        .map(MaterialFormAttrEnum.MANUFACTURED::equals)
        .orElse("自制".equals(normalized) || "原材料联动".equals(normalized));
  }

  private PriceTypeRoute syntheticMakeRoute(String code) {
    return new PriceTypeRoute(
        code,
        MaterialFormAttrEnum.MANUFACTURED,
        PriceTypeEnum.MAKE,
        1,
        null,
        null,
        "cost-run-synthetic",
        PriceTypeEnum.MAKE.getDbText());
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
   * 决定成本试算取价日（影响 period + effective 窗口）。
   *
   * <p>当前试算按系统当前月份取价；OA.apply_date 只表示单据申请时间，不作为试算价格月份。
   */
  LocalDate resolveQuoteDate(String oaNo) {
    return LocalDate.now();
  }

  /** 用试算取价日推算 period（yyyy-MM）；未来可扩展按账期查找服务。 */
  private static String inferPeriod(LocalDate date) {
    return date.toString().substring(0, 7);
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
