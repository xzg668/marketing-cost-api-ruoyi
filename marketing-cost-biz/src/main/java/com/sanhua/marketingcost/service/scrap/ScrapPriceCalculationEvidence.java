package com.sanhua.marketingcost.service.scrap;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.PriceResolveEvidence;
import com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService;
import java.math.BigDecimal;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 核算持有完整输入；历史重放只使用本快照，绝不回读当前废料价格。 */
@Component
public class ScrapPriceCalculationEvidence {
  public static final String SOURCE = "SCRAP_PRICE";
  private static final ObjectMapper SOURCE_READER = new ObjectMapper();
  private final ObjectMapper json;
  private final ScrapPriceRuleCodec rules;
  private final ScrapPriceCalculator calculator;

  public ScrapPriceCalculationEvidence(ObjectMapper json) {
    this.json = json;
    this.rules = new ScrapPriceRuleCodec(json);
    this.calculator = new ScrapPriceCalculator(rules);
  }

  public record Source(
      Long monthlyPriceId,
      String businessUnitType,
      String priceMonth,
      String ruleSourceMonth,
      Long ruleId,
      Integer ruleRevision,
      Long rowVersion,
      String importBatchNo,
      String fileSha256,
      String publicInputFingerprint) {}

  public record Supplier(
      String code,
      String name,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal ratio,
      Long ratioRecordId,
      String selectionRule) {}

  public record Snapshot(
      String calculatorVersion,
      String priceSource,
      Source source,
      String consumerOrgCode,
      String financeOrgCode,
      QuotePriceScenarioType scenarioType,
      String currency,
      String unit,
      boolean taxIncluded,
      ScrapPriceRule rule,
      @JsonSerialize(contentUsing = ToStringSerializer.class) Map<String, BigDecimal> inputs,
      PriceFactorContext context,
      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal unitPrice,
      Supplier supplier) {}

  public PriceFactorContext publicFactors(ScrapPrice row) {
    if ("FIXED".equals(row.getPriceType())) return null;
    try {
      var factors = json.readTree(row.getCalculationJson()).get("factors");
      if (factors == null || factors.isNull()) throw new IllegalArgumentException("废料月价缺少基价证据");
      return json.treeToValue(factors, PriceFactorContext.class).financialOnly();
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("废料基价证据无法读取：" + row.getMaterialCode(), error);
    }
  }

  public Snapshot capture(
      ScrapPrice row,
      String consumerOrg,
      QuotePriceScenarioType scenario,
      ScrapPriceCalculator.Input input,
      BigDecimal amount,
      SupplierPriceSelectionService.Selection selected) {
    var ratio = selected.ratio();
    return new Snapshot(
        ScrapPriceCalculator.VERSION,
        SOURCE,
        new Source(
            row.getId(),
            row.getBusinessUnitType(),
            row.getPriceMonth(),
            row.getRuleSourceMonth(),
            row.getSourceRuleId() == null ? row.getId() : row.getSourceRuleId(),
            row.getSourceRuleId() == null ? row.getRuleRevision() : row.getSourceRuleRevision(),
            row.getRowVersion(),
            row.getImportBatchNo(),
            row.getSourceFileSha256(),
            row.getInputFingerprint()),
        consumerOrg,
        "210",
        scenario,
        "CNY",
        "KG",
        false,
        input.rule(),
        input.variables(),
        input.factors(),
        amount,
        new Supplier(
            row.getSupplierCode(),
            row.getSupplierName(),
            ratio == null ? null : ratio.getSupplyRatio(),
            ratio == null ? null : ratio.getId(),
            selected.rule()));
  }

  public PriceResolveEvidence resolveEvidence(Snapshot snapshot) {
    var source = snapshot.source();
    var supplier = snapshot.supplier();
    return new PriceResolveEvidence(
        source.monthlyPriceId(),
        source.importBatchNo(),
        supplier.name(),
        supplier.code(),
        supplier.ratio(),
        supplier.ratioRecordId(),
        null,
        null,
        !source.priceMonth().equals(source.ruleSourceMonth()),
        null,
        rules.encode(snapshot));
  }

  public static boolean isScrap(String encoded) {
    // 只是价源识别；真正读取时仍做完整结构与金额校验。
    if (encoded == null || !encoded.contains(SOURCE)) return false;
    try {
      return SOURCE.equals(SOURCE_READER.readTree(encoded).path("priceSource").asText());
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("废料价格快照损坏", error);
    }
  }

  public Snapshot read(String encoded) {
    try {
      Snapshot snapshot = json.readValue(encoded, Snapshot.class);
      if (!SOURCE.equals(snapshot.priceSource())
          || !ScrapPriceCalculator.VERSION.equals(snapshot.calculatorVersion())
          || !"210".equals(snapshot.financeOrgCode())
          || !"CNY".equals(snapshot.currency())
          || !"KG".equals(snapshot.unit())
          || snapshot.taxIncluded()
          || snapshot.source() == null
          || snapshot.supplier() == null
          || snapshot.unitPrice() == null) throw new IllegalArgumentException("废料价格快照身份或金额口径不完整");
      var row = new ScrapPrice();
      rules.write(snapshot.rule(), row);
      row.setBusinessUnitType(snapshot.source().businessUnitType());
      row.setPriceMonth(snapshot.source().priceMonth());
      row.setRuleSourceMonth(snapshot.source().ruleSourceMonth());
      row.setId(snapshot.source().ruleId());
      row.setRuleRevision(snapshot.source().ruleRevision());
      var input = calculator.prepare(row, snapshot.context());
      if (!sameValues(input.variables(), snapshot.inputs())
          || calculator.calculate(input).compareTo(snapshot.unitPrice()) != 0)
        throw new IllegalArgumentException("废料价格快照的公式输入或金额不一致");
      return snapshot;
    } catch (JsonProcessingException error) {
      throw new IllegalArgumentException("废料价格快照无法读取", error);
    }
  }

  /** P0以废料自己的商用财务快照为准；P1仅Cu改为报价客户价。 */
  public boolean validatePair(
      String material,
      String oaJson,
      String financeJson,
      BigDecimal oaPrice,
      BigDecimal financePrice,
      BigDecimal customerCuPerKg) {
    Snapshot oa = read(oaJson);
    Snapshot finance = read(financeJson);
    if (!material.equals(oa.rule().materialCode())
        || !material.equals(finance.rule().materialCode())
        || oa.scenarioType() != QuotePriceScenarioType.OA_LOCKED
        || finance.scenarioType() != QuotePriceScenarioType.FINANCE_QUOTE_BASE
        || !java.util.Objects.equals(oa.consumerOrgCode(), finance.consumerOrgCode())
        || !json.valueToTree(oa.source()).equals(json.valueToTree(finance.source()))
        || !json.valueToTree(oa.rule()).equals(json.valueToTree(finance.rule()))
        || !json.valueToTree(oa.supplier()).equals(json.valueToTree(finance.supplier())))
      throw new IllegalStateException("废料两侧的范围、规则或选供依据不一致：" + material);
    requireAmount(material, oaPrice, oa.unitPrice());
    requireAmount(material, financePrice, finance.unitPrice());
    boolean copper = oa.rule().factorBindings().containsValue(PriceFactorContext.COPPER);
    if (oa.context() != null || finance.context() != null) {
      if (oa.context() == null
          || finance.context() == null
          || !json.valueToTree(oa.context().financialOnly())
              .equals(json.valueToTree(finance.context().financialOnly()))
          || finance.context().customerCopperPricePerTon() != null)
        throw new IllegalStateException("废料两侧的商用基价依据不一致：" + material);
      if (copper) {
        BigDecimal customer = oa.context().customerCopperPricePerTon();
        if (customer == null
            || customerCuPerKg == null
            || customer.movePointLeft(3).compareTo(customerCuPerKg) != 0)
          throw new IllegalStateException("废料客户Cu与报价版本不一致：" + material);
      }
    }
    if (!copper && oa.unitPrice().compareTo(finance.unitPrice()) != 0)
      throw new IllegalStateException("非铜废料两侧单价不一致：" + material);
    return copper;
  }

  private void requireAmount(String material, BigDecimal actual, BigDecimal captured) {
    if (actual == null
        || actual
                .setScale(8, java.math.RoundingMode.HALF_UP)
                .compareTo(captured.setScale(8, java.math.RoundingMode.HALF_UP))
            != 0) throw new IllegalStateException("废料单价与捕获快照不一致：" + material);
  }

  private boolean sameValues(Map<String, BigDecimal> expected, Map<String, BigDecimal> actual) {
    return actual != null
        && expected.keySet().equals(actual.keySet())
        && expected.entrySet().stream()
            .allMatch(
                e ->
                    actual.get(e.getKey()) != null
                        && e.getValue().compareTo(actual.get(e.getKey())) == 0);
  }
}
