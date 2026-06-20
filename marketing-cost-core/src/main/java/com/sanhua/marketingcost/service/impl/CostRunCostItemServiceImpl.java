package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
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
import com.sanhua.marketingcost.entity.ThreeExpenseDimensionMapping;
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
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import com.sanhua.marketingcost.service.CmsCostEffectiveSourceEnsureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.time.Year;
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
  private static final BigDecimal CMS_AUX_UPLIFT_RATE = new BigDecimal("1.05");
  private static final String EXCLUDED_AUX_SUBJECT_PACKAGING = "包装辅料";
  private static final String OTHER_EXP_PREFIX = "OTHER_EXP_";
  /** T11：包装费固定 cost_code，区别于 lp_other_expense_rate 的 OTHER_EXP_<id> 系列 */
  private static final String OTHER_EXP_PACKAGE = "OTHER_EXP_PACKAGE";
  /** T11：运费固定 cost_code，取 oa_form_item.shipping_fee */
  private static final String OTHER_EXP_FREIGHT = "OTHER_EXP_FREIGHT";
  /** T11：lp_material_master.cost_element 表示包装材料的固定文本（U9 同步上来的中文枚举值） */
  private static final String COST_ELEMENT_PACKAGE = "主要材料-包装材料";
  /** 报价净损失率匹配层级：产品料号。 */
  private static final String LOSS_MATCH_LEVEL_MATERIAL_CODE = "MATERIAL_CODE";
  /** 报价净损失率匹配层级：产品型号。 */
  private static final String LOSS_MATCH_LEVEL_MATERIAL_MODEL = "MATERIAL_MODEL";
  /** 制造费用率匹配层级：料号。 */
  private static final String MANUFACTURE_MATCH_LEVEL_MATERIAL_CODE = "MATERIAL_CODE";
  /** 制造费用率匹配层级：产品型号。 */
  private static final String MANUFACTURE_MATCH_LEVEL_MATERIAL_MODEL = "MATERIAL_MODEL";
  /** 制造费用率匹配层级：事业部 + 产品名称。 */
  private static final String MANUFACTURE_MATCH_LEVEL_DIVISION_PRODUCT_NAME = "DIVISION_PRODUCT_NAME";
  /** 制造费用率匹配层级：事业部。 */
  private static final String MANUFACTURE_MATCH_LEVEL_DIVISION = "DIVISION";
  private static final String MANUFACTURE_MATCH_KEY_SEPARATOR = "::";
  private static final List<String> DEPARTMENT_SUBJECT_OVERHAUL = List.of("大修费用", "大修费");
  private static final List<String> DEPARTMENT_SUBJECT_TOOLING =
      List.of("工装零星费用", "工装零星修理费", "零星工装费用", "零星工装费");
  private static final List<String> DEPARTMENT_SUBJECT_WATER = List.of("水电费用", "水电费", "水电");
  private static final List<String> DEPARTMENT_SUBJECT_OTHER = List.of("其他费用");
  private static final String THREE_EXPENSE_DIM_COMPANY = "COMPANY";
  private static final String THREE_EXPENSE_DIM_DIVISION = "PRODUCTION_DIVISION";
  private static final String THREE_EXPENSE_DIM_DEPARTMENT = "DEPARTMENT";
  private static final String THREE_EXPENSE_DIM_OFFICE = "OFFICE";
  private static final String THREE_EXPENSE_SOURCE_SYSTEM_OA = "OA";
  private static final DateTimeFormatter ACCOUNTING_MONTH_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM");

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
   * <p>包装组件父件金额已经由包装价格服务按子件价和 U9 母件底数折算完成；见机表只叠加 5%。
   * <b>TODO</b> 业务方拍板后改为从 lp_product_property / 配置表读取（v1-business-followup #T24）。
   */
  private static final BigDecimal PACKAGE_COEFFICIENT = new BigDecimal("1.05");
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
   * <p>Excel 见机表3 的口径是「水电费独立列报，同时进入材料费基数」，故默认 true。开关保留是为了
   * 历史报表回溯时可以临时恢复「水电不进材料费」口径。
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
      @Value("${cost.material.includeWaterPower:true}") boolean includeWaterPowerInMaterial) {
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
    return calculateItems(oaNoValue, productCodeValue, materialCodes, costSourceContext, null, true);
  }

  @Override
  public List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode) {
    // T24：单参签名 = 兼容旧调用，默认只返 EXPENSE（传统费用项），不暴露 BOM_BUCKET 行
    return listStoredByOaNo(oaNo, productCode, CostItemCategory.EXPENSE);
  }

  @Override
  public List<CostRunCostItemDto> listStoredByCostRunNo(String costRunNo) {
    return listStoredByCostRunNo(costRunNo, CostItemCategory.EXPENSE);
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
    return toDtos(stored);
  }

  @Override
  public List<CostRunCostItemDto> listStoredByCostRunNo(String costRunNo, String category) {
    if (!StringUtils.hasText(costRunNo)) {
      return Collections.emptyList();
    }
    String catFilter = StringUtils.hasText(category) ? category.trim() : null;
    List<CostRunCostItem> stored =
        costRunCostItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunCostItem.class)
                .eq(CostRunCostItem::getCostRunNo, costRunNo.trim())
                .eq(catFilter != null, CostRunCostItem::getCategory, catFilter)
                .orderByAsc(CostRunCostItem::getLineNo));
    return toDtos(stored);
  }

  private List<CostRunCostItemDto> toDtos(List<CostRunCostItem> stored) {
    if (stored.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunCostItemDto> items = new ArrayList<>();
    for (CostRunCostItem item : stored) {
      CostRunCostItemDto dto = new CostRunCostItemDto();
      dto.setId(item.getId());
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
    return listByMaterialCodes(oaNo, productCode, materialCodes, null, true, progress);
  }

  @Override
  public List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo,
      String productCode,
      Set<String> materialCodes,
      List<CostRunPartItemDto> currentPartItems,
      boolean persistDailyResult,
      java.util.function.IntConsumer progress) {
    return listByMaterialCodes(
        oaNo, productCode, materialCodes, null, currentPartItems, persistDailyResult, progress);
  }

  @Override
  public List<CostRunCostItemDto> listByMaterialCodes(
      String oaNo,
      String productCode,
      Set<String> materialCodes,
      CostRunContext context,
      List<CostRunPartItemDto> currentPartItems,
      boolean persistDailyResult,
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
            resolveCostSourceContext(oaNo.trim(), productCode.trim(), context),
            currentPartItems,
            persistDailyResult);
    progress.accept(100);
    return result;
  }

  private List<CostRunCostItemDto> calculateItems(
      String oaNoValue,
      String productCodeValue,
      Set<String> materialCodes,
      CostSourceContext costSourceContext,
      List<CostRunPartItemDto> currentPartItems,
      boolean persistDailyResult) {
    ensureCmsCostSources(costSourceContext);
    List<SalaryCost> salaryCosts =
        salaryCostMapper.selectList(
            Wrappers.lambdaQuery(SalaryCost.class).in(SalaryCost::getMaterialCode, materialCodes));

    // 1) 先算人工 -> 部门经费。正式核算有成本年度时，工资金额只取 CMS 公共生效来源。
    LaborCostResult laborResult = buildLaborCostResult(materialCodes, salaryCosts, costSourceContext);
    BigDecimal directTotal = laborResult.directTotal;
    BigDecimal indirectTotal = laborResult.indirectTotal;

    DepartmentFeeResult feeResult =
        calculateDepartmentFees(productCodeValue, directTotal.add(indirectTotal), costSourceContext);

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
    //    Task #9：水电费默认计入材料费（与 Excel 见机表3 口径对齐），
    //    通过 cost.material.includeWaterPower=false 保留历史口径回溯能力。
    //    包装组件已在部品取价阶段按子件汇总价 / 母件底数折算成父件金额；
    //    见机表只需对该包装父件金额乘 1.05。
    PartTotalSplit partSplit = splitPartAmount(oaNoValue, productCodeValue, currentPartItems);
    BigDecimal partTotal = partSplit.nonPackageTotal();
    BigDecimal rawPackageAmount = partSplit.packageTotal();
    BigDecimal packageBucketAmount =
        calculatePackageBucketAmount(oaNoValue, productCodeValue, currentPartItems);
    BigDecimal packageAmount =
        packageBucketAmount.signum() > 0 ? packageBucketAmount : rawPackageAmount;
    BigDecimal freightAmount = lookupFreight(oaNoValue, productCodeValue);
    // 包装进 materialTotal；优先用包装组件父件新口径，取不到包装组件数据时退回原始包装件金额。
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
    RateLookup lossLookup = findLossRate(productCodeValue, costSourceContext);
    BigDecimal lossRate = lossLookup.rate();
    String lossRemark = lossLookup.remark();
    BigDecimal lossBase = materialTotal.add(directTotal).add(indirectTotal);
    BigDecimal lossAmount =
        lossRate == null
            ? null
            : lossBase.multiply(lossRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    RateLookup manufactureLookup =
        findManufactureRate(productCodeValue, materialCodes, costSourceContext);
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
    ProductCoefficientLookup coefficientLookup =
        lookupProductCoefficient(productCodeValue, costSourceContext);
    BigDecimal coefficient = coefficientLookup.coefficient();
    String adjustedManufactureRemark =
        joinRemarks(manufactureRemark, coefficientLookup.remark());
    BigDecimal adjustedManufactureCost =
        manufactureCost == null
            ? null
            : manufactureCost.multiply(coefficient).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

    ThreeExpenseLookup threeExpLookup = findThreeExpenseRate(costSourceContext);
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
        adjustedManufactureRemark));
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
    //   - 包装 BUCKET_PACKAGE: Σ(包装组件父件 amount) × 1.05
    List<CostRunCostItemDto> bucketItems =
        buildBucketItems(oaNoValue, productCodeValue, currentPartItems);
    for (CostRunCostItemDto b : bucketItems) {
      items.add(b);
      if (StringUtils.hasText(b.getCostCode())) {
        costCodes.add(b.getCostCode());
      }
    }

    if (persistDailyResult) {
      saveCostRunItems(oaNoValue, productCodeValue, items, costCodes);
    }
    return items;
  }

  /**
   * T24：构建见机表原材料汇总行（BOM_BUCKET）。
   *
   * <p>本期范围：焊料 + 包装两类。其他 cost_element 桶后续迭代再加。
   *
   * <p>设计文档：docs/cost-bucket-aggregation-20260501-design.md
   */
  private List<CostRunCostItemDto> buildBucketItems(
      String oaNoValue, String productCodeValue, List<CostRunPartItemDto> currentPartItems) {
    List<CostRunCostItemDto> result = new ArrayList<>();

    // 焊料：从 part_item join material_master 按 cost_element 聚合
    BigDecimal weldSum =
        sumPartByCostElement(oaNoValue, productCodeValue, COST_ELEMENT_WELD, currentPartItems);
    if (weldSum != null && weldSum.signum() > 0) {
      result.add(buildBucketItem(BUCKET_WELD, "焊料", weldSum));
    }

    BigDecimal pkgAmount =
        calculatePackageBucketAmount(oaNoValue, productCodeValue, currentPartItems);
    if (pkgAmount != null && pkgAmount.signum() > 0) {
      result.add(buildBucketItem(BUCKET_PACKAGE, "包装", pkgAmount));
    }

    return result;
  }

  /** 见机表包装金额 = 包装组件父件金额 × 1.05，用于材料费和 BOM_BUCKET_PACKAGE。 */
  BigDecimal calculatePackageBucketAmount(String oaNoValue, String productCodeValue) {
    return calculatePackageBucketAmount(oaNoValue, productCodeValue, null);
  }

  private BigDecimal calculatePackageBucketAmount(
      String oaNoValue, String productCodeValue, List<CostRunPartItemDto> currentPartItems) {
    BigDecimal packageParentAmount =
        sumPackageComponentParentAmount(oaNoValue, productCodeValue, currentPartItems);
    if (packageParentAmount == null || packageParentAmount.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return packageParentAmount.multiply(PACKAGE_COEFFICIENT).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 按包装组件父件聚合 part_item.amount。
   *
   * <p>包装父件自身的 unit_price 已由 PackageComponentPriceService 按子件价、子件用量和
   * U9 母件底数折算完成；这里不再展开子件，也不再硬编码 /12。
   */
  BigDecimal sumPackageComponentParentAmount(String oaNo, String productCode) {
    return sumPackageComponentParentAmount(oaNo, productCode, null);
  }

  private BigDecimal sumPackageComponentParentAmount(
      String oaNo, String productCode, List<CostRunPartItemDto> currentPartItems) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return BigDecimal.ZERO;
    }
    if (currentPartItems != null) {
      return sumPackageComponentParentAmount(currentPartItems);
    }
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
    Set<String> packageParentCodes = lookupPackageComponentParentCodes(partCodes);
    if (packageParentCodes.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (CostRunPartItem p : parts) {
      String code = p.getPartCode() == null ? null : p.getPartCode().trim();
      if (code != null && packageParentCodes.contains(code) && p.getAmount() != null) {
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
    return sumPartByCostElement(oaNo, productCode, targetCostElement, null);
  }

  private BigDecimal sumPartByCostElement(
      String oaNo,
      String productCode,
      String targetCostElement,
      List<CostRunPartItemDto> currentPartItems) {
    if (currentPartItems != null) {
      return sumPartByCostElement(currentPartItems, targetCostElement);
    }
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

  private BigDecimal sumPackageComponentParentAmount(List<CostRunPartItemDto> currentPartItems) {
    if (currentPartItems == null || currentPartItems.isEmpty()) {
      return BigDecimal.ZERO;
    }
    Set<String> partCodes = collectPartCodes(currentPartItems);
    Set<String> packageParentCodes = lookupPackageComponentParentCodes(partCodes);
    if (packageParentCodes.isEmpty()) {
      return BigDecimal.ZERO;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (CostRunPartItemDto item : currentPartItems) {
      String code = item.getPartCode() == null ? null : item.getPartCode().trim();
      if (code != null && packageParentCodes.contains(code) && item.getAmount() != null) {
        sum = sum.add(item.getAmount());
      }
    }
    return sum.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private BigDecimal sumPartByCostElement(
      List<CostRunPartItemDto> currentPartItems, String targetCostElement) {
    if (currentPartItems == null || currentPartItems.isEmpty()) {
      return BigDecimal.ZERO;
    }
    Set<String> partCodes = collectPartCodes(currentPartItems);
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
    for (MaterialMaster master : masters) {
      if (StringUtils.hasText(master.getMaterialCode())) {
        targetCodes.add(master.getMaterialCode().trim());
      }
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (CostRunPartItemDto item : currentPartItems) {
      String code = item.getPartCode() == null ? null : item.getPartCode().trim();
      if (code != null && targetCodes.contains(code) && item.getAmount() != null) {
        sum = sum.add(item.getAmount());
      }
    }
    return sum.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private Set<String> collectPartCodes(List<CostRunPartItemDto> currentPartItems) {
    Set<String> partCodes = new LinkedHashSet<>();
    if (currentPartItems == null) {
      return partCodes;
    }
    for (CostRunPartItemDto item : currentPartItems) {
      if (item != null && StringUtils.hasText(item.getPartCode())) {
        partCodes.add(item.getPartCode().trim());
      }
    }
    return partCodes;
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
    return resolveCostSourceContext(oaNo, productCode, null);
  }

  private CostSourceContext resolveCostSourceContext(
      String oaNo, String productCode, CostRunContext context) {
    if (!StringUtils.hasText(oaNo)) {
      return new CostSourceContext(resolveCostYear(context), "");
    }
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, oaNo)
                .last("LIMIT 1"));
    if (form == null) {
      return new CostSourceContext(resolveCostYear(context), "");
    }
    List<OaFormItem> rows =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class)
                .eq(OaFormItem::getOaFormId, form.getId())
                .eq(StringUtils.hasText(productCode), OaFormItem::getMaterialNo, productCode));
    return resolveCostSourceContext(form, rows, productCode, context);
  }

  private CostSourceContext resolveCostSourceContext(
      OaForm form, List<OaFormItem> formItems, String productCode) {
    return resolveCostSourceContext(form, formItems, productCode, null);
  }

  private CostSourceContext resolveCostSourceContext(
      OaForm form, List<OaFormItem> formItems, String productCode, CostRunContext context) {
    // 成本核算年度按核算执行时间取，不按 OA 申请日期取。
    Integer costYear = resolveCostYear(context);
    String businessUnitType = null;
    String productName = null;
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
        if (businessUnitType == null) {
          businessUnitType = trimToNull(item.getBusinessUnitType());
        }
        if (productName == null) {
          productName = trimToNull(item.getProductName());
        }
        if (businessUnitType != null && productName != null) {
          break;
        }
      }
    }
    if (businessUnitType == null && form != null) {
      businessUnitType = trimToNull(form.getBusinessUnitType());
    }
    String accountingPeriodMonth = null;
    String sourceSystem = null;
    String sourceCompany = null;
    String sourceBusinessDivision = null;
    String applicantDept = null;
    String applicantOffice = null;
    String expenseProductCategory = null;
    ThreeExpenseMatchContext threeExpenseMatchContext = buildThreeExpenseMatchContext(form, costYear);
    if (form != null) {
      accountingPeriodMonth = trimToNull(form.getAccountingPeriodMonth());
      if (accountingPeriodMonth == null && form.getApplyDate() != null) {
        accountingPeriodMonth = ACCOUNTING_MONTH_FORMATTER.format(form.getApplyDate());
      }
      sourceSystem = trimToNull(form.getSourceSystem());
      sourceCompany = trimToNull(form.getSourceCompany());
      sourceBusinessDivision = trimToNull(form.getSourceBusinessDivision());
      applicantDept = trimToNull(form.getApplicantDept());
      applicantOffice = trimToNull(form.getApplicantOffice());
      expenseProductCategory = trimToNull(form.getExpenseProductCategory());
    }
    return new CostSourceContext(
        costYear,
        normalizeBusinessUnit(businessUnitType),
        MaterialOrganization.forQuoteProcess(
            form == null ? null : form.getProcessCode(),
            form == null ? null : form.getOaNo(),
            productName),
        accountingPeriodMonth,
        sourceSystem,
        sourceCompany,
        sourceBusinessDivision,
        applicantDept,
        applicantOffice,
        expenseProductCategory,
        threeExpenseMatchContext);
  }

  ThreeExpenseMatchContext buildThreeExpenseMatchContext(OaForm form) {
    return buildThreeExpenseMatchContext(form, currentCostYear());
  }

  ThreeExpenseMatchContext buildThreeExpenseMatchContext(OaForm form, Integer costYear) {
    // 三项费用按成本核算当天年份取，不按 OA 申请日期。
    Integer periodYear = costYear == null ? currentCostYear() : costYear;
    if (form == null) {
      return new ThreeExpenseMatchContext(periodYear, null, null, null, null, null, null, null, null,
          "OA 表头为空");
    }
    String oaNo = trimToNull(form.getOaNo());
    String processCode = trimToNull(form.getProcessCode());
    String businessUnitType = normalizeBusinessUnit(form.getBusinessUnitType());
    String productCategory = resolveThreeExpenseProductCategory(processCode, oaNo, businessUnitType);
    // 不同 OA 单据字段名不同：FI-SC-020/FI-SC-006 取“事业部”，FI-SC-005 取“产品事业部”，家用取“所属事业部”；入库后统一落在 sourceBusinessDivision。
    String sourceDivision = trimToNull(form.getSourceBusinessDivision());
    String productLine = resolveThreeExpenseProductLine(sourceDivision);
    String sourceDepartment = trimToNull(form.getApplicantDept());
    String sourceOffice = normalizeOfficeForThreeExpense(form.getApplicantOffice());
    String commercialOverseasFlag = null;
    String homeApplianceSalesModeFlag = null;
    String matchedDepartment = null;
    String departmentMatchBase = StringUtils.hasText(sourceOffice) ? sourceOffice : sourceDepartment;
    if (StringUtils.hasText(departmentMatchBase) && StringUtils.hasText(productCategory)) {
      boolean flag = yesFlag(form.getOverseasSalesMode());
      if ("商用直销产品".equals(productCategory)) {
        commercialOverseasFlag = flag ? "是" : "否";
        matchedDepartment = departmentMatchBase + (flag ? "-海外" : "-直销");
      } else {
        homeApplianceSalesModeFlag = flag ? "是" : "否";
        matchedDepartment = departmentMatchBase + (flag ? "-代销" : "-直销");
      }
    }
    String missingReason = null;
    if (!StringUtils.hasText(productCategory)) {
      missingReason = "缺少流程编号或业务单元，无法推导产品口径";
    } else if (!StringUtils.hasText(sourceDivision)) {
      missingReason = "缺少事业部字段，无法推导产线";
    } else if (!StringUtils.hasText(departmentMatchBase)) {
      missingReason = "缺少申请部门或申请处室，无法拼接匹配部门";
    }
    return new ThreeExpenseMatchContext(
        periodYear,
        oaNo,
        productCategory,
        productLine,
        sourceDepartment,
        sourceOffice,
        commercialOverseasFlag,
        homeApplianceSalesModeFlag,
        matchedDepartment,
        missingReason);
  }

  private String resolveThreeExpenseProductCategory(
      String processCode, String oaNo, String businessUnitType) {
    String process = StringUtils.hasText(processCode) ? processCode : oaNo;
    if (StringUtils.hasText(process)) {
      return process.trim().startsWith("FI-SC") ? "商用直销产品" : "家代商代销产品";
    }
    if ("HOUSEHOLD".equals(businessUnitType)) {
      return "家代商代销产品";
    }
    if ("COMMERCIAL".equals(businessUnitType)) {
      return "商用直销产品";
    }
    return null;
  }

  private String resolveThreeExpenseProductLine(String division) {
    if (!StringUtils.hasText(division)) {
      return null;
    }
    String text = division.trim();
    if (text.contains("越南")) {
      return "越南事业部";
    }
    if (text.contains("墨西哥")) {
      return "墨西哥产线";
    }
    return "国内产线";
  }

  private String normalizeOfficeForThreeExpense(String office) {
    String value = trimToNull(office);
    return "/".equals(value) ? "" : value;
  }

  private boolean yesFlag(String value) {
    if (!StringUtils.hasText(value)) {
      return false;
    }
    String text = value.trim();
    return "是".equals(text)
        || "Y".equalsIgnoreCase(text)
        || "YES".equalsIgnoreCase(text)
        || "TRUE".equalsIgnoreCase(text)
        || "1".equals(text);
  }

  private void ensureCmsCostSources(CostSourceContext costSourceContext) {
    if (costSourceContext == null || costSourceContext.costYear == null) {
      return;
    }
    cmsCostEffectiveSourceEnsureService.ensureDefaultSources(
        costSourceContext.costYear, CMS_AUTO_OPERATOR, costSourceContext.businessUnitType);
  }

  int currentCostYear() {
    return Year.now().getValue();
  }

  private Integer resolveCostYear(CostRunContext context) {
    if (context != null && StringUtils.hasText(context.getPricingMonth())) {
      String pricingMonth = context.getPricingMonth().trim();
      if (pricingMonth.length() >= 4) {
        try {
          return Integer.parseInt(pricingMonth.substring(0, 4));
        } catch (NumberFormatException ignored) {
          // malformed legacy context falls back to runtime year
        }
      }
    }
    return currentCostYear();
  }

  private DepartmentFeeResult calculateDepartmentFees(
      String finishedProductCode, BigDecimal laborTotal, CostSourceContext costSourceContext) {
    DepartmentFeeResult result = new DepartmentFeeResult();
    if (laborTotal == null || laborTotal.signum() == 0) {
      // T10：无人工数据 → 4 项部门费率项都不会算出来
      result.remark = "无人工数据，无法取部门经费率";
      return result;
    }

    String businessUnitType =
        costSourceContext == null ? "" : normalizeBusinessUnit(costSourceContext.businessUnitType);
    Integer rateYear =
        costSourceContext == null || costSourceContext.costYear == null
            ? null
            : costSourceContext.costYear;

    DepartmentSubjectAmount overhaul =
        calculateDepartmentSubjectAmount(
            finishedProductCode,
            rateYear,
            businessUnitType,
            costSourceContext,
            DEPARTMENT_SUBJECT_OVERHAUL,
            laborTotal);
    DepartmentSubjectAmount tooling =
        calculateDepartmentSubjectAmount(
            finishedProductCode,
            rateYear,
            businessUnitType,
            costSourceContext,
            DEPARTMENT_SUBJECT_TOOLING,
            laborTotal);
    DepartmentSubjectAmount water =
        calculateDepartmentSubjectAmount(
            finishedProductCode,
            rateYear,
            businessUnitType,
            costSourceContext,
            DEPARTMENT_SUBJECT_WATER,
            laborTotal);
    DepartmentSubjectAmount other =
        calculateDepartmentSubjectAmount(
            finishedProductCode,
            rateYear,
            businessUnitType,
            costSourceContext,
            DEPARTMENT_SUBJECT_OTHER,
            laborTotal);

    result.overhaul = amountOrZero(overhaul.amount());
    result.toolingRepair = amountOrZero(tooling.amount());
    result.waterPower = amountOrZero(water.amount());
    result.other = amountOrZero(other.amount());
    result.baseAmount = firstNonNull(overhaul.baseAmount(), tooling.baseAmount(), water.baseAmount(), other.baseAmount());
    result.overhaulRate = overhaul.rate();
    result.toolingRepairRate = tooling.rate();
    result.waterPowerRate = water.rate();
    result.otherRate = other.rate();
    result.remark =
        joinRemarks(overhaul.remark(), tooling.remark(), water.remark(), other.remark());
    return result;
  }

  private DepartmentSubjectAmount calculateDepartmentSubjectAmount(
      String finishedProductCode,
      Integer rateYear,
      String businessUnitType,
      CostSourceContext costSourceContext,
      List<String> expenseSubjects,
      BigDecimal laborTotal) {
    DepartmentFundLookup lookup =
        findDepartmentFundRate(
            finishedProductCode, rateYear, businessUnitType, costSourceContext, expenseSubjects);
    DepartmentFundRate rate = lookup.rate();
    if (rate == null) {
      return new DepartmentSubjectAmount(null, null, null, lookup.remark());
    }
    BigDecimal manhourRate = rate.getManhourRate();
    if (!isPositive(manhourRate)) {
      return new DepartmentSubjectAmount(
          null,
          null,
          null,
          "lp_department_fund_rate.manhour_rate 非正数, id=" + rate.getId());
    }
    BigDecimal quoteRatio = rate.getQuoteRatio();
    if (quoteRatio == null) {
      return new DepartmentSubjectAmount(
          null,
          null,
          null,
          "lp_department_fund_rate.quote_ratio 为空, id=" + rate.getId());
    }
    BigDecimal upliftRatio =
        rate.getUpliftRatio() == null ? BigDecimal.ONE : rate.getUpliftRatio();
    BigDecimal effectiveRate = quoteRatio.multiply(upliftRatio);
    BigDecimal manhourCost = divide(laborTotal, manhourRate);
    return new DepartmentSubjectAmount(
        manhourCost, effectiveRate, multiplyAmount(manhourCost, effectiveRate), null);
  }

  private DepartmentFundLookup findDepartmentFundRate(
      String finishedProductCode,
      Integer rateYear,
      String businessUnitType,
      CostSourceContext costSourceContext,
      List<String> expenseSubjects) {
    String code = trimToNull(finishedProductCode);
    if (rateYear == null) {
      return new DepartmentFundLookup(null, "部门经费率未命中：报价时间为空，无法确定年度");
    }
    if (code == null) {
      return new DepartmentFundLookup(null, "部门经费率未命中：报价单产品料号为空");
    }
    MaterialMasterRaw raw = lookupMaterialByCode(code, costSourceContext);
    String productionDivision = trimToNull(raw == null ? null : raw.getProductionDivision());
    if (productionDivision == null) {
      return new DepartmentFundLookup(
          null, "部门经费率未命中：报价料号 " + code + " 在 lp_material_master_raw 未找到 production_division");
    }
    for (String subject : expenseSubjects) {
      DepartmentFundRate rate =
          findDepartmentFundRateBySubject(productionDivision, subject, rateYear, businessUnitType);
      if (rate != null) {
        return new DepartmentFundLookup(rate, null);
      }
    }
    return new DepartmentFundLookup(
        null,
        "部门经费率未命中：报价料号 "
            + code
            + " 主档事业部 "
            + productionDivision
            + "，"
            + rateYear
            + " 年费用科目 "
            + String.join("/", expenseSubjects)
            + " 无配置");
  }

  private DepartmentFundRate findDepartmentFundRateBySubject(
      String businessDivision, String expenseSubject, Integer rateYear, String businessUnitType) {
    if (!StringUtils.hasText(businessDivision)
        || !StringUtils.hasText(expenseSubject)
        || rateYear == null) {
      return null;
    }
    var query =
        Wrappers.lambdaQuery(DepartmentFundRate.class)
            .eq(DepartmentFundRate::getRateYear, rateYear)
            .eq(DepartmentFundRate::getBusinessDivision, businessDivision.trim())
            .eq(DepartmentFundRate::getExpenseSubject, expenseSubject.trim())
            .eq(
                StringUtils.hasText(businessUnitType),
                DepartmentFundRate::getBusinessUnitType,
                businessUnitType.trim())
            .orderByDesc(DepartmentFundRate::getId)
            .last("LIMIT 1");
    return departmentFundRateMapper.selectOne(query);
  }

  /**
   * 报价净损失率：先按当前报价产品料号匹配；料号未命中，再用该料号查 raw 主档 material_model 按型号匹配。
   */
  private RateLookup findLossRate(String productCode, CostSourceContext costSourceContext) {
    String code = trimToNull(productCode);
    if (code == null) {
      return new RateLookup(null, "产品料号为空，无法匹配报价净损失率");
    }
    Integer rateYear =
        costSourceContext == null || costSourceContext.costYear == null
            ? Year.now().getValue()
            : costSourceContext.costYear;
    String businessUnitType =
        costSourceContext == null ? "" : normalizeBusinessUnit(costSourceContext.businessUnitType);

    QualityLossRate codeRate =
        findQualityLossRateByMatch(
            LOSS_MATCH_LEVEL_MATERIAL_CODE, code, rateYear, businessUnitType);
    RateLookup codeLookup = toLossLookup(codeRate);
    if (codeLookup != null) {
      return codeLookup;
    }

    String materialModel = lookupMaterialModelByCode(code, costSourceContext);
    if (materialModel == null) {
      return new RateLookup(
          null,
          "lp_quality_loss_rate 无产品料号="
              + code
              + "、rateYear="
              + rateYear
              + " 配置；且 lp_material_master_raw 未找到 material_model");
    }
    QualityLossRate modelRate =
        findQualityLossRateByMatch(
            LOSS_MATCH_LEVEL_MATERIAL_MODEL, materialModel, rateYear, businessUnitType);
    RateLookup modelLookup = toLossLookup(modelRate);
    if (modelLookup != null) {
      return modelLookup;
    }
    return new RateLookup(
        null,
        "lp_quality_loss_rate 无产品料号="
            + code
            + " 或产品型号="
            + materialModel
            + "、rateYear="
            + rateYear
            + " 配置");
  }

  private QualityLossRate findQualityLossRateByMatch(
      String matchLevel, String matchKey, Integer rateYear, String businessUnitType) {
    if (!StringUtils.hasText(matchLevel) || !StringUtils.hasText(matchKey) || rateYear == null) {
      return null;
    }
    var query =
        Wrappers.lambdaQuery(QualityLossRate.class)
            .eq(QualityLossRate::getRateYear, rateYear)
            .eq(QualityLossRate::getMatchLevel, matchLevel.trim())
            .eq(QualityLossRate::getMatchKey, matchKey.trim())
            .eq(
                StringUtils.hasText(businessUnitType),
                QualityLossRate::getBusinessUnitType,
                businessUnitType.trim())
            .orderByDesc(QualityLossRate::getId)
            .last("LIMIT 1");
    return qualityLossRateMapper.selectOne(query);
  }

  private RateLookup toLossLookup(QualityLossRate rate) {
    if (rate == null) {
      return null;
    }
    if (rate.getLossRate() == null) {
      return new RateLookup(null, "lp_quality_loss_rate.loss_rate 为空 (id=" + rate.getId() + ")");
    }
    return new RateLookup(rate.getLossRate(), null);
  }

  private String lookupMaterialModelByCode(String productCode, CostSourceContext costSourceContext) {
    MaterialMasterRaw row = lookupMaterialByCode(productCode, costSourceContext);
    return trimToNull(row == null ? null : row.getMaterialModel());
  }

  private MaterialMasterRaw lookupMaterialByCode(String materialCode, CostSourceContext costSourceContext) {
    if (!StringUtils.hasText(materialCode)) {
      return null;
    }
    List<MaterialMasterRaw> rows =
        selectRawRows(List.of(materialCode.trim()), costSourceContext);
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    return rows.get(0);
  }

  private List<MaterialMasterRaw> selectRawRows(
      List<String> materialCodes, CostSourceContext costSourceContext) {
    String organization =
        costSourceContext == null
            ? MaterialOrganization.COMMERCIAL.getCode()
            : MaterialOrganization.normalize(costSourceContext.materialOrganizationCode);
    if (MaterialOrganization.COMMERCIAL.getCode().equals(organization)) {
      return materialMasterRawMapper.selectByLatestBatchAndCodes(materialCodes, null);
    }
    return materialMasterRawMapper.selectByLatestBatchAndCodes(materialCodes, null, organization);
  }

  /** 制造费用率：成品料号优先；未命中时按成本料号查 U9 型号、名称+事业部；最后按成品料号事业部兜底。 */
  private RateLookup findManufactureRate(
      String finishedProductCode, Set<String> costMaterialCodes, CostSourceContext costSourceContext) {
    String finishedCode = trimToNull(finishedProductCode);
    Integer rateYear =
        costSourceContext == null || costSourceContext.costYear == null
            ? Year.now().getValue()
            : costSourceContext.costYear;
    String businessUnitType =
        costSourceContext == null ? "" : normalizeBusinessUnit(costSourceContext.businessUnitType);
    List<String> missReasons = new ArrayList<>();

    if (finishedCode != null) {
      ManufactureRate codeRate =
          findManufactureRateByMatch(
              MANUFACTURE_MATCH_LEVEL_MATERIAL_CODE, finishedCode, rateYear, businessUnitType);
      RateLookup codeLookup = toManufactureLookup(codeRate);
      if (codeLookup != null) {
        return codeLookup;
      }
      missReasons.add("成品料号 " + finishedCode + " 无料号级配置");
    } else {
      missReasons.add("成品料号为空，无法做料号级和事业部级匹配");
    }

    for (String costCodeValue : normalizeCostMaterialCodes(costMaterialCodes, finishedCode)) {
      MaterialMasterRaw raw = lookupMaterialByCode(costCodeValue, costSourceContext);
      if (raw == null) {
        missReasons.add("成本料号 " + costCodeValue + " 未找到 U9 主档");
        continue;
      }
      String materialModel = trimToNull(raw.getMaterialModel());
      if (materialModel != null) {
        ManufactureRate modelRate =
            findManufactureRateByMatch(
                MANUFACTURE_MATCH_LEVEL_MATERIAL_MODEL, materialModel, rateYear, businessUnitType);
        RateLookup modelLookup = toManufactureLookup(modelRate);
        if (modelLookup != null) {
          return modelLookup;
        }
        missReasons.add("成本料号 " + costCodeValue + " 主档型号 " + materialModel + " 无型号级配置");
      } else {
        missReasons.add("成本料号 " + costCodeValue + " 主档 product_model 为空");
      }

      String materialName = trimToNull(raw.getMaterialName());
      String division = trimToNull(raw.getProductionDivision());
      if (materialName != null && division != null) {
        String matchKey = buildManufactureDivisionProductKey(division, materialName);
        ManufactureRate nameRate =
            findManufactureRateByMatch(
                MANUFACTURE_MATCH_LEVEL_DIVISION_PRODUCT_NAME, matchKey, rateYear, businessUnitType);
        RateLookup nameLookup = toManufactureLookup(nameRate);
        if (nameLookup != null) {
          return nameLookup;
        }
        missReasons.add(
            "成本料号 " + costCodeValue + " 主档名称/事业部 " + materialName + "/" + division
                + " 无产品名称+事业部配置");
      } else {
        missReasons.add("成本料号 " + costCodeValue + " 主档 material_name 或 production_division 为空");
      }
    }

    if (finishedCode != null) {
      MaterialMasterRaw finishedRaw = lookupMaterialByCode(finishedCode, costSourceContext);
      String finishedDivision =
          trimToNull(finishedRaw == null ? null : finishedRaw.getProductionDivision());
      if (finishedDivision != null) {
        ManufactureRate divisionRate =
            findManufactureRateByMatch(
                MANUFACTURE_MATCH_LEVEL_DIVISION, finishedDivision, rateYear, businessUnitType);
        RateLookup divisionLookup = toManufactureLookup(divisionRate);
        if (divisionLookup != null) {
          return divisionLookup;
        }
        missReasons.add("成品料号 " + finishedCode + " 主档事业部 " + finishedDivision + " 无事业部级配置");
      } else {
        missReasons.add("成品料号 " + finishedCode + " 未找到 U9 主档事业部");
      }
    }

    return new RateLookup(
        null,
        "制造费用率未命中：rateYear="
            + rateYear
            + "，businessUnitType="
            + (StringUtils.hasText(businessUnitType) ? businessUnitType : "空")
            + "；"
            + String.join("；", missReasons));
  }

  private List<String> normalizeCostMaterialCodes(Set<String> costMaterialCodes, String fallbackCode) {
    LinkedHashSet<String> codes = new LinkedHashSet<>();
    if (costMaterialCodes != null) {
      for (String code : costMaterialCodes) {
        String value = trimToNull(code);
        if (value != null) {
          codes.add(value);
        }
      }
    }
    if (codes.isEmpty() && StringUtils.hasText(fallbackCode)) {
      codes.add(fallbackCode.trim());
    }
    return new ArrayList<>(codes);
  }

  private ManufactureRate findManufactureRateByMatch(
      String matchLevel, String matchKey, Integer rateYear, String businessUnitType) {
    if (!StringUtils.hasText(matchLevel) || !StringUtils.hasText(matchKey) || rateYear == null) {
      return null;
    }
    var query =
        Wrappers.lambdaQuery(ManufactureRate.class)
            .eq(ManufactureRate::getRateYear, rateYear)
            .eq(ManufactureRate::getMatchLevel, matchLevel.trim())
            .eq(ManufactureRate::getMatchKey, matchKey.trim())
            .eq(
                StringUtils.hasText(businessUnitType),
                ManufactureRate::getBusinessUnitType,
                businessUnitType.trim())
            .orderByDesc(ManufactureRate::getId)
            .last("LIMIT 1");
    return manufactureRateMapper.selectOne(query);
  }

  private RateLookup toManufactureLookup(ManufactureRate rate) {
    if (rate == null) {
      return null;
    }
    if (rate.getFeeRate() == null) {
      return new RateLookup(null, "lp_manufacture_rate.fee_rate 为空 (id=" + rate.getId() + ")");
    }
    return new RateLookup(rate.getFeeRate(), null);
  }

  private String buildManufactureDivisionProductKey(String division, String productName) {
    return division + MANUFACTURE_MATCH_KEY_SEPARATOR + productName;
  }

  /**
   * T10：查三项费用率，包成 (rate, remark)；MGMT/SALES/FIN 三项共用同一 remark。
   * 缺率时 rate=null；命中正常时 remark=null。
   */
  private ThreeExpenseLookup findThreeExpenseRate(CostSourceContext context) {
    if (context == null) {
      return new ThreeExpenseLookup(null, "三项费用未命中：报价单核算维度上下文为空");
    }
    ThreeExpenseMatchContext match = context.threeExpenseMatchContext;
    if (match == null) {
      return new ThreeExpenseLookup(null, "三项费用未命中：三项费用匹配上下文为空");
    }
    if (StringUtils.hasText(match.missingReason)) {
      return new ThreeExpenseLookup(null, "三项费用未命中：" + match.missingReason);
    }
    String businessUnitType =
        StringUtils.hasText(context.businessUnitType)
            ? context.businessUnitType
            : ("家代商代销产品".equals(match.productCategory) ? "HOUSEHOLD" : "COMMERCIAL");
    if (!StringUtils.hasText(businessUnitType)) {
      return new ThreeExpenseLookup(null, "三项费用未命中：businessUnitType 缺失");
    }
    List<ThreeExpenseRate> candidates =
        threeExpenseRateMapper.selectList(
            Wrappers.lambdaQuery(ThreeExpenseRate.class)
                .eq(ThreeExpenseRate::getBusinessUnitType, businessUnitType)
                .eq(ThreeExpenseRate::getPeriodYear, match.periodYear)
                .eq(ThreeExpenseRate::getProductCategory, match.productCategory)
                .eq(ThreeExpenseRate::getProductLine, match.productLine)
                .orderByDesc(ThreeExpenseRate::getId));
    if (candidates == null || candidates.isEmpty()) {
      return new ThreeExpenseLookup(null, threeExpenseMissRemark(match));
    }

    ThreeExpenseRate matched = matchThreeExpenseRateCandidate(match, candidates);
    if (matched != null) {
      return new ThreeExpenseLookup(matched, null);
    }
    return new ThreeExpenseLookup(null, threeExpenseMissRemark(match));
  }

  ThreeExpenseRate matchThreeExpenseRateCandidate(
      ThreeExpenseMatchContext match, List<ThreeExpenseRate> candidates) {
    // 处室有具体值时优先按处室精确匹配；配置处室为 "/" 或空时，才走申请部门+后缀匹配。
    if (StringUtils.hasText(match.sourceOffice)) {
      for (ThreeExpenseRate candidate : candidates) {
        if (StringUtils.hasText(normalizeOfficeForThreeExpense(candidate.getApplicantOffice()))
            && match.sourceOffice.equals(normalizeOfficeForThreeExpense(candidate.getApplicantOffice()))) {
          // OEM费用率本期只导入和展示，不参与 MGMT_EXP / SALES_EXP / FIN_EXP 计算。
          return candidate;
        }
      }
    }
    for (ThreeExpenseRate candidate : candidates) {
      if (!StringUtils.hasText(normalizeOfficeForThreeExpense(candidate.getApplicantOffice()))
          && StringUtils.hasText(match.matchedDepartment)
          && match.matchedDepartment.equals(candidate.getApplicantDepartment())) {
        return candidate;
      }
    }
    return null;
  }

  private String threeExpenseMissRemark(ThreeExpenseMatchContext match) {
    // 未命中 remark 保留完整上下文，便于排查是年度、标题维度、处室还是部门后缀导致未命中。
    return "三项费用未命中：periodYear="
        + match.periodYear
        + "，oaNo="
        + valueOrEmpty(match.oaNo)
        + "，productCategory="
        + valueOrEmpty(match.productCategory)
        + "，productLine="
        + valueOrEmpty(match.productLine)
        + "，sourceDepartment="
        + valueOrEmpty(match.sourceDepartment)
        + "，sourceOffice="
        + valueOrEmpty(match.sourceOffice)
        + "，commercialOverseasFlag="
        + valueOrEmpty(match.commercialOverseasFlag)
        + "，homeApplianceSalesModeFlag="
        + valueOrEmpty(match.homeApplianceSalesModeFlag)
        + "，matchedDepartment="
        + valueOrEmpty(match.matchedDepartment);
  }

  private String firstMissingDimension(CostSourceContext context) {
    if (!StringUtils.hasText(context.businessUnitType)) {
      return "businessUnitType";
    }
    if (!StringUtils.hasText(context.accountingPeriodMonth)) {
      return "accountingPeriodMonth";
    }
    if (!StringUtils.hasText(context.sourceCompany)) {
      return "sourceCompany";
    }
    if (!StringUtils.hasText(context.sourceBusinessDivision)) {
      return "sourceBusinessDivision";
    }
    if (!StringUtils.hasText(context.applicantDept)) {
      return "applicantDept";
    }
    if (!StringUtils.hasText(context.applicantOffice)) {
      return "applicantOffice";
    }
    return null;
  }

  private DimensionLookup resolveThreeExpenseDimension(
      CostSourceContext context, String dimensionType, String sourceValue, String label) {
    String sourceSystem =
        StringUtils.hasText(context.sourceSystem) ? context.sourceSystem : THREE_EXPENSE_SOURCE_SYSTEM_OA;
    ThreeExpenseDimensionMapping mapping =
        cacheLookup.findThreeExpenseDimensionMapping(
            context.businessUnitType, dimensionType, sourceSystem, sourceValue);
    if (mapping == null || !StringUtils.hasText(mapping.getStandardValue())) {
      return new DimensionLookup(
          null,
          "三项费用未命中："
              + dimensionType
              + " 未配置映射，sourceValue="
              + sourceValue
              + "，sourceSystem="
              + sourceSystem
              + "，businessUnitType="
              + context.businessUnitType);
    }
    return new DimensionLookup(mapping.getStandardValue().trim(), null);
  }

  /** T10：单事业部 → rate 元组；多事业部场景 rate=null+remark 由调用侧组装。 */
  private record RateLookup(BigDecimal rate, String remark) {}

  /** T10：三项费用率元组，命中时 rate 为整行 ThreeExpenseRate，缺时 null+remark。 */
  private record ThreeExpenseLookup(ThreeExpenseRate rate, String remark) {}

  private record DimensionLookup(String standardValue, String missingRemark) {}

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
        BigDecimal baseAmount =
            unitPrice == null ? BigDecimal.ZERO : unitPrice.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
        if (isCmsEffectiveAuxSubject(subject)) {
          amount = baseAmount.multiply(CMS_AUX_UPLIFT_RATE).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
          rateForDisplay = CMS_AUX_UPLIFT_RATE;
        } else {
          amount = baseAmount;
          rateForDisplay = null;
        }
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
    return splitPartAmount(oaNo, productCode, null);
  }

  private PartTotalSplit splitPartAmount(
      String oaNo, String productCode, List<CostRunPartItemDto> currentPartItems) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return new PartTotalSplit(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    if (currentPartItems != null) {
      return splitPartAmount(currentPartItems);
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
    Set<String> packageCodes = new LinkedHashSet<>(lookupPackageCodes(partCodes));
    packageCodes.addAll(lookupPackageComponentParentCodes(partCodes));
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

  private PartTotalSplit splitPartAmount(List<CostRunPartItemDto> currentPartItems) {
    if (currentPartItems == null || currentPartItems.isEmpty()) {
      return new PartTotalSplit(BigDecimal.ZERO, BigDecimal.ZERO);
    }
    Set<String> partCodes = collectPartCodes(currentPartItems);
    Set<String> packageCodes = new LinkedHashSet<>(lookupPackageCodes(partCodes));
    packageCodes.addAll(lookupPackageComponentParentCodes(partCodes));
    BigDecimal pkg = BigDecimal.ZERO;
    BigDecimal nonPkg = BigDecimal.ZERO;
    for (CostRunPartItemDto item : currentPartItems) {
      if (item == null || item.getAmount() == null) {
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

  /** 从 raw 主档识别包装组件父件料号；父件通常是虚拟件，不一定在同步主档。 */
  private Set<String> lookupPackageComponentParentCodes(Set<String> partCodes) {
    if (partCodes == null || partCodes.isEmpty()) {
      return Collections.emptySet();
    }
    List<MaterialMasterRaw> rows =
        materialMasterRawMapper.selectPackageComponentParentsByLatestBatch(MAIN_CATEGORY_PACKAGE, null);
    if (rows == null || rows.isEmpty()) {
      return Collections.emptySet();
    }
    Set<String> result = new LinkedHashSet<>();
    for (MaterialMasterRaw row : rows) {
      String code = row.getMaterialCode() == null ? null : row.getMaterialCode().trim();
      if (code != null && partCodes.contains(code)) {
        result.add(code);
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

  private String valueOrEmpty(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    String value = trimToNull(businessUnitType);
    return value == null ? "" : value;
  }

  private String joinRemarks(String first, String second) {
    if (!StringUtils.hasText(first)) {
      return trimToNull(second);
    }
    if (!StringUtils.hasText(second)) {
      return trimToNull(first);
    }
    return first.trim() + "；" + second.trim();
  }

  private String joinRemarks(String... remarks) {
    String result = null;
    if (remarks == null) {
      return null;
    }
    for (String remark : remarks) {
      result = joinRemarks(result, remark);
    }
    return result;
  }

  private BigDecimal amountOrZero(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO : amount;
  }

  private BigDecimal firstNonNull(BigDecimal... values) {
    if (values == null) {
      return null;
    }
    for (BigDecimal value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
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

  private boolean isCmsEffectiveAuxSubject(AuxCostItemDto subject) {
    return subject != null && CMS_SOURCE_EFFECTIVE.equalsIgnoreCase(trimToNull(subject.getSource()));
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

  ProductCoefficientLookup lookupProductCoefficient(
      String productCode, CostSourceContext costSourceContext) {
    Integer propertyYear = costSourceContext == null ? Year.now().getValue() : costSourceContext.costYear;
    String businessUnitType = costSourceContext == null ? "" : costSourceContext.businessUnitType;
    return lookupProductCoefficient(productCode, propertyYear, businessUnitType);
  }

  ProductCoefficientLookup lookupProductCoefficient(
      String productCode, Integer propertyYear, String businessUnitType) {
    if (!StringUtils.hasText(productCode)) {
      return new ProductCoefficientLookup(BigDecimal.ONE, "产品料号为空，产品属性系数回落=1");
    }
    ProductProperty property =
        cacheLookup.findProductProperty(productCode, propertyYear, businessUnitType);
    if (property == null || property.getCoefficient() == null) {
      String remark =
          "产品属性系数未命中，回落=1：productCode="
              + productCode.trim()
              + "，propertyYear="
              + propertyYear
              + "，businessUnitType="
              + (StringUtils.hasText(businessUnitType) ? businessUnitType : "空");
      log.debug(remark);
      return new ProductCoefficientLookup(BigDecimal.ONE, remark);
    }
    return new ProductCoefficientLookup(property.getCoefficient(), null);
  }

  record ProductCoefficientLookup(BigDecimal coefficient, String remark) {}

  private record DepartmentFundLookup(DepartmentFundRate rate, String remark) {}

  private record DepartmentSubjectAmount(
      BigDecimal baseAmount, BigDecimal rate, BigDecimal amount, String remark) {}

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
    private final String materialOrganizationCode;
    private final String accountingPeriodMonth;
    private final String sourceSystem;
    private final String sourceCompany;
    private final String sourceBusinessDivision;
    private final String applicantDept;
    private final String applicantOffice;
    private final String expenseProductCategory;
    private final ThreeExpenseMatchContext threeExpenseMatchContext;

    private CostSourceContext(Integer costYear, String businessUnitType) {
      this(
          costYear,
          businessUnitType,
          MaterialOrganization.COMMERCIAL.getCode(),
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }

    private CostSourceContext(
        Integer costYear,
        String businessUnitType,
        String materialOrganizationCode,
        String accountingPeriodMonth,
        String sourceSystem,
        String sourceCompany,
        String sourceBusinessDivision,
        String applicantDept,
        String applicantOffice,
        String expenseProductCategory,
        ThreeExpenseMatchContext threeExpenseMatchContext) {
      this.costYear = costYear;
      this.businessUnitType = businessUnitType == null ? "" : businessUnitType;
      this.materialOrganizationCode = MaterialOrganization.normalize(materialOrganizationCode);
      this.accountingPeriodMonth = accountingPeriodMonth;
      this.sourceSystem = sourceSystem;
      this.sourceCompany = sourceCompany;
      this.sourceBusinessDivision = sourceBusinessDivision;
      this.applicantDept = applicantDept;
      this.applicantOffice = applicantOffice;
      this.expenseProductCategory = expenseProductCategory;
      this.threeExpenseMatchContext = threeExpenseMatchContext;
    }
  }

  static class ThreeExpenseMatchContext {
    final Integer periodYear;
    final String oaNo;
    final String productCategory;
    final String productLine;
    final String sourceDepartment;
    final String sourceOffice;
    final String commercialOverseasFlag;
    final String homeApplianceSalesModeFlag;
    final String matchedDepartment;
    final String missingReason;

    private ThreeExpenseMatchContext(
        Integer periodYear,
        String oaNo,
        String productCategory,
        String productLine,
        String sourceDepartment,
        String sourceOffice,
        String commercialOverseasFlag,
        String homeApplianceSalesModeFlag,
        String matchedDepartment,
        String missingReason) {
      this.periodYear = periodYear;
      this.oaNo = oaNo;
      this.productCategory = productCategory;
      this.productLine = productLine;
      this.sourceDepartment = sourceDepartment;
      this.sourceOffice = sourceOffice;
      this.commercialOverseasFlag = commercialOverseasFlag;
      this.homeApplianceSalesModeFlag = homeApplianceSalesModeFlag;
      this.matchedDepartment = matchedDepartment;
      this.missingReason = missingReason;
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
