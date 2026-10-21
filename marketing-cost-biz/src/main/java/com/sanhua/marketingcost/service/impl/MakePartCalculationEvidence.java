package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.MakePartPriceCalculator;
import com.sanhua.marketingcost.service.settlement.SettlementCalculationEvidence;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.util.StringUtils;

/** 制造件本次计算行随报价准备保存；重算中间表不再改变已捕获的价格和重量依据。 */
final class MakePartCalculationEvidence {
  static final String VERSION = "MAKE_PART_V1";
  private final ObjectMapper json;

  MakePartCalculationEvidence(ObjectMapper json) { this.json = json; }

  String capture(BomCostingRow parent, List<MakePartPriceCalcRow> rows) {
    try {
      var snapshot = json.createObjectNode();
      snapshot.put("calculatorVersion", VERSION);
      snapshot.put("materialCode", parent.getMaterialCode());
      snapshot.put("businessUnitType", parent.getBusinessUnitType());
      snapshot.put("priceOrgCode", parent.getPriceOrgCode());
      snapshot.put("periodMonth", parent.getPeriodMonth());
      snapshot.put("sourceCostingRowId", parent.getId());
      snapshot.set("rows", json.valueToTree(rows));
      var components = snapshot.putArray("components");
      for (var row : rows) {
        for (String kind : List.of("RAW", "SCRAP")) {
          boolean raw = "RAW".equals(kind);
          String inputs = raw ? row.getRawInputSnapshotJson() : row.getScrapInputSnapshotJson();
          if (!StringUtils.hasText(inputs)) continue;
          var component = components.addObject();
          component.put("kind", kind);
          component.put("materialCode", raw ? row.getChildMaterialNo() : row.getScrapCode());
          component.put("unitPrice", raw ? row.getRawUnitPrice() : row.getScrapUnitPrice());
          component.set("inputs", read(inputs));
        }
      }
      return snapshot.toString();
    } catch (Exception failure) {
      throw new IllegalStateException("制造件计算底稿保存失败", failure);
    }
  }

  /** 旧底稿没有完整计算行时返回 null，继续走明确的历史兼容路径。 */
  List<MakePartPriceCalcRow> rows(PricePrepareItem prepared) {
    if (prepared == null || !StringUtils.hasText(prepared.getInputSnapshotJson())) return null;
    try {
      var snapshot = read(prepared.getInputSnapshotJson());
      if (!VERSION.equals(snapshot.path("calculatorVersion").asText())) return null;
      same(prepared.getMaterialCode(), snapshot.path("materialCode").asText(), "制造件料号");
      same(prepared.getBusinessUnitType(), snapshot.path("businessUnitType").asText(), "业务单元");
      same(prepared.getPriceOrgCode(), snapshot.path("priceOrgCode").asText(), "组织");
      same(prepared.getPeriodMonth(), snapshot.path("periodMonth").asText(), "月份");
      if (!snapshot.path("rows").isArray() || snapshot.path("rows").isEmpty())
        throw new IllegalStateException("缺少制造件计算行");
      var rows = new ArrayList<MakePartPriceCalcRow>();
      for (var captured : snapshot.path("rows")) {
        var row = json.treeToValue(captured, MakePartPriceCalcRow.class);
        same(prepared.getMaterialCode(), row.getParentMaterialNo(), "计算行父件");
        same(prepared.getBusinessUnitType(), row.getBusinessUnitType(), "计算行业务单元");
        same(prepared.getPeriodMonth(), row.getPricingMonth(), "计算行月份");
        same("OK", row.getStatus(), "计算行状态");
        validateSettlement(prepared, row);
        rows.add(row);
      }
      var recomputed = new MakePartPriceCalculator().calculate(rows);
      for (int i = 0; i < rows.size(); i++) {
        same("OK", recomputed.get(i).getStatus(), "重验状态");
        price(rows.get(i).getCostPrice(), recomputed.get(i).getCostPrice());
        price(rows.get(i).getParentTotalCostPrice(), recomputed.get(i).getParentTotalCostPrice());
      }
      price(prepared.getUnitPrice(), recomputed.getFirst().getParentTotalCostPrice());
      return List.copyOf(rows);
    } catch (Exception failure) {
      throw new IllegalStateException("制造件计算底稿校验失败：" + prepared.getMaterialCode(), failure);
    }
  }

  private void validateSettlement(PricePrepareItem parent, MakePartPriceCalcRow row) throws Exception {
    if (!StringUtils.hasText(row.getRawInputSnapshotJson())) return;
    var source = read(row.getRawInputSnapshotJson());
    if (!"SETTLEMENT_PRICE".equals(source.path("resultRefType").asText())) return;
    var child = new PricePrepareItem();
    child.setMaterialCode(row.getChildMaterialNo());
    child.setBusinessUnitType(parent.getBusinessUnitType());
    child.setPriceOrgCode(parent.getPriceOrgCode());
    child.setPeriodMonth(parent.getPeriodMonth());
    child.setResultRefId(row.getRawSourcePriceRecordId());
    child.setUnitPrice(row.getRawUnitPrice());
    child.setInputSnapshotJson(row.getRawInputSnapshotJson());
    new SettlementCalculationEvidence(json).read(child);
  }

  private JsonNode read(String value) throws Exception {
    return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(value);
  }

  private void same(Object expected, Object actual, String field) {
    if (expected == null || !Objects.equals(expected, actual)) throw new IllegalStateException(field + "不一致");
  }

  private void price(BigDecimal expected, BigDecimal actual) {
    if (expected == null || actual == null || expected.setScale(8, RoundingMode.HALF_UP)
        .compareTo(actual.setScale(8, RoundingMode.HALF_UP)) != 0) throw new IllegalStateException("制造件金额不一致");
  }
}
