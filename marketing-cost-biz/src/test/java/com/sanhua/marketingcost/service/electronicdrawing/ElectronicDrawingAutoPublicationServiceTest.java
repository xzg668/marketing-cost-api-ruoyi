package com.sanhua.marketingcost.service.electronicdrawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.PublicationContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T1 电子图库混合 BOM 独立自动发布")
class ElectronicDrawingAutoPublicationServiceTest {

  private final ElectronicDrawingWorkflowContextPort contextPort =
      mock(ElectronicDrawingWorkflowContextPort.class);
  private final QuoteBomSupplementVersionMapper versionMapper =
      mock(QuoteBomSupplementVersionMapper.class);
  private final ElectronicDrawingSourceNodeRepository sourceRepository =
      mock(ElectronicDrawingSourceNodeRepository.class);
  private final ApprovedElectronicBomRawSnapshotPublisher rawPublisher =
      mock(ApprovedElectronicBomRawSnapshotPublisher.class);

  private ElectronicDrawingAutoPublicationService service;
  private ElectronicDrawingWorkContext context;
  private QuoteBomSupplementVersion version;

  @BeforeEach
  void setUp() {
    service = new ElectronicDrawingAutoPublicationService(
        contextPort, versionMapper, sourceRepository, rawPublisher,
        mock(com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService.class));
    context = context("BOM_IN_PROGRESS", "E_DRAWING_COMPOSED", null);
    version = version("DRAFT");
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(201L)).thenReturn(version);
    when(sourceRepository.findPendingByVersionId(201L)).thenReturn(List.of());
    when(rawPublisher.publish(any(PublicationContext.class)))
        .thenReturn("SUPPLEMENT_VERSION:201");
    when(versionMapper.updateById(version)).thenReturn(1);
    when(contextPort.completePublication(any(), anyString(), any()))
        .thenReturn(context("READY_FOR_COSTING", "E_DRAWING_COMPOSED", "FP-COMPOSED"));
  }

  @Test
  void publishesUsingOnlySharedBomIdentityAndContextPort() {
    var result = service.publish(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.rawBatchId()).isEqualTo("SUPPLEMENT_VERSION:201");
    assertThat(result.idempotent()).isFalse();
    assertThat(result.context().workflowStatus()).isEqualTo("READY_FOR_COSTING");
    assertThat(result.context().compositionFingerprint()).isEqualTo("FP-COMPOSED");
    assertThat(version.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(version.getReviewerName()).isEqualTo("系统");
    verify(rawPublisher).publish(new PublicationContext(
        201L, "P-1", null, "210", "COMMERCIAL", "2026-08"));
  }

  @Test
  void unresolvedMappingBlocksPublication() {
    when(sourceRepository.findPendingByVersionId(201L))
        .thenReturn(List.of(new ElectronicDrawingSourceNode()));

    assertThatThrownBy(() -> service.publish(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("未选择 U9 料号");
    verify(rawPublisher, never()).publish(any(PublicationContext.class));
    verify(contextPort, never()).completePublication(any(), anyString(), any());
  }

  @Test
  void rawPublicationFailureStopsOuterStateTransition() {
    when(rawPublisher.publish(any(PublicationContext.class)))
        .thenThrow(new IllegalStateException("模拟原始层写入失败"));

    assertThatThrownBy(() -> service.publish(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("模拟原始层写入失败");
    verify(contextPort, never()).completePublication(any(), anyString(), any());
  }

  @Test
  void publishedReplayIsIdempotent() {
    context = context("READY_FOR_COSTING", "E_DRAWING_COMPOSED", "FP-COMPOSED");
    version = version("APPROVED");
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(201L)).thenReturn(version);
    when(contextPort.completePublication(any(), anyString(), any())).thenReturn(context);

    var result = service.publish(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.idempotent()).isTrue();
    verify(versionMapper, never()).updateById(any(QuoteBomSupplementVersion.class));
    verify(rawPublisher).publish(any(PublicationContext.class));
  }

  private ElectronicDrawingWorkContext context(
      String status, String stage, String fingerprint) {
    return new ElectronicDrawingWorkContext(
        101L, 3, 401L, 201L, 1L, 2L, "TASK-1", "OA-1", "P-1", null,
        "产品", null, null, null, "FULL_BOM", "2026-08", "210", "COMMERCIAL",
        "COMMERCIAL", "210", true, true, status, stage, null, "系统处理中",
        fingerprint);
  }

  private QuoteBomSupplementVersion version(String status) {
    QuoteBomSupplementVersion value = new QuoteBomSupplementVersion();
    value.setId(201L);
    value.setPreparationId(401L);
    value.setQuoteProductCode("P-1");
    value.setPeriodMonth("2026-08");
    value.setMaterialOrgCode("COMMERCIAL");
    value.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    value.setCompositionFingerprint("FP-COMPOSED");
    value.setVersionStatus(status);
    value.setActiveFlag(1);
    return value;
  }
}
