package com.sanhua.marketingcost.service.effectivebom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class QuoteBomMonthlyFreezeServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC);
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 4, 2, 0);

  @Test
  void enteringStepTwoStagesOneReplaceableDraftWithoutFreezingCard() {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    when(repository.findActiveSuccessForUpdate(key()))
        .thenReturn(Optional.of(snapshot("DRAFT")));
    when(repository.findStatusForUpdate(101L)).thenReturn(Optional.of(status(101L)));
    when(persistence.persistConfirmed(any()))
        .thenReturn(
            new QuoteEffectiveBomPersistenceResult(
                "BUILD-1", "a".repeat(64), false, 2));
    when(repository.stageDraft(11L, "BUILD-1", "a".repeat(64), NOW))
        .thenReturn(1);
    when(repository.bindStatus(21L, 101L, 11L, "BUILD-1", NOW))
        .thenReturn(1);

    QuoteBomMonthlyFreezeResult result =
        service(repository, persistence).stage(command(101L, candidate()));

    assertThat(result.buildBatchId()).isEqualTo("BUILD-1");
    assertThat(result.reusedFrozenSnapshot()).isFalse();
    assertThat(result.frozenAt()).isNull();
    verify(repository).stageDraft(11L, "BUILD-1", "a".repeat(64), NOW);
    verify(repository, never()).freezeDraft(any(), any(), any(), any(), any());
  }

  @Test
  void firstConfirmationPersistsCandidateFreezesCardAndBindsCurrentOaRow()
      throws Exception {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    QuoteBomMonthlySnapshot snapshot = snapshot(null);
    QuoteBomStatus status = status(101L);
    when(repository.findActiveSuccessForUpdate(key()))
        .thenReturn(Optional.of(snapshot));
    when(repository.findStatusForUpdate(101L)).thenReturn(Optional.of(status));
    when(persistence.persistConfirmed(any()))
        .thenReturn(
            new QuoteEffectiveBomPersistenceResult(
                "BUILD-1", "a".repeat(64), false, 2));
    when(repository.freezeDraft(
            eq(11L), eq("BUILD-1"), eq("a".repeat(64)), eq(9527L), eq(NOW)))
        .thenReturn(1);
    when(repository.bindStatus(
            eq(21L), eq(101L), eq(11L), eq("BUILD-1"), eq(NOW)))
        .thenReturn(1);

    QuoteBomMonthlyFreezeResult result =
        service(repository, persistence).freeze(command(101L, candidate()));

    assertThat(result.monthlySnapshotId()).isEqualTo(11L);
    assertThat(result.buildBatchId()).isEqualTo("BUILD-1");
    assertThat(result.reusedFrozenSnapshot()).isFalse();
    assertThat(result.reusedEffectiveBuild()).isFalse();
    assertThat(result.frozenAt()).isEqualTo(NOW);
    verify(persistence)
        .persistConfirmed(
            new QuoteEffectiveBomPersistenceRequest(
                11L, 9527L, Map.of("ALT-GROUP-1", 81L), candidate()));
  }

  @Test
  void frozenCardIgnoresChangedCandidateAndOnlyBindsHistoricalBuild() {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    QuoteBomMonthlySnapshot snapshot = snapshot("FROZEN");
    snapshot.setEffectiveBuildBatchId("BUILD-OLD");
    snapshot.setEffectiveVariantHash("b".repeat(64));
    snapshot.setFrozenAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    QuoteBomStatus status = status(102L);
    when(repository.findActiveSuccessForUpdate(key()))
        .thenReturn(Optional.of(snapshot));
    when(repository.findStatusForUpdate(102L)).thenReturn(Optional.of(status));
    when(repository.bindStatus(
            eq(22L), eq(102L), eq(11L), eq("BUILD-OLD"), eq(NOW)))
        .thenReturn(1);

    EffectiveBomVariantInput changed =
        copyCandidate("2026-09", "OTHER-BATCH", "220", "OTHER", "BAG");
    QuoteBomMonthlyFreezeResult result =
        service(repository, persistence).freeze(command(102L, changed));

    assertThat(result.buildBatchId()).isEqualTo("BUILD-OLD");
    assertThat(result.reusedFrozenSnapshot()).isTrue();
    assertThat(result.reusedEffectiveBuild()).isTrue();
    assertThat(result.frozenAt()).isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));
    verifyNoInteractions(persistence);
    verify(repository, never())
        .freezeDraft(any(), any(), any(), any(), any());
  }

  @Test
  void firstFreezeRejectsCandidateFromAnotherMonthProductOrgPackageOrSource() {
    assertCandidateMismatch(
        copyCandidate("2026-09", "RAW-BATCH-1", "210", "P", "BOX"),
        "核算月份");
    assertCandidateMismatch(
        copyCandidate("2026-08", "RAW-BATCH-1", "210", "OTHER", "BOX"),
        "顶层产品");
    assertCandidateMismatch(
        copyCandidate("2026-08", "RAW-BATCH-1", "220", "P", "BOX"),
        "U9组织");
    assertCandidateMismatch(
        copyCandidate("2026-08", "RAW-BATCH-1", "210", "P", "BAG"),
        "包装方式");
    assertCandidateMismatch(
        copyCandidate("2026-08", "OTHER-BATCH", "210", "P", "BOX"),
        "原始BOM批次");
  }

  @Test
  void refusesToBindOaStatusFromAnotherCustomerScenario() {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    QuoteBomStatus status = status(101L);
    status.setCustomerCode("CUSTOMER-B");
    when(repository.findActiveSuccessForUpdate(key()))
        .thenReturn(Optional.of(snapshot("DRAFT")));
    when(repository.findStatusForUpdate(101L)).thenReturn(Optional.of(status));

    assertThatThrownBy(
            () -> service(repository, persistence).freeze(command(101L, candidate())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("客户隔离键");
    verifyNoInteractions(persistence);
  }

  @Test
  void missingMonthlyCardBlocksBeforeAnyWrite() {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    when(repository.findActiveSuccessForUpdate(key())).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service(repository, persistence).freeze(command(101L, candidate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("请先完成BOM同步");
    verifyNoInteractions(persistence);
  }

  @Test
  void cardConditionalUpdateFailurePropagatesForOuterTransactionRollback() {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    when(repository.findActiveSuccessForUpdate(key()))
        .thenReturn(Optional.of(snapshot("DRAFT")));
    when(repository.findStatusForUpdate(101L))
        .thenReturn(Optional.of(status(101L)));
    when(persistence.persistConfirmed(any()))
        .thenReturn(
            new QuoteEffectiveBomPersistenceResult(
                "BUILD-1", "a".repeat(64), false, 2));
    when(repository.freezeDraft(any(), any(), any(), any(), any()))
        .thenReturn(0);

    assertThatThrownBy(
            () -> service(repository, persistence).freeze(command(101L, candidate())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("事务已回滚");
    verify(repository, never()).bindStatus(any(), any(), any(), any(), any());
  }

  @Test
  void serviceDeclaresRequiredRollbackForExceptionTransaction() throws Exception {
    for (String method : java.util.List.of("stage", "freeze")) {
      Transactional transactional =
          QuoteBomMonthlyFreezeServiceImpl.class
              .getMethod(method, QuoteBomMonthlyFreezeCommand.class)
              .getAnnotation(Transactional.class);

      assertThat(transactional).isNotNull();
      assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
      assertThat(transactional.rollbackFor()).contains(Exception.class);
    }
  }

  @Test
  void keyNormalizesMonthWhitespaceAndEmptyPackageWithoutChangingIdentity() {
    QuoteBomMonthlyFreezeKey normalized =
        new QuoteBomMonthlyFreezeKey(
            " 2026-08 ", " P ", " CUSTOMER-A ", " / ", " 210 ");

    assertThat(normalized)
        .isEqualTo(
            new QuoteBomMonthlyFreezeKey(
                "2026-08", "P", "CUSTOMER-A", "", "210"));
    assertThatThrownBy(
            () ->
                new QuoteBomMonthlyFreezeKey(
                    "2026-13", "P", "CUSTOMER-A", "", "210"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("YYYY-MM");
  }

  private void assertCandidateMismatch(
      EffectiveBomVariantInput mismatch, String expectedMessage) {
    QuoteBomMonthlyFreezeRepository repository =
        mock(QuoteBomMonthlyFreezeRepository.class);
    QuoteEffectiveBomPersistenceService persistence =
        mock(QuoteEffectiveBomPersistenceService.class);
    when(repository.findActiveSuccessForUpdate(key()))
        .thenReturn(Optional.of(snapshot("DRAFT")));
    when(repository.findStatusForUpdate(101L))
        .thenReturn(Optional.of(status(101L)));

    assertThatThrownBy(
            () -> service(repository, persistence).freeze(command(101L, mismatch)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(expectedMessage);
    verifyNoInteractions(persistence);
  }

  private QuoteBomMonthlyFreezeServiceImpl service(
      QuoteBomMonthlyFreezeRepository repository,
      QuoteEffectiveBomPersistenceService persistence) {
    return new QuoteBomMonthlyFreezeServiceImpl(repository, persistence, CLOCK);
  }

  private QuoteBomMonthlyFreezeCommand command(
      long oaFormItemId, EffectiveBomVariantInput candidate) {
    return new QuoteBomMonthlyFreezeCommand(
        key(), oaFormItemId, 9527L, Map.of("ALT-GROUP-1", 81L), candidate);
  }

  private QuoteBomMonthlyFreezeKey key() {
    return new QuoteBomMonthlyFreezeKey(
        "2026-08", "P", "CUSTOMER-A", "BOX", "210");
  }

  private QuoteBomMonthlySnapshot snapshot(String freezeStatus) {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setId(11L);
    snapshot.setBomBatchId("RAW-BATCH-1");
    snapshot.setFreezeStatus(freezeStatus);
    return snapshot;
  }

  private QuoteBomStatus status(long oaFormItemId) {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(oaFormItemId - 80L);
    status.setOaFormItemId(oaFormItemId);
    status.setProductCode("P");
    status.setCustomerCode("CUSTOMER-A");
    status.setPackageMethod("BOX");
    status.setCostPeriodMonth("2026-08");
    return status;
  }

  private EffectiveBomVariantInput candidate() {
    return EffectiveBomPersistenceTestSupport.variant();
  }

  private EffectiveBomVariantInput copyCandidate(
      String month,
      String sourceBatch,
      String org,
      String product,
      String packageMethod) {
    EffectiveBomVariantInput source = candidate();
    return new EffectiveBomVariantInput(
        month,
        sourceBatch,
        org,
        product,
        packageMethod,
        source.selectedMaterialCodeByGroupKey(),
        source.buildResult());
  }
}
