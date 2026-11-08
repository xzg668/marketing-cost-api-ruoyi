package com.sanhua.marketingcost.service.costing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.Selection;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import java.util.*;
import org.springframework.stereotype.Component;

/** Saved-cost storage format. Public facts are restored by immutable adopted ID, never current lookup. */
@Component
public final class MonthlyCostSnapshotDocuments {
  private static final String REFERENCES = "monthlyPriceReferences";
  private final MonthlyPriceDocumentCodec codec;
  private final MonthlyMaterialPrices prices;

  public MonthlyCostSnapshotDocuments(MonthlyPriceDocumentCodec codec, MonthlyMaterialPrices prices) {
    this.codec = codec;
    this.prices = prices;
  }

  /** The outer envelope stays plain: SQL reverse references use adoptedPrices.selection.monthlyPriceId. */
  public String write(MonthlyCostVersionService.Snapshot snapshot, boolean shareAdoptedFacts) {
    String encoded = codec.encode(snapshot);
    if (!shareAdoptedFacts) return codec.unwrap(encoded);
    var document = codec.decode(encoded, ObjectNode.class);
    document.put(REFERENCES, 1);
    for (var row : adopted(document)) {
      var selected = codec.decode(codec.wrap(row.required("selection").toString()), Selection.class);
      if (selected.monthlyPriceId() == null || selected.monthlyPriceId() <= 0)
        throw new IllegalArgumentException("保存成本必须引用已采用月价");
      var reference = ((ObjectNode) row).putObject("selection");
      reference.put("monthlyPriceId", selected.monthlyPriceId());
      reference.put("sha256", fingerprint(selected));
    }
    return codec.unwrap(codec.encode(document));
  }

  public MonthlyCostVersionService.Snapshot read(String unit, String encoded) {
    return readBatch(unit, Map.of(0L, encoded)).get(0L);
  }

  /** One bounded page shares the historic fact read across all of its customer versions. */
  public Map<Long, MonthlyCostVersionService.Snapshot> readBatch(String unit, Map<Long, String> encoded) {
    if (encoded.size() > 1000) throw new IllegalArgumentException("保存成本读取超过每页上限");
    var documents = new LinkedHashMap<Long, ObjectNode>();
    var ids = new TreeSet<Long>();
    encoded.forEach((key, value) -> {
      var document = codec.decode(value, ObjectNode.class);
      if (!Objects.equals(unit, document.path("businessUnitType").asText()))
        throw new IllegalArgumentException("保存成本不属于当前业务单元");
      if (document.has(REFERENCES)) {
        if (!document.path(REFERENCES).isIntegralNumber() || !document.path(REFERENCES).canConvertToInt()
            || document.path(REFERENCES).intValue() != 1)
          throw new IllegalArgumentException("不支持的保存月价引用格式");
        for (var row : adopted(document)) {
          var reference = row.required("selection");
          if (!reference.isObject() || reference.size() != 2 || !reference.path("monthlyPriceId").canConvertToLong()
              || !reference.path("monthlyPriceId").isIntegralNumber() || reference.path("monthlyPriceId").longValue() <= 0
              || !reference.path("sha256").asText().matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("保存成本月价引用不完整");
          ids.add(reference.path("monthlyPriceId").longValue());
        }
      }
      documents.put(key, document);
    });
    // readByIds chunks its SQL and restores original applicability, including superseded monthly facts.
    var saved = ids.isEmpty() ? Map.<Long, Selection>of() : prices.readByIds(unit, List.copyOf(ids));
    var fingerprints = new HashMap<Long, String>();
    var result = new LinkedHashMap<Long, MonthlyCostVersionService.Snapshot>();
    documents.forEach((key, document) -> {
      if (document.has(REFERENCES)) {
        for (var row : adopted(document)) {
          var reference = row.required("selection");
          long id = reference.path("monthlyPriceId").longValue();
          var selected = saved.get(id);
          if (selected == null || !Objects.equals(selected.monthlyPriceId(), id)
              || !reference.path("sha256").asText().equals(fingerprints.computeIfAbsent(id, ignored -> fingerprint(selected))))
            throw new IllegalArgumentException("保存成本引用的历史月价缺失或已被修改");
          var request = codec.decode(codec.wrap(row.required("request").toString()), MonthlyPriceRequest.class);
          if (!unit.equals(request.family().businessUnitType())
              || !document.path("pricingMonth").asText().equals(request.family().month().toString())
              || !selected.applicability().matches(request))
            throw new IllegalArgumentException("保存成本月价引用适用范围不一致");
          ((ObjectNode) row).set("selection", codec.decode(codec.encode(selected), ObjectNode.class));
        }
        document.remove(REFERENCES);
      }
      result.put(key, codec.decode(codec.wrap(document.toString()), MonthlyCostVersionService.Snapshot.class));
    });
    return Map.copyOf(result);
  }

  private static JsonNode adopted(ObjectNode document) {
    var rows = document.required("calculation").required("adoptedPrices");
    if (!rows.isArray()) throw new IllegalArgumentException("保存成本缺少完整的已采用月价集合");
    return rows;
  }

  /** Canonical coverage/dependency order and exact numeric values make this stable across worker JVMs. */
  private String fingerprint(Selection selected) {
    var fact = selected.fact();
    var fields = new TreeMap<String, Object>();
    fields.put("id", selected.monthlyPriceId());
    fields.put("applicability", selected.applicability().canonical());
    fields.put("type", fact.type());
    fields.put("price", fact.price());
    fields.put("householdRate", fact.householdRate());
    fields.put("evidence", fact.evidence().sha256());
    fields.put("dependencies", fact.dependencies().stream()
        .map(request -> SourceEvidence.ofJson(codec.unwrap(codec.encode(request))).json()).sorted().toList());
    return SourceEvidence.ofJson(codec.unwrap(codec.encode(fields))).sha256();
  }
}
