package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceReader;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceRequest;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务维护视图与核算共用批量取价；事务保证规则列表与计算依据属于同一读取快照。 */
@Service
public class SettlementPriceQuery {
  public record Entry(JsonNode rule, String unitPrice, String mode, JsonNode base,
      boolean ruleCarriedForward, boolean baseCarriedForward, String failureCode, String message) {}

  private final SettlementPriceRepository prices;
  private final SettlementPriceReader reader;
  private final ObjectMapper json;

  public SettlementPriceQuery(SettlementPriceRepository prices, SettlementPriceReader reader, ObjectMapper json) {
    this.prices = prices;
    this.reader = reader;
    this.json = json;
  }

  @Transactional(readOnly = true)
  public List<Entry> list(SettlementScope scope, String materialCode, SettlementPriceType priceType) {
    var requests = prices.effective(scope, materialCode, priceType).stream().map(row ->
        new SettlementPriceRequest(scope.businessUnitType(), row.getMaterialCode(), scope.priceMonth(), "210", row.getPriceType())).toList();
    var results = reader.resolveBatch(requests);
    return requests.stream().map(request -> entry(results.get(request))).toList();
  }

  private Entry entry(PriceResolveResult result) {
    try {
      JsonNode snapshot = json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
          .readTree(result.evidence().inputSnapshotJson());
      // 页面编辑保留数据库十进制精度，不经过 JavaScript Number 往返。
      decimalStrings(snapshot.path("rule"), "givenPrice", "plannedPrice", "markupRatio", "baseSettlePrice",
          "budgetCu", "budgetZn", "referenceCu", "referenceZn", "originalCopperWeightG", "originalZincWeightG",
          "copperWeightG", "zincWeightG", "taxDivisor");
      var base = snapshot.path("base");
      decimalStrings(base, "copperPrice", "zincPrice");
      return new Entry(snapshot.path("rule"), result.unitPrice() == null ? null : result.unitPrice().toPlainString(),
          snapshot.path("mode").asText(), base.isNull() ? null : base,
          snapshot.path("ruleCarriedForward").asBoolean(), snapshot.path("baseCarriedForward").asBoolean(),
          result.failureCode(), result.remark());
    } catch (JsonProcessingException invalid) {
      throw new IllegalStateException("结算价格维护依据读取失败", invalid);
    }
  }

  private static void decimalStrings(JsonNode node, String... fields) {
    if (!(node instanceof ObjectNode object)) return;
    for (String field : fields) {
      JsonNode value = object.path(field);
      if (value.isNumber()) object.put(field, value.decimalValue().toPlainString());
    }
  }
}
