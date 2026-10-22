package com.sanhua.marketingcost.service.settlement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** 按原稿追加业务修订；相同原稿重导时保留已存历史重量，原稿重量变化时采用新值。 */
@Component
public class SettlementRuleRevisionFactory {
  private final ObjectMapper json;

  public SettlementRuleRevisionFactory(ObjectMapper json) { this.json = json; }

  public SettlementPriceRecord fromImport(SettlementWorkbookParser.Rule rule,
      SettlementWorkbookParser.ParsedWorkbook book, SettlementScope scope, SettlementPriceRecord previous, String actor) {
    var row = new SettlementPriceRecord();
    row.setBusinessUnitType(scope.businessUnitType());
    row.setPriceMonth(scope.priceMonth());
    row.setMaterialCode(SettlementScope.required(rule.materialCode(), "料号", 64));
    row.setMaterialName(SettlementScope.required(rule.materialName(), "名称", 255));
    row.setSpecModel(rule.model());
    row.setPriceType(rule.priceType());
    row.setActive(true);
    row.setRevisionNo(previous != null && previous.getPriceMonth().equals(scope.priceMonth()) ? previous.getRevisionNo() + 1 : 1);
    row.setGivenPrice(rule.givenPrice());
    var formula = rule.formula();
    row.setPlannedPrice(formula.plannedPrice());
    row.setMarkupRatio(formula.markupRatio());
    row.setBaseSettlePrice(formula.baseSettlePrice());
    row.setBudgetCu(formula.budgetCu());
    row.setBudgetZn(formula.budgetZn());
    row.setReferenceCu(formula.referenceCu());
    row.setReferenceZn(formula.referenceZn());
    row.setTaxDivisor(formula.taxDivisor());
    row.setExpression(formula.expression());
    row.setFormulaVersion(formula.version());
    row.setOriginalCopperWeightG(rule.weights().originalCopperG());
    row.setOriginalZincWeightG(rule.weights().originalZincG());
    // 财务确认：新版原稿的空白锌重遵循 Excel 乘法语义，以 0 克计算；原始字段仍保留空白。
    boolean usableSourceWeights = rule.weights().originalCopperG() != null;
    BigDecimal sourceZincWeight = rule.weights().originalZincG() == null
        ? BigDecimal.ZERO : rule.weights().originalZincG();
    boolean changedSource = previous == null
        || !sameNumber(previous.getOriginalCopperWeightG(), rule.weights().originalCopperG())
        || !sameNumber(previous.getOriginalZincWeightG(), rule.weights().originalZincG());
    if (SettlementFormula.ROUNDED_VERSION.equals(formula.version()) && usableSourceWeights
        && (changedSource || !previous.isWeightsConfirmed())) {
      row.setCopperWeightG(rule.weights().originalCopperG());
      row.setZincWeightG(sourceZincWeight);
      row.setWeightsConfirmed(true);
      row.setWeightConfirmationNote("确认导入最终结算原稿重量：" + book.fileName() + " / 第" + rule.sources().getFirst().row() + "行"
          + (rule.weights().originalZincG() == null ? "；原稿锌重为空，按原公式以0克计算" : ""));
      row.setWeightsConfirmedBy(actor);
      row.setWeightsConfirmedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
    } else if (!changedSource && previous != null && previous.isWeightsConfirmed()) {
      row.setCopperWeightG(previous.getCopperWeightG());
      row.setZincWeightG(previous.getZincWeightG());
      row.setWeightsConfirmed(true);
      row.setWeightConfirmationNote(previous.getWeightConfirmationNote());
      row.setWeightsConfirmedBy(previous.getWeightsConfirmedBy());
      row.setWeightsConfirmedAt(previous.getWeightsConfirmedAt());
    }
    row.setUnitBasis("BOM_MATERIAL_UNIT");
    row.setMaterialUnit(previous == null ? null : previous.getMaterialUnit());
    row.setSourceSystem("EXCEL");
    row.setSourceFileName(book.fileName());
    row.setSourceFileSha256(book.sha256());
    row.setSourceFileRef(scope.businessUnitType() + "/" + book.sha256());
    row.setSourceSheet(book.sheet());
    row.setSourceRowsJson(writeJson(rule.sources()));
    row.setSourceFormula(rule.sources().getFirst().resolvedSourceFormula());
    row.setRemark(rule.remark());
    row.setCreatedBy(actor);
    validateNumbers(row);
    row.setContentHash(contentHash(row));
    return row;
  }

  private void validateNumbers(SettlementPriceRecord row) {
    if (row.getSpecModel() != null && row.getSpecModel().length() > 512)
      throw new IllegalArgumentException("结算型号不能超过 512 字符：" + row.getMaterialCode());
    for (var value : new BigDecimal[]{row.getGivenPrice(), row.getPlannedPrice(), row.getBaseSettlePrice()})
      validateDecimal(value, 38, 18, row.getMaterialCode());
    for (var value : new BigDecimal[]{row.getMarkupRatio(), row.getBudgetCu(), row.getBudgetZn(), row.getReferenceCu(),
        row.getReferenceZn(), row.getOriginalCopperWeightG(), row.getOriginalZincWeightG(), row.getCopperWeightG(),
        row.getZincWeightG(), row.getTaxDivisor()}) validateDecimal(value, 24, 12, row.getMaterialCode());
  }

  private boolean sameNumber(BigDecimal first, BigDecimal second) {
    return first == null ? second == null : second != null && first.compareTo(second) == 0;
  }

  private void validateDecimal(BigDecimal value, int precision, int scale, String material) {
    if (value == null) return;
    try {
      if (value.signum() < 0 || value.setScale(scale, RoundingMode.UNNECESSARY).precision() > precision)
        throw new ArithmeticException("overflow");
    } catch (ArithmeticException invalid) {
      throw new IllegalArgumentException("结算数值超出精度或范围，不能静默舍入：" + material, invalid);
    }
  }

  public String contentHash(SettlementPriceRecord row) {
    ObjectNode fields = json.valueToTree(row);
    // 文件版式、修订号和操作时间不是定价变化；每个已存修订仍保留自己的完整来源证据。
    fields.remove(List.of("id", "revisionNo", "contentHash", "createdBy", "createdAt", "sourceSystem", "sourceFileName",
        "sourceFileSha256", "sourceFileRef", "sourceSheet", "sourceRowsJson", "sourceFormula"));
    return hash(fields);
  }

  public String hash(Object value) {
    return SettlementWorkbookParser.sha256(writeJson(canonical(json.valueToTree(value))).getBytes(StandardCharsets.UTF_8));
  }

  private Object canonical(JsonNode node) {
    if (node.isNull()) return null;
    if (node.isNumber()) return node.decimalValue().stripTrailingZeros().toPlainString();
    if (node.isBoolean()) return node.booleanValue();
    if (node.isTextual()) return node.textValue();
    if (node.isArray()) {
      List<Object> values = new ArrayList<>();
      node.forEach(item -> values.add(canonical(item)));
      return values;
    }
    Map<String, Object> values = new TreeMap<>();
    node.fields().forEachRemaining(entry -> values.put(entry.getKey(), canonical(entry.getValue())));
    return values;
  }

  private String writeJson(Object value) {
    try { return json.writeValueAsString(value); }
    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("结算原稿证据序列化失败", failure); }
  }
}
