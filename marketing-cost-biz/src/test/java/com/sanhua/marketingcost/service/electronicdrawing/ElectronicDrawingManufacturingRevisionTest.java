package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ElectronicDrawingManufacturingRevisionTest {
  private final QuoteBomPreparationRecordMapper preparations = mock(QuoteBomPreparationRecordMapper.class);
  private final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  private final ElectronicDrawingSourceNodeRepository nodes = mock(ElectronicDrawingSourceNodeRepository.class);
  private final ElectronicDrawingWorkflowContextPort contexts = mock(ElectronicDrawingWorkflowContextPort.class);
  private final ElectronicDrawingManufacturingRevision service =
      new ElectronicDrawingManufacturingRevision(preparations, versions, nodes, contexts);
  private final ElectronicDrawingWorkContext context = context(31L, 2);
  private QuoteBomSupplementVersion original;
  private QuoteBomPreparationRecord preparation;

  @BeforeEach void setup() {
    preparation = new QuoteBomPreparationRecord();
    preparation.setId(21L); preparation.setElectronicWorkflowVersion(2); preparation.setElectronicSourceVersionId(31L);
    when(preparations.selectForElectronicDrawingImport(21L)).thenReturn(preparation);
    original = new QuoteBomSupplementVersion();
    original.setId(31L); original.setPreparationId(21L); original.setOaFormItemId(11L);
    original.setTaskNo("TASK"); original.setQuoteProductCode("TOP"); original.setPeriodMonth("2026-10");
    original.setMaterialOrgCode("COMMERCIAL"); original.setActiveFlag(1); original.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    original.setVersionStatus("APPROVED"); original.setVersionNo(4); original.setSupplementScope("NON_BARE_FULL_BOM");
    original.setCompositionFingerprint("published"); original.setReviewerUserId(3L); original.setReviewerName("领导");
    original.setReviewedAt(LocalDateTime.of(2026, 10, 2, 10, 0)); original.setSourceFileSha256("same-file");
    when(versions.selectById(31L)).thenReturn(original);
  }

  @Test void keepsApprovedSourceAndMappingsButStartsAnIndependentUnapprovedBom() {
    when(versions.selectList(any())).thenReturn(List.of(original));
    when(versions.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(call -> {
      ((QuoteBomSupplementVersion) call.getArgument(0)).setId(42L); return 1;
    });
    var oldNode = node(100L, 31L);
    when(nodes.findByVersionId(31L)).thenReturn(List.of(oldNode));
    when(nodes.findByVersionId(42L)).thenReturn(List.of(node(200L, 42L)));
    var next = context(42L, 3);
    when(contexts.attachSourceVersion(eq(context), eq(42L), any())).thenReturn(next);
    when(contexts.updateStage(eq(next), eq(ElectronicDrawingWorkflowStage.MATCHED), any(), any(), any())).thenReturn(next);

    var revision = service.prepare(context);

    assertThat(revision.sourceNodeIds()).isEqualTo(Map.of(100L, 200L));
    var created = ArgumentCaptor.forClass(QuoteBomSupplementVersion.class);
    verify(versions).insert(created.capture());
    assertThat(created.getValue().getVersionStatus()).isEqualTo("DRAFT");
    assertThat(created.getValue().getVersionNo()).isEqualTo(5);
    assertThat(created.getValue().getReusedFromVersionId()).isEqualTo(31L);
    assertThat(created.getValue().getSourceFileSha256()).isEqualTo("same-file");
    assertThat(created.getValue().getCompositionFingerprint()).isNull();
    assertThat(created.getValue().getReviewerUserId()).isNull();
    assertThat(created.getValue().getReviewedAt()).isNull();
    assertThat(original.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(original.getCompositionFingerprint()).isEqualTo("published");
    assertThat(oldNode.getId()).isEqualTo(100L);
    @SuppressWarnings("unchecked") var copied = ArgumentCaptor.forClass(List.class);
    verify(nodes).insertAll(eq(42L), copied.capture());
    var copy = (ElectronicDrawingSourceNode) copied.getValue().getFirst();
    assertThat(copy.getId()).isNull();
    assertThat(copy.getResolvedMaterialCode()).isEqualTo("PARENT");
    assertThat(copy.getReferenceWeight()).isEqualByComparingTo("1.3");
    assertThat(copy.getReferenceWeightUnit()).isEqualTo("g");
    verify(versions, never()).updateById(any(QuoteBomSupplementVersion.class));
  }

  @Test void repeatedSavesUseTheExistingDraftWithoutAnotherCopy() {
    original.setVersionStatus("DRAFT");
    assertThat(service.prepare(context).sourceNodeIds()).isEmpty();
    verify(versions, never()).insert(any(QuoteBomSupplementVersion.class));
    verifyNoInteractions(nodes, contexts);
  }

  @Test void changedContextDoesNotCreateAnOrphanRevision() {
    preparation.setElectronicWorkflowVersion(3);
    assertThatThrownBy(() -> service.prepare(context)).hasMessageContaining("已变化");
    verifyNoInteractions(versions, nodes, contexts);
  }

  @Test void anotherMonthOrInactiveSourceCannotBeCloned() {
    original.setPeriodMonth("2026-09");
    assertThatThrownBy(() -> service.prepare(context)).hasMessageContaining("归属不一致");
    original.setPeriodMonth("2026-10"); original.setActiveFlag(0);
    assertThatThrownBy(() -> service.prepare(context)).hasMessageContaining("归属不一致");
    verify(versions, never()).insert(any(QuoteBomSupplementVersion.class));
    verifyNoInteractions(nodes, contexts);
  }

  private static ElectronicDrawingWorkContext context(Long source, int revision) {
    return new ElectronicDrawingWorkContext(11L, revision, 21L, source, 10L, 11L, "TASK", "OA", "TOP",
        null, null, null, null, "NON_BARE", "FULL_BOM", "2026-10", "210", "COMMERCIAL", "COMMERCIAL",
        "210", true, true, "READY_FOR_COSTING", "PUBLISHED", 7L, "技术员", "published");
  }

  private static ElectronicDrawingSourceNode node(long id, long version) {
    var node = new ElectronicDrawingSourceNode();
    node.setId(id); node.setSupplementVersionId(version); node.setSourceSequence("9");
    node.setResolvedMaterialCode("PARENT"); node.setMatchStatus("MANUALLY_SELECTED");
    node.setResolvedBy("财务"); node.setResolvedAt(LocalDateTime.of(2026, 10, 2, 9, 0));
    node.setReferenceWeight(new BigDecimal("1.3")); node.setReferenceWeightUnit("g");
    return node;
  }
}
