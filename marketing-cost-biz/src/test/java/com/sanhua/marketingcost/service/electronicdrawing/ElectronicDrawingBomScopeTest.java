package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class ElectronicDrawingBomScopeTest {
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final ElectronicDrawingU9SubBomPort u9 = mock(ElectronicDrawingU9SubBomPort.class);
  private final ElectronicDrawingBomScope scope = ElectronicDrawingTestScope.create(materials, u9);
  private final ElectronicDrawingWorkContext context = new ElectronicDrawingWorkContext(11L, 2, 21L, 31L,
      10L, 11L, "TASK", "OA", "TOP", null, null, null, null, null, "FULL_BOM", "2026-10",
      "210", "COMMERCIAL", "COMMERCIAL", "210", true, true, "EDITING", "MATERIALS_MATCHED", null, null, null);

  @Test void unmatchedStructureFollowsDrawingChildrenWithoutInventingU9Code() {
    var parent = node(1, null, null);
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("RAW", "采购件")));
    var result = scope.inspect(context, List.of(parent, node(2, "RAW", "1")), LocalDate.of(2026, 10, 1));
    assertThat(result.pendingIds()).isEmpty();
    assertThat(result.nodes()).hasSize(2);
    assertThat(result.branches().get(1L).state()).isEqualTo(ElectronicDrawingBomScope.State.DRAWING_READY);
    assertThat(parent.getResolvedMaterialCode()).isNull();
    assertThat(result.nodes().getFirst().material().drawingNo()).isEqualTo("DRAW-1");
    verifyNoInteractions(u9);
  }

  @Test void ambiguousParentStillRequiresFinanceEvenWithDrawingChildren() {
    var parent = node(1, null, null); parent.setMatchStatus(ElectronicDrawingSourceNode.MATCH_AMBIGUOUS);
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("RAW", "采购件")));
    var result = scope.inspect(context, List.of(parent, node(2, "RAW", "1")), LocalDate.of(2026, 10, 1));
    assertThat(result.pendingIds()).containsExactly(1L);
    assertThat(result.nodes()).isEmpty();
    verifyNoInteractions(u9);
  }

  @Test void drawingChildrenStillRequireMatchingEvenWhenParentHasU9() {
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(material("M", "制造件")));
    lenient().when(u9.query(any())).thenReturn(SubBomResult.available("M", "210", "COMMERCIAL", List.of(
        new U9Node("RAW", null, 1L, null, "RAW", "铜棒", null, null, null, "采购件", null, null, null,
            "主制造", "1", BigDecimal.ONE, BigDecimal.ONE, "kg", 1))));
    var result = scope.inspect(context, List.of(node(1, "M", null), node(2, null, "1")), LocalDate.of(2026, 10, 1));
    assertThat(result.pendingIds()).containsExactly(2L);
    assertThat(result.nodes()).hasSize(1);
    assertThat(result.branches().get(1L).state()).isEqualTo(ElectronicDrawingBomScope.State.DRAWING_READY);
    assertThat(result.branches().get(2L).state()).isEqualTo(ElectronicDrawingBomScope.State.WAIT_FINANCE);
    verifyNoInteractions(u9);
  }

  @Test void drawingStructureWinsAndOnlyManufacturedDrawingLeavesQueryU9() {
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(
        material("SWITCH", "制造件"), material("DIAPHRAGM", "制造件"), material("PLATE", "采购件")));
    when(u9.query(any())).thenAnswer(call -> {
      SubBomQuery request = call.getArgument(0);
      assertThat(request.parentMaterialCode()).isEqualTo("DIAPHRAGM");
      return SubBomResult.available("DIAPHRAGM", "210", "COMMERCIAL", List.of(
          new U9Node("RAW", null, 1L, null, "301240123", "不锈钢带", null, null, null, "采购件",
              null, null, null, "主制造", "1", new BigDecimal("0.000701"), BigDecimal.ONE, "千克", 1)));
    });
    var part = node(2, "DIAPHRAGM", "1"); part.setQty(new BigDecimal("7"));
    var result = scope.inspect(context, List.of(node(1, "SWITCH", null), part, node(3, "PLATE", "1")),
        LocalDate.of(2026, 10, 1));
    assertThat(result.nodes()).hasSize(3);
    assertThat(result.branches().get(1L).state()).isEqualTo(ElectronicDrawingBomScope.State.DRAWING_READY);
    assertThat(result.branches().get(2L).state()).isEqualTo(ElectronicDrawingBomScope.State.U9_READY);
    assertThat(result.nodes().get(1).quantity()).isEqualByComparingTo("7");
    assertThat(result.branches().get(3L).state()).isEqualTo(ElectronicDrawingBomScope.State.PURCHASE);
    verify(u9, times(1)).query(any());
  }

  @Test void purchaseStopsUnmatchedChildrenAndTimeoutDoesNotBecomeRawSupplement() {
    when(materials.selectByLatestBatchAndCodes(any(), any(), any())).thenReturn(List.of(
        material("P", "采购件"), material("M", "制造件")));
    when(u9.query(any())).thenReturn(SubBomResult.failure(Status.TIMEOUT, "M", "U9超时"));
    var result = scope.inspect(context, List.of(node(1, "P", null), node(2, null, "1"), node(3, "M", null)),
        LocalDate.of(2026, 10, 1));
    assertThat(result.pendingIds()).isEmpty();
    assertThat(result.branches().get(2L).state()).isEqualTo(ElectronicDrawingBomScope.State.EXCLUDED);
    assertThat(result.branches().get(3L).state()).isEqualTo(ElectronicDrawingBomScope.State.ERROR);
    verify(u9, times(1)).query(any());
  }

  private ElectronicDrawingSourceNode node(long id, String code, String parent) {
    var row = new ElectronicDrawingSourceNode(); row.setId(id); row.setSourceSequence(Long.toString(id));
    row.setSourceRowNo((int) id + 1); row.setParentSourceSequence(parent); row.setDrawingCode("DRAW-" + id);
    row.setSourceName("图库物料" + id); row.setQty(BigDecimal.ONE); row.setReferenceWeight(BigDecimal.TEN);
    row.setReferenceWeightUnit("g"); row.setResolvedMaterialCode(code);
    row.setMatchStatus(code == null ? ElectronicDrawingSourceNode.MATCH_UNMATCHED : ElectronicDrawingSourceNode.MATCH_MANUAL);
    return row;
  }
  private MaterialMasterRaw material(String code, String shape) {
    var row = new MaterialMasterRaw(); row.setMaterialCode(code); row.setShapeAttr(shape); row.setUnit("kg"); return row;
  }
}
