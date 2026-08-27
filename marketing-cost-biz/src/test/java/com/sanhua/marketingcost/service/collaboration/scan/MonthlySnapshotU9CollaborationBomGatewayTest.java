package com.sanhua.marketingcost.service.collaboration.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MonthlySnapshotU9CollaborationBomGatewayTest {
  private final QuoteBomMonthlySnapshotMapper mapper = mock(QuoteBomMonthlySnapshotMapper.class);
  private final QuoteCollaborationLiveU9BomGateway live =
      mock(QuoteCollaborationLiveU9BomGateway.class);
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-08-25T18:00:00Z"), ZoneId.of("UTC"));
  private MonthlySnapshotU9CollaborationBomGateway gateway;

  @BeforeEach
  void setUp() {
    gateway = new MonthlySnapshotU9CollaborationBomGateway(mapper, live, clock);
  }

  @Test
  void firstAvailableQueryCreatesAndCompletesOneSharedSnapshot() {
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot claim = invocation.getArgument(0);
      claim.setId(100L);
      return 1;
    }).when(mapper).insertU9MonthlyClaim(any());
    when(live.readLive(any())).thenReturn(
        CurrentU9BomResult.available("U9", "V3", "BUILD-3", 20, "F".repeat(64)));
    when(mapper.completeU9MonthlyClaim(eq(100L), eq("SUCCESS"), any(), any()))
        .thenReturn(1);

    CurrentU9BomResult result = gateway.read(context("OA-FIRST", 10L, "2026-08"));

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.AVAILABLE);
    assertThat(result.monthlySnapshotId()).isEqualTo(100L);
    assertThat(result.monthlySnapshotCreated()).isTrue();
    ArgumentCaptor<QuoteBomMonthlySnapshot> claim =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(mapper).insertU9MonthlyClaim(claim.capture());
    assertThat(claim.getValue().getCustomerCode()).isEmpty();
    assertThat(claim.getValue().getPackageMethod()).isEmpty();
    assertThat(claim.getValue().getSyncStatus()).isEqualTo("SYNCING");
    assertThat(claim.getValue().getSnapshotIdentityKey()).hasSize(64);
  }

  @Test
  void existingAvailableSnapshotNeverReadsLiveU9Again() {
    QuoteBomMonthlySnapshot stored = stored(101L, "SUCCESS");
    stored.setBomVersion("V1");
    stored.setBomBatchId("BUILD-1");
    stored.setLineCount(9);
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(stored);

    CurrentU9BomResult result = gateway.read(context("OA-LATER", 11L, "2026-08"));

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.AVAILABLE);
    assertThat(result.monthlySnapshotId()).isEqualTo(101L);
    assertThat(result.monthlySnapshotCreated()).isFalse();
    verify(live, never()).readLive(any());
    verify(mapper, never()).insertU9MonthlyClaim(any());
  }

  @Test
  void explicitNotFoundIsFrozenAndReusedWithoutLiveRequery() {
    QuoteBomMonthlySnapshot stored = stored(102L, "NOT_FOUND");
    stored.setErrorMessage("首次查询无BOM");
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(stored);

    CurrentU9BomResult result = gateway.read(context("OA-LATER", 12L, "2026-08"));

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.NOT_FOUND);
    assertThat(result.message()).isEqualTo("首次查询无BOM");
    assertThat(result.monthlySnapshotId()).isEqualTo(102L);
    verify(live, never()).readLive(any());
  }

  @Test
  void timeoutDeletesClaimAndDoesNotFreezeAResult() {
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot claim = invocation.getArgument(0);
      claim.setId(103L);
      return 1;
    }).when(mapper).insertU9MonthlyClaim(any());
    when(live.readLive(any())).thenReturn(CurrentU9BomResult.timeout("timeout"));
    when(mapper.deleteU9MonthlyClaim(103L)).thenReturn(1);

    CurrentU9BomResult result = gateway.read(context("OA-ERROR", 13L, "2026-08"));

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.TIMEOUT);
    assertThat(result.monthlySnapshotId()).isNull();
    verify(mapper).deleteU9MonthlyClaim(103L);
    verify(mapper, never()).completeU9MonthlyClaim(any(), any(), any(), any());
  }

  @Test
  void concurrentLoserWaitsForWinnerAndNeverRunsSecondLiveQuery() {
    QuoteBomMonthlySnapshot winner = stored(104L, "NOT_FOUND");
    winner.setErrorMessage("winner not found");
    when(mapper.insertU9MonthlyClaim(any())).thenReturn(0);
    when(mapper.selectU9MonthlyByIdentityForUpdate(any())).thenReturn(winner);

    CurrentU9BomResult result = gateway.read(context("OA-CONCURRENT", 14L, "2026-08"));

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.NOT_FOUND);
    assertThat(result.monthlySnapshotId()).isEqualTo(104L);
    verify(live, never()).readLive(any());
  }

  @Test
  void nextMonthCreatesANewClaim() {
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot claim = invocation.getArgument(0);
      claim.setId(105L);
      return 1;
    }).when(mapper).insertU9MonthlyClaim(any());
    when(live.readLive(any())).thenReturn(CurrentU9BomResult.notFound("September no BOM"));
    when(mapper.completeU9MonthlyClaim(eq(105L), eq("NOT_FOUND"), any(), any()))
        .thenReturn(1);

    gateway.read(context("OA-SEPTEMBER", 15L, "2026-09"));

    ArgumentCaptor<QuoteBomMonthlySnapshot> claim =
        ArgumentCaptor.forClass(QuoteBomMonthlySnapshot.class);
    verify(mapper).insertU9MonthlyClaim(claim.capture());
    assertThat(claim.getValue().getCostPeriodMonth()).isEqualTo("2026-09");
  }

  private QuoteCollaborationScanContext context(String oaNo, Long itemId, String month) {
    return new QuoteCollaborationScanContext(
        1L, itemId, oaNo, month, "COMMERCIAL", "P-1", "产品", "规格", "型号",
        "210", "COMMERCIAL", LocalDate.of(2026, 8, 25),
        LocalDateTime.of(2026, 8, 25, 18, 0));
  }

  private QuoteBomMonthlySnapshot stored(Long id, String status) {
    QuoteBomMonthlySnapshot row = new QuoteBomMonthlySnapshot();
    row.setId(id);
    row.setSnapshotIdentityKey("A".repeat(64));
    row.setSyncStatus(status);
    row.setBomSource("U9");
    row.setStructureFingerprint("F".repeat(64));
    return row;
  }
}
