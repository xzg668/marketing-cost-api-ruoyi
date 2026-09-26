package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.AssembleCommand;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.ElectronicNode;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.MaterialSnapshot;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.ManufacturingRawNode;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.Status;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.SubBomResult;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.U9Node;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("电子图库与 U9 子 BOM 混合合成")
class ElectronicDrawingHybridBomAssemblerTest {
  private ElectronicDrawingU9SubBomPort port;
  private ElectronicDrawingHybridBomAssembler assembler;

  @BeforeEach
  void setUp() {
    port = mock(ElectronicDrawingU9SubBomPort.class);
    assembler = new ElectronicDrawingHybridBomAssembler(port);
  }

  @Test
  void purchaseNodeIsAQuotationLeafAndNeverQueriesU9() {
    var result = assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-P", "2", material("P", "采购件")))));

    assertThat(result.nodes()).hasSize(2);
    assertThat(result.electronicDrawingPurchaseLeafCount()).isOne();
    assertThat(result.u9PurchaseLeafCount()).isZero();
    assertThat(result.quotationLeafCount()).isOne();
    verify(port, never()).query(any());
  }

  @Test
  void sameDrawingAndMaterialOnDifferentSourceNodesPreserveEachOccurrenceAndQuantity() {
    var result = assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-P", "4", material("P", "采购件")),
        ed(2L, "2", null, "DRAW-P", "3", material("P", "采购件")))));

    assertThat(result.nodes()).extracting(node -> node.nodeKey())
        .containsExactly("ROOT:TOP", "ED:1", "ED:2");
    assertThat(result.nodes()).extracting(node -> node.path())
        .containsExactly("/TOP/", "/TOP/ED:1/", "/TOP/ED:2/");
    assertThat(result.nodes().get(1).qtyPerTop()).isEqualByComparingTo("4");
    assertThat(result.nodes().get(2).qtyPerTop()).isEqualByComparingTo("3");
    assertThat(result.quotationLeafCount()).isEqualTo(2);
    verify(port, never()).query(any());
  }

  @Test
  void repeatedManufacturedOccurrencesKeepTheirOwnExpandedU9QuantityAndPath() {
    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "210", "COMMERCIAL", List.of(
            u9("raw-a", null, 101L, "RAW", "采购件", "6", "2", 1),
            u9("raw-b", null, 102L, "RAW", "采购件", "2", "2", 2))));
    var result = assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-M", "2", material("M", "制造件")),
        ed(2L, "2", null, "DRAW-M", "3", material("M", "制造件")))));

    var leaves = result.nodes().stream().filter(node -> "RAW".equals(node.materialCode())).toList();
    assertThat(leaves).extracting(node -> node.path()).containsExactly(
        "/TOP/ED:1/raw-a/", "/TOP/ED:1/raw-b/",
        "/TOP/ED:2/raw-a/", "/TOP/ED:2/raw-b/");
    assertThat(leaves).extracting(node -> node.qtyPerTop().intValueExact())
        .containsExactly(6, 2, 9, 3);
    assertThat(result.quotationLeafCount()).isEqualTo(4);
  }

  @Test
  void availableU9BranchReplacesElectronicDescendantsAndRecalculatesMultiLevelQuantity() {
    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "210", "COMMERCIAL", List.of(
            u9("m1", null, 101L, "U9-M", "制造件", "4", "2", 1),
            u9("p1", "m1", 102L, "RAW", "采购件", "3", "5", 1))));
    List<ElectronicNode> source = List.of(
        ed(1L, "1", null, "DRAW-M", "2", material("M", "制造件")),
        ed(2L, "1.1", "1", "DRAW-IGNORED", "7", material("IGNORED", "采购件")));

    var result = assembler.assemble(command(source));

    assertThat(result.nodes()).extracting(node -> node.materialCode())
        .containsExactly("TOP", "M", "U9-M", "RAW")
        .doesNotContain("IGNORED");
    assertThat(result.replacedElectronicDescendantCount()).isOne();
    assertThat(result.u9PurchaseLeafCount()).isOne();
    assertThat(result.electronicDrawingPurchaseLeafCount()).isZero();
    assertThat(result.nodes()).filteredOn(node -> "RAW".equals(node.materialCode()))
        .singleElement().satisfies(node -> {
          assertThat(node.qtyPerParent()).isEqualByComparingTo("3");
          assertThat(node.parentBaseQty()).isEqualByComparingTo("5");
          assertThat(node.qtyPerTop()).isEqualByComparingTo("2.4");
          assertThat(node.nodeSourceType()).isEqualTo("U9_EXPANDED");
          assertThat(node.sourceElectronicNodeId()).isEqualTo(1L);
          assertThat(node.sourceRawHierarchyId()).isEqualTo(102L);
          assertThat(node.drawingNo()).isEqualTo("DRAW-M");
        });
  }

  @Test
  void currentU9SourceRowIsAValidStableIdentityWhenFormalRawSnapshotDoesNotExistYet() {
    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "210", "COMMERCIAL", List.of(new U9Node(
            "U9SRC:5001", null, null, 5001L, "RAW", "原材料", "规格", null, null,
            "采购件", "CAT", "U9", "RAW", "主制造", "V1",
            BigDecimal.ONE, BigDecimal.ONE, "kg", 1))));

    var result = assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-M", "1", material("M", "制造件")))));

    assertThat(result.nodes()).filteredOn(node -> "RAW".equals(node.materialCode()))
        .singleElement().satisfies(node -> {
          assertThat(node.nodeKey()).contains("U9SRC:5001");
          assertThat(node.sourceRawHierarchyId()).isNull();
          assertThat(node.sourceU9BomId()).isEqualTo(5001L);
        });
  }

  @Test
  void explicitNotFoundContinuesElectronicDrawingChildren() {
    when(port.query(any())).thenReturn(
        SubBomResult.failure(Status.NOT_FOUND, "M", "明确无当前有效子 BOM"));
    var result = assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-M", "2", material("M", "制造件")),
        ed(2L, "1.1", "1", "DRAW-P", "3", material("P", "采购件")))));

    assertThat(result.nodes()).extracting(node -> node.materialCode())
        .containsExactly("TOP", "M", "P");
    assertThat(result.electronicDrawingPurchaseLeafCount()).isOne();
    assertThat(result.nodes()).filteredOn(node -> "P".equals(node.materialCode()))
        .singleElement().extracting(node -> node.qtyPerTop()).isEqualTo(new BigDecimal("6"));
  }

  @Test
  void outsourceAndVirtualNodesUseTheSameU9FirstAndElectronicFallbackRule() {
    when(port.query(any())).thenReturn(
        SubBomResult.failure(Status.NOT_FOUND, "M", "明确无当前有效子 BOM"));
    for (String nature : List.of("委外件", "虚拟件")) {
      var result = assembler.assemble(command(List.of(
          ed(1L, "1", null, "DRAW-M", "2", material("M", nature)),
          ed(2L, "1.1", "1", "DRAW-P", "3", material("P", "采购件")))));

      assertThat(result.electronicDrawingPurchaseLeafCount()).isOne();
      assertThat(result.nodes()).filteredOn(node -> "P".equals(node.materialCode()))
          .singleElement().extracting(node -> node.qtyPerTop()).isEqualTo(new BigDecimal("6"));
    }
  }

  @Test
  void timeoutErrorMultipleAndOrganizationMismatchNeverFallBackToElectronicChildren() {
    for (Status status : List.of(
        Status.TIMEOUT, Status.ERROR, Status.MULTIPLE, Status.ORGANIZATION_MISMATCH)) {
      when(port.query(any())).thenReturn(SubBomResult.failure(status, "M", "阻断"));
      assertThatThrownBy(() -> assembler.assemble(command(List.of(
          ed(1L, "1", null, "DRAW-M", "1", material("M", "制造件")),
          ed(2L, "1.1", "1", "DRAW-P", "1", material("P", "采购件"))))))
          .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
              error -> assertThat(error.code()).isEqualTo(
                  ElectronicDrawingHybridBomException.U9_QUERY_BLOCKED));
    }
  }

  @Test
  void availableResultForAnotherOrganizationIsBlocked() {
    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "999", "PLATE", List.of(
            u9("p1", null, 101L, "RAW", "采购件", "1", "1", 1))));

    assertThatThrownBy(() -> assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-M", "1", material("M", "制造件"))))))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.U9_QUERY_BLOCKED));
  }

  @Test
  void purchaseWithChildrenIsRejectedInsteadOfSilentlyDroppingData() {
    assertThatThrownBy(() -> assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-P", "1", material("P", "采购件")),
        ed(2L, "1.1", "1", "DRAW-C", "1", material("C", "采购件"))))))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.STRUCTURE_INVALID));
    verify(port, never()).query(any());
  }

  @Test
  void manufacturingNodeWithoutU9OrElectronicChildrenIsABomGap() {
    when(port.query(any())).thenReturn(SubBomResult.failure(Status.NOT_FOUND, "M", "无BOM"));

    assertThatThrownBy(() -> assembler.assemble(command(List.of(
        ed(1L, "1", null, "DRAW-M", "1", material("M", "制造件"))))))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.BOM_GAP));
  }

  @Test
  void incompleteMappingIsRejectedBeforeAnyU9Query() {
    ElectronicNode unresolved = new ElectronicNode(
        1L, 2, "1", null, "DRAW", "物料", BigDecimal.ONE,
        ElectronicDrawingSourceNode.MATCH_UNMATCHED, material("M", "制造件"));

    assertThatThrownBy(() -> assembler.assemble(command(List.of(unresolved))))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.MAPPING_INCOMPLETE));
    verify(port, never()).query(any());
  }

  @Test
  void orphanDuplicateCycleAndInvalidQuantityAreRejected() {
    ElectronicNode orphan = ed(1L, "1.1", "1", "D", "1", material("P", "采购件"));
    assertStructureFailure(List.of(orphan));

    ElectronicNode first = ed(1L, "1", null, "D1", "1", material("P1", "采购件"));
    ElectronicNode duplicate = ed(2L, "1", null, "D2", "1", material("P2", "采购件"));
    assertStructureFailure(List.of(first, duplicate));
    assertStructureFailure(List.of(first,
        ed(1L, "2", null, "D1", "2", material("P1", "采购件"))));

    ElectronicNode cycleA = ed(1L, "1", "2", "D1", "1", material("M1", "制造件"));
    ElectronicNode cycleB = ed(2L, "2", "1", "D2", "1", material("M2", "制造件"));
    assertStructureFailure(List.of(cycleA, cycleB));

    ElectronicNode invalidQty = ed(1L, "1", null, "D", "0", material("P", "采购件"));
    assertStructureFailure(List.of(invalidQty));
  }

  @Test
  void blankElectronicOrU9UnitIsRejected() {
    MaterialSnapshot blankElectronicUnit = new MaterialSnapshot(
        "P", "名称P", "规格P", "型号P", "图号P", "采购件",
        "CAT", "生产分类", "成本要素", " ");
    assertStructureFailure(List.of(
        ed(1L, "1", null, "D", "1", blankElectronicUnit)));

    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "210", "COMMERCIAL", List.of(new U9Node(
            "p", null, 104L, 1104L, "RAW", "名称RAW", "规格RAW", "型号RAW",
            "图号RAW", "采购件", "CAT", "U9", "RAW", "主制造", "V1",
            BigDecimal.ONE, BigDecimal.ONE, " ", 1))));
    assertStructureFailure(List.of(
        ed(1L, "1", null, "D", "1", material("M", "制造件"))));
  }

  @Test
  void u9PurchaseWithChildrenAndManufacturingLeafAreRejected() {
    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "210", "COMMERCIAL", List.of(
            u9("p", null, 101L, "P", "采购件", "1", "1", 1),
            u9("c", "p", 102L, "C", "采购件", "1", "1", 1))));
    assertStructureFailure(List.of(
        ed(1L, "1", null, "D", "1", material("M", "制造件"))));

    when(port.query(any())).thenReturn(SubBomResult.available(
        "M", "210", "COMMERCIAL", List.of(
            u9("m", null, 103L, "M2", "制造件", "1", "1", 1))));
    assertThatThrownBy(() -> assembler.assemble(command(List.of(
        ed(1L, "1", null, "D", "1", material("M", "制造件"))))))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.BOM_GAP));
  }

  @Test
  void sameInputProducesSameStableFingerprintAndSourceKeys() {
    List<ElectronicNode> source = List.of(
        ed(8L, "2", null, "D2", "2", material("P2", "采购件")),
        ed(7L, "1", null, "D1", "1", material("P1", "采购件")));

    var first = assembler.assemble(command(source));
    var second = assembler.assemble(command(source));

    assertThat(first.compositionFingerprint()).hasSize(64)
        .isEqualTo(second.compositionFingerprint());
    assertThat(first.nodes()).extracting(node -> node.nodeKey())
        .containsExactly("ROOT:TOP", "ED:7", "ED:8");
  }

  @Test
  void reimportedU9IdsDoNotChangeCompositionIdentity() {
    var source = List.of(ed(1L, "1", null, "DRAW-M", "1", material("M", "制造件")));
    when(port.query(any())).thenReturn(SubBomResult.available("M", "210", "COMMERCIAL",
        List.of(u9("stable-business-key", null, 101L, "RAW", "采购件", "2", "1", 1))));
    var before = assembler.assemble(command(source));

    when(port.query(any())).thenReturn(SubBomResult.available("M", "210", "COMMERCIAL",
        List.of(u9("stable-business-key", null, 999L, "RAW", "采购件", "2", "1", 1))));
    var after = assembler.assemble(command(source));

    assertThat(after.compositionFingerprint()).isEqualTo(before.compositionFingerprint());
    assertThat(after.nodes()).extracting(node -> node.path())
        .containsExactlyElementsOf(before.nodes().stream().map(node -> node.path()).toList());
    assertThat(after.nodes().getLast().sourceRawHierarchyId()).isEqualTo(999L);
  }

  private void assertStructureFailure(List<ElectronicNode> nodes) {
    assertThatThrownBy(() -> assembler.assemble(command(nodes)))
        .isInstanceOf(ElectronicDrawingHybridBomException.class);
  }

  @Test
  void technicalRawBindsDistinctOccurrencesAndMultipliesBomQuantityOnlyOnce() {
    when(port.query(any())).thenReturn(SubBomResult.failure(Status.NOT_FOUND, "M", "无正式下级"));
    var nodes = List.of(ed(1L, "1", null, "D", "4", material("M", "制造件")),
        ed(2L, "2", null, "D", "3", material("M", "制造件")));
    var result = assembler.assemble(command(nodes, List.of(raw(1L, "R1", "0.012"), raw(2L, "R2", "0.020"))));
    assertThat(result.quotationLeafCount()).isEqualTo(2);
    var first = result.nodes().stream().filter(node -> "R1".equals(node.materialCode())).findFirst().orElseThrow();
    var second = result.nodes().stream().filter(node -> "R2".equals(node.materialCode())).findFirst().orElseThrow();
    assertThat(first.parentNodeKey()).isEqualTo("ED:1");
    assertThat(first.path()).isEqualTo("/TOP/ED:1/TECH:71:RAW:1/");
    assertThat(first.sourceElectronicNodeId()).isEqualTo(1L);
    assertThat(first.qtyPerParent()).isEqualByComparingTo("0.012");
    assertThat(first.qtyPerTop()).isEqualByComparingTo("0.048");
    assertThat(second.parentNodeKey()).isEqualTo("ED:2");
    assertThat(second.qtyPerTop()).isEqualByComparingTo("0.060");
  }

  @Test
  void technicalRawCannotFillAnotherOccurrenceOrDuplicateOneParent() {
    var nodes = List.of(ed(1L, "1", null, "D", "4", material("M", "制造件")),
        ed(2L, "2", null, "D", "3", material("M", "制造件")));
    when(port.query(any())).thenReturn(SubBomResult.failure(Status.NOT_FOUND, "M", "无正式下级"));
    assertThatThrownBy(() -> assembler.assemble(command(nodes, List.of(raw(1L, "R", "0.012")))))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(ElectronicDrawingHybridBomException.BOM_GAP));
    assertThatThrownBy(() -> assembler.assemble(command(nodes,
        List.of(raw(1L, "R", "0.012"), raw(1L, "R2", "0.02")))))
        .hasMessageContaining("同一制造件");
    assertThatThrownBy(() -> assembler.assemble(command(nodes, List.of(raw(99L, "R", "0.012")))))
        .hasMessageContaining("不属于当前图库源节点");
  }

  @Test
  void formalU9StillWinsAndQueryFailureCannotFallBackToTechnicalRaw() {
    var input = command(List.of(ed(1L, "1", null, "D", "4", material("M", "制造件"))), List.of(raw(1L, "R", "0.012")));
    when(port.query(any())).thenReturn(SubBomResult.available("M", "210", "COMMERCIAL",
        List.of(u9("u1", null, 101L, "U9-R", "采购件", "0.015", "1", 1))));
    assertThat(assembler.assemble(input).nodes()).extracting(node -> node.materialCode()).contains("U9-R").doesNotContain("R");
    when(port.query(any())).thenReturn(SubBomResult.failure(Status.TIMEOUT, "M", "timeout"));
    assertThatThrownBy(() -> assembler.assemble(input)).isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
        error -> assertThat(error.code()).isEqualTo(ElectronicDrawingHybridBomException.U9_QUERY_BLOCKED));
  }

  private ManufacturingRawNode raw(Long parent, String code, String quantity) {
    return new ManufacturingRawNode(71L, "RAW:" + parent, parent, "M",
        new MaterialSnapshot(code, code, "T2", null, "D-" + code, "采购件", "CAT", "RAW", "RAW", "kg"), new BigDecimal(quantity));
  }

  private AssembleCommand command(List<ElectronicNode> nodes, List<ManufacturingRawNode> raw) {
    return new AssembleCommand("OA-1", 11L, material("TOP", "制造件"), "2026-08", "210", "COMMERCIAL",
        "COMMERCIAL", null, LocalDate.of(2026, 8, 1), nodes, raw);
  }

  private AssembleCommand command(List<ElectronicNode> nodes) {
    return new AssembleCommand(
        "OA-1", 11L, material("TOP", "制造件"), "2026-08",
        "210", "COMMERCIAL", "COMMERCIAL", null,
        LocalDate.of(2026, 8, 1), nodes, List.of());
  }

  private ElectronicNode ed(
      Long id,
      String sequence,
      String parent,
      String drawing,
      String quantity,
      MaterialSnapshot material) {
    return new ElectronicNode(
        id, id.intValue() + 1, sequence, parent, drawing, "电子物料" + id,
        new BigDecimal(quantity), ElectronicDrawingSourceNode.MATCH_AUTO, material);
  }

  private MaterialSnapshot material(String code, String nature) {
    return new MaterialSnapshot(
        code, "名称" + code, "规格" + code, "型号" + code, "图号" + code,
        nature, "CAT", "生产分类", "成本要素", "件");
  }

  private U9Node u9(
      String key,
      String parent,
      Long rawId,
      String code,
      String nature,
      String quantity,
      String base,
      int sort) {
    return new U9Node(
        key, parent, rawId, rawId + 1000, code, "名称" + code, "规格" + code,
        "型号" + code, "U9图号" + code, nature, "CAT", "U9", "RAW",
        "主制造", "V1", new BigDecimal(quantity), new BigDecimal(base), "kg", sort);
  }
}
