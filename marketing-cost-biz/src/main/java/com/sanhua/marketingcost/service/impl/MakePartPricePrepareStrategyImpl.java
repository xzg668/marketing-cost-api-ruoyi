package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.priceprepare.MakePartPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.MakePartPriceGenerationService;
import com.sanhua.marketingcost.service.MakePartPricePrepareStrategy;
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
  private final MakePartPriceCalcRowMapper calcRowMapper;

  public MakePartPricePrepareStrategyImpl(
      MakePartPriceGenerationService generationService,
      MakePartPriceCalcRowMapper calcRowMapper) {
    this.generationService = generationService;
    this.calcRowMapper = calcRowMapper;
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

    MakePartPriceCalcRow ready =
        selectLatestReady(
            parentMaterialNo,
            normalizedOaNo,
            normalizedBusinessUnitType,
            normalizedPeriod,
            priceAsOfTime);
    if (ready != null) {
      return readyResult(ready, planItem, "自制件价格准备复用当期已有生成结果");
    }

    generationService.generateByOa(
        normalizedOaNo, normalizedBusinessUnitType, normalizedPeriod, priceAsOfTime);
    ready = selectLatestReady(
        parentMaterialNo,
        normalizedOaNo,
        normalizedBusinessUnitType,
        normalizedPeriod,
        priceAsOfTime);
    if (ready != null) {
      return readyResult(ready, planItem, "自制件价格准备已触发生成并取得价格");
    }

    List<MakePartPriceCalcRow> rows = selectLatestRows(
        parentMaterialNo,
        normalizedOaNo,
        normalizedBusinessUnitType,
        normalizedPeriod,
        priceAsOfTime);
    List<MakePartPricePrepareResult.Gap> gaps = buildGaps(parentMaterialNo, rows);
    String status = hasOnlyStructureGaps(gaps) ? STATUS_MISSING_STRUCTURE : STATUS_MISSING_PRICE;
    String message = rows.isEmpty()
        ? "缺制造件价格生成结果，已触发生成但未返回该自制件明细"
        : "自制件价格生成结果存在缺口，当前阶段只记录不阻断";
    return MakePartPricePrepareResult.notReady(status, message, gaps);
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

  private MakePartPriceCalcRow selectLatestReady(
      String parentMaterialNo,
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime) {
    List<MakePartPriceCalcRow> rows = calcRowMapper.selectList(baseQuery(
            parentMaterialNo, oaNo, businessUnitType, periodMonth, priceAsOfTime)
        .eq(MakePartPriceCalcRow::getStatus, STATUS_OK)
        .eq(MakePartPriceCalcRow::getPriceComplete, true)
        .isNotNull(MakePartPriceCalcRow::getParentTotalCostPrice)
        .orderByDesc(MakePartPriceCalcRow::getCreatedAt)
        .orderByDesc(MakePartPriceCalcRow::getId)
        .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private List<MakePartPriceCalcRow> selectLatestRows(
      String parentMaterialNo,
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime) {
    LambdaQueryWrapper<MakePartPriceCalcRow> query =
        baseQuery(parentMaterialNo, oaNo, businessUnitType, periodMonth, priceAsOfTime);
    List<MakePartPriceCalcRow> rows = calcRowMapper.selectList(
        query.orderByDesc(MakePartPriceCalcRow::getCreatedAt)
            .orderByDesc(MakePartPriceCalcRow::getId));
    return rows == null ? List.of() : rows;
  }

  private LambdaQueryWrapper<MakePartPriceCalcRow> baseQuery(
      String parentMaterialNo,
      String oaNo,
      String businessUnitType,
      String periodMonth,
      LocalDateTime priceAsOfTime) {
    LambdaQueryWrapper<MakePartPriceCalcRow> query = Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
        .eq(MakePartPriceCalcRow::getParentMaterialNo, parentMaterialNo)
        .eq(MakePartPriceCalcRow::getOaNo, oaNo)
        .eq(MakePartPriceCalcRow::getBusinessUnitType, businessUnitType)
        .eq(MakePartPriceCalcRow::getPricingMonth, periodMonth);
    if (priceAsOfTime != null) {
      // 月度调价必须严格复用批次取价时点，不能把其他时间生成的当前结果混进来。
      query.eq(MakePartPriceCalcRow::getPriceAsOfTime, priceAsOfTime);
    }
    return query;
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
          "lp_bom_u9_source",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_WEIGHT.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_STRUCTURE,
          firstText(row.getChildMaterialNo(), parentMaterialNo),
          "MakePartWeightService",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_SCRAP_MAPPING.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_STRUCTURE,
          firstText(row.getChildMaterialNo(), parentMaterialNo),
          "lp_material_scrap_ref",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_RAW_PRICE.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          firstText(row.getChildMaterialNo(), parentMaterialNo),
          "lp_make_part_price_gap_item",
          message);
    }
    if (MakePartPriceCalculator.STATUS_MISSING_SCRAP_PRICE.equals(status)) {
      return new MakePartPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          firstText(row.getScrapCode(), parentMaterialNo),
          "lp_make_part_price_gap_item",
          message);
    }
    return new MakePartPricePrepareResult.Gap(
        GAP_TYPE_MISSING_STRUCTURE,
        parentMaterialNo,
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
