package com.sanhua.marketingcost.service.jjb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.ProcessingFee;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.formula.workbook.ExcelCellReferences;
import com.sanhua.marketingcost.formula.workbook.LinkedApprovalTable;
import com.sanhua.marketingcost.mapper.FactorUploadBatchMapper;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;

/** Projects the processing input actually used by the saved linked price onto its own sheet row. */
final class JjbProcessingFees {
  private record Batch(Long id, String businessUnit, String org) {}
  private record FeeColumn(int headerRow, String name) {}
  private static final Pattern FEE_HEADER = Pattern.compile(
      "(?:现|原|当前)?(?:(?:含税|不含税|未税)?加工费|加工费[（(](?:含税|不含税|未税)[）)])(?:[（(]元[^）)]*[）)])?\\d*");
  private static final Pattern FEE_VARIABLE = Pattern.compile("\\[process_fee]", Pattern.CASE_INSENSITIVE);
  private final ObjectMapper json;
  private final MonthlyPriceDocumentCodec codec;
  private final FactorUploadBatchMapper batches;
  private final Cache<Batch, Map<String, Map<Integer, FeeColumn>>> feeColumns = Caffeine.newBuilder()
      .maximumSize(64).expireAfterAccess(java.time.Duration.ofMinutes(10)).build();

  JjbProcessingFees(ObjectMapper json, FactorUploadBatchMapper batches) {
    this.json = json;
    this.codec = new MonthlyPriceDocumentCodec(json);
    this.batches = batches;
  }

  ProcessingFee fromPreparation(JsonNode document, String material) {
    var captured = object(document);
    if (!same(material, captured.path("request").path("family"), "materialCode")) return null;
    return fromEvidence(object(captured.path("selection").path("fact").path("evidence").path("json")), material);
  }

  ProcessingFee fromEvidence(JsonNode evidence, String material) {
    var source = evidence.path("source");
    var selected = source.path("selected");
    if (!same(material, selected, "materialCode")) return null;
    return switch (evidence.path("sourceKind").asText()) {
      case "LINKED" -> original(selected, object(source.path("calculationJson")));
      case "LINKED_LEGACY" -> legacy(selected, object(source.path("calculation")));
      default -> null;
    };
  }

  ProcessingFee fromLinkedTrace(JsonNode snapshot, String material) {
    var trace = object(snapshot.path("linkedTrace"));
    var item = snapshot.path("linkedItem");
    if (!same(material, item, "materialCode") || trace.path("result").isMissingNode()
        || trace.path("result").isNull() || text(trace, "error") != null) return null;
    return legacy(item, trace);
  }

  private ProcessingFee legacy(JsonNode selected, JsonNode calculation) {
    String expression = first(text(calculation, "expression"), text(calculation, "normalizedExpr"));
    var amount = decimal(calculation.path("primitiveInputs").path("process_fee"));
    if (amount == null) amount = decimal(calculation.path("inputs").path("process_fee"));
    if (amount == null && expression != null && FEE_VARIABLE.matcher(expression).find())
      amount = decimal(selected.path("processFee"));
    return fee(amount, text(selected, "unit"), taxBasis(text(selected, "formulaExprCn")));
  }

  private ProcessingFee original(JsonNode selected, JsonNode calculation) {
    if (batches == null || !"ORIGINAL_V1".equals(selected.path("expressionVersion").asText())) return null;
    var rule = object(selected.path("sourceInputSnapshotJson")).path("rule");
    var identity = rule.path("identity");
    String unit = text(selected, "unit"), org = text(selected, "orgCode"), group = text(selected, "businessUnitType");
    if (!same(unit, identity, "unit") || !same(org, identity, "orgCode") || !same(group, identity, "businessUnitType")
        || !same(text(selected, "materialCode"), identity, "materialCode")
        || !same(text(selected, "sourceSheetName"), rule, "sheet")
        || !same(text(selected, "sourceFormulaCellRef"), rule, "cell")
        || !same(text(selected, "sourceFormulaExpr"), rule, "sourceFormula")
        || selected.path("sourceRowNumber").asInt(-1) != rule.path("row").asInt(-2)) return null;
    long batchId = selected.path("sourceUploadBatchId").asLong(-1);
    if (batchId <= 0) return null;
    if (!calculation.isEmpty() && (!same(text(selected, "id"), calculation, "ruleId")
        || calculation.path("sourceUploadBatchId").asLong() != batchId
        || !same(text(rule, "sourceFormula"), calculation, "sourceFormula"))) return null;
    var columns = feeColumns.get(new Batch(batchId, group, org), this::readFeeColumns);
    if (columns.isEmpty()) return null;
    var cells = new HashMap<String, JsonNode>();
    for (var cell : rule.path("compiled").path("sources")) {
      var address = cell.path("address");
      cells.put(ExcelCellReferences.key(text(address, "sheet"), text(address, "cell")), cell);
    }
    String sheet = text(rule, "sheet"), root = text(rule, "cell");
    var inputs = new LinkedHashMap<String, ProcessingFee>();
    collect(cells.get(ExcelCellReferences.key(sheet, root)), cells, columns, calculation, rule, unit,
        inputs, new HashSet<>());
    return inputs.size() == 1 ? inputs.values().iterator().next() : null;
  }

  private void collect(JsonNode cell, Map<String, JsonNode> cells, Map<String, Map<Integer, FeeColumn>> columns,
      JsonNode calculation, JsonNode rule, String unit, Map<String, ProcessingFee> inputs, Set<String> visited) {
    if (cell == null) return;
    var address = cell.path("address");
    String sheet = text(address, "sheet"), ref = text(address, "cell");
    String key = ExcelCellReferences.key(sheet, ref);
    if (sheet == null || ref == null || !visited.add(key)) return;
    var position = new CellReference(ref);
    var column = columns.getOrDefault(sheet, Map.of()).get((int) position.getCol());
    if (column != null && position.getRow() > column.headerRow()) {
      String variable = text(cell, "variable");
      BigDecimal amount = variable == null ? decimal(cell.path("rawValue")) : decimal(calculation.path("inputs").path(variable));
      // A referenced Excel subtotal is safe when its saved inputs are constants. Live market-dependent
      // subexpressions are not reconstructed from a workbook cache.
      if (variable == null && usesMarketInput(cell, cells, new HashSet<>())) amount = null;
      String tax = first(taxBasis(column.name()), taxBasis(text(rule, "sourceFormulaText")));
      if (tax == null && isAddendBeforeVatDivision(rule, sheet, ref)) tax = "含税";
      inputs.put(key, fee(amount, unit, tax));
      return;
    }
    for (var dependency : ExcelCellReferences.parse(text(cell, "formula"), sheet))
      collect(cells.get(ExcelCellReferences.key(dependency.sheetName(), dependency.cellRef())), cells, columns,
          calculation, rule, unit, inputs, visited);
  }

  private static boolean usesMarketInput(JsonNode cell, Map<String, JsonNode> cells, Set<String> visited) {
    String sheet = text(cell.path("address"), "sheet");
    if (!visited.add(ExcelCellReferences.key(sheet, text(cell.path("address"), "cell")))) return false;
    if (text(cell, "variable") != null) return true;
    for (var dependency : ExcelCellReferences.parse(text(cell, "formula"), sheet)) {
      var input = cells.get(ExcelCellReferences.key(dependency.sheetName(), dependency.cellRef()));
      if (input == null || usesMarketInput(input, cells, visited)) return true;
    }
    return false;
  }

  /** A fee used directly as an addend in the amount explicitly divided by VAT retains its gross value. */
  private static boolean isAddendBeforeVatDivision(JsonNode rule, String sheet, String ref) {
    if (rule.path("taxIncluded").asBoolean(true)) return false;
    String expression = rule.path("sourceFormula").asText().replaceAll("\\s+", "");
    if (!expression.startsWith("(") || !expression.endsWith(")/1.13")) return false;
    int depth = 1;
    int closing = expression.length() - ")/1.13".length();
    for (int index = 1; index < closing; index++) {
      char token = expression.charAt(index);
      if (token == '(') depth++;
      if (token == ')' && --depth == 0) return false;
    }
    if (depth != 1) return false;
    for (var input : ExcelCellReferences.parse(expression, text(rule, "sheet"))) {
      if (input.start() > 0 && input.end() < expression.length() && sheet.equals(input.sheetName())
          && ref.equals(input.cellRef()) && expression.charAt(input.start() - 1) == '+'
          && input.end() == closing) return true;
    }
    return false;
  }

  private Map<String, Map<Integer, FeeColumn>> readFeeColumns(Batch key) {
    var batch = batches.selectOne(Wrappers.lambdaQuery(FactorUploadBatch.class)
        .select(FactorUploadBatch::getFileSha256, FactorUploadBatch::getSourceWorkbook)
        .eq(FactorUploadBatch::getId, key.id()).eq(FactorUploadBatch::getBusinessUnitType, key.businessUnit())
        .eq(FactorUploadBatch::getPriceOrgCode, key.org()).eq(FactorUploadBatch::getImportType, "LINKED_ORIGINAL"));
    if (batch == null || batch.getSourceWorkbook() == null || !sha256(batch.getSourceWorkbook()).equals(batch.getFileSha256()))
      return Map.of();
    try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(batch.getSourceWorkbook()))) {
      var sheets = new HashMap<String, Map<Integer, FeeColumn>>();
      for (var layout : LinkedApprovalTable.layouts(workbook)) {
        var header = layout.header();
        var columns = new HashMap<Integer, FeeColumn>();
        header.columns().forEach((name, index) -> {
          if (FEE_HEADER.matcher(name.replaceAll("\\s+", "")).matches())
            columns.put(index, new FeeColumn(header.row(), name));
        });
        if (!columns.isEmpty()) sheets.put(header.sheet().getSheetName(), columns);
      }
      return sheets;
    } catch (IOException | RuntimeException invalidArchive) {
      return Map.of();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private JsonNode object(JsonNode value) {
    try {
      var node = value.isTextual() ? json.readTree(value.textValue()) : value;
      if (node != null && node.has("document")) return object(json.readTree(codec.unwrap(node.toString())));
      return node != null && node.isObject() ? node : json.createObjectNode();
    } catch (IOException | IllegalArgumentException | IllegalStateException invalidHistory) {
      return json.createObjectNode();
    }
  }

  private static ProcessingFee fee(BigDecimal amount, String unit, String tax) {
    if (amount == null || unit == null) return null;
    String pricingUnit = unit.replaceFirst("^(?:人民币|CNY)[,，/]\\s*", "").strip();
    if (pricingUnit.isBlank()) return null;
    if (!pricingUnit.startsWith("元/")) pricingUnit = "元/" + pricingUnit;
    return new ProcessingFee(amount.stripTrailingZeros().toPlainString(), pricingUnit, tax);
  }

  private static String taxBasis(String label) {
    if (label == null) return null;
    if (label.contains("不含税加工费") || label.contains("未税加工费") || label.contains("加工费（不含税")
        || label.contains("加工费(不含税") || label.contains("加工费（未税") || label.contains("加工费(未税")) return "未税";
    if (label.contains("含税加工费") || label.contains("加工费（含税") || label.contains("加工费(含税")) return "含税";
    return null;
  }

  private static boolean same(String expected, JsonNode node, String field) {
    return expected != null && expected.equals(text(node, field));
  }

  private static String first(String preferred, String fallback) {
    return preferred == null ? fallback : preferred;
  }

  private static String text(JsonNode node, String field) {
    String value = node.path(field).asText(null);
    return value == null || value.isBlank() ? null : value;
  }

  private static BigDecimal decimal(JsonNode value) {
    if (value.isNumber()) return value.decimalValue();
    if (!value.isTextual()) return null;
    try { return new BigDecimal(value.textValue()); }
    catch (NumberFormatException invalid) { return null; }
  }
}
