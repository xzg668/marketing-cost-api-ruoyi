package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.RollupPartComponentDto;
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
import com.sanhua.marketingcost.service.CostBusinessRuleProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
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
 *   <li>对每行：MaterialPriceRouterService.listCandidates 给出当前最新价格类型（最多一条）</li>
 *   <li>调用该类型对应的 PriceResolver；价格源内部负责主供应商和历史价沿用</li>
 *   <li>没有类型或当前类型没有可用价格 → priceSource 标 ERROR / NO_ROUTE，remark 写具体原因</li>
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
  private static final BigDecimal DEFAULT_PACKAGE_COEFFICIENT = new BigDecimal("1.05");
  private static final int DISPLAY_AMOUNT_SCALE = 6;
  private static final int DISPLAY_UNIT_PRICE_SCALE = 8;
  private static final String SOURCE_TYPE_U9 = "U9";
  private static final String PRICE_SOURCE_PACKAGE_COMPONENT = "包装组件价格";
  private final CostBusinessRuleProvider businessRuleProvider;

  @Autowired
  public CostRunPartItemServiceImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      MaterialPriceRouterService materialPriceRouterService,
      PackageComponentIdentifyService packageComponentIdentifyService,
      PackageComponentPriceService packageComponentPriceService,
      OaFormMapper oaFormMapper,
      MaterialMasterMapper materialMasterMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      com.sanhua.marketingcost.mapper.BomRawHierarchyMapper bomRawHierarchyMapper,
      List<PriceResolver> priceResolvers,
      CostBusinessRuleProvider businessRuleProvider) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.materialPriceRouterService = materialPriceRouterService;
    this.packageComponentIdentifyService = packageComponentIdentifyService;
    this.packageComponentPriceService = packageComponentPriceService;
    this.oaFormMapper = oaFormMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.businessRuleProvider = businessRuleProvider;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Collections.unmodifiableMap(map);
  }

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
    this(
        costRunPartItemMapper,
        materialPriceRouterService,
        packageComponentIdentifyService,
        packageComponentPriceService,
        oaFormMapper,
        materialMasterMapper,
        materialMasterRawMapper,
        bomRawHierarchyMapper,
        priceResolvers,
        (ruleCode, pricingMonth, businessUnitType, fallbackValue) -> fallbackValue);
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
    Map<Integer, PriceResolveResult> results =
        resolveAll(
            oaNoValue,
            priceDate,
            items,
            context,
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
      QuoteDataOrganization storedOrganization = storedReadOrganization(item);
      dto.setPriceOrgCode(
          storedOrganization == null ? item.getPriceOrgCode() : storedOrganization.priceOrgCode());
      dto.setMaterialOrganizationCode(
          storedOrganization == null
              ? item.getMaterialOrganizationCode()
              : storedOrganization.materialOrganizationCode());
      items.add(dto);
    }
    return items;
  }

  /**
   * V182/V183 以前的历史成本行没有保存 U9 组织。历史结果只读展示时，可用已落库的
   * OA 流程号和业务隔离字段恢复唯一可判定的 COMMERCIAL/PLATE 组织；不回写历史表，
   * 也不放宽新核算链路“组织必须由上游显式传入”的校验。
   */
  private QuoteDataOrganization storedReadOrganization(CostRunPartItem item) {
    String priceOrgCode = normalizeBlankToNull(item.getPriceOrgCode());
    String materialOrganizationCode = normalizeBlankToNull(item.getMaterialOrganizationCode());
    if (priceOrgCode != null || materialOrganizationCode != null) {
      return normalizeOrganization(
          priceOrgCode, materialOrganizationCode, "历史成本结果组织不完整");
    }
    String persistedScope = normalizeBlankToNull(item.getBusinessUnitType());
    String materialScope =
        "COMMERCIAL".equalsIgnoreCase(persistedScope)
                || "PLATE".equalsIgnoreCase(persistedScope)
            ? persistedScope
            : null;
    try {
      return MaterialOrganization.quoteDataForQuoteProcess(
          null, item.getOaNo(), materialScope);
    } catch (IllegalArgumentException exception) {
      return null;
    }
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
    // 普通部品的料号来自结算行，名称/图号等展示字段从该组织的当前料品档案补齐。
    // 上卷父件随后拆行时仍保留这里补齐的父件料号和父件图号。
    enrichPartFieldsFromMaterialArchive(filtered);
    filtered = expandRollupDisplayRows(filtered);
    partCodes.clear();
    for (CostRunPartItemDto p : filtered) {
      if (StringUtils.hasText(p.getPartCode())) {
        partCodes.add(p.getPartCode().trim());
      }
    }
    // 2) 查焊料子件集合
    Set<String> weldCodes = lookupCodesByCostElement(partCodes, COST_ELEMENT_WELD);
    // 3) 查包装组件父件集合
    Set<String> packageParentKeys = lookupPackageParentKeys(filtered);

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
      } else if (code != null && packageParentKeys.contains(packageFlagKey(
          requiredItemOrganization(null, p, "包装组件聚合").materialOrganizationCode(), code))) {
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
      BigDecimal packageCoefficient = packageCoefficient(oaNo);
      BigDecimal pkgAmount = packageParentSum
          .multiply(packageCoefficient)
          .setScale(6, RoundingMode.HALF_UP);
      result.add(buildPackageAggregatedRow(
          oaNo, productCodeValue, packageRows, pkgAmount,
          "包装汇总（包装组件父件金额 × "
              + packageCoefficient.stripTrailingZeros().toPlainString() + "）",
          packageCoefficient));
    }
    return result;
  }

  /**
   * 将上卷父件按命中子件拆成多条展示行。
   *
   * <p>底层名称、料号、图号仍保留母件原值，新增 display 字段按“母件换行【子件】”输出；
   * 数量展示命中子件累计到顶层产品的用量；金额取本次核算实际制造件价格批次中的子件成本贡献。
   * 最后一行吸收六位小数舍入差，保证拆分金额合计严格等于原父件金额。
   */
  private List<CostRunPartItemDto> expandRollupDisplayRows(List<CostRunPartItemDto> rows) {
    List<Long> partItemIds = rows.stream()
        .map(CostRunPartItemDto::getId)
        .filter(java.util.Objects::nonNull)
        .toList();
    if (partItemIds.isEmpty()) {
      return rows;
    }
    List<RollupPartComponentDto> queried =
        costRunPartItemMapper.selectRollupDisplayComponents(partItemIds);
    if (queried == null || queried.isEmpty()) {
      return rows;
    }

    Map<Long, LinkedHashMap<String, RollupComponentTotal>> componentsByPartItem =
        new LinkedHashMap<>();
    for (RollupPartComponentDto component : queried) {
      if (component == null
          || component.getPartItemId() == null
          || !StringUtils.hasText(component.getChildMaterialCode())) {
        continue;
      }
      String childCode = component.getChildMaterialCode().trim();
      RollupComponentTotal total =
          componentsByPartItem
              .computeIfAbsent(component.getPartItemId(), ignored -> new LinkedHashMap<>())
              .computeIfAbsent(childCode, ignored -> new RollupComponentTotal(childCode));
      total.accept(component);
    }
    if (componentsByPartItem.isEmpty()) {
      return rows;
    }

    Map<String, MaterialMasterRaw> childArchiveByKey =
        loadChildMaterialArchive(rows, componentsByPartItem);
    List<CostRunPartItemDto> expanded = new ArrayList<>();
    for (CostRunPartItemDto parent : rows) {
      LinkedHashMap<String, RollupComponentTotal> componentMap =
          parent.getId() == null ? null : componentsByPartItem.get(parent.getId());
      if (componentMap == null || componentMap.isEmpty()) {
        expanded.add(parent);
        continue;
      }
      List<RollupComponentTotal> components = new ArrayList<>(componentMap.values());
      boolean complete = components.stream().allMatch(component -> component.unitCost != null);
      if (!complete && components.size() > 1) {
        parent.setRemark(appendRemark(
            parent.getRemark(), "上卷展示未拆分：制造件价格分项不完整"));
        expanded.add(parent);
        continue;
      }
      expanded.addAll(splitRollupParent(parent, components, childArchiveByKey));
    }
    return expanded;
  }

  private List<CostRunPartItemDto> splitRollupParent(
      CostRunPartItemDto parent,
      List<RollupComponentTotal> components,
      Map<String, MaterialMasterRaw> childArchiveByKey) {
    List<BigDecimal> amounts = calculateRollupComponentAmounts(parent, components);
    List<CostRunPartItemDto> result = new ArrayList<>(components.size());
    for (int i = 0; i < components.size(); i++) {
      RollupComponentTotal component = components.get(i);
      BigDecimal amount = amounts.get(i);
      CostRunPartItemDto row = copyPartItem(parent);
      String parentName = firstText(parent.getPartName(), parent.getPartCode(), "父件");
      String childName =
          firstText(component.childName, component.childMaterialCode, "上卷子件");
      row.setDisplayPartName(parentChildDisplay(parentName, childName));
      row.setDisplayPartCode(
          parentChildDisplay(parent.getPartCode(), component.childMaterialCode));
      BigDecimal displayQty =
          component.qtyPerTop == null || component.qtyPerTop.signum() == 0
              ? parent.getPartQty()
              : component.qtyPerTop;
      row.setPartQty(displayQty);
      row.setAmount(amount);
      row.setUnitPrice(calculateSplitUnitPrice(
          amount,
          displayQty,
          component.unitCost,
          parent.getPartQty(),
          parent.getUnitPrice()));
      if (StringUtils.hasText(component.rawPriceType)) {
        row.setPriceSource(component.rawPriceType);
      }
      MaterialMasterRaw childArchive =
          childArchiveByKey.get(materialArchiveKey(
              parent.getMaterialOrganizationCode(), component.childMaterialCode));
      String childDrawingNo = firstText(
          childArchive == null ? null : childArchive.getDrawingNo(),
          component.childSpec,
          childArchive == null ? null : childArchive.getMaterialModel(),
          childArchive == null ? null : childArchive.getMaterialSpec());
      row.setDisplayPartDrawingNo(
          parentChildDisplay(parent.getPartDrawingNo(), childDrawingNo));
      if (childArchive != null) {
        if (StringUtils.hasText(childArchive.getShapeAttr())) {
          row.setShapeAttr(childArchive.getShapeAttr().trim());
          row.setMaterialShape(childArchive.getShapeAttr().trim());
        }
        if (StringUtils.hasText(childArchive.getGlobalSeg4Material())) {
          row.setMaterial(childArchive.getGlobalSeg4Material().trim());
        }
        if (StringUtils.hasText(childArchive.getCostElement())) {
          row.setCostElement(childArchive.getCostElement().trim());
        }
      }
      row.setRemark(appendRemark(
          parent.getRemark(), "上卷拆分子件=" + component.childMaterialCode));
      result.add(row);
    }
    return result;
  }

  private List<BigDecimal> calculateRollupComponentAmounts(
      CostRunPartItemDto parent, List<RollupComponentTotal> components) {
    List<BigDecimal> amounts = new ArrayList<>(components.size());
    BigDecimal parentQty = parent.getPartQty();
    BigDecimal unitCostTotal = components.stream()
        .map(component -> component.unitCost)
        .filter(java.util.Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    for (RollupComponentTotal component : components) {
      BigDecimal amount;
      if (component.unitCost != null && parentQty != null) {
        amount = component.unitCost.multiply(parentQty);
      } else if (component.unitCost != null
          && parent.getAmount() != null
          && unitCostTotal.signum() != 0) {
        amount = parent.getAmount()
            .multiply(component.unitCost)
            .divide(unitCostTotal, DISPLAY_AMOUNT_SCALE + 4, RoundingMode.HALF_UP);
      } else {
        amount = parent.getAmount();
      }
      amounts.add(amount == null
          ? null
          : amount.setScale(DISPLAY_AMOUNT_SCALE, RoundingMode.HALF_UP));
    }
    if (parent.getAmount() != null && amounts.stream().allMatch(java.util.Objects::nonNull)) {
      BigDecimal target = parent.getAmount().setScale(DISPLAY_AMOUNT_SCALE, RoundingMode.HALF_UP);
      BigDecimal sum = amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
      int last = amounts.size() - 1;
      amounts.set(last, amounts.get(last).add(target.subtract(sum)));
    }
    return amounts;
  }

  private BigDecimal calculateSplitUnitPrice(
      BigDecimal amount,
      BigDecimal qty,
      BigDecimal componentUnitCost,
      BigDecimal parentQty,
      BigDecimal parentUnitPrice) {
    if (componentUnitCost != null
        && parentQty != null
        && qty != null
        && qty.signum() != 0) {
      return componentUnitCost
          .multiply(parentQty)
          .divide(qty, DISPLAY_UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    }
    if (amount != null && qty != null && qty.signum() != 0) {
      return amount.divide(qty, DISPLAY_UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    }
    if (componentUnitCost != null) {
      return componentUnitCost.setScale(DISPLAY_UNIT_PRICE_SCALE, RoundingMode.HALF_UP);
    }
    return parentUnitPrice;
  }

  private void enrichPartFieldsFromMaterialArchive(List<CostRunPartItemDto> rows) {
    Map<String, Set<String>> codesByOrganization = new LinkedHashMap<>();
    for (CostRunPartItemDto row : rows) {
      if (row == null
          || !StringUtils.hasText(row.getMaterialOrganizationCode())
          || !StringUtils.hasText(row.getPartCode())) {
        continue;
      }
      codesByOrganization
          .computeIfAbsent(
              row.getMaterialOrganizationCode().trim(), ignored -> new LinkedHashSet<>())
          .add(row.getPartCode().trim());
    }
    Map<String, MaterialMasterRaw> archiveByKey = loadMaterialArchive(codesByOrganization);
    for (CostRunPartItemDto row : rows) {
      MaterialMasterRaw archive =
          archiveByKey.get(materialArchiveKey(
              row.getMaterialOrganizationCode(), row.getPartCode()));
      if (archive == null) {
        continue;
      }
      if (!StringUtils.hasText(row.getPartName())
          && StringUtils.hasText(archive.getMaterialName())) {
        row.setPartName(archive.getMaterialName().trim());
      }
      if (StringUtils.hasText(archive.getDrawingNo())) {
        row.setPartDrawingNo(archive.getDrawingNo().trim());
      }
      if (!StringUtils.hasText(row.getShapeAttr())
          && StringUtils.hasText(archive.getShapeAttr())) {
        row.setShapeAttr(archive.getShapeAttr().trim());
      }
      if (!StringUtils.hasText(row.getMaterial())
          && StringUtils.hasText(archive.getGlobalSeg4Material())) {
        row.setMaterial(archive.getGlobalSeg4Material().trim());
      }
    }
  }

  private Map<String, MaterialMasterRaw> loadChildMaterialArchive(
      List<CostRunPartItemDto> rows,
      Map<Long, LinkedHashMap<String, RollupComponentTotal>> componentsByPartItem) {
    Map<String, Set<String>> codesByOrganization = new LinkedHashMap<>();
    for (CostRunPartItemDto row : rows) {
      if (row == null
          || row.getId() == null
          || !StringUtils.hasText(row.getMaterialOrganizationCode())) {
        continue;
      }
      Map<String, RollupComponentTotal> components = componentsByPartItem.get(row.getId());
      if (components == null || components.isEmpty()) {
        continue;
      }
      codesByOrganization
          .computeIfAbsent(
              row.getMaterialOrganizationCode().trim(), ignored -> new LinkedHashSet<>())
          .addAll(components.keySet());
    }
    return loadMaterialArchive(codesByOrganization);
  }

  private Map<String, MaterialMasterRaw> loadMaterialArchive(
      Map<String, Set<String>> codesByOrganization) {
    Map<String, MaterialMasterRaw> archiveByKey = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : codesByOrganization.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      List<MaterialMasterRaw> archives =
          materialMasterRawMapper.selectByLatestBatchAndCodes(
              entry.getValue(), null, entry.getKey());
      if (archives == null) {
        continue;
      }
      for (MaterialMasterRaw archive : archives) {
        if (archive != null && StringUtils.hasText(archive.getMaterialCode())) {
          archiveByKey.put(
              materialArchiveKey(entry.getKey(), archive.getMaterialCode()), archive);
        }
      }
    }
    return archiveByKey;
  }

  private String materialArchiveKey(String organizationCode, String materialCode) {
    return normalizeBlankToNull(organizationCode) + "|" + normalizeBlankToNull(materialCode);
  }

  private String appendRemark(String original, String addition) {
    if (!StringUtils.hasText(original)) {
      return addition;
    }
    if (!StringUtils.hasText(addition)) {
      return original;
    }
    return original + "；" + addition;
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String parentChildDisplay(String parentValue, String childValue) {
    String parent = normalizeBlankToNull(parentValue);
    String child = normalizeBlankToNull(childValue);
    if (child == null) {
      return parent;
    }
    if (parent == null) {
      return "【" + child + "】";
    }
    return parent + "\n【" + child + "】";
  }

  private CostRunPartItemDto copyPartItem(CostRunPartItemDto source) {
    CostRunPartItemDto target = new CostRunPartItemDto();
    target.setId(source.getId());
    target.setBomRowId(source.getBomRowId());
    target.setPricePrepareItemId(source.getPricePrepareItemId());
    target.setOaNo(source.getOaNo());
    target.setPartName(source.getPartName());
    target.setPartCode(source.getPartCode());
    target.setProductCode(source.getProductCode());
    target.setPartDrawingNo(source.getPartDrawingNo());
    target.setDisplayPartName(source.getDisplayPartName());
    target.setDisplayPartCode(source.getDisplayPartCode());
    target.setDisplayPartDrawingNo(source.getDisplayPartDrawingNo());
    target.setPartQty(source.getPartQty());
    target.setShapeAttr(source.getShapeAttr());
    target.setMaterial(source.getMaterial());
    target.setPriceType(source.getPriceType());
    target.setPriceSource(source.getPriceSource());
    target.setRemark(source.getRemark());
    target.setUnitPrice(source.getUnitPrice());
    target.setAmount(source.getAmount());
    target.setPriceOrgCode(source.getPriceOrgCode());
    target.setMaterialOrganizationCode(source.getMaterialOrganizationCode());
    target.setMaterialShape(source.getMaterialShape());
    target.setPriority(source.getPriority());
    target.setEffectiveFrom(source.getEffectiveFrom());
    target.setEffectiveTo(source.getEffectiveTo());
    target.setSourceSystem(source.getSourceSystem());
    target.setCostElement(source.getCostElement());
    return target;
  }

  private static final class RollupComponentTotal {
    private final String childMaterialCode;
    private String childName;
    private String childSpec;
    private BigDecimal qtyPerTop;
    private BigDecimal unitCost;
    private String rawPriceType;

    private RollupComponentTotal(String childMaterialCode) {
      this.childMaterialCode = childMaterialCode;
    }

    private void accept(RollupPartComponentDto component) {
      if (StringUtils.hasText(component.getChildMaterialName())) {
        childName = component.getChildMaterialName().trim();
      }
      if (StringUtils.hasText(component.getChildMaterialSpec())) {
        childSpec = component.getChildMaterialSpec().trim();
      }
      if (qtyPerTop == null && component.getChildQtyPerTop() != null) {
        qtyPerTop = component.getChildQtyPerTop();
      }
      if (component.getChildUnitCost() != null) {
        unitCost = unitCost == null
            ? component.getChildUnitCost()
            : unitCost.add(component.getChildUnitCost());
      }
      if (StringUtils.hasText(component.getChildRawPriceType())) {
        rawPriceType = component.getChildRawPriceType().trim();
      }
    }
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

  private Set<String> lookupPackageParentKeys(List<CostRunPartItemDto> rows) {
    if (rows == null || rows.isEmpty()) {
      return Collections.emptySet();
    }
    Map<String, Set<String>> codesByOrganization = new LinkedHashMap<>();
    for (CostRunPartItemDto row : rows) {
      if (row == null || !StringUtils.hasText(row.getPartCode())) {
        continue;
      }
      QuoteDataOrganization organization = requiredItemOrganization(null, row, "包装组件聚合");
      codesByOrganization
          .computeIfAbsent(organization.materialOrganizationCode(), ignored -> new LinkedHashSet<>())
          .add(row.getPartCode().trim());
    }
    if (codesByOrganization.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> keys = new LinkedHashSet<>();
    for (Map.Entry<String, Set<String>> entry : codesByOrganization.entrySet()) {
      for (String code : lookupPackageParentCodes(entry.getValue(), entry.getKey())) {
        keys.add(packageFlagKey(entry.getKey(), code));
      }
    }
    return keys;
  }

  private List<MaterialMasterRaw> selectPackageComponentParents(String organizationCode) {
    String organization = requiredMaterialOrganizationCode(organizationCode, "包装组件父件识别");
    return materialMasterRawMapper.selectPackageComponentParentsByLatestBatch(
        MAIN_CATEGORY_PACKAGE, null, organization);
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
      String remark,
      BigDecimal packageCoefficient) {
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
    dto.setPriceOrgCode(first.getPriceOrgCode());
    dto.setMaterialOrganizationCode(first.getMaterialOrganizationCode());
    dto.setUnitPrice(calculateDisplayUnitPrice(first, amount, packageCoefficient));
    return dto;
  }

  private BigDecimal calculateDisplayUnitPrice(
      CostRunPartItemDto row, BigDecimal amount, BigDecimal packageCoefficient) {
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
        : unitPrice.multiply(packageCoefficient).setScale(6, RoundingMode.HALF_UP);
  }

  private BigDecimal packageCoefficient(String oaNo) {
    String businessUnitType = null;
    if (StringUtils.hasText(oaNo)) {
      OaForm form = oaFormMapper.selectOne(
          Wrappers.lambdaQuery(OaForm.class)
              .eq(OaForm::getOaNo, oaNo.trim())
              .last("LIMIT 1"));
      businessUnitType = form == null ? null : form.getBusinessUnitType();
    }
    return businessRuleProvider.decimalValue(
        CostBusinessRuleProvider.PACKAGE_COMPONENT_COEFFICIENT,
        com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth(),
        businessUnitType,
        DEFAULT_PACKAGE_COEFFICIENT);
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
      Map<Integer, PriceTypeRoute> winningRoutes,
      java.util.function.IntConsumer progress) {
    Map<Integer, PriceResolveResult> results = new HashMap<>();
    // 月度调价必须使用批次 pricing_month；普通报价未传 context 时才从取价日推导月份。
    String period = resolvePricingMonth(context, quoteDate);
    List<QuoteDataOrganization> itemOrganizations =
        resolveItemOrganizations(items, context, "包装组件识别");
    Map<String, Boolean> packageFlags = identifyPackageComponents(items, itemOrganizations);
    int total = Math.max(1, items.size());
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      QuoteDataOrganization organization = itemOrganizations.get(i);
      String code = item.getPartCode();
      // partCode 缺失：直接标 ERROR，不查 Router 也不抛异常（继续下一行）
      if (!StringUtils.hasText(code)) {
        results.put(i, PriceResolveResult.error("partCode 为空"));
        continue;
      }
      String normalizedCode = code.trim();
      if (Boolean.TRUE.equals(packageFlags.get(
          packageFlagKey(organization.materialOrganizationCode(), normalizedCode)))) {
        results.put(i, resolvePackageComponentPrice(
            oaNoValue, quoteDate, period, context, organization, item));
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
      List<CostRunPartItemDto> items, List<QuoteDataOrganization> organizations) {
    Map<String, Set<String>> codesByOrganization = new LinkedHashMap<>();
    for (int i = 0; i < items.size(); i++) {
      CostRunPartItemDto item = items.get(i);
      if (item != null && StringUtils.hasText(item.getPartCode())) {
        String organizationCode = organizations.get(i).materialOrganizationCode();
        codesByOrganization
            .computeIfAbsent(organizationCode, ignored -> new LinkedHashSet<>())
            .add(item.getPartCode().trim());
      }
    }
    if (codesByOrganization.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, Boolean> result = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : codesByOrganization.entrySet()) {
      String organizationCode = requiredMaterialOrganizationCode(
          entry.getKey(), "包装组件识别");
      Map<String, Boolean> flags =
          packageComponentIdentifyService.batchIdentify(entry.getValue(), organizationCode);
      if (flags == null || flags.isEmpty()) {
        continue;
      }
      for (Map.Entry<String, Boolean> flag : flags.entrySet()) {
        if (StringUtils.hasText(flag.getKey())) {
          result.put(packageFlagKey(organizationCode, flag.getKey().trim()), flag.getValue());
        }
      }
    }
    return result;
  }

  private PriceResolveResult resolvePackageComponentPrice(
      String oaNoValue,
      LocalDate quoteDate,
      String period,
      CostRunContext context,
      QuoteDataOrganization organization,
      CostRunPartItemDto item) {
    PackagePriceRequest request = new PackagePriceRequest();
    request.setPackageMaterialCode(item.getPartCode().trim());
    request.setPriceOrgCode(organization.priceOrgCode());
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

  private List<QuoteDataOrganization> resolveItemOrganizations(
      List<CostRunPartItemDto> items, CostRunContext context, String action) {
    List<QuoteDataOrganization> organizations = new ArrayList<>(items.size());
    for (CostRunPartItemDto item : items) {
      organizations.add(requiredItemOrganization(context, item, action));
    }
    return organizations;
  }

  private QuoteDataOrganization requiredItemOrganization(
      CostRunContext context, CostRunPartItemDto item, String action) {
    QuoteDataOrganization contextOrganization = organizationFromContext(context, action);
    QuoteDataOrganization rowOrganization = organizationFromPartItem(item, action);
    if (contextOrganization != null && rowOrganization != null
        && !sameOrganization(contextOrganization, rowOrganization)) {
      throw new IllegalStateException(action + "成本行组织与上游上下文不一致");
    }
    QuoteDataOrganization organization =
        contextOrganization == null ? rowOrganization : contextOrganization;
    if (organization == null) {
      throw new IllegalStateException(action + "缺少上游组织");
    }
    return organization;
  }

  private QuoteDataOrganization organizationFromContext(CostRunContext context, String action) {
    if (context == null) {
      return null;
    }
    return normalizeOrganization(
        normalizeBlankToNull(context.getPriceOrgCode()),
        normalizeBlankToNull(context.getMaterialOrganizationCode()),
        action + "上游上下文组织不完整");
  }

  private QuoteDataOrganization organizationFromPartItem(CostRunPartItemDto item, String action) {
    if (item == null) {
      return null;
    }
    return normalizeOrganization(
        normalizeBlankToNull(item.getPriceOrgCode()),
        normalizeBlankToNull(item.getMaterialOrganizationCode()),
        action + "成本行组织不完整");
  }

  private QuoteDataOrganization normalizeOrganization(
      String priceOrgCode, String materialOrganizationCode, String incompleteMessage) {
    if (priceOrgCode == null && materialOrganizationCode == null) {
      return null;
    }
    if (priceOrgCode == null || materialOrganizationCode == null) {
      throw new IllegalStateException(incompleteMessage);
    }
    return MaterialOrganization.normalizeQuoteDataOrganization(
        new QuoteDataOrganization(priceOrgCode, materialOrganizationCode));
  }

  private boolean sameOrganization(QuoteDataOrganization left, QuoteDataOrganization right) {
    return left.priceOrgCode().equals(right.priceOrgCode())
        && left.materialOrganizationCode().equals(right.materialOrganizationCode());
  }

  private String requiredMaterialOrganizationCode(String value, String action) {
    String normalized = normalizeBlankToNull(value);
    if (normalized == null) {
      throw new IllegalStateException(action + "缺少上游 materialOrganizationCode");
    }
    return MaterialOrganization.fromCode(normalized).getCode();
  }

  private String packageFlagKey(String organizationCode, String materialCode) {
    return requiredMaterialOrganizationCode(organizationCode, "包装组件识别")
        + "|"
        + (materialCode == null ? "" : materialCode.trim());
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
    return com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate();
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
      entity.setBomRowId(item.getBomRowId());
      entity.setPricePrepareItemId(item.getPricePrepareItemId());
      entity.setPriceOrgCode(item.getPriceOrgCode());
      entity.setMaterialOrganizationCode(item.getMaterialOrganizationCode());
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
