package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrawingJsonNodesTest {
  @Test
  void requestAndMatchingPlmBindSingleUnknownGroupWithoutChangingProviderEvidence() {
    var raw = com.sanhua.marketingcost.integration.drawing.DrawingVerificationFixtures.drawing(
        "unknown-group", "PRODUCT-MX", List.of(
            List.of("001", "PART-A", "母件", "2.5000", "钢", "B", "C", "", "原文"),
            List.of("001.01", "PART-B", "子件", "3.0000", "铜", "B", "C", "", "")));
    var data = (com.fasterxml.jackson.databind.node.ObjectNode) raw.get("data");
    data.set("_unknown_1", data.remove("PRODUCT-MX"));
    raw.put("status", "reviewRequired");
    raw.put("verificationStatus", "partial");
    raw.putArray("reviewReasons").add("drawingUncertain");
    String original = raw.toString();
    var payload = new com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec(new ObjectMapper()).parse(original);

    var rows = DrawingJsonNodes.forProduct(payload.summary(), "PRODUCT-MX");

    assertThat(rows).hasSize(2);
    assertThat(rows.getFirst().getQty().toPlainString()).isEqualTo("2.5000");
    assertThat(rows.get(1).getParentSourceSequence()).isEqualTo("G1:R1");
    assertThat(payload.rawJson()).isEqualTo(original);
    assertThat(payload.summary().data()).containsOnlyKeys("_unknown_1");
  }

  @Test
  void unknownGroupRequiresUniqueMatchingPlmAndNeverSubstitutesANamedOtherProduct() throws Exception {
    var json = new ObjectMapper();
    var base = com.sanhua.marketingcost.integration.drawing.DrawingVerificationFixtures.drawing(
        "unknown-guards", "PRODUCT-MX", List.of(
            List.of("1", "PART-A", "部件", "1", "", "", "", "", "")));
    var data = (com.fasterxml.jackson.databind.node.ObjectNode) base.get("data");
    data.set("_unknown_1", data.remove("PRODUCT-MX"));
    var codec = new com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec(json);
    for (String source : List.of("[]", "[{\"drawingNo\":\"OTHER-MX\"}]",
        "[{\"drawingNo\":\"PRODUCT-MX\"},{\"drawingNo\":\"OTHER-MX\"}]",
        "[{\"drawingNo\":\"PRODUCT-MX\"},{}]", "[{\"drawingNo\":null}]")) {
      var raw = base.deepCopy();
      raw.set("plm_data", json.readTree(source));
      assertThatThrownBy(() -> DrawingJsonNodes.forProduct(codec.parse(raw.toString()).summary(), "PRODUCT-MX"))
          .isInstanceOf(ElectronicDrawingSourceImportException.class).hasMessageContaining("本产品图号");
    }
    var multiple = base.deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) multiple.get("data"))
        .set("_unknown_2", data.get("_unknown_1").deepCopy());
    assertThatThrownBy(() -> DrawingJsonNodes.forProduct(codec.parse(multiple.toString()).summary(), "PRODUCT-MX"))
        .isInstanceOf(ElectronicDrawingSourceImportException.class);
    var namedOther = base.deepCopy();
    var otherData = (com.fasterxml.jackson.databind.node.ObjectNode) namedOther.get("data");
    otherData.set("OTHER-MX", otherData.remove("_unknown_1"));
    assertThatThrownBy(() -> DrawingJsonNodes.forProduct(codec.parse(namedOther.toString()).summary(), "PRODUCT-MX"))
        .isInstanceOf(ElectronicDrawingSourceImportException.class);
    // 同图号多个 PLM 版本没有图号歧义，来源记录全部保留。
    ((com.fasterxml.jackson.databind.node.ArrayNode) base.get("plm_data"))
        .add(base.get("plm_data").get(0).deepCopy());
    assertThat(DrawingJsonNodes.forProduct(codec.parse(base.toString()).summary(), "PRODUCT-MX")).hasSize(1);
  }

  @Test
  void savedProduct29RowsRestoreSeventeenEdgesWithoutChangingOriginalValues() throws Exception {
    var json = new ObjectMapper();
    var data = json.readTree(getClass().getResourceAsStream("/fixtures/drawing-ycqc15h802-mx.json"));
    var raw = com.sanhua.marketingcost.integration.drawing.DrawingVerificationFixtures.drawing(
        "local-regression", "YCQC15H802-MX", List.of());
    raw.set("data", data);
    var payload = new com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec(json).parse(raw.toString());
    var nodes = DrawingJsonNodes.forProduct(payload.summary(), "YCQC15H802-MX");
    assertThat(nodes).hasSize(29);
    assertThat(nodes.stream().filter(n -> n.getParentSourceSequence() != null)).hasSize(17);
    assertThat(nodes.get(6).getParentSourceSequence()).isEqualTo("G1:R6");
    for (int i = 0; i < nodes.size(); i++) {
      var original = data.path("YCQC15H802-MX").path("params").path("items").get(i);
      assertThat(nodes.get(i).getDrawingCode()).isEqualTo(original.path("code").asText());
      assertThat(nodes.get(i).getSourceName()).isEqualTo(original.path("name").asText());
      assertThat(nodes.get(i).getQty()).isEqualByComparingTo(original.path("qty").asText());
      if (nodes.get(i).getReferenceWeight() != null) {
        assertThat(nodes.get(i).getReferenceWeightUnit()).isEqualTo("g");
        assertThat(nodes.get(i).getReferenceWeight()).isEqualByComparingTo(original.path("weight").asText());
      }
    }
  }

  @Test
  void dottedNumbersRestoreParentsIncludingPdfLineWrappingWithoutChangingRowIdentity() {
    var rows = nodes("1", "1.1", "1.2", "2", "2.1", "2.1.1", "2.1.1\\n.1", "2.1.1\n.2");
    assertThat(rows).extracting(n -> n.getSourceSequence())
        .containsExactly("G1:R1", "G1:R2", "G1:R3", "G1:R4", "G1:R5", "G1:R6", "G1:R7", "G1:R8");
    assertThat(rows).extracting(n -> n.getParentSourceSequence())
        .containsExactly(null, "G1:R1", "G1:R1", null, "G1:R4", "G1:R5", "G1:R6", "G1:R6");
  }

  @Test
  void parentMayFollowChildAndLeadingZerosRemainPartOfItsNumber() {
    var rows = nodes("001.02", "001");
    assertThat(rows.getFirst().getParentSourceSequence()).isEqualTo("G1:R2");
  }

  @Test
  void hierarchyCannotSilentlyPromoteOrphanToRootOrChooseADuplicateParent() {
    assertThatThrownBy(() -> nodes("1", "2.1"))
        .isInstanceOf(ElectronicDrawingSourceImportException.class).hasMessageContaining("2.1", "2");
    assertThatThrownBy(() -> nodes("1", "1", "1.1"))
        .isInstanceOf(ElectronicDrawingSourceImportException.class).hasMessageContaining("重复", "1");
  }

  @Test
  void FlatOriginalListsKeepBlankAndRepeatedNumbersAsIndependentRows() {
    assertThat(nodes("001", "001", "", null)).allSatisfy(n -> {
      assertThat(n.getParentSourceSequence()).isNull();
      assertThat(n.getReferenceWeight()).isNull();
      assertThat(n.getReferenceWeightUnit()).isNull();
    });
  }

  private List<com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode> nodes(String... numbers) {
    var items = java.util.Arrays.stream(numbers).map(no -> new DrawingVerificationSummary.Item(
        no, "PART", "部件", "1", null, null, null, null, null)).toList();
    var json = new ObjectMapper();
    var summary = new DrawingVerificationSummary("saved", "done", 0,
        Map.of("DRAW-MX", new DrawingVerificationSummary.Drawing(null, new DrawingVerificationSummary.Parameters(items))),
        json.createArrayNode(), null, null, List.of(), null, 1, null, null, 0, null, json.createObjectNode(), null, 2);
    return DrawingJsonNodes.forProduct(summary, "DRAW-MX");
  }
}
