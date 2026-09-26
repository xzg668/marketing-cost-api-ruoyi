package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 将本人本次提交的冻结内容转成 OA 单字段说明；不读取当前草稿，不查询或发送 OA。 */
@Component
public class TechnicalDataSubmissionRemark {
  private final ObjectMapper json;

  public TechnicalDataSubmissionRemark(ObjectMapper json) {
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  /** productLabel 由调用方从提交关联的产品取得（料号；无料号时用明确的型号/明细行标识）。 */
  public String generate(String productLabel, QuoteTechSubmission submission) {
    if (productLabel == null || productLabel.isBlank() || submission == null
        || !Integer.valueOf(2).equals(submission.getContentSchemaVersion())) {
      throw invalid("需要产品标识和九模块提交记录");
    }
    JsonNode snapshot = read(submission.getContentSnapshotJson());
    if (snapshot.path("schemaVersion").asInt() != 2) throw invalid("提交快照版本不是 2");
    JsonNode details = object(snapshot, "details");
    JsonNode supplements = object(snapshot, "supplements");
    EnumSet<TechnicalDataModuleType> scope = EnumSet.noneOf(TechnicalDataModuleType.class);
    JsonNode types = read(submission.getModuleTypesJson());
    if (!types.isArray() || types.isEmpty()) throw invalid("本次提交模块为空");
    for (JsonNode type : types) {
      try {
        if (!type.isTextual() || !scope.add(TechnicalDataModuleType.valueOf(type.asText()))) {
          throw invalid("本次提交模块重复或格式错误");
        }
      } catch (IllegalArgumentException exception) {
        throw invalid("本次提交包含未知、重复或无效模块");
      }
    }
    var recorded = EnumSet.noneOf(TechnicalDataModuleType.class);
    for (JsonNode module : rows(details, "modules")) {
      if (module.path("required").asBoolean()) {
        try { recorded.add(TechnicalDataModuleType.valueOf(module.path("moduleType").asText())); }
        catch (IllegalArgumentException exception) { throw invalid("快照包含未知模块"); }
      }
    }
    if (!recorded.containsAll(scope)) throw invalid("本次提交模块与冻结快照不一致");

    List<String> sections = new ArrayList<>();
    for (TechnicalDataModuleType type : scope) {
      String value = switch (type) {
        case PROFILE -> profile(details, object(supplements, "productFees"));
        case DRAWING_BOM -> drawing(object(supplements, "drawingBom"));
        case MANUFACTURING -> manufacturing(object(supplements, "manufacturing"));
        case PACKAGE -> packaging(details, object(supplements, "packaging"));
        case AUXILIARY -> auxiliary(details);
        case SOLDER -> solder(object(supplements, "solder"));
        case SALARY -> salary(details);
        case NET_LOSS -> netLoss(object(supplements, "netLoss"));
        case PRICE -> prices(object(supplements, "prices"));
      };
      if (value.isBlank()) throw invalid(type.displayName() + "缺少实际内容");
      sections.add(type.displayName() + "[" + value + "]");
    }
    // 只压缩格式，不截断明细；OA 字段长度尚无明确契约，不能静默丢掉已提交内容。
    return "产品" + compact(productLabel) + "：" + String.join("；", sections) + "。";
  }

  private String profile(JsonNode details, JsonNode fees) {
    List<String> fields = new ArrayList<>();
    fields.add("属性" + requiredText(details, "productProperty"));
    JsonNode included = fees.path("includesNewToolingMouldCertificationFee");
    if (!included.isBoolean()) throw invalid("产品费用选项缺失");
    fields.add("新增工装模具认证费" + (included.asBoolean() ? "是" : "否"));
    String unit = currency(fees) + "/件";
    fields.add("工装" + number(fees, "unitToolingFee") + unit);
    fields.add("模具" + number(fees, "unitMouldFee") + unit);
    fields.add("认证" + number(fees, "unitCertificationFee") + unit);
    return join(fields);
  }

  private String drawing(JsonNode content) {
    JsonNode nodes = rows(content, "nodes");
    Map<String, String> names = new HashMap<>();
    for (JsonNode node : nodes) names.put(requiredText(node, "itemKey"), itemName(node));
    List<String> items = new ArrayList<>();
    for (JsonNode node : nodes) {
      List<String> fields = new ArrayList<>();
      String parent = text(node, "parentItemKey");
      if (!parent.isEmpty() && !names.containsKey(parent)) throw invalid("图库明细父项缺失");
      fields.add((parent.isEmpty() ? "" : names.get(parent) + "→") + itemName(node));
      addText(fields, node, "drawingNo", "图号");
      addText(fields, node, "specification", "规格");
      // 当前图库快照没有数量单位，保留数量本身，不能把它擅自解释为重量或件数。
      fields.add("数量" + number(node, "quantityPerParent") + text(node, "unit") + "/母件");
      addNumber(fields, node, "sourceWeight", "重量", text(node, "sourceWeightUnit"));
      addText(fields, node, "sourceMaterial", "材质");
      addText(fields, node, "sourceRemark", "备注");
      items.add(join(fields));
    }
    return items(items);
  }

  private String manufacturing(JsonNode content) {
    List<String> items = new ArrayList<>();
    for (JsonNode row : rows(content, "items")) {
      List<String> fields = new ArrayList<>();
      fields.add(requiredText(row, "parentMaterialNo") + "→" + requiredText(row, "rawMaterialNo"));
      addNumber(fields, row, "netLengthMm", "净长", "mm");
      addNumber(fields, row, "grossWeightKg", "毛重", "kg");
      fields.add("用量" + number(row, "quantityPerParent") + requiredText(row, "unit") + "/制造件");
      items.add(join(fields));
    }
    return items(items);
  }

  private String packaging(JsonNode details, JsonNode content) {
    List<String> fields = new ArrayList<>();
    addText(fields, content, "referenceMaterialNo", "参考料号");
    addText(fields, content, "parentMaterialNo", "母件");
    fields.add("母件用量" + number(content, "parentQuantity") + "组件/件产品");
    for (JsonNode row : rows(details, "packageItems")) {
      List<String> item = new ArrayList<>();
      addText(item, row, "componentMaterialNo", "");
      addText(item, row, "componentName", "");
      JsonNode evidence = evidence(row);
      if (!text(evidence, "model").equals(text(row, "componentMaterialNo"))) addText(item, evidence, "model", "型号");
      addText(item, row, "componentSpec", "规格");
      item.add("用量" + number(row, "quantity") + requiredText(row, "originalUnit") + "/组件");
      addText(item, row, "remark", "备注");
      fields.add("子件(" + join(item) + ")");
    }
    return join(fields);
  }

  private String auxiliary(JsonNode details) {
    List<String> items = new ArrayList<>();
    for (JsonNode row : rows(details, "auxiliaryItems")) {
      List<String> fields = new ArrayList<>();
      addText(fields, row, "auxiliaryMaterialNo", "");
      addText(fields, row, "auxiliaryName", "");
      addText(fields, row, "subjectCode", "科目");
      if (!text(row, "subjectName").equals(text(row, "auxiliaryName"))) addText(fields, row, "subjectName", "");
      fields.add(number(row, "amount") + "元/只");
      JsonNode source = evidence(row);
      addText(fields, source.path("cms"), "materialNo", "参考料号");
      JsonNode uploaded = source.path("upload").path("item");
      addText(fields, uploaded, "partName", "部件");
      addText(fields, uploaded, "processName", "工序");
      addText(fields, row, "remark", "备注");
      items.add(join(fields));
    }
    return items(items);
  }

  private String solder(JsonNode content) {
    List<String> fields = new ArrayList<>();
    addText(fields, content.path("reference"), "materialNo", "参考料号");
    for (JsonNode row : rows(content, "items")) {
      fields.add(requiredText(row, "materialNo") + "用量" + number(row, "quantityPerProduct")
          + requiredText(row, "unit") + "/产品");
    }
    return fields.isEmpty() ? "无明细" : join(fields);
  }

  private String salary(JsonNode details) {
    JsonNode rows = rows(details, "salaryItems");
    List<String> references = new ArrayList<>();
    for (JsonNode row : rows) references.add(text(evidence(row).path("reference"), "materialNo"));
    String sharedReference = !references.isEmpty() && !references.getFirst().isEmpty()
        && references.stream().distinct().count() == 1 ? references.getFirst() : "";
    List<String> items = new ArrayList<>();
    for (int index = 0; index < rows.size(); index++) {
      JsonNode row = rows.get(index);
      String name = switch (requiredText(row, "laborType")) {
        case "DIRECT" -> "直接人工";
        case "INDIRECT" -> "辅助人工";
        default -> throw invalid("未知工资类型");
      };
      String item = name + number(row, "amount") + "元/只";
      String reference = references.get(index);
      if (sharedReference.isEmpty() && !reference.isEmpty()) item += "(参考料号" + reference + ")";
      items.add(item);
    }
    return (sharedReference.isEmpty() ? "" : "参考料号" + sharedReference + "，") + join(items);
  }

  private String netLoss(JsonNode content) {
    List<String> fields = new ArrayList<>();
    fields.add(decimal(content, "rate").movePointRight(2).stripTrailingZeros().toPlainString() + "%");
    addText(fields, content.path("reference").path("source"), "materialNo", "参考料号");
    return join(fields);
  }

  private String prices(JsonNode content) {
    List<String> items = new ArrayList<>();
    for (JsonNode row : rows(content, "items")) {
      List<String> fields = new ArrayList<>();
      fields.add(requiredText(row, "materialNo"));
      addText(fields, row, "organizationCode", "组织");
      String unit = currency(row) + "/" + requiredText(row, "unit");
      String mode = requiredText(row, "entryMode");
      if ("FIXED".equals(mode)) {
        fields.add("不含税单价" + number(row, "unitPrice") + unit);
      } else if ("REFERENCE".equals(mode) || "MANUAL".equals(mode)) {
        fields.add("不含税公式(" + unit + ")=" + requiredText(row, "formula"));
        addText(fields, row, "referenceMaterialNo", "参考料号");
        if (row.hasNonNull("parameters")) {
          JsonNode parameters = object(row, "parameters");
          addNumber(fields, parameters, "blankWeight", "下料重", text(parameters, "weightUnit"));
          addNumber(fields, parameters, "netWeight", "净重", text(parameters, "weightUnit"));
          addNumber(fields, parameters, "processFee", "加工费", text(parameters, "feeUnit"));
          addNumber(fields, parameters, "agentFee", "代理费", text(parameters, "feeUnit"));
        }
      } else throw invalid("未知价格填写方式");
      addText(fields, row, "notes", "备注");
      items.add(join(fields));
    }
    return items(items);
  }

  private JsonNode evidence(JsonNode item) {
    String value = text(item, "sourceSnapshotJson");
    return value.isEmpty() ? json.createObjectNode() : read(value);
  }

  private JsonNode read(String value) {
    if (value == null || value.isBlank()) throw invalid("提交快照为空");
    try {
      JsonNode result = json.readTree(value);
      if (result == null || result.isNull()) throw invalid("提交快照为空");
      return result;
    } catch (JsonProcessingException exception) {
      throw invalid("提交快照无法解析");
    }
  }

  private static JsonNode object(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (!value.isObject()) throw invalid(field + "内容缺失");
    return value;
  }

  private static JsonNode rows(JsonNode parent, String field) {
    JsonNode value = parent.path(field);
    if (!value.isArray()) throw invalid(field + "明细缺失");
    for (JsonNode row : value) if (!row.isObject()) throw invalid(field + "明细格式错误");
    return value;
  }

  private static String itemName(JsonNode row) {
    List<String> fields = new ArrayList<>();
    addText(fields, row, "materialNo", "");
    addText(fields, row, "name", "");
    if (fields.isEmpty()) throw invalid("明细缺少料号和名称");
    return String.join("/", fields);
  }

  private static void addText(List<String> fields, JsonNode row, String key, String label) {
    String value = text(row, key);
    if (!value.isEmpty()) fields.add(label + value);
  }

  private static void addNumber(List<String> fields, JsonNode row, String key, String label, String unit) {
    if (row.hasNonNull(key)) fields.add(label + number(row, key) + unit);
  }

  private static String text(JsonNode row, String key) {
    JsonNode value = row.path(key);
    if (value.isMissingNode() || value.isNull()) return "";
    if (!value.isTextual()) throw invalid(key + "应为文本");
    return compact(value.asText());
  }

  private static String requiredText(JsonNode row, String key) {
    String value = text(row, key);
    if (value.isEmpty()) throw invalid(key + "缺失");
    return value;
  }

  private static BigDecimal decimal(JsonNode row, String field) {
    JsonNode value = row.path(field);
    if (!value.isNumber()) throw invalid(field + "数值缺失或格式错误");
    return value.decimalValue();
  }

  private static String number(JsonNode row, String field) {
    return decimal(row, field).stripTrailingZeros().toPlainString();
  }

  private static String currency(JsonNode row) {
    String value = requiredText(row, "currency");
    return "CNY".equals(value) ? "元" : value;
  }

  private static String compact(String value) { return value.replaceAll("[\\p{Cntrl}\\s]+", " ").trim(); }
  private static String join(List<String> fields) { return String.join("，", fields); }
  private static String items(List<String> items) { return items.isEmpty() ? "无明细" : String.join("；", items); }
  private static IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException("无法生成补录说明：" + message);
  }
}
