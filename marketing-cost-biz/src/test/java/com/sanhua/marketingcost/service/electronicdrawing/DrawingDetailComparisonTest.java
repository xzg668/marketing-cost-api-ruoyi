package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class DrawingDetailComparisonTest {
  @Test
  void reorderedRowsAndNumericFormattingDoNotChangeTheDrawing() {
    var old = List.of(row("1", null, "A", "2.00"), row("2", "1", "B", "1"));
    var next = List.of(row("9", "8", "B", "1.000"), row("8", null, "A", "2"));
    assertThat(DrawingDetailComparison.compare(old, next).unchanged()).isTrue();
  }

  @Test
  void quantityChangeIsSeparatedFromMaterialOrStructureChanges() {
    var diff = DrawingDetailComparison.compare(List.of(row("1", null, "A", "2")),
        List.of(row("2", null, "A", "3")));
    assertThat(diff.unchanged()).isFalse();
    assertThat(diff.matches().getFirst().quantityChanged()).isTrue();
    assertThat(diff.matches().getFirst().contentChanged()).isFalse();
    var changed = row("2", null, "A", "2");
    changed.setMaterial("316L");
    assertThat(DrawingDetailComparison.compare(List.of(row("1", null, "A", "2")), List.of(changed))
        .matches().getFirst().contentChanged()).isTrue();
  }

  @Test
  void movingAComponentToAnotherParentCannotReuseItsOldBranch() {
    var old = List.of(row("1", null, "A", "1"), row("2", null, "B", "1"), row("3", "1", "C", "2"));
    var next = List.of(row("1", null, "A", "1"), row("2", null, "B", "1"), row("3", "2", "C", "2"));
    var diff = DrawingDetailComparison.compare(old, next);
    assertThat(diff.unchanged()).isFalse();
    assertThat(diff.added()).extracting(ElectronicDrawingSourceNode::getDrawingCode).containsExactly("C");
    assertThat(diff.removed()).extracting(ElectronicDrawingSourceNode::getDrawingCode).containsExactly("C");
  }

  @Test
  void repeatedPartsAreNotCollapsedOrArbitrarilyMatchedAfterAmbiguousChanges() {
    var old = List.of(row("1", null, "A", "1"), row("2", null, "A", "2"));
    assertThat(DrawingDetailComparison.compare(old, List.of(row("1", null, "A", "2"), row("2", null, "A", "1"))).unchanged()).isTrue();
    var diff = DrawingDetailComparison.compare(old, List.of(row("1", null, "A", "3"), row("2", null, "A", "4")));
    assertThat(diff.matches()).isEmpty();
    assertThat(diff.added()).hasSize(2);
    assertThat(diff.removed()).hasSize(2);
  }

  @Test
  void malformedHierarchyDoesNotSilentlyReuseOldBom() {
    assertThatThrownBy(() -> DrawingDetailComparison.compare(List.of(), List.of(row("1", "missing", "A", "1"))))
        .hasMessageContaining("父项");
  }

  @Test
  void childrenMovedBetweenRepeatedParentsChangeTheBomEvenWhenAllRowsStillExist() {
    var old = List.of(row("1", null, "A", "1"), row("2", null, "A", "2"),
        row("3", "1", "B", "3"), row("4", "2", "B", "4"));
    var next = List.of(row("1", null, "A", "1"), row("2", null, "A", "2"),
        row("3", "2", "B", "3"), row("4", "1", "B", "4"));
    var diff = DrawingDetailComparison.compare(old, next);
    assertThat(diff.unchanged()).isFalse();
    assertThat(diff.matches()).isEmpty();
  }

  @Test
  void reorderedRepeatedParentsKeepTheirOwnChildrenAndCorrectionIdentity() {
    var old = List.of(row("1", null, "A", "1"), row("2", null, "A", "1"),
        row("3", "1", "B", "3"), row("4", "2", "B", "4"));
    var next = List.of(row("8", null, "A", "1"), row("9", null, "A", "1"),
        row("10", "8", "B", "4"), row("11", "9", "B", "3"));
    var diff = DrawingDetailComparison.compare(old, next);
    assertThat(diff.unchanged()).isTrue();
    var ids = diff.matches().stream().collect(java.util.stream.Collectors.toMap(
        match -> match.previous().getSourceSequence(), match -> match.current().getSourceSequence()));
    assertThat(ids).containsEntry("1", "9").containsEntry("3", "11").containsEntry("2", "8").containsEntry("4", "10");
  }

  private ElectronicDrawingSourceNode row(String sequence, String parent, String drawing, String qty) {
    var row = new ElectronicDrawingSourceNode();
    row.setSourceSequence(sequence);
    row.setParentSourceSequence(parent);
    row.setDrawingCode(drawing);
    row.setSourceName(drawing);
    row.setQty(new BigDecimal(qty));
    return row;
  }
}
