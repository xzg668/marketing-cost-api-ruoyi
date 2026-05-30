package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 制造件价格纯计算组件：只做内存计算，不读取价格表或旧自制件人工价。 */
@Component
public class MakePartPriceCalculator {

  public static final String STATUS_OK = "OK";
  public static final String STATUS_MISSING_WEIGHT = "MISSING_WEIGHT";
  public static final String STATUS_MISSING_RAW_PRICE = "MISSING_RAW_PRICE";
  public static final String STATUS_MISSING_SCRAP_MAPPING = "MISSING_SCRAP_MAPPING";
  public static final String STATUS_MISSING_SCRAP_PRICE = "MISSING_SCRAP_PRICE";
  public static final String STATUS_INVALID_PROCESS_TYPE = "INVALID_PROCESS_TYPE";

  private static final BigDecimal G_TO_KG = new BigDecimal("1000");
  private static final int AMOUNT_SCALE = 8;

  public List<MakePartPriceCalcRow> calculate(List<MakePartPriceCalcRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    List<MakePartPriceCalcRow> calculated = new ArrayList<>(rows.size());
    Map<String, BigDecimal> okTotalByParent = new LinkedHashMap<>();
    for (MakePartPriceCalcRow row : rows) {
      MakePartPriceCalcRow calculatedRow = calculateOne(row);
      calculated.add(calculatedRow);
      if (STATUS_OK.equals(calculatedRow.getStatus())) {
        String parent = parentKey(calculatedRow);
        okTotalByParent.merge(parent, calculatedRow.getCostPrice(), BigDecimal::add);
      }
    }
    for (MakePartPriceCalcRow row : calculated) {
      BigDecimal total = okTotalByParent.get(parentKey(row));
      if (total != null) {
        // 多废料口径：每个废料独立核算，最后把 OK 明细加总到制造件价格。
        row.setParentTotalCostPrice(scale(total));
      }
    }
    return calculated;
  }

  private MakePartPriceCalcRow calculateOne(MakePartPriceCalcRow input) {
    MakePartPriceCalcRow row = copy(input);
    List<String> missingRemarks = new ArrayList<>();
    String missingStatus = validate(row, missingRemarks);
    if (missingStatus != null) {
      row.setStatus(missingStatus);
      row.setCostPrice(null);
      row.setParentTotalCostPrice(null);
      row.setRemark(appendRemark(row.getRemark(), String.join("；", missingRemarks)));
      return row;
    }

    BigDecimal grossWeightG = row.getGrossWeightG();
    BigDecimal netWeightG = row.getNetWeightG();
    BigDecimal scrapWeightKg = grossWeightG.subtract(netWeightG)
        .divide(G_TO_KG, AMOUNT_SCALE, RoundingMode.HALF_UP);
    BigDecimal scrapDeduction = scrapWeightKg.multiply(row.getScrapUnitPrice());
    BigDecimal cost;
    if (MakePartProcessTypePolicy.PROCESS_TYPE_RAW.equals(row.getItemProcessType())) {
      // 原材料加工：毛重/净重是 g，原材料价和废料价是元/kg，所以参与计算前必须 /1000 转 kg。
      BigDecimal materialAmount = grossWeightG
          .divide(G_TO_KG, AMOUNT_SCALE, RoundingMode.HALF_UP)
          .multiply(row.getRawUnitPrice());
      cost = materialAmount.subtract(scrapDeduction);
    } else {
      // 毛坯加工：采购单价按元/件，不再乘毛重；只有废料抵扣按 g 转 kg 计算。
      cost = row.getRawUnitPrice().subtract(scrapDeduction);
    }
    row.setCostPrice(scale(cost));
    row.setParentTotalCostPrice(null);
    row.setStatus(STATUS_OK);
    row.setRemark(appendRemark(row.getRemark(), calculationTrace(row, scrapWeightKg)));
    return row;
  }

  private String validate(MakePartPriceCalcRow row, List<String> missingRemarks) {
    if (row == null) {
      missingRemarks.add("计算明细为空");
      return STATUS_MISSING_WEIGHT;
    }
    if (StringUtils.hasText(row.getStatus()) && !STATUS_OK.equals(row.getStatus())) {
      missingRemarks.add("上游异常，跳过成本计算(status=" + row.getStatus() + ")");
      return row.getStatus();
    }
    if (!MakePartProcessTypePolicy.PROCESS_TYPE_RAW.equals(row.getItemProcessType())
        && !MakePartProcessTypePolicy.PROCESS_TYPE_BLANK.equals(row.getItemProcessType())) {
      missingRemarks.add("未知料件类型(item_process_type=" + row.getItemProcessType() + ")");
      return STATUS_INVALID_PROCESS_TYPE;
    }
    if (row.getGrossWeightG() == null || row.getNetWeightG() == null) {
      missingRemarks.add("缺重量(gross_weight_g 或 net_weight_g 为空)");
      return STATUS_MISSING_WEIGHT;
    }
    if (row.getRawUnitPrice() == null) {
      missingRemarks.add("缺原材料价格(child_material_no=" + row.getChildMaterialNo() + ")");
      return STATUS_MISSING_RAW_PRICE;
    }
    if (!StringUtils.hasText(row.getScrapCode())) {
      missingRemarks.add("缺废料映射(child_material_no=" + row.getChildMaterialNo() + ")");
      return STATUS_MISSING_SCRAP_MAPPING;
    }
    if (row.getScrapUnitPrice() == null) {
      missingRemarks.add("缺回收价格(scrap_code=" + row.getScrapCode() + ")");
      return STATUS_MISSING_SCRAP_PRICE;
    }
    return null;
  }

  private String calculationTrace(MakePartPriceCalcRow row, BigDecimal scrapWeightKg) {
    return "计算追溯: item_process_type=" + row.getItemProcessType()
        + ", gross_weight_g=" + format(row.getGrossWeightG())
        + ", net_weight_g=" + format(row.getNetWeightG())
        + ", scrap_weight_kg=" + format(scrapWeightKg)
        + ", raw_unit_price=" + format(row.getRawUnitPrice())
        + ", scrap_unit_price=" + format(row.getScrapUnitPrice())
        + ", outsource_fee_ignored=" + format(row.getOutsourceFee());
  }

  private BigDecimal scale(BigDecimal value) {
    return value == null ? null : value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private String parentKey(MakePartPriceCalcRow row) {
    return StringUtils.hasText(row.getParentMaterialNo())
        ? row.getParentMaterialNo().trim()
        : "";
  }

  private String appendRemark(String oldRemark, String newRemark) {
    if (!StringUtils.hasText(oldRemark)) {
      return newRemark;
    }
    if (!StringUtils.hasText(newRemark)) {
      return oldRemark;
    }
    return oldRemark + "；" + newRemark;
  }

  private String format(BigDecimal value) {
    return value == null ? "" : value.stripTrailingZeros().toPlainString();
  }

  private MakePartPriceCalcRow copy(MakePartPriceCalcRow source) {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    if (source == null) {
      return row;
    }
    row.setId(source.getId());
    row.setCalcBatchId(source.getCalcBatchId());
    row.setOaNo(source.getOaNo());
    row.setBusinessUnitType(source.getBusinessUnitType());
    row.setPricingMonth(source.getPricingMonth());
    row.setPriceAsOfTime(source.getPriceAsOfTime());
    row.setParentMaterialNo(source.getParentMaterialNo());
    row.setParentMaterialName(source.getParentMaterialName());
    row.setDrawingNo(source.getDrawingNo());
    row.setItemProcessType(source.getItemProcessType());
    row.setChildMaterialNo(source.getChildMaterialNo());
    row.setChildMaterialName(source.getChildMaterialName());
    row.setChildMaterialSpec(source.getChildMaterialSpec());
    row.setStockUnit(source.getStockUnit());
    row.setQtyPerParent(source.getQtyPerParent());
    row.setGrossWeightG(source.getGrossWeightG());
    row.setNetWeightG(source.getNetWeightG());
    row.setRawPriceType(source.getRawPriceType());
    row.setRawUnitPrice(source.getRawUnitPrice());
    row.setScrapCode(source.getScrapCode());
    row.setScrapName(source.getScrapName());
    row.setScrapPriceType(source.getScrapPriceType());
    row.setScrapUnitPrice(source.getScrapUnitPrice());
    row.setOutsourceFee(source.getOutsourceFee());
    row.setCostPrice(source.getCostPrice());
    row.setParentTotalCostPrice(source.getParentTotalCostPrice());
    row.setPriceComplete(source.getRawUnitPrice() != null && source.getScrapUnitPrice() != null);
    row.setStatus(source.getStatus());
    row.setRemark(source.getRemark());
    row.setCreatedAt(source.getCreatedAt());
    return row;
  }
}
