package com.sanhua.marketingcost.integration.drawing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 明确的新协议夹具，仅测试代码使用。 */
public final class DrawingVerificationFixtures {
  private DrawingVerificationFixtures() {}

  public static ObjectNode initial(String externalTaskId) {
    try (var stream = DrawingVerificationFixtures.class.getResourceAsStream("/fixtures/drawing/verify-initial.json")) {
      if (stream == null) throw new IllegalStateException("Missing drawing fixture");
      ObjectNode node = (ObjectNode) new ObjectMapper().readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
      node.put("taskId", externalTaskId);
      return node;
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  public static ObjectNode confirmed(String externalTaskId) {
    ObjectNode node = initial(externalTaskId);
    node.put("status", "confirmed");
    node.put("differenceCount", 2);
    node.put("revision", 3);
    node.put("confirmedAt", 1792550100000L);
    node.put("deliveryStatus", "pendingRetry");
    node.put("deliveryAttempts", 1);
    node.putArray("plm_data");
    return node;
  }

  public static ObjectNode drawing(String externalTaskId, String drawingNo, java.util.List<java.util.List<String>> rows) {
    var node = initial(externalTaskId);
    var drawing = node.putObject("data").putObject(drawingNo);
    drawing.putNull("company");
    var items = drawing.putObject("params").putArray("items");
    var fields = java.util.List.of("no","code","name","qty","mat","grade","hsf","weight","note");
    for (var row : rows) {
      if (row.size() != 9) throw new IllegalArgumentException("Fixture needs nine fields");
      var item = items.addObject();
      for (int i=0;i<fields.size();i++) item.put(fields.get(i),row.get(i));
    }
    node.get("plm_data").forEach(value -> ((ObjectNode)value).put("drawingNo",drawingNo));
    return node;
  }
}
