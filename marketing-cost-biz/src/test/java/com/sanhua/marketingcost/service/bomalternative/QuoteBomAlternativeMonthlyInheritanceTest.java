package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeRepository;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBomAlternativeMonthlyInheritanceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-04T02:00:00Z"), ZoneOffset.UTC);
  private static final QuoteBomMonthlyFreezeKey KEY_A =
      new QuoteBomMonthlyFreezeKey("2026-08", "P", "CUSTOMER-A", "BOX", "210");

  private QuoteBomMonthlyFreezeRepository monthlyRepository;
  private QuoteEffectiveBomRepository effectiveBomRepository;
  private QuoteBomAlternativeSelectionTestSupport.InMemoryRepository selectionRepository;
  private QuoteBomAlternativeMonthlyInheritanceServiceImpl service;

  @BeforeEach
  void setUp() {
    monthlyRepository = mock(QuoteBomMonthlyFreezeRepository.class);
    effectiveBomRepository = mock(QuoteEffectiveBomRepository.class);
    selectionRepository =
        new QuoteBomAlternativeSelectionTestSupport.InMemoryRepository();
    service =
        new QuoteBomAlternativeMonthlyInheritanceServiceImpl(
            monthlyRepository,
            effectiveBomRepository,
            selectionRepository,
            CLOCK);
  }

  @Test
  void draftKeepsExistingFirstQuoteDefaultBehavior() {
    when(monthlyRepository.findActiveSuccessForUpdate(KEY_A))
        .thenReturn(Optional.of(snapshot(10L, "DRAFT", null, 100L)));

    QuoteBomAlternativeMonthlyInheritanceResult result =
        service.inheritIfFrozen(KEY_A, scope("OA-NEW", 200L, "2026-08"));

    assertThat(result.frozen()).isFalse();
    assertThat(result.selections()).isEmpty();
    assertThat(selectionRepository.rows).isEmpty();
    verify(effectiveBomRepository, never()).findNodesByBuildBatchId(any());
  }

  @Test
  void sameCustomerNewOaInheritsAlternativeFromFrozenFinalTree() {
    QuoteBomAlternativeSelection source =
        sourceSelection("OA-FIRST", 100L, "GROUP-1", "S", "T", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    selectionRepository.insert(source);
    frozen(KEY_A, snapshot(10L, "FROZEN", "BUILD-T", 100L),
        List.of(node("BUILD-T", "2026-08", "GROUP-1", "T", "ALTERNATIVE", source.getId())));

    QuoteBomAlternativeMonthlyInheritanceResult result =
        service.inheritIfFrozen(KEY_A, scope("OA-NEW", 200L, "2026-08"));

    assertThat(result.frozen()).isTrue();
    assertThat(result.inherited()).isTrue();
    assertThat(result.monthlySnapshotId()).isEqualTo(10L);
    assertThat(result.selections()).singleElement().satisfies(row -> {
      assertThat(row.getOaNo()).isEqualTo("OA-NEW");
      assertThat(row.getOaFormItemId()).isEqualTo(200L);
      assertThat(row.getSelectedMaterialCode()).isEqualTo("T");
      assertThat(row.getSelectionSource())
          .isEqualTo(QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY);
      assertThat(row.getInheritedMonthlySnapshotId()).isEqualTo(10L);
      assertThat(row.getCandidateSnapshotJson()).isEqualTo(source.getCandidateSnapshotJson());
    });
    assertThat(source.getSelectionSource())
        .isEqualTo(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    assertThat(source.getSelectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_ACTIVE);
  }

  @Test
  void repeatedInheritanceIsIdempotentAndDoesNotCreateAnotherVersion() {
    QuoteBomAlternativeSelection source =
        sourceSelection("OA-FIRST", 100L, "GROUP-1", "S", "T", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    selectionRepository.insert(source);
    frozen(KEY_A, snapshot(10L, "FROZEN", "BUILD-T", 100L),
        List.of(node("BUILD-T", "2026-08", "GROUP-1", "T", "ALTERNATIVE", source.getId())));
    QuoteBomAlternativeSelectionScope target = scope("OA-NEW", 200L, "2026-08");

    QuoteBomAlternativeMonthlyInheritanceResult first =
        service.inheritIfFrozen(KEY_A, target);
    QuoteBomAlternativeMonthlyInheritanceResult second =
        service.inheritIfFrozen(KEY_A, target);

    assertThat(first.inherited()).isTrue();
    assertThat(second.inherited()).isFalse();
    assertThat(second.selections().getFirst().getId())
        .isEqualTo(first.selections().getFirst().getId());
    assertThat(selectionRepository.findHistory(target, "GROUP-1")).hasSize(1);
  }

  @Test
  void firstOaKeepsItsOriginalManualHistoryAfterFreeze() {
    QuoteBomAlternativeSelection source =
        sourceSelection("OA-FIRST", 100L, "GROUP-1", "S", "T", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    selectionRepository.insert(source);
    frozen(KEY_A, snapshot(10L, "FROZEN", "BUILD-T", 100L),
        List.of(node("BUILD-T", "2026-08", "GROUP-1", "T", "ALTERNATIVE", source.getId())));

    QuoteBomAlternativeMonthlyInheritanceResult result =
        service.inheritIfFrozen(KEY_A, scope("OA-FIRST", 100L, "2026-08"));

    assertThat(result.frozen()).isTrue();
    assertThat(result.inherited()).isFalse();
    assertThat(result.selections()).containsExactly(source);
    assertThat(selectionRepository.findHistory(scope("OA-FIRST", 100L, "2026-08"), "GROUP-1"))
        .containsExactly(source);
  }

  @Test
  void sourceOaCanReleaseProvisionalBomBeforeStepTwoConfirmation() {
    when(monthlyRepository.findActiveSuccessForUpdate(KEY_A))
        .thenReturn(Optional.of(snapshot(10L, "FROZEN", "BUILD-T", 100L)));
    when(monthlyRepository.hasActiveConfirmationForBuild("BUILD-T")).thenReturn(false);
    when(monthlyRepository.releaseProvisional(
            10L, "BUILD-T", LocalDateTime.of(2026, 8, 4, 2, 0)))
        .thenReturn(1);
    when(effectiveBomRepository.deleteUnreferencedByOriginMonthlySnapshotId(10L))
        .thenReturn(2);

    boolean released =
        service.releaseProvisional(KEY_A, scope("OA-FIRST", 100L, "2026-08"));

    assertThat(released).isTrue();
    verify(monthlyRepository)
        .clearStatusBindings("BUILD-T", LocalDateTime.of(2026, 8, 4, 2, 0));
    verify(effectiveBomRepository)
        .deleteUnreferencedByOriginMonthlySnapshotId(10L);
  }

  @Test
  void readingUnconfirmedFrozenSourceKeepsProvisionalBomUntilUserActuallyChangesSelection() {
    when(monthlyRepository.findActiveSuccessForUpdate(KEY_A))
        .thenReturn(Optional.of(snapshot(10L, "FROZEN", "BUILD-T", 100L)));

    QuoteBomAlternativeMonthlyInheritanceResult result =
        service.inheritIfFrozen(
            KEY_A, scope("OA-FIRST", 100L, "2026-08"));

    assertThat(result.frozen()).isFalse();
    assertThat(result.provisional()).isTrue();
    assertThat(result.monthlySnapshotId()).isEqualTo(10L);
    assertThat(result.buildBatchId()).isEqualTo("BUILD-T");
    verify(effectiveBomRepository, never()).findNodesByBuildBatchId("BUILD-T");
    verify(monthlyRepository, never()).releaseProvisional(any(), any(), any());
    verify(monthlyRepository, never()).clearStatusBindings(any(), any());
    verify(effectiveBomRepository, never())
        .deleteUnreferencedByOriginMonthlySnapshotId(any());
  }

  @Test
  void confirmedStepTwoKeepsTheFrozenBomLocked() {
    when(monthlyRepository.findActiveSuccessForUpdate(KEY_A))
        .thenReturn(Optional.of(snapshot(10L, "FROZEN", "BUILD-T", 100L)));
    when(monthlyRepository.hasActiveConfirmation(
            "OA-FIRST", 100L, "P", "2026-08"))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                service.releaseProvisional(
                    KEY_A, scope("OA-FIRST", 100L, "2026-08")))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting(error -> ((QuoteBomAlternativeSelectionException) error).getCode())
        .isEqualTo(QuoteBomAlternativeMonthlyInheritanceServiceImpl.ALT_MONTHLY_FROZEN);
    verify(monthlyRepository, never()).releaseProvisional(any(), any(), any());
    verify(effectiveBomRepository, never())
        .deleteUnreferencedByOriginMonthlySnapshotId(any());
  }

  @Test
  void anotherOaCannotReleaseTheSourceOasProvisionalBom() {
    when(monthlyRepository.findActiveSuccessForUpdate(KEY_A))
        .thenReturn(Optional.of(snapshot(10L, "FROZEN", "BUILD-T", 100L)));

    assertThatThrownBy(
            () ->
                service.releaseProvisional(
                    KEY_A, scope("OA-OTHER", 200L, "2026-08")))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting(error -> ((QuoteBomAlternativeSelectionException) error).getCode())
        .isEqualTo(QuoteBomAlternativeMonthlyInheritanceServiceImpl.ALT_MONTHLY_FROZEN);
    verify(monthlyRepository, never()).releaseProvisional(any(), any(), any());
    verify(effectiveBomRepository, never())
        .deleteUnreferencedByOriginMonthlySnapshotId(any());
  }

  @Test
  void autoStandardCreatedBeforeInheritanceIsSupersededByFrozenAlternative() {
    QuoteBomAlternativeSelection source =
        sourceSelection("OA-FIRST", 100L, "GROUP-1", "S", "T", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    QuoteBomAlternativeSelection auto =
        sourceSelection("OA-NEW", 200L, "GROUP-1", "S", "S", "STANDARD",
            QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    selectionRepository.insert(source);
    selectionRepository.insert(auto);
    frozen(KEY_A, snapshot(10L, "FROZEN", "BUILD-T", 100L),
        List.of(node("BUILD-T", "2026-08", "GROUP-1", "T", "ALTERNATIVE", source.getId())));

    QuoteBomAlternativeMonthlyInheritanceResult result =
        service.inheritIfFrozen(KEY_A, scope("OA-NEW", 200L, "2026-08"));

    assertThat(auto.getSelectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_SUPERSEDED);
    assertThat(result.selections().getFirst().getSelectionVersion()).isEqualTo(2);
    assertThat(result.selections().getFirst().getSelectedMaterialCode()).isEqualTo("T");
  }

  @Test
  void newOaCannotKeepManualChoiceAfterMonthlyScenarioWasFrozen() {
    QuoteBomAlternativeSelection source =
        sourceSelection("OA-FIRST", 100L, "GROUP-1", "S", "T", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    QuoteBomAlternativeSelection conflicting =
        sourceSelection("OA-NEW", 200L, "GROUP-1", "S", "S", "STANDARD",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD);
    selectionRepository.insert(source);
    selectionRepository.insert(conflicting);
    frozen(KEY_A, snapshot(10L, "FROZEN", "BUILD-T", 100L),
        List.of(node("BUILD-T", "2026-08", "GROUP-1", "T", "ALTERNATIVE", source.getId())));

    assertThatThrownBy(
            () -> service.inheritIfFrozen(KEY_A, scope("OA-NEW", 200L, "2026-08")))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting(error -> ((QuoteBomAlternativeSelectionException) error).getCode())
        .isEqualTo(QuoteBomAlternativeMonthlyInheritanceServiceImpl.ALT_MONTHLY_FROZEN);
    assertThat(conflicting.getSelectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_ACTIVE);
  }

  @Test
  void groupAbsentFromFrozenFinalTreeDoesNotProduceForcedSelection() {
    QuoteBomAlternativeSelection hidden =
        sourceSelection("OA-NEW", 200L, "HIDDEN-GROUP", "HS", "HS", "STANDARD",
            QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    selectionRepository.insert(hidden);
    QuoteBomMonthlySnapshot snapshot = snapshot(10L, "FROZEN", "BUILD-NONE", 100L);
    frozen(KEY_A, snapshot, List.of(node("BUILD-NONE", "2026-08", null, "P", null, null)));

    QuoteBomAlternativeMonthlyInheritanceResult result =
        service.inheritIfFrozen(KEY_A, scope("OA-NEW", 200L, "2026-08"));

    assertThat(result.selections()).isEmpty();
    assertThat(hidden.getSelectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_STALE);
    assertThat(selectionRepository.findCurrent(scope("OA-NEW", 200L, "2026-08"), "HIDDEN-GROUP"))
        .isNull();
  }

  @Test
  void customerAAndCustomerBInheritTheirOwnFrozenChoices() {
    QuoteBomMonthlyFreezeKey keyB =
        new QuoteBomMonthlyFreezeKey("2026-08", "P", "CUSTOMER-B", "BOX", "210");
    QuoteBomAlternativeSelection sourceT =
        sourceSelection("OA-A-FIRST", 101L, "GROUP-1", "S", "T", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    QuoteBomAlternativeSelection sourceS =
        sourceSelection("OA-B-FIRST", 102L, "GROUP-1", "S", "S", "STANDARD",
            QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    selectionRepository.insert(sourceT);
    selectionRepository.insert(sourceS);
    when(monthlyRepository.findActiveSuccessForUpdate(KEY_A))
        .thenReturn(Optional.of(snapshot(10L, "FROZEN", "BUILD-T", 101L)));
    when(monthlyRepository.findActiveSuccessForUpdate(keyB))
        .thenReturn(Optional.of(snapshot(20L, "FROZEN", "BUILD-S", 102L)));
    when(monthlyRepository.hasActiveConfirmationForBuild("BUILD-T"))
        .thenReturn(true);
    when(monthlyRepository.hasActiveConfirmationForBuild("BUILD-S"))
        .thenReturn(true);
    when(effectiveBomRepository.findNodesByBuildBatchId("BUILD-T"))
        .thenReturn(List.of(node("BUILD-T", "2026-08", "GROUP-1", "T", "ALTERNATIVE", sourceT.getId())));
    when(effectiveBomRepository.findNodesByBuildBatchId("BUILD-S"))
        .thenReturn(List.of(node("BUILD-S", "2026-08", "GROUP-1", "S", "STANDARD", sourceS.getId())));

    QuoteBomAlternativeMonthlyInheritanceResult inheritedA =
        service.inheritIfFrozen(KEY_A, scope("OA-A-NEW", 201L, "2026-08"));
    QuoteBomAlternativeMonthlyInheritanceResult inheritedB =
        service.inheritIfFrozen(keyB, scope("OA-B-NEW", 202L, "2026-08"));

    assertThat(inheritedA.selections().getFirst().getSelectedMaterialCode()).isEqualTo("T");
    assertThat(inheritedA.selections().getFirst().getInheritedMonthlySnapshotId()).isEqualTo(10L);
    assertThat(inheritedB.selections().getFirst().getSelectedMaterialCode()).isEqualTo("S");
    assertThat(inheritedB.selections().getFirst().getInheritedMonthlySnapshotId()).isEqualTo(20L);
  }

  @Test
  void frozenMonthUsesStoredBuildWhileNextDraftMonthReturnsToLiveQbaFlow() {
    QuoteBomAlternativeSelection source =
        sourceSelection("OA-FIRST", 100L, "GROUP-1", "S-OLD", "T-OLD", "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    selectionRepository.insert(source);
    frozen(KEY_A, snapshot(10L, "FROZEN", "BUILD-OLD", 100L),
        List.of(node("BUILD-OLD", "2026-08", "GROUP-1", "T-OLD", "ALTERNATIVE", source.getId())));
    QuoteBomMonthlyFreezeKey september =
        new QuoteBomMonthlyFreezeKey("2026-09", "P", "CUSTOMER-A", "BOX", "210");
    when(monthlyRepository.findActiveSuccessForUpdate(september))
        .thenReturn(Optional.of(snapshot(30L, "DRAFT", null, 300L)));

    QuoteBomAlternativeMonthlyInheritanceResult august =
        service.inheritIfFrozen(KEY_A, scope("OA-AUG", 200L, "2026-08"));
    QuoteBomAlternativeMonthlyInheritanceResult nextMonth =
        service.inheritIfFrozen(september, scope("OA-SEP", 300L, "2026-09"));

    assertThat(august.selections().getFirst().getSelectedMaterialCode()).isEqualTo("T-OLD");
    assertThat(nextMonth.frozen()).isFalse();
    verify(effectiveBomRepository, never()).findNodesByBuildBatchId("BUILD-NEW");
  }

  @Test
  void inheritedSelectionCannotBeChangedOrStaledByLiveCandidateSynchronization() {
    QuoteBomAlternativeSelectionTestSupport support =
        new QuoteBomAlternativeSelectionTestSupport();
    QuoteBomAlternativeSelection inherited =
        sourceSelection(
            "OA-1",
            10L,
            QuoteBomAlternativeSelectionTestSupport.GROUP_KEY,
            "STD",
            "ALT",
            "ALTERNATIVE",
            QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY);
    inherited.setTopProductCode("TOP");
    inherited.setPeriodMonth("2026-07");
    inherited.setInheritedMonthlySnapshotId(10L);
    support.repository.insert(inherited);
    BomAlternativeGroup liveGroup = support.group();

    assertThatThrownBy(
            () ->
                support.service.save(
                    new QuoteBomAlternativeSelectionCommand(
                        support.scope(),
                        liveGroup.alternativeGroupKey(),
                        liveGroup.standardCandidate().materialCode(),
                        1,
                        null,
                        "finance",
                        null),
                    liveGroup))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .extracting(error -> ((QuoteBomAlternativeSelectionException) error).getCode())
        .isEqualTo(QuoteBomAlternativeSelectionServiceImpl.ALT_MONTHLY_FROZEN);

    List<QuoteBomAlternativeSelectionResult> synchronizedSelections =
        support.service.synchronize(support.scope(), List.of());
    assertThat(synchronizedSelections).isEmpty();
    assertThat(inherited.getSelectionStatus())
        .isEqualTo(QuoteBomAlternativeSelection.STATUS_ACTIVE);
  }

  private void frozen(
      QuoteBomMonthlyFreezeKey key,
      QuoteBomMonthlySnapshot snapshot,
      List<QuoteEffectiveBomNode> nodes) {
    when(monthlyRepository.findActiveSuccessForUpdate(key))
        .thenReturn(Optional.of(snapshot));
    when(monthlyRepository.hasActiveConfirmationForBuild(
            snapshot.getEffectiveBuildBatchId()))
        .thenReturn(true);
    when(effectiveBomRepository.findNodesByBuildBatchId(snapshot.getEffectiveBuildBatchId()))
        .thenReturn(nodes);
  }

  private static QuoteBomMonthlySnapshot snapshot(
      long id, String status, String buildBatchId, Long sourceItemId) {
    QuoteBomMonthlySnapshot snapshot = new QuoteBomMonthlySnapshot();
    snapshot.setId(id);
    snapshot.setFreezeStatus(status);
    snapshot.setEffectiveBuildBatchId(buildBatchId);
    snapshot.setSourceOaFormItemId(sourceItemId);
    return snapshot;
  }

  private static QuoteEffectiveBomNode node(
      String buildBatchId,
      String month,
      String groupKey,
      String materialCode,
      String childType,
      Long selectionId) {
    QuoteEffectiveBomNode node = new QuoteEffectiveBomNode();
    node.setBuildBatchId(buildBatchId);
    node.setTopProductCode("P");
    node.setCostPeriodMonth(month);
    node.setPriceOrgCode("210");
    node.setNodeLevel(groupKey == null ? 0 : 1);
    node.setSortSeq(1);
    node.setNodePath("/P/" + materialCode + "/");
    node.setMaterialCode(materialCode);
    node.setAlternativeGroupKey(groupKey);
    node.setAlternativeChildType(childType);
    node.setAlternativeSelectionId(selectionId);
    return node;
  }

  private static QuoteBomAlternativeSelection sourceSelection(
      String oaNo,
      long itemId,
      String groupKey,
      String standardCode,
      String selectedCode,
      String selectedType,
      String source) {
    QuoteBomAlternativeSelection row = new QuoteBomAlternativeSelection();
    row.setSelectionNo("SEL-" + oaNo + "-" + groupKey);
    row.setOaNo(oaNo);
    row.setOaFormItemId(itemId);
    row.setTopProductCode("P");
    row.setPeriodMonth("2026-08");
    row.setPriceOrgCode("210");
    row.setAlternativeGroupKey(groupKey);
    row.setParentPath("/P/");
    row.setParentMaterialCode("P");
    row.setChildSeq(10);
    row.setProcessSeq("010");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setStandardMaterialCode(standardCode);
    row.setSelectedMaterialCode(selectedCode);
    row.setSelectedChildType(selectedType);
    row.setSelectionSource(source);
    row.setSelectionVersion(1);
    row.setSelectionStatus(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    row.setCurrentSlot(QuoteBomAlternativeSelection.CURRENT_SLOT);
    row.setCandidateSnapshotJson(
        "{\"alternativeGroupKey\":\"" + groupKey + "\",\"candidates\":[]}");
    row.setSourceImportBatchId("IMPORT-1");
    row.setSourceBuildBatchId("RAW-BUILD-1");
    row.setSelectedBy("finance");
    row.setSelectedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    row.setBusinessUnitType("COMMERCIAL");
    row.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    row.setUpdatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    return row;
  }

  private static QuoteBomAlternativeSelectionScope scope(
      String oaNo, long itemId, String month) {
    return new QuoteBomAlternativeSelectionScope(
        oaNo, itemId, "P", month, "210", "COMMERCIAL");
  }
}
