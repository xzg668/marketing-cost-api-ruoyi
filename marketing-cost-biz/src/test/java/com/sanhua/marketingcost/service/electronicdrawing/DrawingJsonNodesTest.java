package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.drawing.DrawingVerificationSummary;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DrawingJsonNodesTest {
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
    assertThat(nodes("001", "001", "", null)).allSatisfy(n -> assertThat(n.getParentSourceSequence()).isNull());
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
