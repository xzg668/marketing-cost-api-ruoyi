package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativeResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomExclusionSummaryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.QuoteBomRuleFingerprintService;
import com.sanhua.marketingcost.service.QuoteCostingInputFingerprintService;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomVariantInput;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomBuildResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomActorProvider;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomCostingCandidate;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomPersistenceService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class QuoteEffectiveBomCostingServiceImplTest {

  private QuoteEffectiveBomApplicationService effectiveBomService;
  private QuoteEffectiveBomPersistenceService persistenceService;
  private QuoteProductBomCostingBuildService costingBuildService;
  private QuoteEffectiveBomActorProvider actorProvider;
  private QuoteBomRuleFingerprintService ruleFingerprintService;
  private QuoteCostingInputFingerprintService inputFingerprintService;
  private QuoteCostingWorkspaceService workspaceService;
  private OaFormItemMapper itemMapper;
  private OaFormMapper formMapper;
  private QuoteEffectiveBomCostingServiceImpl service;

  @BeforeEach
  void setUp() {
    effectiveBomService = mock(QuoteEffectiveBomApplicationService.class);
    persistenceService = mock(QuoteEffectiveBomPersistenceService.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    actorProvider = mock(QuoteEffectiveBomActorProvider.class);
    ruleFingerprintService = mock(QuoteBomRuleFingerprintService.class);
    inputFingerprintService = mock(QuoteCostingInputFingerprintService.class);
    workspaceService = mock(QuoteCostingWorkspaceService.class);
    itemMapper = mock(OaFormItemMapper.class);
    formMapper = mock(OaFormMapper.class);
    service =
        new QuoteEffectiveBomCostingServiceImpl(
            effectiveBomService,
            persistenceService,
            costingBuildService,
            actorProvider,
            ruleFingerprintService,
            inputFingerprintService,
            workspaceService,
            itemMapper,
            formMapper);
  }

  @Test
  void persistsBuildsAndSwitchesTheLockedWorkspacePointer() {
    OaFormItem item = item();
    QuoteCostingWorkspace workspace = workspace();
    when(itemMapper.selectById(10L)).thenReturn(item);
    when(effectiveBomService.prepareCostingCandidate("OA-1", 10L)).thenReturn(candidate());
    when(workspaceService.lockOrCreate("OA-1", 10L, "P", "2026-08", "COMMERCIAL"))
        .thenReturn(workspace);
    when(actorProvider.currentUserId()).thenReturn(99L);
    when(persistenceService.persistCurrentVariant(any()))
        .thenReturn(new QuoteEffectiveBomPersistenceResult("BUILD-2", "SOURCE-HASH", false, 2));
    QuoteBomCostingBuildResponse build = build("BUILD-2");
    when(costingBuildService.buildFromEffectiveBom(10L, "BUILD-2")).thenReturn(build);
    when(ruleFingerprintService.currentFingerprint()).thenReturn("RULE-HASH");
    when(inputFingerprintService.calculate(any())).thenReturn("INPUT-HASH");
    when(workspaceService.update(workspace, 4)).thenReturn(workspace);

    assertThat(service.prepareCurrent("OA-1", 10L)).isSameAs(build);
    assertThat(workspace.getCurrentBomBuildBatchId()).isEqualTo("BUILD-2");
    assertThat(workspace.getBomSourceFingerprint()).isEqualTo("SOURCE-HASH");
    assertThat(workspace.getBomRuleFingerprint()).isEqualTo("RULE-HASH");
    assertThat(workspace.getInputFingerprint()).isEqualTo("INPUT-HASH");
    assertThat(workspace.getWorkspaceStatus()).isEqualTo("BOM_READY");
    assertThat(workspace.getCurrentPrepareNo()).isNull();

    InOrder order = inOrder(workspaceService, persistenceService, costingBuildService);
    order.verify(workspaceService).lockOrCreate("OA-1", 10L, "P", "2026-08", "COMMERCIAL");
    order.verify(persistenceService).persistCurrentVariant(any());
    order.verify(costingBuildService).buildFromEffectiveBom(10L, "BUILD-2");
    order.verify(workspaceService).update(workspace, 4);
  }

  @Test
  void failedBuildDoesNotSwitchWorkspacePointer() {
    OaFormItem item = item();
    QuoteCostingWorkspace workspace = workspace();
    workspace.setCurrentBomBuildBatchId("BUILD-OLD");
    workspace.setCurrentCostVersionId(88L);
    when(itemMapper.selectById(10L)).thenReturn(item);
    when(effectiveBomService.prepareCostingCandidate("OA-1", 10L)).thenReturn(candidate());
    when(workspaceService.lockOrCreate(any(), any(), any(), any(), any())).thenReturn(workspace);
    when(persistenceService.persistCurrentVariant(any()))
        .thenReturn(new QuoteEffectiveBomPersistenceResult("BUILD-2", "SOURCE-HASH", false, 2));
    when(costingBuildService.buildFromEffectiveBom(10L, "BUILD-2"))
        .thenThrow(new IllegalStateException("模拟结算行写入失败"));

    assertThatThrownBy(() -> service.prepareCurrent("OA-1", 10L))
        .hasMessageContaining("模拟结算行写入失败");
    assertThat(workspace.getCurrentBomBuildBatchId()).isEqualTo("BUILD-OLD");
    assertThat(workspace.getCurrentCostVersionId()).isEqualTo(88L);
    verify(workspaceService, never()).update(any(), any(Integer.class));
  }

  @Test
  void transactionCoversBuildAndPointerSwitch() throws Exception {
    Transactional annotation =
        QuoteEffectiveBomCostingServiceImpl.class
            .getMethod("prepareCurrent", String.class, Long.class)
            .getAnnotation(Transactional.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.propagation()).isEqualTo(Propagation.REQUIRED);
    assertThat(annotation.rollbackFor()).contains(Exception.class);
  }

  private OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(10L);
    item.setMaterialNo("P");
    item.setBusinessUnitType("COMMERCIAL");
    item.setPackageMethod("BOX");
    return item;
  }

  private QuoteCostingWorkspace workspace() {
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setId(1L);
    workspace.setOaFormItemId(10L);
    workspace.setPeriodMonth("2026-08");
    workspace.setLockVersion(4);
    workspace.setCurrentPrepareNo("PREP-OLD");
    return workspace;
  }

  private QuoteEffectiveBomCostingCandidate candidate() {
    QuoteEffectiveBomResponse response =
        new QuoteEffectiveBomResponse(
            "DRAFT",
            "OA-1",
            10L,
            "2026-08",
            "P",
            "CUSTOMER",
            "OA",
            "BOX",
            "210",
            "COMMERCIAL",
            7L,
            "RAW-1",
            null,
            "SOURCE-HASH",
            10L,
            List.of(),
            List.of(new QuoteEffectiveBomAlternativeResponse(
                "G1", "S", "S", "STANDARD", "AUTO_STANDARD", 1, 5L, true)),
            new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
            List.of(),
            List.of());
    return new QuoteEffectiveBomCostingCandidate(
        response,
        Map.of("G1", 5L),
        new EffectiveBomVariantInput(
            "2026-08",
            "RAW-1",
            "210",
            "P",
            "BOX",
            Map.of("G1", "S"),
            new EffectiveBomBuildResult(List.of(), List.of(), List.of(), List.of())));
  }

  private QuoteBomCostingBuildResponse build(String buildBatchId) {
    return new QuoteBomCostingBuildResponse(
        1L,
        10L,
        "OA-1",
        "P",
        "NON_BARE",
        "2026-08",
        buildBatchId,
        2,
        2,
        0,
        Map.of(),
        List.of(),
        LocalDateTime.now());
  }
}
