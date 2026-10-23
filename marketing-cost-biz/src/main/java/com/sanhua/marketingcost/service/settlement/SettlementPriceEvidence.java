package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceRequest;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Component;

/** 月度结果保存完整计算输入，核算时只补充消费月份/组织；旧底稿字段继续兼容。 */
@Component
public class SettlementPriceEvidence {
  private final ObjectMapper json;
  public SettlementPriceEvidence(ObjectMapper json) { this.json = json; }

  public String capture(SettlementPriceRecord rule, SettlementBasePrice base, SettlementPriceCalculator.Calculation value) {
    try {
      ObjectNode source = json.valueToTree(rule);
      source.remove("sourceRowsJson");
      source.set("sourceRows", json.readTree(rule.getSourceRowsJson()));
      var snapshot = json.createObjectNode();
      snapshot.put("sourceTable", SettlementPriceReaderImpl.SOURCE_TABLE);
      snapshot.put("resultRefType", com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceReader.RESULT_REF_TYPE);
      snapshot.put("sourceRecordId", rule.getId());
      snapshot.put("priceType", rule.getPriceType().name());
      snapshot.put("mode", value.mode().name());
      snapshot.set("rule", source);
      snapshot.set("base", json.valueToTree(base));
      snapshot.put("unitPrice", value.unitPrice());
      snapshot.put("unitPriceScale", SettlementPriceCalculator.UNIT_PRICE_SCALE);
      snapshot.put("calculatorVersion", SettlementPriceCalculator.CALCULATOR_VERSION);
      return json.writeValueAsString(snapshot);
    } catch (Exception failure) { throw new IllegalStateException("结算计算依据无法保存", failure); }
  }

  public PriceResolveEvidence forRequest(SettlementPriceRequest request, String captured,
      SettlementEffectivePriceRepository.Result result) {
    try {
      ObjectNode snapshot = (ObjectNode) json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(captured);
      var rule = snapshot.path("rule");
      String ruleMonth = rule.path("priceMonth").asText();
      String baseMonth = snapshot.path("base").path("priceMonth").asText();
      boolean ruleCarry = ruleMonth.compareTo(request.accountingMonth()) < 0;
      boolean baseCarry = !baseMonth.isBlank() && baseMonth.compareTo(request.accountingMonth()) < 0;
      snapshot.put("accountingMonth", request.accountingMonth());
      snapshot.put("consumerOrgCode", request.consumerOrgCode());
      snapshot.put("ruleCarriedForward", ruleCarry);
      snapshot.put("baseCarriedForward", baseCarry);
      if (result != null) {
        if (rule.path("id").asLong() != result.ruleId() || !snapshot.path("unitPrice").isNumber()
            || snapshot.path("unitPrice").decimalValue().compareTo(result.unitPrice()) != 0
            || !snapshot.path("mode").asText().equals(result.priceMode()))
          throw new IllegalStateException("结算月度结果与保存依据不一致");
        var monthly = snapshot.putObject("effectivePrice");
        monthly.put("id", result.id()); monthly.put("priceMonth", result.effectiveMonth());
        monthly.put("revisionNo", result.revisionNo()); monthly.put("createdAt", result.createdAt().toString());
      }
      List<String> notes = new ArrayList<>();
      if (ruleCarry) notes.add("沿用 " + ruleMonth + " 结算原稿");
      if (baseCarry) notes.add("沿用 " + baseMonth + " 商用财务基价");
      long id = rule.path("id").asLong();
      return new PriceResolveEvidence(id, "STP-" + id + "-R" + rule.path("revisionNo").asInt(),
          null, null, null, null, YearMonth.parse(ruleMonth).atDay(1), null, ruleCarry || baseCarry,
          notes.isEmpty() ? null : String.join("；", notes), json.writeValueAsString(snapshot));
    } catch (Exception failure) { throw new IllegalStateException("结算月度结果依据读取失败", failure); }
  }
}
