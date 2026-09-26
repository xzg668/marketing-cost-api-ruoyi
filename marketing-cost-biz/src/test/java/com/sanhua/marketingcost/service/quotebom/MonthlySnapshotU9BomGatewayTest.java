package com.sanhua.marketingcost.service.quotebom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MonthlySnapshotU9BomGatewayTest {
  private final QuoteBomMonthlySnapshotMapper mapper = mock(QuoteBomMonthlySnapshotMapper.class);
  private final LiveU9BomGateway live = mock(LiveU9BomGateway.class);
  private final MonthlyBomSnapshotDetailService details = mock(MonthlyBomSnapshotDetailService.class);
  private final Clock clock =
      Clock.fixed(Instant.parse("2026-08-25T18:00:00Z"), ZoneId.of("UTC"));
  private MonthlySnapshotU9BomGateway gateway;

  @BeforeEach
  void setUp() {
    gateway = new MonthlySnapshotU9BomGateway(mapper, live, details, clock);
    when(details.load(any())).thenReturn(List.of(new BomRawHierarchy()));
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
    verify(details).captureU9(eq(100L), any(), eq("BUILD-3"));
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
  void overwrittenLegacySourceStartsNewCardWithoutDeletingOldHeader() {
    QuoteBomMonthlySnapshot old = stored(301L, "SUCCESS");
    old.setBomBatchId("OLD-DELETED-BATCH");
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(old);
    when(mapper.selectU9MonthlyByIdentityForUpdate(any())).thenReturn(old);
    when(details.load(301L)).thenReturn(List.of());
    org.mockito.Mockito.doThrow(new MonthlyBomSnapshotDetailService.SourceUnavailableException("旧批次已不存在"))
        .when(details).captureU9(eq(301L), any(), eq("OLD-DELETED-BATCH"));
    when(live.readLive(any())).thenReturn(
        CurrentU9BomResult.available("U9", "NEW", "CURRENT-BATCH", 4));
    when(mapper.retireUnavailableU9MonthlySnapshot(eq(301L), any(), any())).thenReturn(1);
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot claim = invocation.getArgument(0);
      claim.setId(302L);
      return 1;
    }).when(mapper).insertU9MonthlyClaim(any());
    when(mapper.completeU9MonthlyClaim(eq(302L), eq("SUCCESS"), any(), any())).thenReturn(1);

    CurrentU9BomResult result = gateway.read(context("OA-REQUOTE", 17L, "2026-08"));

    assertThat(result.monthlySnapshotId()).isEqualTo(302L);
    assertThat(result.syncBatchId()).isEqualTo("CURRENT-BATCH");
    verify(mapper, never()).deleteU9MonthlyClaim(301L);
    verify(details).captureU9(eq(302L), any(), eq("CURRENT-BATCH"));
  }

  @Test
  void stillMissingReusesOriginalEvidenceAfterCheckingCurrentU9() {
    QuoteBomMonthlySnapshot stored = stored(102L, "NOT_FOUND");
    stored.setErrorMessage("首次查询无BOM");
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(stored);
    when(live.readLive(any())).thenReturn(CurrentU9BomResult.notFound("仍无BOM"));

    CurrentU9BomResult result = gateway.read(context("OA-LATER", 12L, "2026-08"));

    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.NOT_FOUND);
    assertThat(result.message()).isEqualTo("首次查询无BOM");
    assertThat(result.monthlySnapshotId()).isEqualTo(102L);
    verify(live).readLive(any());
    verify(mapper, never()).insertU9MonthlyClaim(any());
  }

  @Test
  void laterAvailableU9CreatesNewSnapshotAndRetainsPreviousMissingEvidence() {
    var previous = stored(102L, "NOT_FOUND");
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(previous);
    when(mapper.selectU9MonthlyByIdentityForUpdate(any())).thenReturn(previous);
    when(live.readLive(any())).thenReturn(CurrentU9BomResult.available("U9", "NEW", "U9-LATER", 5, "B".repeat(64)));
    when(mapper.retireMissingU9MonthlySnapshot(eq(102L), any(), any())).thenReturn(1);
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot claim = invocation.getArgument(0);
      claim.setId(106L);
      return 1;
    }).when(mapper).insertU9MonthlyClaim(any());
    when(mapper.completeU9MonthlyClaim(eq(106L), eq("SUCCESS"), any(), any())).thenReturn(1);
    var result = gateway.read(context("OA-NEW", 16L, "2026-08"));
    assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.AVAILABLE);
    assertThat(result.monthlySnapshotId()).isEqualTo(106L);
    assertThat(result.syncBatchId()).isEqualTo("U9-LATER");
    assertThat(previous.getSyncStatus()).isEqualTo("NOT_FOUND");
    verify(mapper, never()).deleteU9MonthlyClaim(102L);
  }

  @Test
  void sourceErrorDoesNotReusePreviousMissingResult() {
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(stored(102L, "NOT_FOUND"));
    when(live.readLive(any())).thenReturn(CurrentU9BomResult.timeout("U9查询超时"));
    assertThat(gateway.read(context("OA-NEW", 16L, "2026-08")).status())
        .isEqualTo(CurrentU9BomResult.Status.TIMEOUT);
    verify(mapper, never()).retireMissingU9MonthlySnapshot(any(), any(), any());
  }

  @Test
  void concurrentSourceSwitchReusesWinnerInsteadOfCreatingAnotherSnapshot() {
    var winner = stored(106L, "SUCCESS");
    winner.setBomBatchId("U9-WINNER");
    when(mapper.selectU9MonthlyByIdentity(any())).thenReturn(stored(102L, "NOT_FOUND"));
    when(live.readLive(any())).thenReturn(CurrentU9BomResult.available("U9", "NEW", "U9-LATER", 5));
    when(mapper.selectU9MonthlyByIdentityForUpdate(any())).thenReturn(winner);
    var result = gateway.read(context("OA-NEW", 16L, "2026-08"));
    assertThat(result.monthlySnapshotId()).isEqualTo(106L);
    assertThat(result.syncBatchId()).isEqualTo("U9-WINNER");
    verify(mapper, never()).insertU9MonthlyClaim(any());
    verify(mapper, never()).retireMissingU9MonthlySnapshot(any(), any(), any());
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

  @Test
  void sameProductReusesAugustBomButSeptemberReadsLatestU9WithoutChangingAugust() {
    var augustContext = context("OA-AUG", 10L, "2026-08");
    var septemberContext = context("OA-SEP", 10L, "2026-09");
    var august = stored(201L, "SUCCESS");
    august.setCostPeriodMonth("2026-08");
    august.setBomVersion("AUG-V1");
    august.setBomBatchId("AUG-BOM");
    String augustKey = U9MonthlySnapshotIdentity.from(augustContext).identityKey();
    when(mapper.selectU9MonthlyByIdentity(any())).thenAnswer(invocation ->
        augustKey.equals(invocation.getArgument(0)) ? august : null);
    doAnswer(invocation -> {
      QuoteBomMonthlySnapshot claim = invocation.getArgument(0);
      claim.setId(202L);
      return 1;
    }).when(mapper).insertU9MonthlyClaim(any());
    when(live.readLive(septemberContext)).thenReturn(
        CurrentU9BomResult.available("U9", "SEP-V2", "SEP-LATEST-BOM", 21, "B".repeat(64)));
    when(mapper.completeU9MonthlyClaim(eq(202L), eq("SUCCESS"), any(), any())).thenReturn(1);

    var augustResult = gateway.read(augustContext);
    var septemberResult = gateway.read(septemberContext);

    assertThat(augustResult.syncBatchId()).isEqualTo("AUG-BOM");
    assertThat(septemberResult.syncBatchId()).isEqualTo("SEP-LATEST-BOM");
    verify(live, never()).readLive(augustContext);
    verify(live).readLive(septemberContext);
    assertThat(august.getCostPeriodMonth()).isEqualTo("2026-08");
    assertThat(august.getBomBatchId()).isEqualTo("AUG-BOM");
  }

  private QuoteBomReadContext context(String oaNo, Long itemId, String month) {
    return new QuoteBomReadContext(
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
