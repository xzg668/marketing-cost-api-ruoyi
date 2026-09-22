package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.MakePartPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MakePartPricePrepareStrategyImpl implements MakePartPricePrepareStrategy {

  static final String STATUS_READY = "READY";
  static final String STATUS_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  static final String STATUS_MISSING_PRICE = "MISSING_PRICE";
  static final String STATUS_FAILED = "FAILED";
  static final String GAP_TYPE_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";
  static final String STATUS_OK = MakePartPriceCalculator.STATUS_OK;
  static final String STATUS_MISSING_BOM = "MISSING_BOM";

  private final MakePartPriceGenerationService generationService;

  public MakePartPricePrepareStrategyImpl(
      MakePartPriceGenerationService generationService) {
    this.generationService = generationService;
  }

  @Override
  public MakePartPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, null, planItem);
  }

  @Override
  public MakePartPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePreparePlanItem planItem) {
    return prepare(
        oaNo, businessUnitType, periodMonth, priceAsOfTime, null, planItem);
  }

  @Override
  public MakePartPricePrepareResult prepare(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return execute(
        oaNo,
        businessUnitType,
        periodMonth,
        priceAsOfTime,
        scenarioContext,
        planItem,
        true);
  }

  @Override
  public MakePartPricePrepareResult calculate(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem) {
    return execute(
        oaNo,
        businessUnitType,
        periodMonth,
        priceAsOfTime,
        scenarioContext,
        planItem,
        false);
  }

  private MakePartPricePrepareResult execute(
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenarioContext,
      PricePreparePlanItem planItem,
      boolean persist) {
    String parentMaterialNo = planItem == null ? null : trimToNull(planItem.getMaterialCode());
    String normalizedOaNo = trimToNull(oaNo);
    String normalizedBusinessUnitType = trimToNull(businessUnitType);
    String normalizedPeriod = trimToNull(periodMonth);
    if (parentMaterialNo == null) {
      return MakePartPricePrepareResult.notReady(
          STATUS_FAILED,
          "自制件价格准备缺料号，无法检查生成结果",
          List.of(new MakePartPricePrepareResult.Gap(
              GAP_TYPE_MISSING_STRUCTURE, "", "lp_bom_costing_row", "自制件价格准备缺料号")));
    }
    if (normalizedOaNo == null || normalizedBusinessUnitType == null || normalizedPeriod == null) {
      return MakePartPricePrepareResult.notReady(
          STATUS_FAILED,
          "自制件价格准备缺 OA、业务单元或期间上下文",
          List.of(new MakePartPricePrepareResult.Gap(
              GAP_TYPE_MISSING_STRUCTURE,
              parentMaterialNo,
              "PricePrepareService",
              "自制件价格准备缺 OA、业务单元或期间上下文")));
    }

    var parent = planItem.getBomRow();
    if (parent == null || !normalizedOaNo.equals(parent.getOaNo())
        || !normalizedBusinessUnitType.equals(parent.getBusinessUnitType())
        || !normalizedPeriod.equals(parent.getPeriodMonth()) || !parentMaterialNo.equals(parent.getMaterialCode())) {
      throw new IllegalArgumentException("制造件计划行与本报价、月份或业务单元不一致");
    }
    var rows = generationService.calculateForBomRow(parent, priceAsOfTime, scenarioContext, persist);
    var ready = rows.stream().filter(row -> STATUS_OK.equals(row.getStatus())
        && Boolean.TRUE.equals(row.getPriceComplete()) && row.getParentTotalCostPrice() != null).findFirst();
    if (ready.isPresent() && rows.stream().allMatch(row -> STATUS_OK.equals(row.getStatus()))) {
      return readyResult(ready.get(), planItem, "本报价制造节点价格计算完成");
    }
    var gaps = buildGaps(parentMaterialNo, rows);
    return MakePartPricePrepareResult.notReady(hasOnlyStructureGaps(gaps) ? STATUS_MISSING_STRUCTURE : STATUS_MISSING_PRICE,
        "本报价制造节点尚有未解决输入", gaps);
  }

  private MakePartPricePrepareResult readyResult(
      MakePartPriceCalcRow row, PricePreparePlanItem planItem, String message) {
    BigDecimal amount = quantity(planItem) == null
        ? null
        : row.getParentTotalCostPrice().multiply(quantity(planItem));
    return MakePartPricePrepareResult.ready(
        row.getParentTotalCostPrice(),
        amount,
        row.getId(),
        message);
  }

  private List<MakePartPricePrepareResult.Gap> buildGaps(
      String parentMaterialNo, List<MakePartPriceCalcRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of(new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_STRUCTURE,
          parentMaterialNo,
          "lp_make_part_price_calc_row",
          "缺制造件价格生成结果"));
    }
    List<MakePartPricePrepareResult.Gap> gaps = new ArrayList<>();
    Set<String> dedupe = new LinkedHashSet<>();
    for (MakePartPriceCalcRow row : rows) {
      if (row == null || STATUS_OK.equals(row.getStatus())) {
        continue;
      }
      MakePartPricePrepareResult.Gap gap = gapFromRow(parentMaterialNo, row);
      String key = gap.getGapType() + "|" + gap.getGapMaterialCode() + "|" + gap.getSourceTable();
      if (dedupe.add(key)) {
        gaps.add(gap);
      }
    }
    if (gaps.isEmpty()) {
      gaps.add(new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          parentMaterialNo,
          "lp_make_part_price_calc_row",
          "制造件价格生成结果未完成或缺父件汇总价"));
    }
    return gaps;
  }

  private MakePartPricePrepareResult.Gap gapFromRow(
      String parentMaterialNo, MakePartPriceCalcRow row) {
    String status = trimToNull(row.getStatus());
    String message = StringUtils.hasText(row.getRemark())
        ? row.getRemark().trim()
        : "制造件价格生成异常(status=" + status + ")";
    if (STATUS_MISSING_BOM.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_STRUCTURE,
          parentMaterialNo,
          row.getParentMaterialName(),
          "lp_bom_u9_source",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_WEIGHT.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_STRUCTURE,
          firstText(row.getChildMaterialNo(), parentMaterialNo),
          row.getChildMaterialName(),
          "MakePartWeightService",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_SCRAP_MAPPING.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_STRUCTURE,
          firstText(row.getChildMaterialNo(), parentMaterialNo),
          row.getChildMaterialName(),
          "lp_material_scrap_ref",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_RAW_PRICE.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          firstText(row.getChildMaterialNo(), parentMaterialNo),
          row.getChildMaterialName(),
          "lp_make_part_price_gap_item",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_SCRAP_PRICE.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          firstText(row.getScrapCode(), parentMaterialNo),
          row.getScrapName(),
          "lp_make_part_price_gap_item",
          message);
    }
    return new MakePartPricePrepareResult.Gap(
        GAP_TYPE_MISSING_STRUCTURE,
        parentMaterialNo,
        row.getParentMaterialName(),
        "lp_make_part_price_calc_row",
        message);
  }

  private boolean hasOnlyStructureGaps(List<MakePartPricePrepareResult.Gap> gaps) {
    if (gaps == null || gaps.isEmpty()) {
      return true;
    }
    for (MakePartPricePrepareResult.Gap gap : gaps) {
      if (gap != null && GAP_TYPE_MISSING_PRICE.equals(gap.getGapType())) {
        return false;
      }
    }
    return true;
  }

  private BigDecimal quantity(PricePreparePlanItem planItem) {
    return planItem == null || planItem.getBomRow() == null ? null : planItem.getBomRow().getQtyPerTop();
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      String trimmed = trimToNull(value);
      if (trimmed != null) {
        return trimmed;
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
