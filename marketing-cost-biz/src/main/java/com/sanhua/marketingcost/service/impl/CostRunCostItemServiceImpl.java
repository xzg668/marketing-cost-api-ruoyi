package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.enums.CostItemCategory;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CmsCostEffectiveSourceEnsureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunCostItemServiceImpl implements CostRunCostItemService {
  private static final Logger log = LoggerFactory.getLogger(CostRunCostItemServiceImpl.class);
  private static final int AMOUNT_SCALE = 6;

  private static final String DIRECT_LABOR = "DIRECT_LABOR";
  private static final String INDIRECT_LABOR = "INDIRECT_LABOR";
  private static final String CMS_SOURCE = "CMS";
  private static final String CMS_SOURCE_EFFECTIVE = "CMS_EFFECTIVE";
  private static final String CMS_SOURCE_TYPE_SALARY_DIRECT = "SALARY_DIRECT";
  private static final String CMS_SOURCE_TYPE_SALARY_INDIRECT = "SALARY_INDIRECT";
  private static final String CMS_AUTO_OPERATOR = "SYSTEM_AUTO";
  private static final String MATERIAL = "MATERIAL";
  private static final String LOSS = "LOSS";
  private static final String MANUFACTURE = "MANUFACTURE";
  private static final String MANUFACTURE_COST = "MANUFACTURE_COST";
  /** Task #9：调整后制造成本 = 制造成本 × 产品属性系数；作为三项费用计提基数 */
  private static final String ADJUSTED_MANUFACTURE_COST = "ADJUSTED_MANUFACTURE_COST";
  private static final String MGMT_EXP = "MGMT_EXP";
  private static final String SALES_EXP = "SALES_EXP";
  private static final String FIN_EXP = "FIN_EXP";
  private static final String TOTAL = "TOTAL";
  private static final String OVERHAUL = "OVERHAUL";
  private static final String TOOLING_REPAIR = "TOOLING_REPAIR";
  private static final String WATER_POWER = "WATER_POWER";
  private static final String DEPT_OTHER = "DEPT_OTHER";
  private static final String AUX_PREFIX = "AUX_";
  private static final String AUX_AMOUNT_MODE_DIRECT = "DIRECT";
  private static final String EXCLUDED_AUX_SUBJECT_PACKAGING = "包装辅料";
  private static final String OTHER_EXP_PREFIX = "OTHER_EXP_";
  /** T11：包装费固定 cost_code，区别于 lp_other_expense_rate 的 OTHER_EXP_<id> 系列 */
  private static final String OTHER_EXP_PACKAGE = "OTHER_EXP_PACKAGE";
  /** T11：运费固定 cost_code，取 oa_form_item.shipping_fee */
  private static final String OTHER_EXP_FREIGHT = "OTHER_EXP_FREIGHT";
  /** T11：lp_material_master.cost_element 表示包装材料的固定文本（U9 同步上来的中文枚举值） */
  private static final String COST_ELEMENT_PACKAGE = "主要材料-包装材料";

  // ==================== T24：见机表汇总行（BOM_BUCKET）相关常量 ====================
  /** T24：焊料桶 cost_code，对应 Excel 见机表 r44 "焊料" 1 行汇总 */
  private static final String BUCKET_WELD = "BOM_BUCKET_WELD";
  /** T24：焊料桶判定字段 — lp_material_master.cost_element 的固定文本（U9 同步上来）*/
  private static final String COST_ELEMENT_WELD = "主要材料-焊料";
  /** T24：包装桶 cost_code，对应 Excel 见机表 r45 "包装" 1 行汇总 */
  private static final String BUCKET_PACKAGE = "BOM_BUCKET_PACKAGE";
  /** T24：包装桶判定字段 — 找 BOM 父件 lp_material_master_raw.main_category_name='包装组件'（虚拟件） */
  private static final String MAIN_CATEGORY_PACKAGE = "包装组件";
  /**
   * T24：包装算法系数（硬编码，业务方后续确认来源）。
   *
   * <p>反算自 OA-001 实测：12.7212(子件SUM) × 1.05 ÷ 12 = 1.113105 = Excel 见机表 r45。
   * 可能是包装管理费率 5%、Excel 硬编码 magic number、或别的业务规则。
   * <b>TODO</b> 业务方拍板后改为从 lp_product_property / 配置表读取（v1-business-followup #T24）。
   */
  private static final BigDecimal PACKAGE_COEFFICIENT = new BigDecimal("1.05");
  /**
   * T24：包装数量（1 套包装管 N 台机），MVP 阶段硬编码 12（OA-001 实测值）。
   *
   * <p><b>风险</b>：多产品上线前必须接入数据源（候选：新建 lp_packaging_count 表 / 主档某字段 /
   * U9 同步带过来）。当前算法对所有产品统一用 12，跑非 OA-001 产品会出错。
   * <b>TODO</b> 业务方提供数据源后实现（v1-business-followup #T24）。
   */
  private static final BigDecimal PACKAGE_COUNT = new BigDecimal("12");

  private final CostRunCostItemMapper costRunCostItemMapper;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final SalaryCostMapper salaryCostMapper;
  private final CmsCostSourceEffectiveMapper cmsCostSourceEffectiveMapper;
  private final CmsCostEffectiveSourceEnsureService cmsCostEffectiveSourceEnsureService;
  private final DepartmentFundRateMapper departmentFundRateMapper;
  private final AuxCostItemMapper auxCostItemMapper;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final QualityLossRateMapper qualityLossRateMapper;
  private final ManufactureRateMapper manufactureRateMapper;
  private final ThreeExpenseRateMapper threeExpenseRateMapper;
  private final OtherExpenseRateMapper otherExpenseRateMapper;
  /** Task #9：产品属性系数来源（lp_product_property.coefficient） */
  private final ProductPropertyMapper productPropertyMapper;
  /** T11：用主档 cost_element 区分包装材料部品 → OTHER_EXP_PACKAGE */
  private final MaterialMasterMapper materialMasterMapper;
  /** T24：包装组件父件查 raw 主档（虚拟件 9830000026238 不在同步表 lp_material_master 里）*/
  private final MaterialMasterRawMapper materialMasterRawMapper;
  /** T24：包装算法需要 BOM 父子关系（找虚拟父件 main_category=包装组件 → 子件 part_code 集合）*/
  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  /** T19：试算路径专用 cached lookup（5 个 rate + ProductProperty），避免重复 SQL */
  private final CostRunCacheLookupService cacheLookup;

  /**
   * Task #9：是否把"水电费"计入材料费。
   *
   * <p>Excel 见机表3 的口径是「水电费独立列报，不进材料费」，故默认 false；老逻辑把 deptTotal
   * （含 waterPower）整体加进 materialTotal，会高估材料费、低估制造成本基数。开关保留是为了
   * 老用户/历史报表回溯时可以临时恢复 legacy 行为。
   */
  private final boolean includeWaterPowerInMaterial;

  public CostRunCostItemServiceImpl(
      CostRunCostItemMapper costRunCostItemMapper,
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      SalaryCostMapper salaryCostMapper,
      CmsCostSourceEffectiveMapper cmsCostSourceEffectiveMapper,
      CmsCostEffectiveSourceEnsureService cmsCostEffectiveSourceEnsureService,
      DepartmentFundRateMapper departmentFundRateMapper,
      AuxCostItemMapper auxCostItemMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      QualityLossRateMapper qualityLossRateMapper,
      ManufactureRateMapper manufactureRateMapper,
      ThreeExpenseRateMapper threeExpenseRateMapper,
      OtherExpenseRateMapper otherExpenseRateMapper,
      ProductPropertyMapper productPropertyMapper,
      MaterialMasterMapper materialMasterMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      BomRawHierarchyMapper bomRawHierarchyMapper,
      CostRunCacheLookupService cacheLookup,
      @Value("${cost.material.includeWaterPower:false}") boolean includeWaterPowerInMaterial) {
    this.costRunCostItemMapper = costRunCostItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.salaryCostMapper = salaryCostMapper;
    this.cmsCostSourceEffectiveMapper = cmsCostSourceEffectiveMapper;
    this.cmsCostEffectiveSourceEnsureService = cmsCostEffectiveSourceEnsureService;
    this.departmentFundRateMapper = departmentFundRateMapper;
    this.auxCostItemMapper = auxCostItemMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.qualityLossRateMapper = qualityLossRateMapper;
    this.manufactureRateMapper = manufactureRateMapper;
    this.threeExpenseRateMapper = threeExpenseRateMapper;
    this.otherExpenseRateMapper = otherExpenseRateMapper;
    this.productPropertyMapper = productPropertyMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.cacheLookup = cacheLookup;
    this.includeWaterPowerInMaterial = includeWaterPowerInMaterial;
  }

  @Override
  public List<CostRunCostItemDto> listByOaNo(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    String productCodeValue = StringUtils.hasText(productCode) ? productCode.trim() : null;

    // 优先查询 OA 表头；表头不存在则不做后续成本计算。
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, oaNoValue)
                .last("LIMIT 1"));
    if (form == null) {
      return Collections.emptyList();
    }

    // 忽略已核算状态，始终按最新基础数据重新计算费用。

    // 读取 OA 明细，若传入 productCode 则按料号过滤。
    List<OaFormItem> formItems =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class)
                .eq(OaFormItem::getOaFormId, form.getId())
                .eq(StringUtils.hasText(productCodeValue), OaFormItem::getMaterialNo, productCodeValue));
    if (formItems.isEmpty()) {
      return Collections.emptyList();
    }

    // 未传入 productCode 时，仅在 OA 只有一个料号时自动推断。
    if (!StringUtils.hasText(productCodeValue)) {
      Set<String> uniqueCodes = new LinkedHashSet<>();
      for (OaFormItem item : formItems) {
        if (StringUtils.hasText(item.getMaterialNo())) {
          uniqueCodes.add(item.getMaterialNo().trim());
        }
      }
      if (uniqueCodes.size() == 1) {
        productCodeValue = uniqueCodes.iterator().next();
      } else {
        return Collections.emptyList();
      }
    }

    // 收集料号，查询工资成本（直接/间接人工 + 事业部）。
    Set<String> materialCodes = new LinkedHashSet<>();
    for (OaFormItem item : formItems) {
      if (StringUtils.hasText(item.getMaterialNo())) {
        materialCodes.add(item.getMaterialNo().trim());
      }
    }
    if (materialCodes.isEmpty()) {
      return Collections.emptyList();
    }

    CostSourceContext costSourceContext = resolveCostSourceContext(form, formItems, productCodeValue);
    return calculateItems(oaNoValue, productCodeValue, materialCodes, costSourceContext);
  }

  @Override
  public List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode) {
    // T24：单参签名 = 兼容旧调用，默认只返 EXPENSE（传统费用项），不暴露 BOM_BUCKET 行
    return listStoredByOaNo(oaNo, productCode, CostItemCategory.EXPENSE);
  }

  @Override
  public List<CostRunCostItemDto> listStoredByOaNo(
      String oaNo, String productCode, String category) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    String productCodeValue = productCode.trim();
    // T24：传 null/空字符串 = 拉全量（EXPENSE + BOM_BUCKET）；明确传一个值就按值过滤
    String catFilter = StringUtils.hasText(category) ? category.trim() : null;
    List<CostRunCostItem> stored =
        costRunCostItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunCostItem.class)
                .eq(CostRunCostItem::getOaNo, oaNoValue)
                .eq(CostRunCostItem::getProductCode, productCodeValue)
                .eq(catFilter != null, CostRunCostItem::getCategory, catFilter)
                .orderByAsc(CostRunCostItem::getLineNo));
    if (stored.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunCostItemDto> items = new ArrayList<>();
    for (CostRunCostItem item : stored) {
      CostRunCostItemDto dto = new CostRunCostItemDto();
      dto.setCostCode(item.getCostCode());
      dto.setCostName(item.getCostName());
      dto.setBaseAmount(item.getBaseAmount());
      dto.setRate(item.getRate());
      dto.setAmount(item.getAmount());
      // T10：把落库的缺率说明回传给前端
      dto.setRemark(item.getRemark());
      // T24：把 category 也回传，前端/对账脚本可识别行类别
      dto.setCategory(item.getCategory());
      items.add(dto);
    }
    return items;
  }

  @Override
  public List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo, String productCode, Set<String> materialCodes,
      java.util.function.IntConsumer progress) {
    if (!StringUtils.hasText(oaNo)
        || !StringUtils.hasText(productCode)
        || materialCodes == null
        || materialCodes.isEmpty()) {
      progress.accept(100);
      return Collections.emptyList();
    }
    // T16：calculateItems 是原子计算，开始/结束各报一次进度（中途无明确切片节点）
    progress.accept(0);
    List<CostRunCostItemDto> result =
        calculateItems(
            oaNo.trim(),
            productCode.trim(),
            materialCodes,
            resolveCostSourceContext(oaNo.trim(), productCode.trim()));
    progress.accept(100);
    return result;
  }

  private List<CostRunCostItemDto> calculateItems(
      String oaNoValue,
      String productCodeValue,
      Set<String> materialCodes,
      CostSourceContext costSourceContext) {
    ensureCmsCostSources(costSourceContext);
    List<SalaryCost> salaryCosts =
        salaryCostMapper.selectList(
            Wrappers.lambdaQuery(SalaryCost.class).in(SalaryCost::getMaterialCode, materialCodes));

    // 1) 先算人工 -> 部门经费。正式核算有成本年度时，工资金额只取 CMS 公共生效来源。
    LaborCostResult laborResult = buildLaborCostResult(materialCodes, salaryCosts, costSourceContext);
    BigDecimal directTotal = laborResult.directTotal;
    BigDecimal indirectTotal = laborResult.indirectTotal;
    Map<String, LaborSum> laborByUnit = laborResult.laborByUnit;

    DepartmentFeeResult feeResult = calculateDepartmentFees(laborByUnit);

    List<String> costCodes = new ArrayList<>();
    costCodes.add(MATERIAL);
    costCodes.add(DIRECT_LABOR);
    costCodes.add(INDIRECT_LABOR);
    costCodes.add(LOSS);
    costCodes.add(MANUFACTURE);
    costCodes.add(MANUFACTURE_COST);
    costCodes.add(ADJUSTED_MANUFACTURE_COST);
    costCodes.add(MGMT_EXP);
    costCodes.add(SALES_EXP);
    costCodes.add(FIN_EXP);
    costCodes.add(TOTAL);
    costCodes.add(OVERHAUL);
    costCodes.add(TOOLING_REPAIR);
    costCodes.add(WATER_POWER);
    costCodes.add(DEPT_OTHER);

    // 2) 再算辅料
    List<CostRunCostItemDto> auxItems = buildAuxItems(materialCodes, costSourceContext);
    BigDecimal auxTotal = BigDecimal.ZERO;
    for (CostRunCostItemDto auxItem : auxItems) {
      if (auxItem.getAmount() != null) {
        auxTotal = auxTotal.add(auxItem.getAmount());
      }
      if (StringUtils.hasText(auxItem.getCostCode())) {
        costCodes.add(auxItem.getCostCode());
      }
    }

    List<CostRunCostItemDto> otherExpenseItems = buildOtherExpenseItems(productCodeValue);
    BigDecimal otherExpenseTotal = BigDecimal.ZERO;
    for (CostRunCostItemDto otherItem : otherExpenseItems) {
      if (otherItem.getAmount() != null) {
        otherExpenseTotal = otherExpenseTotal.add(otherItem.getAmount());
      }
      if (StringUtils.hasText(otherItem.getCostCode())) {
        costCodes.add(otherItem.getCostCode());
      }
    }

    // 3) 最后汇总材料费(部品+辅料+部门经费+见机表包装)
    //    Task #9：水电费默认不计入材料费（与 Excel 见机表3 口径对齐），
    //    通过 cost.material.includeWaterPower 开关保留 legacy 行为以便回溯。
    //    T24：包装材料不能按原始子件 SUM 进材料费，需按见机表口径 SUM × 1.05 ÷ 12。
    PartTotalSplit partSplit = splitPartAmount(oaNoValue, productCodeValue);
    BigDecimal partTotal = partSplit.nonPackageTotal();
    BigDecimal rawPackageAmount = partSplit.packageTotal();
    BigDecimal packageBucketAmount = calculatePackageBucketAmount(oaNoValue, productCodeValue);
    BigDecimal packageAmount =
        packageBucketAmount.signum() > 0 ? packageBucketAmount : rawPackageAmount;
    BigDecimal freightAmount = lookupFreight(oaNoValue, productCodeValue);
    // T24：包装进 materialTotal；优先用见机表包装汇总，取不到包装组件数据时退回原始包装子件金额。
    //      totalAmount 只额外累加运费等真正价外项，避免重复。
    otherExpenseTotal = otherExpenseTotal.add(freightAmount);
    costCodes.add(OTHER_EXP_PACKAGE);
    costCodes.add(OTHER_EXP_FREIGHT);
    BigDecimal deptTotal =
        feeResult.overhaul
            .add(feeResult.toolingRepair)
            .add(feeResult.other);
    if (includeWaterPowerInMaterial) {
      deptTotal = deptTotal.add(feeResult.waterPower);
    }
    BigDecimal materialTotal =
        partTotal
            .add(auxTotal)
            .add(deptTotal)
            .add(packageAmount)
            .setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    // T10：把 5 处 rate 缺失原因带回，组装 buildItem 时塞 remark
    RateLookup lossLookup = findLossRate(laborByUnit);
    BigDecimal lossRate = lossLookup.rate();
    String lossRemark = lossLookup.remark();
    BigDecimal lossBase = materialTotal.add(directTotal).add(indirectTotal);
    BigDecimal lossAmount =
        lossRate == null
            ? null
            : lossBase.multiply(lossRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    RateLookup manufactureLookup = findManufactureRate(laborByUnit);
    BigDecimal manufactureRate = manufactureLookup.rate();
    String manufactureRemark = manufactureLookup.remark();
    BigDecimal lossAmountValue = lossAmount == null ? BigDecimal.ZERO : lossAmount;
    BigDecimal manufactureBase =
        materialTotal.add(directTotal).add(indirectTotal).add(lossAmountValue);
    BigDecimal manufactureCost = null;
    BigDecimal manufactureFee = null;
    if (manufactureRate != null) {
      BigDecimal denominator = BigDecimal.ONE.subtract(manufactureRate);
      if (denominator.compareTo(BigDecimal.ZERO) > 0) {
        manufactureCost =
            manufactureBase.divide(denominator, AMOUNT_SCALE, RoundingMode.HALF_UP);
        manufactureFee =
            manufactureCost.multiply(manufactureRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      } else {
        // T10：denominator <= 0（rate>=1）数据异常 → 也算 miss
        manufactureRemark =
            "lp_manufacture_rate.fee_rate=" + manufactureRate + " 不在 (0,1) 范围";
      }
    }
    // Task #9：产品属性系数 → 调整后制造成本 = 制造成本 × 系数；作为三项费用基数
    BigDecimal coefficient = lookupProductCoefficient(productCodeValue);
    BigDecimal adjustedManufactureCost =
        manufactureCost == null
            ? null
            : manufactureCost.multiply(coefficient).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

    ThreeExpenseLookup threeExpLookup = findThreeExpenseRate(laborByUnit);
    ThreeExpenseRate threeExpenseRate = threeExpLookup.rate();
    String threeExpRemark = threeExpLookup.remark();
    BigDecimal mgmtRate = threeExpenseRate == null ? null : threeExpenseRate.getManagementExpenseRate();
    BigDecimal salesRate = threeExpenseRate == null ? null : threeExpenseRate.getSalesExpenseRate();
    BigDecimal financeRate = threeExpenseRate == null ? null : threeExpenseRate.getFinanceExpenseRate();
    // 三项费用基数从 manufactureCost 改成 adjustedManufactureCost（系数=1 时等价旧逻辑）
    BigDecimal mgmtAmount =
        adjustedManufactureCost == null || mgmtRate == null
            ? null
            : adjustedManufactureCost.multiply(mgmtRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    BigDecimal salesAmount =
        adjustedManufactureCost == null || salesRate == null
            ? null
            : adjustedManufactureCost.multiply(salesRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    BigDecimal financeAmount =
        adjustedManufactureCost == null || financeRate == null
            ? null
            : adjustedManufactureCost.multiply(financeRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    BigDecimal totalAmount = null;
    if (adjustedManufactureCost != null) {
      // 不含税总成本 = 调整后制造成本 + 三项费用 + 其他费用（与 Excel 一致）
      totalAmount = adjustedManufactureCost;
      if (mgmtAmount != null) {
        totalAmount = totalAmount.add(mgmtAmount);
      }
      if (salesAmount != null) {
        totalAmount = totalAmount.add(salesAmount);
      }
      if (financeAmount != null) {
        totalAmount = totalAmount.add(financeAmount);
      }
      if (otherExpenseTotal.compareTo(BigDecimal.ZERO) != 0) {
        totalAmount = totalAmount.add(otherExpenseTotal);
      }
      totalAmount = totalAmount.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    List<CostRunCostItemDto> items = new ArrayList<>();
    items.add(buildItem(MATERIAL, "材料费", null, null, materialTotal));
    items.addAll(auxItems);
    items.add(buildItem(DIRECT_LABOR, "直接人工工资", null, null, directTotal, laborResult.directRemark));
    items.add(buildItem(INDIRECT_LABOR, "辅助人工工资", null, null, indirectTotal, laborResult.indirectRemark));
    items.add(buildItem(LOSS, "净损失率", lossBase, lossRate, lossAmount, lossRemark));
    // T10：MANUFACTURE / MANUFACTURE_COST / ADJUSTED_MANUFACTURE_COST 共享同一份 remark
    items.add(buildItem(
        MANUFACTURE, "制造费用", manufactureCost, manufactureRate, manufactureFee, manufactureRemark));
    items.add(buildItem(MANUFACTURE_COST, "制造成本", null, null, manufactureCost, manufactureRemark));
    // Task #9：调整后制造成本（baseAmount=制造成本，rate=系数）；T10 沿用同一 remark
    items.add(buildItem(
        ADJUSTED_MANUFACTURE_COST,
        "调整后制造成本",
        manufactureCost,
        coefficient,
        adjustedManufactureCost,
        manufactureRemark));
    // T10：MGMT/SALES/FIN 三项共享 threeExpRemark
    items.add(buildItem(
        MGMT_EXP, "管理费用", adjustedManufactureCost, mgmtRate, mgmtAmount, threeExpRemark));
    items.add(buildItem(
        SALES_EXP, "销售费用", adjustedManufactureCost, salesRate, salesAmount, threeExpRemark));
    items.add(buildItem(
        FIN_EXP, "财务费用", adjustedManufactureCost, financeRate, financeAmount, threeExpRemark));
    items.addAll(otherExpenseItems);
    // T11：包装费/运费固定项，紧跟 lp_other_expense_rate 系列
    items.add(buildItem(OTHER_EXP_PACKAGE, "包装费", rawPackageAmount, null, packageAmount));
    items.add(buildItem(OTHER_EXP_FREIGHT, "运费", null, null, freightAmount));
    items.add(buildItem(TOTAL, "不含税总成本", null, null, totalAmount));
    // T10：4 项部门经费共享 feeResult.remark
    items.add(buildItem(
        OVERHAUL, "大修费", feeResult.baseAmount, feeResult.overhaulRate, feeResult.overhaul, feeResult.remark));
    items.add(buildItem(
        TOOLING_REPAIR,
        "工装零星修理费",
        feeResult.baseAmount,
        feeResult.toolingRepairRate,
        feeResult.toolingRepair,
        feeResult.remark));
    items.add(buildItem(
        WATER_POWER, "水电费", feeResult.baseAmount, feeResult.waterPowerRate, feeResult.waterPower, feeResult.remark));
    items.add(buildItem(
        DEPT_OTHER, "其他费用", feeResult.baseAmount, feeResult.otherRate, feeResult.other, feeResult.remark));

    // T24：见机表原材料汇总（不参与 totalAmount 累加；category=BOM_BUCKET 与上面 EXPENSE 行严格隔离）
    //   - 焊料 BUCKET_WELD: Σ(part 子件 cost_element=主要材料-焊料)
    //   - 包装 BUCKET_PACKAGE: Σ(BOM 父件 main_category=包装组件 子件 amount) × 1.05 ÷ 12
    List<CostRunCostItemDto> bucketItems = buildBucketItems(oaNoValue, productCodeValue);
    for (CostRunCostItemDto b : bucketItems) {
      items.add(b);
      if (StringUtils.hasText(b.getCostCode())) {
        costCodes.add(b.getCostCode());
      }
    }

    saveCostRunItems(oaNoValue, productCodeValue, items, costCodes);
    return items;
  }

  /**
   * T24：构建见机表原材料汇总行（BOM_BUCKET）。
   *
   * <p>本期范围：焊料 + 包装两类。其他 cost_element 桶后续迭代再加。
   *
   * <p>设计文档：docs/cost-bucket-aggregation-20260501-design.md
   */
  private List<CostRunCostItemDto> buildBucketItems(String oaNoValue, String productCodeValue) {
    List<CostRunCostItemDto> result = new ArrayList<>();

    // 焊料：从 part_item join material_master 按 cost_element 聚合
    BigDecimal weldSum = sumPartByCostElement(oaNoValue, productCodeValue, COST_ELEMENT_WELD);
    if (weldSum != null && weldSum.signum() > 0) {
      result.add(buildBucketItem(BUCKET_WELD, "焊料", weldSum));
    }

    BigDecimal pkgAmount = calculatePackageBucketAmount(oaNoValue, productCodeValue);
    if (pkgAmount != null && pkgAmount.signum() > 0) {
      result.add(buildBucketItem(BUCKET_PACKAGE, "包装", pkgAmount));
    }

    return result;
  }

  /** T24：见机表包装金额 = 包装组件子件 SUM × 1.05 ÷ 12，用于材料费和 BOM_BUCKET_PACKAGE。 */
  BigDecimal calculatePackageBucketAmount(String oaNoValue, String productCodeValue) {
    BigDecimal pkgChildSum =
        sumPartByBomParentMainCategory(oaNoValue, productCodeValue, MAIN_CATEGORY_PACKAGE);
    if (pkgChildSum == null || pkgChildSum.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return pkgChildSum
        .multiply(PACKAGE_COEFFICIENT)
        .divide(PACKAGE_COUNT, AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * T24：按 BOM 父件 main_category_name 聚合 part_item.amount。
   *
   * <p>用于"包装"这种"通过虚拟父件归集"的场景：包装子件本身的 main_category 是叶子分类
   * （纸箱 / 纸质内附件），无法直接命中"包装组件"，必须先在 BOM 里找虚拟父件
   * （main_category=包装组件 的 9830000026238 等）→ 取下挂子件 → SUM amount。
   *
   * <p>注意：包装组件父件可能是虚拟件，不在同步表 lp_material_master，必须查 raw 表。
   *
   * <p>包私有以便单测覆盖。
   */
  BigDecimal sumPartByBomParentMainCategory(
      String oaNo, String productCode, String parentMainCategory) {
    // 1) 从 raw 主档拿到所有 main_category=parentMainCategory 的 material_code（候选父件集合）
    List<MaterialMasterRaw> parentMasters =
        materialMasterRawMapper.selectList(
            Wrappers.lambdaQuery(MaterialMasterRaw.class)
                .eq(MaterialMasterRaw::getMainCategoryName, parentMainCategory));
    if (parentMasters == null || parentMasters.isEmpty()) {
      return BigDecimal.ZERO;
    }
    Set<String> parentCodes = new LinkedHashSet<>();
    for (MaterialMasterRaw m : parentMasters) {
      if (StringUtils.hasText(m.getMaterialCode())) {
        parentCodes.add(m.getMaterialCode());
      }
    }
    if (parentCodes.isEmpty()) {
      return BigDecimal.ZERO;
    }
    // 2) 在 BOM hierarchy 找 top_product_code=本产品 + parent_code 命中候选集合的所有子件
    List<BomRawHierarchy> bomChildren =
        bomRawHierarchyMapper.selectList(
            Wrappers.lambdaQuery(BomRawHierarchy.class)
                .eq(BomRawHierarchy::getTopProductCode, productCode)
                .in(BomRawHierarchy::getParentCode, parentCodes));
    if (bomChildren == null || bomChildren.isEmpty()) {
      return BigDecimal.ZERO;
    }
    Set<String> targetChildCodes = new LinkedHashSet<>();
    for (BomRawHierarchy c : bomChildren) {
      if (StringUtils.hasText(c.getMaterialCode())) {
        targetChildCodes.add(c.getMaterialCode());
      }
    }
    if (targetChildCodes.isEmpty()) {
      return BigDecimal.ZERO;
    }
    // 3) 在 part_item 里 SUM 命中子件的 amount
    List<CostRunPartItem> parts =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class)
                .eq(CostRunPartItem::getOaNo, oaNo)
                .eq(CostRunPartItem::getProductCode, productCode)
                .in(CostRunPartItem::getPartCode, targetChildCodes));
    if (parts == null || parts.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (CostRunPartItem p : parts) {
      if (p.getAmount() != null) {
        sum = sum.add(p.getAmount());
      }
    }
    return sum.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * T24：按子件 cost_element 聚合 part_item.amount。
   *
   * <p>实现：先从 lp_cost_run_part_item 取该 OA 该产品的所有 part_code + amount，
   * 再批量查 lp_material_master 取每个 part_code 的 cost_element，
   * 命中 targetCostElement 的累加 amount。
   *
   * <p>主档查不到的 part_code 不计入（保守：缺主档不归桶）。
   *
   * <p>包私有以便单测覆盖。
   */
  BigDecimal sumPartByCostElement(String oaNo, String productCode, String targetCostElement) {
    List<CostRunPartItem> parts =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class)
                .eq(CostRunPartItem::getOaNo, oaNo)
                .eq(CostRunPartItem::getProductCode, productCode));
    if (parts == null || parts.isEmpty()) {
      return BigDecimal.ZERO;
    }
    Set<String> partCodes = new LinkedHashSet<>();
    for (CostRunPartItem p : parts) {
      if (StringUtils.hasText(p.getPartCode())) {
        partCodes.add(p.getPartCode().trim());
      }
    }
    if (partCodes.isEmpty()) {
      return BigDecimal.ZERO;
    }
    List<MaterialMaster> masters =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .in(MaterialMaster::getMaterialCode, partCodes)
                .eq(MaterialMaster::getCostElement, targetCostElement));
    if (masters == null || masters.isEmpty()) {
      return BigDecimal.ZERO;
    }
    Set<String> targetCodes = new LinkedHashSet<>();
    for (MaterialMaster m : masters) {
      if (StringUtils.hasText(m.getMaterialCode())) {
        targetCodes.add(m.getMaterialCode());
      }
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (CostRunPartItem p : parts) {
      String code = p.getPartCode() == null ? null : p.getPartCode().trim();
      if (code != null && targetCodes.contains(code) && p.getAmount() != null) {
        sum = sum.add(p.getAmount());
      }
    }
    return sum.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /** T24：构造一行 BOM_BUCKET 汇总 DTO（rate/baseAmount 留空，只填金额 + category 标记） */
  private CostRunCostItemDto buildBucketItem(String code, String name, BigDecimal amount) {
    CostRunCostItemDto dto = new CostRunCostItemDto();
    dto.setCostCode(code);
    dto.setCostName(name);
    dto.setAmount(amount);
    dto.setCategory(CostItemCategory.BOM_BUCKET);
    return dto;
  }

  private List<CostRunCostItemDto> loadStoredItems(String oaNo, String productCode) {
    List<CostRunCostItem> stored =
        costRunCostItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunCostItem.class)
                .eq(CostRunCostItem::getOaNo, oaNo)
                .eq(StringUtils.hasText(productCode), CostRunCostItem::getProductCode, productCode)
                .in(
                    CostRunCostItem::getCostCode,
                    MATERIAL,
                    DIRECT_LABOR,
                    INDIRECT_LABOR,
                    LOSS,
                    MANUFACTURE,
                    MANUFACTURE_COST,
                    MGMT_EXP,
                    SALES_EXP,
                    FIN_EXP,
                    TOTAL,
                    OVERHAUL,
                    TOOLING_REPAIR,
                    WATER_POWER,
                    DEPT_OTHER,
                    // T11：固定其他费用项；OTHER_EXP_<id> 系列因前缀不固定不放白名单
                    OTHER_EXP_PACKAGE,
                    OTHER_EXP_FREIGHT));
    if (stored.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunCostItemDto> items = new ArrayList<>();
    for (CostRunCostItem item : stored) {
      CostRunCostItemDto dto = new CostRunCostItemDto();
      dto.setCostCode(item.getCostCode());
      dto.setCostName(item.getCostName());
      dto.setBaseAmount(item.getBaseAmount());
      dto.setRate(item.getRate());
      dto.setAmount(item.getAmount());
      // T10：把落库的缺率说明回传给前端
      dto.setRemark(item.getRemark());
      items.add(dto);
    }
    return items;
  }

  private CostSourceContext resolveCostSourceContext(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return new CostSourceContext(null, "");
    }
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, oaNo)
                .last("LIMIT 1"));
    if (form == null) {
      return new CostSourceContext(null, "");
    }
    List<OaFormItem> rows =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class)
                .eq(OaFormItem::getOaFormId, form.getId())
                .eq(StringUtils.hasText(productCode), OaFormItem::getMaterialNo, productCode));
    return resolveCostSourceContext(form, rows, productCode);
  }

  private CostSourceContext resolveCostSourceContext(
      OaForm form, List<OaFormItem> formItems, String productCode) {
    Integer costYear = null;
    String businessUnitType = null;
    if (formItems != null) {
      for (OaFormItem item : formItems) {
        if (item == null) {
          continue;
        }
        if (StringUtils.hasText(productCode)
            && StringUtils.hasText(item.getMaterialNo())
            && !productCode.trim().equals(item.getMaterialNo().trim())) {
          continue;
        }
        if (costYear == null && item.getValidDate() != null) {
          costYear = item.getValidDate().getYear();
        }
        if (businessUnitType == null) {
          businessUnitType = trimToNull(item.getBusinessUnitType());
        }
        if (costYear != null && businessUnitType != null) {
          break;
        }
      }
    }
    if (costYear == null && form != null && form.getApplyDate() != null) {
      costYear = form.getApplyDate().getYear();
    }
    if (businessUnitType == null && form != null) {
      businessUnitType = trimToNull(form.getBusinessUnitType());
    }
    return new CostSourceContext(costYear, normalizeBusinessUnit(businessUnitType));
  }

  private void ensureCmsCostSources(CostSourceContext costSourceContext) {
    if (costSourceContext == null || costSourceContext.costYear == null) {
      return;
    }
    cmsCostEffectiveSourceEnsureService.ensureDefaultSources(
        costSourceContext.costYear, CMS_AUTO_OPERATOR, costSourceContext.businessUnitType);
  }

  private DepartmentFeeResult calculateDepartmentFees(Map<String, LaborSum> laborByUnit) {
    DepartmentFeeResult result = new DepartmentFeeResult();
    if (laborByUnit.isEmpty()) {
      // T10：无人工数据 → 4 项部门费率项都不会算出来
      result.remark = "无人工数据(lp_salary_cost 缺料号)，无法取部门经费率";
      return result;
    }

    boolean singleUnit = laborByUnit.size() == 1;
    for (Map.Entry<String, LaborSum> entry : laborByUnit.entrySet()) {
      String businessUnit = entry.getKey();
      LaborSum sum = entry.getValue();
      // T19：走 cached lookup
      DepartmentFundRate fundRate = cacheLookup.findDepartmentFundRate(businessUnit);
      if (fundRate == null) {
        // T10：单事业部缺率 → 4 项费率都缺；记 remark；多事业部下不覆盖（避免互相覆盖丢信息）
        if (singleUnit) {
          result.remark =
              "lp_department_fund_rate 无 businessUnit=" + businessUnit + " 配置";
        }
        continue;
      }

      BigDecimal manhourRate = fundRate.getManhourRate();
      if (!isPositive(manhourRate)) {
        if (singleUnit) {
          result.remark =
              "lp_department_fund_rate.manhour_rate 非正数, businessUnit=" + businessUnit;
        }
        continue;
      }

      BigDecimal laborTotal = sum.direct.add(sum.indirect);
      BigDecimal manhourCost = divide(laborTotal, manhourRate);
      BigDecimal upliftRate = fundRate.getUpliftRate() == null ? BigDecimal.ONE : fundRate.getUpliftRate();

      BigDecimal overhaulRate = multiplyRate(fundRate.getOverhaulRate(), upliftRate);
      BigDecimal toolingRate = multiplyRate(fundRate.getToolingRepairRate(), upliftRate);
      BigDecimal waterRate = multiplyRate(fundRate.getWaterPowerRate(), upliftRate);
      BigDecimal otherRate = multiplyRate(fundRate.getOtherRate(), upliftRate);

      result.overhaul = result.overhaul.add(multiplyAmount(manhourCost, overhaulRate));
      result.toolingRepair = result.toolingRepair.add(multiplyAmount(manhourCost, toolingRate));
      result.waterPower = result.waterPower.add(multiplyAmount(manhourCost, waterRate));
      result.other = result.other.add(multiplyAmount(manhourCost, otherRate));

      if (singleUnit) {
        result.baseAmount = manhourCost;
        result.overhaulRate = overhaulRate;
        result.toolingRepairRate = toolingRate;
        result.waterPowerRate = waterRate;
        result.otherRate = otherRate;
      }
    }

    if (!singleUnit) {
      result.baseAmount = null;
      result.overhaulRate = null;
      result.toolingRepairRate = null;
      result.waterPowerRate = null;
      result.otherRate = null;
    }
    return result;
  }

  /**
   * T10：查损失率，把"缺率"原因带回去；正常命中 remark=null。
   *
   * <p>多事业部 / 事业部为空 / 表里查不到 都给独立 remark 文案，便于前端定位是配置缺失还是数据异常。
   */
  private RateLookup findLossRate(Map<String, LaborSum> laborByUnit) {
    if (laborByUnit == null || laborByUnit.isEmpty()) {
      return new RateLookup(null, "无人工数据(lp_salary_cost 缺料号)，无法取损失率");
    }
    if (laborByUnit.size() > 1) {
      return new RateLookup(null, "OA 含多事业部" + laborByUnit.keySet() + "，未取损失率");
    }
    String businessUnit = laborByUnit.keySet().iterator().next();
    if (!StringUtils.hasText(businessUnit)) {
      return new RateLookup(null, "事业部为空，未取损失率");
    }
    // T19：走 cached lookup（5min TTL）
    QualityLossRate rate = cacheLookup.findQualityLossRate(businessUnit);
    if (rate == null) {
      return new RateLookup(null, "lp_quality_loss_rate 无 businessUnit=" + businessUnit + " 配置");
    }
    return new RateLookup(rate.getLossRate(), null);
  }

  /** T10：查制造费用率，缺率原因随返回；同 findLossRate 文案规则。 */
  private RateLookup findManufactureRate(Map<String, LaborSum> laborByUnit) {
    if (laborByUnit == null || laborByUnit.isEmpty()) {
      return new RateLookup(null, "无人工数据(lp_salary_cost 缺料号)，无法取制造费用率");
    }
    if (laborByUnit.size() > 1) {
      return new RateLookup(null, "OA 含多事业部" + laborByUnit.keySet() + "，未取制造费用率");
    }
    String businessUnit = laborByUnit.keySet().iterator().next();
    if (!StringUtils.hasText(businessUnit)) {
      return new RateLookup(null, "事业部为空，未取制造费用率");
    }
    // T19：走 cached lookup
    ManufactureRate rate = cacheLookup.findManufactureRate(businessUnit);
    if (rate == null) {
      return new RateLookup(null, "lp_manufacture_rate 无 businessUnit=" + businessUnit + " 配置");
    }
    return new RateLookup(rate.getFeeRate(), null);
  }

  /**
   * T10：查三项费用率，包成 (rate, remark)；MGMT/SALES/FIN 三项共用同一 remark。
   * 缺率时 rate=null；命中正常时 remark=null。
   */
  private ThreeExpenseLookup findThreeExpenseRate(Map<String, LaborSum> laborByUnit) {
    if (laborByUnit == null || laborByUnit.isEmpty()) {
      return new ThreeExpenseLookup(null, "无人工数据(lp_salary_cost 缺料号)，无法取三项费用率");
    }
    if (laborByUnit.size() > 1) {
      return new ThreeExpenseLookup(null, "OA 含多事业部" + laborByUnit.keySet() + "，未取三项费用率");
    }
    String businessUnit = laborByUnit.keySet().iterator().next();
    if (!StringUtils.hasText(businessUnit)) {
      return new ThreeExpenseLookup(null, "事业部为空，未取三项费用率");
    }
    // T19：走 cached lookup
    ThreeExpenseRate rate = cacheLookup.findThreeExpenseRate(businessUnit);
    if (rate == null) {
      return new ThreeExpenseLookup(null, "lp_three_expense_rate 无 businessUnit=" + businessUnit + " 配置");
    }
    return new ThreeExpenseLookup(rate, null);
  }

  /** T10：单事业部 → rate 元组；多事业部场景 rate=null+remark 由调用侧组装。 */
  private record RateLookup(BigDecimal rate, String remark) {}

  /** T10：三项费用率元组，命中时 rate 为整行 ThreeExpenseRate，缺时 null+remark。 */
  private record ThreeExpenseLookup(ThreeExpenseRate rate, String remark) {}

  private void saveCostRunItems(
      String oaNo, String productCode, List<CostRunCostItemDto> items, List<String> costCodes) {
    if (!StringUtils.hasText(oaNo)
        || !StringUtils.hasText(productCode)
        || items == null) {
      return;
    }
    costRunCostItemMapper.delete(
        Wrappers.lambdaQuery(CostRunCostItem.class)
            .eq(CostRunCostItem::getOaNo, oaNo)
            .eq(CostRunCostItem::getProductCode, productCode));
    if (items.isEmpty()) {
      return;
    }
    List<CostRunCostItem> entities = new ArrayList<>(items.size());
    int lineNo = 1;
    for (CostRunCostItemDto item : items) {
      CostRunCostItem entity = new CostRunCostItem();
      entity.setOaNo(oaNo);
      entity.setProductCode(productCode);
      entity.setLineNo(lineNo++);
      entity.setCostCode(item.getCostCode());
      entity.setCostName(item.getCostName());
      entity.setBaseAmount(item.getBaseAmount());
      entity.setRate(item.getRate());
      entity.setAmount(item.getAmount());
      // T10：把缺率说明落库，便于复算 / 对账时直接看历史
      entity.setRemark(item.getRemark());
      // T24：未显式标 category 时默认 EXPENSE（传统费用项），buildBucketItems 写入时会主动标 BOM_BUCKET
      entity.setCategory(
          StringUtils.hasText(item.getCategory()) ? item.getCategory() : CostItemCategory.EXPENSE);
      entities.add(entity);
    }
    batchInsert(entities);
  }

  private void batchInsert(List<CostRunCostItem> entities) {
    if (entities.isEmpty()) {
      return;
    }
    for (CostRunCostItem entity : entities) {
      costRunCostItemMapper.insert(entity);
    }
  }

  private List<CostRunCostItemDto> buildOtherExpenseItems(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return Collections.emptyList();
    }
    // T19：走 cached lookup
    List<OtherExpenseRate> rates = cacheLookup.findOtherExpenseRates(productCode);
    if (rates == null || rates.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunCostItemDto> items = new ArrayList<>();
    int fallbackIndex = 1;
    for (OtherExpenseRate rate : rates) {
      String expenseType = trimToNull(rate.getExpenseType());
      String codeSuffix =
          rate.getId() == null ? ("TMP" + fallbackIndex++) : String.valueOf(rate.getId());
      String code = OTHER_EXP_PREFIX + codeSuffix;
      // T10：行级 expense_amount 为空 → 仍出条目但带 remark，让前端能区分"该料号无此项"vs"配置缺金额"
      String otherRemark =
          rate.getExpenseAmount() == null
              ? "lp_other_expense_rate.expense_amount 为空 (id=" + rate.getId() + ")"
              : null;
      items.add(
          buildItem(
              code,
              expenseType == null ? "其他费用" : expenseType,
              null,
              null,
              rate.getExpenseAmount(),
              otherRemark));
    }
    return items;
  }

  List<CostRunCostItemDto> buildAuxItems(Set<String> materialCodes) {
    return buildAuxItems(materialCodes, null);
  }

  List<CostRunCostItemDto> buildAuxItems(
      Set<String> materialCodes, Integer costYear, String businessUnitType) {
    return buildAuxItems(
        materialCodes, new CostSourceContext(costYear, normalizeBusinessUnit(businessUnitType)));
  }

  private List<CostRunCostItemDto> buildAuxItems(
      Set<String> materialCodes, CostSourceContext costSourceContext) {
    if (materialCodes == null || materialCodes.isEmpty()) {
      return Collections.emptyList();
    }
    boolean useCmsEffectiveOnly = hasCostSourceContext(costSourceContext);
    List<AuxCostItemDto> auxSubjects = new ArrayList<>();
    if (useCmsEffectiveOnly) {
      if (costSourceContext.costYear != null) {
        List<AuxCostItemDto> cmsItems =
            auxCostItemMapper.selectEffectiveAuxCostItems(
                costSourceContext.costYear, materialCodes, costSourceContext.businessUnitType);
        if (cmsItems != null) {
          for (AuxCostItemDto cmsItem : cmsItems) {
            if (isExcludedAuxSubject(cmsItem)) {
              continue;
            }
            auxSubjects.add(cmsItem);
          }
        }
      }
    } else {
      List<AuxCostItemDto> legacySubjects = auxCostItemMapper.selectByMaterialCodes(materialCodes);
      if (legacySubjects != null) {
        for (AuxCostItemDto subject : legacySubjects) {
          if (isExcludedAuxSubject(subject)) {
            continue;
          }
          auxSubjects.add(subject);
        }
      }
    }
    if (auxSubjects.isEmpty()) {
      return Collections.emptyList();
    }
    auxSubjects.sort(
        Comparator.comparing(
                (AuxCostItemDto subject) ->
                    subject.getDisplayOrder() == null ? Integer.MAX_VALUE : subject.getDisplayOrder())
            .thenComparing(
                subject -> {
                  String code = trimToNull(subject.getAuxSubjectCode());
                  return code == null ? "" : code;
                }));
    List<CostRunCostItemDto> items = new ArrayList<>();
    for (AuxCostItemDto subject : auxSubjects) {
      String code = trimToNull(subject.getAuxSubjectCode());
      String name = trimToNull(subject.getAuxSubjectName());
      BigDecimal unitPrice = subject.getUnitPrice();
      BigDecimal floatRate = subject.getFloatRate();
      if (code == null || name == null) {
        continue;
      }
      String amountCalcMode = trimToNull(subject.getAmountCalcMode());
      BigDecimal rateForDisplay = floatRate;
      BigDecimal amount = BigDecimal.ZERO;
      if (AUX_AMOUNT_MODE_DIRECT.equalsIgnoreCase(amountCalcMode)) {
        amount =
            unitPrice == null
                ? BigDecimal.ZERO
                : unitPrice.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        rateForDisplay = null;
      } else if (unitPrice != null && floatRate != null) {
        amount = unitPrice.multiply(floatRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      }
      items.add(buildItem(AUX_PREFIX + code, name, unitPrice, rateForDisplay, amount));
    }
    return items;
  }

  private LaborCostResult buildLaborCostResult(
      Set<String> materialCodes, List<SalaryCost> salaryCosts, CostSourceContext costSourceContext) {
    LaborCostResult result = new LaborCostResult();
    boolean useCmsEffectiveOnly = hasCostSourceContext(costSourceContext);
    Map<String, List<SalaryCost>> salaryByMaterial = groupSalaryCostsByMaterial(salaryCosts);
    Map<String, String> refMaterialByMaterial =
        useCmsEffectiveOnly
            ? Collections.emptyMap()
            : resolveSalaryRefMaterials(salaryCosts, materialCodes);
    Map<String, CmsCostSourceEffective> effectiveByTypeAndParent =
        loadSalaryEffectiveSources(materialCodes, refMaterialByMaterial, costSourceContext);

    for (String materialCode : materialCodes) {
      List<SalaryCost> costs = salaryByMaterial.getOrDefault(materialCode, Collections.emptyList());
      String lookupParentCode = refMaterialByMaterial.getOrDefault(materialCode, materialCode);
      CmsCostSourceEffective directEffective =
          effectiveByTypeAndParent.get(effectiveKey(CMS_SOURCE_TYPE_SALARY_DIRECT, lookupParentCode));
      CmsCostSourceEffective indirectEffective =
          effectiveByTypeAndParent.get(effectiveKey(CMS_SOURCE_TYPE_SALARY_INDIRECT, lookupParentCode));

      if (directEffective != null) {
        BigDecimal amount = nullToZero(directEffective.getAmountYuan());
        result.directTotal = result.directTotal.add(amount);
        addLaborMetadata(result.laborByUnit, costs, amount, BigDecimal.ZERO);
      } else if (useCmsEffectiveOnly) {
        result.missingDirectCodes.add(materialCode);
      } else {
        BigDecimal manualAmount = sumManualSalary(costs, true);
        result.directTotal = result.directTotal.add(manualAmount);
        addManualLaborByUnit(result.laborByUnit, costs, true);
        if (costSourceContext != null
            && costSourceContext.costYear != null
            && manualAmount.signum() == 0) {
          result.missingDirectCodes.add(materialCode);
        }
      }

      if (indirectEffective != null) {
        BigDecimal amount = nullToZero(indirectEffective.getAmountYuan());
        result.indirectTotal = result.indirectTotal.add(amount);
        addLaborMetadata(result.laborByUnit, costs, BigDecimal.ZERO, amount);
      } else if (useCmsEffectiveOnly) {
        result.missingIndirectCodes.add(materialCode);
      } else {
        BigDecimal manualAmount = sumManualSalary(costs, false);
        result.indirectTotal = result.indirectTotal.add(manualAmount);
        addManualLaborByUnit(result.laborByUnit, costs, false);
        if (costSourceContext != null
            && costSourceContext.costYear != null
            && manualAmount.signum() == 0) {
          result.missingIndirectCodes.add(materialCode);
        }
      }
    }

    if (!result.missingDirectCodes.isEmpty()) {
      result.directRemark =
          missingEffectiveRemark(costSourceContext.costYear, "直接人工工资", result.missingDirectCodes);
    }
    if (!result.missingIndirectCodes.isEmpty()) {
      result.indirectRemark =
          missingEffectiveRemark(costSourceContext.costYear, "辅助员工工资", result.missingIndirectCodes);
    }
    return result;
  }

  private Map<String, String> resolveSalaryRefMaterials(
      List<SalaryCost> salaryCosts, Set<String> materialCodes) {
    Map<String, String> result = new HashMap<>();
    if (salaryCosts == null || materialCodes == null || materialCodes.isEmpty()) {
      return result;
    }
    for (SalaryCost cost : salaryCosts) {
      String materialCode = trimToNull(cost.getMaterialCode());
      String refMaterialCode = trimToNull(cost.getRefMaterialCode());
      if (materialCode != null && refMaterialCode != null && materialCodes.contains(materialCode)) {
        result.putIfAbsent(materialCode, refMaterialCode);
      }
    }
    return result;
  }

  private Map<String, List<SalaryCost>> groupSalaryCostsByMaterial(List<SalaryCost> salaryCosts) {
    Map<String, List<SalaryCost>> result = new HashMap<>();
    if (salaryCosts == null) {
      return result;
    }
    for (SalaryCost cost : salaryCosts) {
      String materialCode = trimToNull(cost.getMaterialCode());
      if (materialCode == null) {
        continue;
      }
      result.computeIfAbsent(materialCode, ignored -> new ArrayList<>()).add(cost);
    }
    return result;
  }

  private Map<String, CmsCostSourceEffective> loadSalaryEffectiveSources(
      Set<String> materialCodes,
      Map<String, String> refMaterialByMaterial,
      CostSourceContext costSourceContext) {
    if (costSourceContext == null
        || costSourceContext.costYear == null
        || materialCodes == null
        || materialCodes.isEmpty()) {
      return Collections.emptyMap();
    }
    Set<String> lookupCodes = new LinkedHashSet<>(materialCodes);
    if (refMaterialByMaterial != null) {
      lookupCodes.addAll(refMaterialByMaterial.values());
    }
    List<CmsCostSourceEffective> rows =
        cmsCostSourceEffectiveMapper.selectList(
            Wrappers.lambdaQuery(CmsCostSourceEffective.class)
                .eq(CmsCostSourceEffective::getCostYear, costSourceContext.costYear)
                .in(
                    CmsCostSourceEffective::getSourceType,
                    CMS_SOURCE_TYPE_SALARY_DIRECT,
                    CMS_SOURCE_TYPE_SALARY_INDIRECT)
                .in(CmsCostSourceEffective::getParentCode, lookupCodes)
                .eq(CmsCostSourceEffective::getBusinessUnitType, costSourceContext.businessUnitType));
    if (rows == null || rows.isEmpty()) {
      return Collections.emptyMap();
    }
    Map<String, CmsCostSourceEffective> result = new HashMap<>();
    for (CmsCostSourceEffective row : rows) {
      String sourceType = trimToNull(row.getSourceType());
      String parentCode = trimToNull(row.getParentCode());
      if (sourceType != null && parentCode != null) {
        result.put(effectiveKey(sourceType, parentCode), row);
      }
    }
    return result;
  }

  private BigDecimal sumManualSalary(List<SalaryCost> costs, boolean direct) {
    BigDecimal sum = BigDecimal.ZERO;
    for (SalaryCost cost : costs) {
      if (isCmsSource(cost.getSource())) {
        continue;
      }
      sum = sum.add(nullToZero(direct ? cost.getDirectLaborCost() : cost.getIndirectLaborCost()));
    }
    return sum;
  }

  private void addManualLaborByUnit(
      Map<String, LaborSum> laborByUnit, List<SalaryCost> costs, boolean direct) {
    for (SalaryCost cost : costs) {
      if (isCmsSource(cost.getSource())) {
        continue;
      }
      String businessUnit = trimToNull(cost.getBusinessUnit());
      if (businessUnit == null) {
        continue;
      }
      LaborSum sum = laborByUnit.computeIfAbsent(businessUnit, ignored -> new LaborSum());
      if (direct) {
        sum.direct = sum.direct.add(nullToZero(cost.getDirectLaborCost()));
      } else {
        sum.indirect = sum.indirect.add(nullToZero(cost.getIndirectLaborCost()));
      }
    }
  }

  private void addLaborMetadata(
      Map<String, LaborSum> laborByUnit,
      List<SalaryCost> costs,
      BigDecimal directAmount,
      BigDecimal indirectAmount) {
    String businessUnit = null;
    for (SalaryCost cost : costs) {
      businessUnit = trimToNull(cost.getBusinessUnit());
      if (businessUnit != null) {
        break;
      }
    }
    if (businessUnit == null) {
      return;
    }
    LaborSum sum = laborByUnit.computeIfAbsent(businessUnit, ignored -> new LaborSum());
    sum.direct = sum.direct.add(nullToZero(directAmount));
    sum.indirect = sum.indirect.add(nullToZero(indirectAmount));
  }

  private String missingEffectiveRemark(
      Integer costYear, String costName, List<String> materialCodes) {
    String yearText = costYear == null ? "当前成本年度" : costYear + " 年";
    return "未找到 "
        + yearText
        + " "
        + materialCodes
        + " 的 CMS 公共生效"
        + costName
        + "，请检查 CMS 已审批数据或刷新公共生效来源";
  }

  private String effectiveKey(String sourceType, String parentCode) {
    return sourceType + "|" + parentCode;
  }

  /**
   * T11：拆 part 金额为 (非包装件总额, 包装件总额)。
   *
   * <p>判定方式：按 part_code 反查 lp_material_master.cost_element，等于
   * {@link #COST_ELEMENT_PACKAGE} 的归到 packageTotal，其余归 nonPackageTotal。
   * 主档查不到的部品默认归非包装（保守 — 缺主档不当包装）。
   *
   * <p>调用侧用 nonPackageTotal 算 materialTotal，packageTotal 单独成 OTHER_EXP_PACKAGE 项，
   * 避免双计入 totalAmount。
   */
  /** 包私有以便单测覆盖；非包外调用面 */
  PartTotalSplit splitPartAmount(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return new PartTotalSplit(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    List<CostRunPartItem> items =
        costRunPartItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunPartItem.class)
                .eq(CostRunPartItem::getOaNo, oaNo)
                .eq(CostRunPartItem::getProductCode, productCode));
    if (items == null || items.isEmpty()) {
      return new PartTotalSplit(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    Set<String> partCodes = new LinkedHashSet<>();
    for (CostRunPartItem item : items) {
      if (StringUtils.hasText(item.getPartCode())) {
        partCodes.add(item.getPartCode().trim());
      }
    }
    Set<String> packageCodes = lookupPackageCodes(partCodes);
    BigDecimal pkg = BigDecimal.ZERO;
    BigDecimal nonPkg = BigDecimal.ZERO;
    for (CostRunPartItem item : items) {
      if (item.getAmount() == null) {
        continue;
      }
      String code = item.getPartCode() == null ? null : item.getPartCode().trim();
      if (code != null && packageCodes.contains(code)) {
        pkg = pkg.add(item.getAmount());
      } else {
        nonPkg = nonPkg.add(item.getAmount());
      }
    }
    return new PartTotalSplit(nonPkg, pkg);
  }

  /** T11：从主档批量查 cost_element='主要材料-包装材料' 的 material_code 集合。 */
  private Set<String> lookupPackageCodes(Set<String> partCodes) {
    if (partCodes == null || partCodes.isEmpty()) {
      return Collections.emptySet();
    }
    List<MaterialMaster> rows =
        materialMasterMapper.selectList(
            Wrappers.lambdaQuery(MaterialMaster.class)
                .in(MaterialMaster::getMaterialCode, partCodes)
                .eq(MaterialMaster::getCostElement, COST_ELEMENT_PACKAGE));
    if (rows == null || rows.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (MaterialMaster m : rows) {
      if (m.getMaterialCode() != null) {
        result.add(m.getMaterialCode());
      }
    }
    return result;
  }

  /**
   * T11：从 OA 行级 shipping_fee 取运费；按 (oa_no, productCode) 命中行求和。
   * OA 多产品时只取 productCode 对应行；空/0 都返 0（不当 miss）。
   */
  /** 包私有以便单测覆盖 */
  BigDecimal lookupFreight(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return BigDecimal.ZERO;
    }
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, oaNo)
                .last("LIMIT 1"));
    if (form == null) {
      return BigDecimal.ZERO;
    }
    List<OaFormItem> rows =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class)
                .eq(OaFormItem::getOaFormId, form.getId())
                .eq(OaFormItem::getMaterialNo, productCode));
    if (rows == null || rows.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (OaFormItem r : rows) {
      if (r.getShippingFee() != null) {
        total = total.add(r.getShippingFee());
      }
    }
    return total;
  }

  /** T11：part 金额拆分元组（非包装/包装），用于材料费 vs 包装费分流；包私有以便单测断言字段 */
  record PartTotalSplit(BigDecimal nonPackageTotal, BigDecimal packageTotal) {}

  private CostRunCostItemDto buildItem(
      String code, String name, BigDecimal baseAmount, BigDecimal rate, BigDecimal amount) {
    return buildItem(code, name, baseAmount, rate, amount, null);
  }

  /** T10：6 参重载，remark 非空时表示该费用项有"缺率/异常"说明，前端可展示告警。 */
  private CostRunCostItemDto buildItem(
      String code,
      String name,
      BigDecimal baseAmount,
      BigDecimal rate,
      BigDecimal amount,
      String remark) {
    CostRunCostItemDto dto = new CostRunCostItemDto();
    dto.setCostCode(code);
    dto.setCostName(name);
    dto.setBaseAmount(baseAmount);
    dto.setRate(rate);
    dto.setAmount(amount);
    dto.setRemark(remark);
    return dto;
  }

  private BigDecimal nullToZero(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    String value = trimToNull(businessUnitType);
    return value == null ? "" : value;
  }

  private boolean isCmsSource(String source) {
    return CMS_SOURCE.equalsIgnoreCase(trimToNull(source))
        || CMS_SOURCE_EFFECTIVE.equalsIgnoreCase(trimToNull(source));
  }

  private boolean hasCostSourceContext(CostSourceContext costSourceContext) {
    return costSourceContext != null;
  }

  private boolean isExcludedAuxSubject(AuxCostItemDto subject) {
    return EXCLUDED_AUX_SUBJECT_PACKAGING.equals(
        trimToNull(subject == null ? null : subject.getAuxSubjectName()));
  }

  private boolean isPositive(BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0;
  }

  private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
    if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    return numerator.divide(denominator, AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal multiplyRate(BigDecimal rate, BigDecimal upliftRate) {
    if (rate == null) {
      return null;
    }
    if (upliftRate == null) {
      return rate;
    }
    return rate.multiply(upliftRate);
  }

  private BigDecimal multiplyAmount(BigDecimal base, BigDecimal rate) {
    if (base == null || rate == null) {
      return BigDecimal.ZERO;
    }
    return base.multiply(rate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Task #9：按产品料号查 lp_product_property.coefficient（标准品=1）。
   *
   * <p>口径：parentCode 命中即用其 coefficient；查多条按 id 倒序取最新；查不到时回落到 1
   * （标准品语义，与 V11 DEFAULT 1.0000 对齐），同时打 debug 日志方便定位脏数据。包私有便于单测。
   */
  BigDecimal lookupProductCoefficient(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return BigDecimal.ONE;
    }
    // T19：走 cached lookup
    ProductProperty property = cacheLookup.findProductProperty(productCode);
    if (property == null || property.getCoefficient() == null) {
      log.debug("产品属性系数未命中，回落=1: productCode={}", productCode);
      return BigDecimal.ONE;
    }
    return property.getCoefficient();
  }

  private static class LaborSum {
    private BigDecimal direct = BigDecimal.ZERO;
    private BigDecimal indirect = BigDecimal.ZERO;
  }

  private static class LaborCostResult {
    private BigDecimal directTotal = BigDecimal.ZERO;
    private BigDecimal indirectTotal = BigDecimal.ZERO;
    private final Map<String, LaborSum> laborByUnit = new LinkedHashMap<>();
    private final List<String> missingDirectCodes = new ArrayList<>();
    private final List<String> missingIndirectCodes = new ArrayList<>();
    private String directRemark;
    private String indirectRemark;
  }

  private static class CostSourceContext {
    private final Integer costYear;
    private final String businessUnitType;

    private CostSourceContext(Integer costYear, String businessUnitType) {
      this.costYear = costYear;
      this.businessUnitType = businessUnitType == null ? "" : businessUnitType;
    }
  }

  private static class DepartmentFeeResult {
    private BigDecimal baseAmount;
    private BigDecimal overhaulRate;
    private BigDecimal toolingRepairRate;
    private BigDecimal waterPowerRate;
    private BigDecimal otherRate;
    private BigDecimal overhaul = BigDecimal.ZERO;
    private BigDecimal toolingRepair = BigDecimal.ZERO;
    private BigDecimal waterPower = BigDecimal.ZERO;
    private BigDecimal other = BigDecimal.ZERO;
    /** T10：部门经费率缺失说明（4 项 OVERHAUL/TOOLING_REPAIR/WATER_POWER/DEPT_OTHER 共用） */
    private String remark;
  }
}
