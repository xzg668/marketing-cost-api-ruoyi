package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.DepartmentFundRateMapper;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OtherExpenseRateMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import com.sanhua.marketingcost.mapper.SalaryCostMapper;
import com.sanhua.marketingcost.mapper.ThreeExpenseRateMapper;
import com.sanhua.marketingcost.service.CostRunCacheLookupService;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
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
  private static final String OTHER_EXP_PREFIX = "OTHER_EXP_";
  /** T11：包装费固定 cost_code，区别于 lp_other_expense_rate 的 OTHER_EXP_<id> 系列 */
  private static final String OTHER_EXP_PACKAGE = "OTHER_EXP_PACKAGE";
  /** T11：运费固定 cost_code，取 oa_form_item.shipping_fee */
  private static final String OTHER_EXP_FREIGHT = "OTHER_EXP_FREIGHT";
  /** T11：lp_material_master.cost_element 表示包装材料的固定文本（U9 同步上来的中文枚举值） */
  private static final String COST_ELEMENT_PACKAGE = "主要材料-包装材料";

  private final CostRunCostItemMapper costRunCostItemMapper;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final SalaryCostMapper salaryCostMapper;
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
      DepartmentFundRateMapper departmentFundRateMapper,
      AuxCostItemMapper auxCostItemMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      QualityLossRateMapper qualityLossRateMapper,
      ManufactureRateMapper manufactureRateMapper,
      ThreeExpenseRateMapper threeExpenseRateMapper,
      OtherExpenseRateMapper otherExpenseRateMapper,
      ProductPropertyMapper productPropertyMapper,
      MaterialMasterMapper materialMasterMapper,
      CostRunCacheLookupService cacheLookup,
      @Value("${cost.material.includeWaterPower:false}") boolean includeWaterPowerInMaterial) {
    this.costRunCostItemMapper = costRunCostItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.salaryCostMapper = salaryCostMapper;
    this.departmentFundRateMapper = departmentFundRateMapper;
    this.auxCostItemMapper = auxCostItemMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.qualityLossRateMapper = qualityLossRateMapper;
    this.manufactureRateMapper = manufactureRateMapper;
    this.threeExpenseRateMapper = threeExpenseRateMapper;
    this.otherExpenseRateMapper = otherExpenseRateMapper;
    this.productPropertyMapper = productPropertyMapper;
    this.materialMasterMapper = materialMasterMapper;
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

    return calculateItems(oaNoValue, productCodeValue, materialCodes);
  }

  @Override
  public List<CostRunCostItemDto> listStoredByOaNo(String oaNo, String productCode) {
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return Collections.emptyList();
    }
    String oaNoValue = oaNo.trim();
    String productCodeValue = productCode.trim();
    List<CostRunCostItem> stored =
        costRunCostItemMapper.selectList(
            Wrappers.lambdaQuery(CostRunCostItem.class)
                .eq(CostRunCostItem::getOaNo, oaNoValue)
                .eq(CostRunCostItem::getProductCode, productCodeValue)
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
    List<CostRunCostItemDto> result = calculateItems(oaNo.trim(), productCode.trim(), materialCodes);
    progress.accept(100);
    return result;
  }

  private List<CostRunCostItemDto> calculateItems(
      String oaNoValue, String productCodeValue, Set<String> materialCodes) {
    List<SalaryCost> salaryCosts =
        salaryCostMapper.selectList(
            Wrappers.lambdaQuery(SalaryCost.class).in(SalaryCost::getMaterialCode, materialCodes));

    // 1) 先算人工 -> 部门经费
    BigDecimal directTotal = BigDecimal.ZERO;
    BigDecimal indirectTotal = BigDecimal.ZERO;
    Map<String, LaborSum> laborByUnit = new LinkedHashMap<>();
    for (SalaryCost cost : salaryCosts) {
      BigDecimal direct = nullToZero(cost.getDirectLaborCost());
      BigDecimal indirect = nullToZero(cost.getIndirectLaborCost());
      directTotal = directTotal.add(direct);
      indirectTotal = indirectTotal.add(indirect);

      String businessUnit = trimToNull(cost.getBusinessUnit());
      if (businessUnit == null) {
        continue;
      }
      LaborSum sum = laborByUnit.computeIfAbsent(businessUnit, (key) -> new LaborSum());
      sum.direct = sum.direct.add(direct);
      sum.indirect = sum.indirect.add(indirect);
    }

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
    List<CostRunCostItemDto> auxItems = buildAuxItems(materialCodes);
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

    // 3) 最后汇总材料费(部品+辅料+部门经费)
    //    Task #9：水电费默认不计入材料费（与 Excel 见机表3 口径对齐），
    //    通过 cost.material.includeWaterPower 开关保留 legacy 行为以便回溯。
    //    T11：拆 part 为 (非包装/包装)，包装件单独成 OTHER_EXP_PACKAGE 不进 materialTotal，避免双计。
    PartTotalSplit partSplit = splitPartAmount(oaNoValue, productCodeValue);
    BigDecimal partTotal = partSplit.nonPackageTotal();
    BigDecimal packageAmount = partSplit.packageTotal();
    BigDecimal freightAmount = lookupFreight(oaNoValue, productCodeValue);
    // T11：包装/运费纳入 otherExpenseTotal，让 totalAmount 自然累加（不漏不重）
    otherExpenseTotal = otherExpenseTotal.add(packageAmount).add(freightAmount);
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
        partTotal.add(auxTotal).add(deptTotal).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
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
    items.add(buildItem(DIRECT_LABOR, "直接人工工资", null, null, directTotal));
    items.add(buildItem(INDIRECT_LABOR, "辅助人工工资", null, null, indirectTotal));
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
    items.add(buildItem(OTHER_EXP_PACKAGE, "包装费", null, null, packageAmount));
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

    saveCostRunItems(oaNoValue, productCodeValue, items, costCodes);
    return items;
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
        || items == null
        || costCodes == null
        || costCodes.isEmpty()) {
      return;
    }
    costRunCostItemMapper.delete(
        Wrappers.lambdaQuery(CostRunCostItem.class)
            .eq(CostRunCostItem::getOaNo, oaNo)
            .eq(CostRunCostItem::getProductCode, productCode)
            .in(CostRunCostItem::getCostCode, costCodes));
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

  private List<CostRunCostItemDto> buildAuxItems(Set<String> materialCodes) {
    if (materialCodes == null || materialCodes.isEmpty()) {
      return Collections.emptyList();
    }
    List<AuxCostItemDto> auxSubjects = auxCostItemMapper.selectByMaterialCodes(materialCodes);
    if (auxSubjects == null || auxSubjects.isEmpty()) {
      return Collections.emptyList();
    }
    List<CostRunCostItemDto> items = new ArrayList<>();
    for (AuxCostItemDto subject : auxSubjects) {
      String code = trimToNull(subject.getAuxSubjectCode());
      String name = trimToNull(subject.getAuxSubjectName());
      BigDecimal unitPrice = subject.getUnitPrice();
      BigDecimal floatRate = subject.getFloatRate();
      if (code == null || name == null) {
        continue;
      }
      BigDecimal amount = BigDecimal.ZERO;
      if (unitPrice != null && floatRate != null) {
        amount = unitPrice.multiply(floatRate).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
      }
      items.add(buildItem(AUX_PREFIX + code, name, unitPrice, floatRate, amount));
    }
    return items;
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
