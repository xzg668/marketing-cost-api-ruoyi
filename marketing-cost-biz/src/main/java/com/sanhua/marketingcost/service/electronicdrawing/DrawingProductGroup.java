package com.sanhua.marketingcost.service.electronicdrawing;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationSummary;
import java.util.LinkedHashSet;
import java.util.Set;

/** 请求及 PLM 来源核实单个未知分组的归属；只返回原始键，不改写来源证据。 */
public final class DrawingProductGroup {
  private DrawingProductGroup() {}

  public static String keyFor(DrawingVerificationSummary summary, String drawingNo) {
    return keyFor(summary.data().keySet(), drawingNo, summary.plmData());
  }

  public static String keyFor(JsonNode data, String drawingNo, JsonNode plmData) {
    var keys = new LinkedHashSet<String>();
    if (data != null && data.isObject()) data.fieldNames().forEachRemaining(keys::add);
    return keyFor(keys, drawingNo, plmData);
  }

  private static String keyFor(Set<String> keys, String drawingNo, JsonNode plmData) {
    if (drawingNo == null || drawingNo.isBlank()) return null;
    if (keys.contains(drawingNo)) return drawingNo;
    if (keys.size() != 1) return null;
    String only = keys.iterator().next();
    if (!only.matches("_unknown_[1-9][0-9]*")
        || plmData == null || !plmData.isArray() || plmData.isEmpty()) return null;
    // 同图号的多个 PLM 历史版本仍是同一来源；空图号或另一张图不能确认归属。
    for (JsonNode source : plmData) {
      JsonNode number = source.get("drawingNo");
      if (number == null || !number.isTextual()
          || !drawingNo.trim().equalsIgnoreCase(number.textValue().trim())) return null;
    }
    return only;
  }
}
