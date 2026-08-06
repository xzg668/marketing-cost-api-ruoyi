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
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomExclusionSummaryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeMonthlyInheritanceService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomVariantInput;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeCommand;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeResult;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomActorProvider;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomConfirmationCandidate;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class QuoteEffectiveBomConfirmServiceTest {

  private QuoteEffectiveBomApplicationService effectiveBomService;
  private QuoteBomMonthlyFreezeService freezeService;
  private QuoteProductBomCostingBuildService costingBuildService;
  private QuoteBomConfirmationService confirmationService;
  private QuoteEffectiveBomActorProvider actorProvider;
  private QuoteBomAlternativeMonthlyInheritanceService monthlyInheritanceService;
  private QuoteEffectiveBomConfirmationServiceImpl service;

  @BeforeEach
  void setUp() {
    effectiveBomService = mock(QuoteEffectiveBomApplicationService.class);
    freezeService = mock(QuoteBomMonthlyFreezeService.class);
    costingBuildService = mock(QuoteProductBomCostingBuildService.class);
    confirmationService = mock(QuoteBomConfirmationService.class);
    actorProvider = mock(QuoteEffectiveBomActorProvider.class);
    monthlyInheritanceService = mock(QuoteBomAlternativeMonthlyInheritanceService.class);
    service =
        new QuoteEffectiveBomConfirmationServiceImpl(
            effectiveBomService,
            freezeService,
            costingBuildService,
            confirmationService,
            actorProvider,
            monthlyInheritanceService);
    when(actorProvider.currentUserId()).thenReturn(9527L);
  }

  @Test
  void firstConfirmationFreezesBuildsAndConfirmsInOrderWithOneBuildId() {
    QuoteEffectiveBomConfirmationCandidate candidate = candidate(false, "DRAFT");
    when(effectiveBomService.prepareConfirmation("OA-1", 10L)).thenReturn(candidate);
    when(freezeService.freeze(any())).thenReturn(frozen(false));
    when(costingBuildService.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .thenReturn(costing());
    when(confirmationService.confirmEffective(
            org.mockito.ArgumentMatchers.eq("OA-1"),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq("qeb_BUILD_1"),
            org.mockito.ArgumentMatchers.eq(1),
            any()))
        .thenReturn(confirmation());

    QuoteEffectiveBomConfirmResponse result =
        service.confirm("OA-1", 10L, new QuoteBomConfirmRequest());

    assertThat(result.buildBatchId()).isEqualTo("qeb_BUILD_1");
    assertThat(result.costingRowCount()).isEqualTo(2);
    assertThat(result.reusedMonthlyFreeze()).isFalse();
    InOrder order =
        inOrder(effectiveBomService, freezeService, costingBuildService, confirmationService);
    order.verify(effectiveBomService).prepareConfirmation("OA-1", 10L);
    order.verify(freezeService).freeze(any(QuoteBomMonthlyFreezeCommand.class));
    order.verify(costingBuildService).buildFromEffectiveBom(10L, "qeb_BUILD_1");
    order.verify(confirmationService)
        .confirmEffective(
            org.mockito.ArgumentMatchers.eq("OA-1"),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq("qeb_BUILD_1"),
            org.mockito.ArgumentMatchers.eq(1),
            any());
    verify(monthlyInheritanceService).releaseProvisional(any(), any());
  }

  @Test
  void enteringStepTwoStagesReplaceableDraftAndBuildsRowsWithoutFreezingOrConfirming() {
    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(false, "DRAFT"));
    when(freezeService.stage(any())).thenReturn(staged());
    when(costingBuildService.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .thenReturn(costing());

    QuoteBomCostingBuildResponse result =
        service.prepareCostingBom("OA-1", 10L);

    assertThat(result.buildBatchId()).isEqualTo("qeb_BUILD_1");
    assertThat(result.costingRowsWritten()).isEqualTo(2);
    InOrder order = inOrder(effectiveBomService, freezeService, costingBuildService);
    order.verify(effectiveBomService).prepareConfirmation("OA-1", 10L);
    order.verify(freezeService).stage(any(QuoteBomMonthlyFreezeCommand.class));
    order.verify(costingBuildService).buildFromEffectiveBom(10L, "qeb_BUILD_1");
    verify(monthlyInheritanceService).releaseProvisional(any(), any());
    verify(freezeService, never()).freeze(any());
    verify(confirmationService, never())
        .confirmEffective(any(), any(), any(), any(Integer.class), any());
  }

  @Test
  void laterOaReusesFrozenTreeButBuildsItsOwnCostingRows() {
    when(effectiveBomService.prepareConfirmation("OA-2", 20L))
        .thenReturn(candidate(true, "REUSED"));
    when(freezeService.freeze(any())).thenReturn(frozen(true));
    QuoteBomCostingBuildResponse costing = costing();
    when(costingBuildService.buildFromEffectiveBom(20L, "qeb_BUILD_1"))
        .thenReturn(costing);
    when(confirmationService.confirmEffective(
            org.mockito.ArgumentMatchers.eq("OA-2"),
            org.mockito.ArgumentMatchers.eq(20L),
            org.mockito.ArgumentMatchers.eq("qeb_BUILD_1"),
            org.mockito.ArgumentMatchers.eq(1),
            any()))
        .thenReturn(confirmation());

    QuoteEffectiveBomConfirmResponse result = service.confirm("OA-2", 20L, null);

    assertThat(result.reusedMonthlyFreeze()).isTrue();
    verify(costingBuildService).buildFromEffectiveBom(20L, "qeb_BUILD_1");
  }

  @Test
  void repeatedConfirmationIsIdempotentAndDoesNotRebuildRows() {
    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(true, "FROZEN"));
    when(confirmationService.hasActiveConfirmation("OA-1", 10L, "P", "2026-08"))
        .thenReturn(true);
    when(freezeService.freeze(any())).thenReturn(frozen(true));
    QuoteBomConfirmResponse existing = confirmation();
    existing.setRowCount(7);
    when(confirmationService.confirmEffective(
            org.mockito.ArgumentMatchers.eq("OA-1"),
            org.mockito.ArgumentMatchers.eq(10L),
            org.mockito.ArgumentMatchers.eq("qeb_BUILD_1"),
            org.mockito.ArgumentMatchers.eq(1),
            any()))
        .thenReturn(existing);

    QuoteEffectiveBomConfirmResponse result = service.confirm("OA-1", 10L, null);

    assertThat(result.reusedExistingConfirmation()).isTrue();
    assertThat(result.costingRowCount()).isEqualTo(7);
    verify(costingBuildService, never()).buildFromEffectiveBom(any(), any());
  }

  @Test
  void costingFailureStopsConfirmationAndBubblesForTransactionRollback() {
    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(false, "DRAFT"));
    when(freezeService.freeze(any())).thenReturn(frozen(false));
    when(costingBuildService.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .thenThrow(new IllegalStateException("模拟第2步失败"));

    assertThatThrownBy(() -> service.confirm("OA-1", 10L, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟第2步失败");
    verify(confirmationService, never()).confirmEffective(any(), any(), any(), any(Integer.class), any());
  }

  @Test
  void buildBatchMismatchBlocksConfirmation() {
    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(false, "DRAFT"));
    when(freezeService.freeze(any())).thenReturn(frozen(false));
    QuoteBomCostingBuildResponse wrong =
        new QuoteBomCostingBuildResponse(
            1L, null, 10L, "OA-1", "P", "NON_BARE", "2026-08", "OTHER", 2, 2, 0,
            Map.of(), List.of(), LocalDateTime.now());
    when(costingBuildService.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .thenReturn(wrong);

    assertThatThrownBy(() -> service.confirm("OA-1", 10L, null))
        .isInstanceOf(QuoteEffectiveBomQueryException.class)
        .hasMessageContaining("第2步结算行");
    verify(confirmationService, never()).confirmEffective(any(), any(), any(), any(Integer.class), any());
  }

  @Test
  void rebuildStagesCurrentDraftAndProtectsConfirmedRows() {
    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(false, "DRAFT"));
    when(freezeService.stage(any())).thenReturn(staged());
    when(costingBuildService.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .thenReturn(costing());

    QuoteBomCostingBuildResponse rebuilt =
        service.rebuildCostingFromEffective("OA-1", 10L);

    assertThat(rebuilt.buildBatchId()).isEqualTo("qeb_BUILD_1");
    verify(freezeService).stage(any());

    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(true, "FROZEN"));
    when(confirmationService.hasActiveConfirmation("OA-1", 10L, "P", "2026-08"))
        .thenReturn(true);
    assertThatThrownBy(() -> service.rebuildCostingFromEffective("OA-1", 10L))
        .isInstanceOf(QuoteEffectiveBomQueryException.class)
        .hasMessageContaining("已经确认");
    verify(costingBuildService).buildFromEffectiveBom(10L, "qeb_BUILD_1");
  }

  @Test
  void rebuildFromFinalFrozenReusesEffectiveBuildPointer() {
    when(effectiveBomService.prepareConfirmation("OA-1", 10L))
        .thenReturn(candidate(true, "FROZEN"));
    when(freezeService.freeze(any())).thenReturn(frozen(true));
    when(costingBuildService.buildFromEffectiveBom(10L, "qeb_BUILD_1"))
        .thenReturn(costing());

    QuoteBomCostingBuildResponse result =
        service.rebuildCostingFromEffective("OA-1", 10L);

    assertThat(result.buildBatchId()).isEqualTo("qeb_BUILD_1");
    verify(freezeService).freeze(any());
  }

  @Test
  void serviceMethodsDeclareRequiredRollbackTransaction() throws Exception {
    for (String method : List.of("prepareCostingBom", "confirm", "rebuildCostingFromEffective")) {
      java.lang.reflect.Method reflected =
          "confirm".equals(method)
              ? QuoteEffectiveBomConfirmationServiceImpl.class.getMethod(
                  method, String.class, Long.class, QuoteBomConfirmRequest.class)
              : QuoteEffectiveBomConfirmationServiceImpl.class.getMethod(
                  method, String.class, Long.class);
      Transactional transactional = reflected.getAnnotation(Transactional.class);
      assertThat(transactional).isNotNull();
      assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
      assertThat(transactional.rollbackFor()).contains(Exception.class);
    }
  }

  private QuoteEffectiveBomConfirmationCandidate candidate(
      boolean frozen, String state) {
    QuoteEffectiveBomResponse response =
        new QuoteEffectiveBomResponse(
            state,
            state.equals("REUSED") ? "OA-2" : "OA-1",
            state.equals("REUSED") ? 20L : 10L,
            "2026-08",
            "P",
            "CUSTOMER-A",
            "OA_HEADER",
            "BOX",
            "210",
            "COMMERCIAL",
            11L,
            "RAW-1",
            frozen ? "qeb_BUILD_1" : null,
            frozen ? "HASH" : null,
            10L,
            List.of(node()),
            List.of(new QuoteEffectiveBomAlternativeResponse(
                "ALT-1", "S", "T", "ALTERNATIVE", "MANUAL", 1, 81L, true)),
            new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
            List.of(),
            List.of());
    return new QuoteEffectiveBomConfirmationCandidate(
        response,
        new QuoteBomMonthlyFreezeKey("2026-08", "P", "CUSTOMER-A", "BOX", "210"),
        Map.of("ALT-1", 81L),
        frozen
            ? null
            : new EffectiveBomVariantInput(
                "2026-08", "RAW-1", "210", "P", "BOX", Map.of(), null));
  }

  private QuoteEffectiveBomNodeResponse node() {
    return new QuoteEffectiveBomNodeResponse(
        "N1", null, 0, 1, "/P/", "P", "产品", null,
        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE,
        "制造件", "MANUFACTURE", "U9", null, null, null, null, null, null,
        null, null, null, null, "U9", "RAW-1", 1L, "/P/");
  }

  private QuoteBomMonthlyFreezeResult frozen(boolean reused) {
    return new QuoteBomMonthlyFreezeResult(
        11L, "qeb_BUILD_1", "HASH", reused, reused, LocalDateTime.now());
  }

  private QuoteBomMonthlyFreezeResult staged() {
    return new QuoteBomMonthlyFreezeResult(
        11L, "qeb_BUILD_1", "HASH", false, false, null);
  }

  private QuoteBomCostingBuildResponse costing() {
    return new QuoteBomCostingBuildResponse(
        1L, null, 10L, "OA-1", "P", "NON_BARE", "2026-08", "qeb_BUILD_1",
        2, 2, 0, Map.of("EFFECTIVE_BOM", 2), List.of(), LocalDateTime.now());
  }

  private QuoteBomConfirmResponse confirmation() {
    QuoteBomConfirmResponse response = new QuoteBomConfirmResponse();
    response.setOaNo("OA-1");
    response.setOaFormItemId(10L);
    response.setTopProductCode("P");
    response.setPeriodMonth("2026-08");
    response.setRowCount(2);
    response.setCostingBuildBatchId("qeb_BUILD_1");
    return response;
  }
}
