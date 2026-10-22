package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceReader;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceRequest;
import java.math.RoundingMode;
import java.util.Objects;

/** 校验并读取报价当时的结算依据；不查询现行价格，历史结果不受后续维护影响。 */
public final class SettlementCalculationEvidence {
  private final ObjectMapper json;

  public SettlementCalculationEvidence(ObjectMapper json) {
    this.json = json;
  }

  public ObjectNode read(PricePrepareItem prepared) {
    try {
      var node = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readTree(prepared.getInputSnapshotJson());
      if (!(node instanceof ObjectNode snapshot)) throw invalid("不是 JSON 对象");
      same(SettlementPriceReader.RESULT_REF_TYPE, snapshot.path("resultRefType").asText(), "来源类型");
      same(SettlementPriceReaderImpl.SOURCE_TABLE, snapshot.path("sourceTable").asText(), "来源表");
      String version = snapshot.path("calculatorVersion").asText();
      boolean legacy = SettlementPriceCalculator.LEGACY_CALCULATOR_VERSION.equals(version);
      if (!legacy) same(SettlementPriceCalculator.CALCULATOR_VERSION, version, "计算版本");
      same(prepared.getResultRefId(), snapshot.path("sourceRecordId").longValue(), "来源 ID");
      if (!(snapshot.get("rule") instanceof ObjectNode capturedRule)) throw invalid("缺少原稿版本");
      var ruleData = capturedRule.deepCopy();
      ruleData.remove("sourceRows");
      var rule = json.treeToValue(ruleData, SettlementPriceRecord.class);
      same(prepared.getResultRefId(), rule.getId(), "原稿 ID");
      same(prepared.getMaterialCode(), rule.getMaterialCode(), "料号");
      same(prepared.getBusinessUnitType(), rule.getBusinessUnitType(), "业务单元");
      same(prepared.getPeriodMonth(), snapshot.path("accountingMonth").asText(), "核算月份");
      same(prepared.getPriceOrgCode(), snapshot.path("consumerOrgCode").asText(), "取价组织");
      same(rule.getPriceType().name(), snapshot.path("priceType").asText(), "结算类型");
      // 复用入口范围约束，保证快照不是跨组织、跨月误挂的同号记录。
      new SettlementPriceRequest(rule.getBusinessUnitType(), rule.getMaterialCode(),
          prepared.getPeriodMonth(), prepared.getPriceOrgCode(), rule.getPriceType());
      if (!rule.isActive() || rule.getRevisionNo() < 1 || rule.getPriceMonth().compareTo(prepared.getPeriodMonth()) > 0)
        throw invalid("原稿月份或修订无效");
      var base = snapshot.path("base").isObject()
          ? json.treeToValue(snapshot.get("base"), SettlementBasePrice.class) : null;
      if (base != null && (base.id() <= 0 || base.revisionNo() < 1
          || base.priceMonth().compareTo(prepared.getPeriodMonth()) > 0)) throw invalid("基价月份或修订无效");
      if (!legacy && base != null) {
        if (base.financial() == null) throw invalid("缺少商用财务发布依据");
        var expected = SettlementBasePrice.fromFinancial(base.financial());
        same(expected.id(), base.id(), "财务发布ID");
        same(expected.businessUnitType(), base.businessUnitType(), "财务业务单元");
        same(expected.priceMonth(), base.priceMonth(), "财务月份");
        same(expected.revisionNo(), base.revisionNo(), "财务修订");
        same(expected.sourceKind(), base.sourceKind(), "财务来源类型");
        if (base.copperPrice().compareTo(expected.copperPrice()) != 0
            || base.zincPrice().compareTo(expected.zincPrice()) != 0) throw invalid("铜锌值与财务发布不一致");
      }
      if (legacy && SettlementFormula.ROUNDED_VERSION.equals(rule.getFormulaVersion())) throw invalid("旧版本不支持新舍入公式");
      var calculated = new SettlementPriceCalculator().calculate(rule, base);
      same(calculated.mode().name(), snapshot.path("mode").asText(), "取价模式");
      if (!snapshot.path("unitPrice").isNumber()
          || calculated.unitPrice().compareTo(snapshot.path("unitPrice").decimalValue()) != 0)
        throw invalid("单价与保存的公式输入不一致");
      // 价格准备表现有精度为 8 位；完整 12 位计算结果继续保存在 JSON 中。
      if (prepared.getUnitPrice() == null || calculated.unitPrice().setScale(8, RoundingMode.HALF_UP)
          .compareTo(prepared.getUnitPrice().setScale(8, RoundingMode.HALF_UP)) != 0)
        throw invalid("价格准备单价不一致");
      return snapshot;
    } catch (Exception failure) {
      throw new IllegalStateException("结算取价底稿校验失败：" + prepared.getMaterialCode(), failure);
    }
  }

  private void same(Object expected, Object actual, String field) {
    if (expected == null || !Objects.equals(expected, actual)) throw invalid(field + "不一致");
  }

  private IllegalStateException invalid(String message) {
    return new IllegalStateException(message);
  }
}
