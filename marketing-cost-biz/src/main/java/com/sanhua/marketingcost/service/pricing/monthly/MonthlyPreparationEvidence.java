package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.entity.PricePrepareItem;

/** Preserves existing calculator evidence while carrying its exact monthly price inputs. */
public final class MonthlyPreparationEvidence {
  private final MonthlyPriceDocumentCodec codec;
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

  public MonthlyPreparationEvidence(MonthlyPriceDocumentCodec codec) { this.codec = codec; }

  public String attach(PricePrepareItem row, MonthlyPreparationInputs inputs) {
    if (MonthlyPreparationPrice.KIND.equals(row.getResultRefType())) {
      return codec.encode(codec.decode(row.getInputSnapshotJson(), MonthlyPreparationPrice.class).withInputs(inputs));
    }
    try {
      var original = json.readTree(row.getInputSnapshotJson());
      if (!(original instanceof ObjectNode object)
          || !java.util.Set.of("MAKE_PART_V1", "PACKAGE_PRICE_V1").contains(object.path("calculatorVersion").asText()))
        throw new IllegalArgumentException("当前聚合准备缺少制造或包装计算底稿");
      object.put("monthlyInputs", codec.encode(inputs));
      return json.writeValueAsString(object);
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("准备计算底稿损坏", failure); }
  }

  /** Null is reserved for explicit old-history evidence, never invented from current sources. */
  public MonthlyPreparationInputs read(PricePrepareItem row) {
    if (row.getInputSnapshotJson() == null || row.getInputSnapshotJson().isBlank()) return null;
    if (MonthlyPreparationPrice.KIND.equals(row.getResultRefType()))
      return codec.decode(row.getInputSnapshotJson(), MonthlyPreparationPrice.class).monthlyInputs();
    try {
      var inputs = json.readTree(row.getInputSnapshotJson()).get("monthlyInputs");
      if (inputs == null) return null;
      if (!inputs.isTextual()) throw new IllegalArgumentException("聚合准备月价依据必须完整保存为文本信封");
      return codec.decode(inputs.textValue(), MonthlyPreparationInputs.class);
    } catch (java.io.IOException failure) { throw new IllegalArgumentException("准备计算底稿损坏", failure); }
  }
}
