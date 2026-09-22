package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 对比实际补录内容，排除模块进度、校验时间和版本流水号，避免重提产生虚假差异。 */
@Component
public class TechnicalDataSubmissionSummary {
  public record Change(String field, String changeType, JsonNode before, JsonNode after) {}
  public record ModuleChange(String moduleType, boolean initialSubmission, List<Change> changes) {}
  private final OaMessageCodec json;

  public TechnicalDataSubmissionSummary(OaMessageCodec json) { this.json = json; }

  public String summarize(String currentJson, String previousJson) {
    JsonNode current = json.read(currentJson);
    JsonNode previous = previousJson == null ? null : json.read(previousJson);
    Set<String> required = requiredModules(current);
    if (previous != null) required.addAll(requiredModules(previous));
    List<ModuleChange> modules = new ArrayList<>();
    for (String type : TechnicalDataModuleType.codesForVersion(2)) {
      if (!required.contains(type)) continue;
      List<Change> changes = new ArrayList<>();
      compare("", previous == null ? null : content(previous, type), content(current, type), changes);
      modules.add(new ModuleChange(type, previous == null, List.copyOf(changes)));
    }
    return json.write(modules);
  }

  private Set<String> requiredModules(JsonNode content) {
    Set<String> result = new LinkedHashSet<>();
    for (JsonNode module : content.path("details").path("modules")) {
      if (module.path("required").asBoolean(false)) result.add(module.path("moduleType").asText());
    }
    return result;
  }

  private JsonNode content(JsonNode snapshot, String type) {
    ObjectNode content = JsonNodeFactory.instance.objectNode();
    JsonNode details = snapshot.path("details");
    JsonNode supplements = snapshot.path("supplements");
    switch (type) {
      case "PROFILE" -> { content.set("productProperty", details.path("productProperty")); content.set("productFees", supplements.path("productFees")); }
      case "DRAWING_BOM" -> content.set("drawingBom", supplements.path("drawingBom"));
      case "MANUFACTURING" -> content.set("manufacturing", supplements.path("manufacturing"));
      case "PACKAGE" -> { content.set("packaging", supplements.path("packaging")); content.set("items", details.path("packageItems")); }
      case "AUXILIARY" -> content.set("items", details.path("auxiliaryItems"));
      case "SOLDER" -> content.set("solder", supplements.path("solder"));
      case "SALARY" -> content.set("items", details.path("salaryItems"));
      case "NET_LOSS" -> content.set("netLoss", supplements.path("netLoss"));
      case "PRICE" -> content.set("prices", supplements.path("prices"));
      default -> throw new IllegalArgumentException("不支持的补录模块：" + type);
    }
    return content;
  }

  private void compare(String path, JsonNode before, JsonNode after, List<Change> changes) {
    if (before != null && before.equals(after)) return;
    if (before != null && before.isObject() && after != null && after.isObject()) {
      Set<String> fields = new LinkedHashSet<>();
      before.fieldNames().forEachRemaining(fields::add); after.fieldNames().forEachRemaining(fields::add);
      for (String name : fields) {
        if (!Set.of("lineNo", "sortSeq").contains(name)) compare(path.isEmpty() ? name : path + "." + name, before.get(name), after.get(name), changes);
      }
    } else if (before != null && before.isArray() && after != null && after.isArray()) {
      var oldItems = items(before); var newItems = items(after);
      Set<String> keys = new LinkedHashSet<>(oldItems.keySet()); keys.addAll(newItems.keySet());
      for (String key : keys) compare(path + "[" + key + "]", oldItems.get(key), newItems.get(key), changes);
    } else {
      boolean absentBefore = before == null || before.isMissingNode() || before.isNull();
      boolean absentAfter = after == null || after.isMissingNode() || after.isNull();
      if (!(absentBefore && absentAfter)) changes.add(new Change(path,
          absentBefore ? "ADDED" : absentAfter ? "REMOVED" : "CHANGED", before, after));
    }
  }

  private Map<String, JsonNode> items(JsonNode items) {
    Map<String, JsonNode> values = new LinkedHashMap<>();
    Map<String, Integer> occurrences = new LinkedHashMap<>();
    int index = 0;
    for (JsonNode item : items) {
      String identity = null;
      for (String field : List.of("itemKey", "sourceReferenceId", "componentMaterialNo", "auxiliaryMaterialNo", "processCode", "materialNo")) {
        if (item.hasNonNull(field)) { identity = field + "=" + item.path(field).asText(); break; }
      }
      if (identity == null) identity = "row=" + index;
      int occurrence = occurrences.merge(identity, 1, Integer::sum);
      values.put(identity + "#" + occurrence, item);
      index++;
    }
    return values;
  }
}
