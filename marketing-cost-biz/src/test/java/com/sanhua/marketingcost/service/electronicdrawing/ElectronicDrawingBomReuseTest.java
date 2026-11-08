package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ElectronicDrawingBomReuseTest {
  final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  final QuoteBomSupplementDetailMapper details = mock(QuoteBomSupplementDetailMapper.class);
  final ElectronicDrawingSourceNodeRepository nodes = mock(ElectronicDrawingSourceNodeRepository.class);
  final ApprovedElectronicBomRawSnapshotPublisher publisher = mock(ApprovedElectronicBomRawSnapshotPublisher.class);
  final ElectronicDrawingBomReuse reuse = new ElectronicDrawingBomReuse(versions, details,
      mock(DrawingVerificationResultMapper.class), nodes,
      mock(com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec.class),
      mock(OaFormItemMapper.class), mock(OaFormMapper.class),
      mock(com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver.class), publisher);

  @Test
  void unchangedOriginalDrawingRetainsCorrectedQuantityAndAdditionalRawMaterial() {
    var raw = row(1L, "R1", null, "P-DRAW", "2");
    var corrected = row(11L, "R1", null, "P-DRAW", "2.5");
    var supplemental = row(12L, "FIN1", "R1", "RAW-DRAW", "0.25");
    var latest = row(null, "R9", null, "P-DRAW", "2.000");
    var plan = plan(List.of(raw), List.of(corrected, supplemental), List.of(latest));

    assertThat(plan.unchanged()).isTrue();
    var retained = reuse.retainedRows(plan, List.of(latest));
    assertThat(retained).hasSize(2);
    assertThat(retained.getFirst().getQty()).isEqualByComparingTo("2.5");
    assertThat(retained.getFirst().getSourceSequence()).isEqualTo("R9");
    assertThat(retained.get(1).getParentSourceSequence()).isEqualTo("R9");
    assertThat(retained.get(1).getCorrectedFromSourceNodeId()).isEqualTo(12L);
    assertThat(corrected.getSourceSequence()).isEqualTo("R1");
    assertThat(supplemental.getParentSourceSequence()).isEqualTo("R1");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"2", "3"})
  void copiesCompleteApprovedBomIncludingSupplementalRowsInsteadOfRebuildingRawDrawing(String latestQuantity) {
    var raw = row(1L, "R1", null, "P-DRAW", "2");
    var saved = row(11L, "R1", null, "P-DRAW", "2");
    var plan = plan(List.of(raw), List.of(saved), List.of(row(null, "R1", null, "P-DRAW", latestQuantity)));
    var newNode = row(91L, "R1", null, "P-DRAW", latestQuantity);
    newNode.setCorrectedFromSourceNodeId(11L);
    when(nodes.findByVersionId(90L)).thenReturn(List.of(newNode));
    var parent = detail("/P/E:11/", "PART", "2", 11L);
    parent.setNodeSourceType("E_DRAWING"); parent.setQtyPerParent(new BigDecimal("2"));
    var technicalRaw = detail("/P/E:11/RAW/", "RAW", "0.5", 11L);
    technicalRaw.setNodeSourceType("TECHNICAL_RAW"); technicalRaw.setQtyPerParent(new BigDecimal("0.25"));
    when(details.selectList(any())).thenReturn(List.of(parent, technicalRaw));
    when(details.insertElectronicDrawingHybridBatch(anyList())).thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
    when(versions.updateById(any(QuoteBomSupplementVersion.class))).thenReturn(1);
    var target = new QuoteBomSupplementVersion();
    target.setId(90L); target.setPreparationId(22L); target.setOaNo("NEW-QUOTE"); target.setOaFormItemId(30L);
    var context = new ElectronicDrawingWorkContext(30L, 0, 22L, 90L, 2L, 30L, "TASK", "NEW-QUOTE", "P", null,
        "Product", null, null, "NON_BARE", "FULL_BOM", "2026-11", "210", "COMMERCIAL", "COMMERCIAL", "210",
        true, true, "NEED_TECH", null, null, null, null);

    reuse.copyApprovedBom(plan, target, context, LocalDateTime.of(2026, 11, 5, 0, 0));

    @SuppressWarnings("unchecked") ArgumentCaptor<List<QuoteBomSupplementDetail>> captured = ArgumentCaptor.forClass(List.class);
    verify(details).insertElectronicDrawingHybridBatch(captured.capture());
    assertThat(captured.getValue()).extracting(QuoteBomSupplementDetail::getMaterialCode).containsExactly("PART", "RAW");
    assertThat(captured.getValue()).allSatisfy(row -> {
      assertThat(row.getSupplementVersionId()).isEqualTo(90L);
      assertThat(row.getSourceElectronicNodeId()).isEqualTo(91L);
      assertThat(row.getOaFormItemId()).isEqualTo(30L);
    });
    assertThat(captured.getValue().get(1).getQtyPerTop()).isEqualByComparingTo(new BigDecimal(latestQuantity).multiply(new BigDecimal("0.25")));
    assertThat(captured.getValue().get(1).getQtyPerParent()).isEqualByComparingTo("0.25");
    assertThat(captured.getValue().get(1).getPath()).isEqualTo(technicalRaw.getPath());
    assertThat(target.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(target.getReviewComment()).contains("沿用已审核完整 BOM");
    verify(publisher).publish(any());
    assertThat(parent.getSourceElectronicNodeId()).isEqualTo(11L);
    assertThat(plan.previous().getId()).isEqualTo(10L);
  }

  @Test
  void quantityChangeKeepsUnaffectedRawRelationButUsesNewQuantity() {
    var original = row(1L, "R1", null, "PART", "2");
    var saved = row(11L, "R1", null, "PART", "2.5");
    var raw = row(12L, "FIN1", "R1", "RAW", "0.25");
    var latest = row(null, "R1", null, "PART", "3");
    var plan = plan(List.of(original), List.of(saved, raw), List.of(latest));
    var retained = reuse.retainedRows(plan, List.of(latest));
    assertThat(plan.unchanged()).isFalse();
    assertThat(retained).hasSize(2);
    assertThat(retained.getFirst().getQty()).isEqualByComparingTo("3");
    assertThat(retained.get(1).getQty()).isEqualByComparingTo("0.25");
    assertThat(plan.reuseCompleteTree()).isTrue();
    verifyNoInteractions(details, publisher);
  }

  @Test
  void changedQuantityOfPreviouslyExcludedNodeDoesNotAlterTheCompleteBom() {
    var raw = row(1L, "R1", null, "EXCLUDED", "2");
    var saved = row(11L, "R1", null, "EXCLUDED", "2");
    var plan = plan(List.of(raw), List.of(saved), List.of(row(null, "R1", null, "EXCLUDED", "3")));
    var newNode = row(91L, "R1", null, "EXCLUDED", "3"); newNode.setCorrectedFromSourceNodeId(11L);
    when(nodes.findByVersionId(90L)).thenReturn(List.of(newNode));
    var root = detail("/P/", "P", "1", null);
    when(details.selectList(any())).thenReturn(List.of(root));
    when(details.insertElectronicDrawingHybridBatch(anyList())).thenReturn(1);
    when(versions.updateById(any(QuoteBomSupplementVersion.class))).thenReturn(1);
    var target = new QuoteBomSupplementVersion(); target.setId(90L);
    var context = new ElectronicDrawingWorkContext(30L, 0, 22L, 90L, 2L, 30L, "TASK", "OA", "P", null,
        "Product", null, null, "NON_BARE", "FULL_BOM", "2026-11", "210", "COMMERCIAL", "COMMERCIAL", "210",
        true, true, "NEED_TECH", null, null, null, null);
    reuse.copyApprovedBom(plan, target, context, LocalDateTime.of(2026, 11, 5, 0, 0));
    assertThat(target.getCompositionFingerprint()).isEqualTo("saved-tree");
    verify(publisher).publish(any());
  }

  @Test
  void changedMaterialDropsOnlyThatBranchesOldSupplement() {
    var original = row(1L, "R1", null, "PART", "2");
    var untouched = row(2L, "R2", null, "OTHER", "1");
    var saved = row(11L, "R1", null, "PART", "2");
    var other = row(12L, "R2", null, "OTHER", "1");
    var raw = row(13L, "FIN1", "R1", "RAW", "0.25");
    var otherRaw = row(14L, "FIN2", "R2", "OTHER-RAW", "0.5");
    var changed = row(null, "R1", null, "PART", "2"); changed.setMaterial("316L");
    var latest = List.of(changed, untouched);
    var result = reuse.retainedRows(plan(List.of(original, untouched), List.of(saved, other, raw, otherRaw), latest), latest);
    assertThat(result).extracting(ElectronicDrawingSourceNode::getDrawingCode).containsExactlyInAnyOrder("PART", "OTHER", "OTHER-RAW");
    assertThat(result.stream().filter(row -> row.getDrawingCode().equals("PART")).findFirst().orElseThrow().getResolvedMaterialCode()).isNull();
  }

  private ElectronicDrawingBomReuse.Plan plan(List<ElectronicDrawingSourceNode> original,
      List<ElectronicDrawingSourceNode> saved, List<ElectronicDrawingSourceNode> latest) {
    var previous = new QuoteBomSupplementVersion(); previous.setId(10L); previous.setCompositionFingerprint("saved-tree");
    return new ElectronicDrawingBomReuse.Plan(previous, DrawingDetailComparison.compare(original, latest), original, saved);
  }

  private ElectronicDrawingSourceNode row(Long id, String sequence, String parent, String code, String quantity) {
    var row = new ElectronicDrawingSourceNode(); row.setId(id); row.setSourceSequence(sequence);
    row.setParentSourceSequence(parent); row.setDrawingCode(code); row.setSourceName(code);
    row.setQty(new BigDecimal(quantity)); return row;
  }

  private QuoteBomSupplementDetail detail(String path, String material, String quantity, Long nodeId) {
    var row = new QuoteBomSupplementDetail(); row.setPath(path); row.setMaterialCode(material);
    row.setQtyPerTop(new BigDecimal(quantity)); row.setSourceElectronicNodeId(nodeId); return row;
  }
}
