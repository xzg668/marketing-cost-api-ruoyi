package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataSharedModulesTest {
  private final TechnicalDataSharedModuleRepository repository = mock(TechnicalDataSharedModuleRepository.class);
  private final TechnicalDataSharedModules shared = new TechnicalDataSharedModules(repository);
  private static final String IDENTITY = "MATERIAL:1145100050625";

  @Test void cancelledUnsubmittedClaimDoesNotBlockAnOldOrNewQuote() {
    for (String version : new String[] {null, "DRAFT", "READY"}) {
      when(repository.find(IDENTITY, "SOLDER")).thenReturn(owner(41L, "CANCELLED", version));
      when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of());
      assertThat(shared.find("1145100050625", 5L, "SOLDER")).isNull();
      assertThat(shared.find("1145100050625", 6L, "SOLDER")).isNull();
    }
    verify(repository, never()).transfer(anyString(), anyString(), anyLong(), anyLong());
  }

  @Test void replacementActiveOwnerIsUsedWhileCancelledClaimAwaitsTransfer() {
    var active = owner(51L, "IN_PROGRESS", "DRAFT");
    when(repository.find(IDENTITY, "SOLDER")).thenReturn(owner(41L, "CANCELLED", "DRAFT"));
    when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of(active));
    assertThat(shared.find("1145100050625", 5L, "SOLDER")).isSameAs(active);
  }

  @Test void emptyUnassignedClaimDoesNotBlockEitherQuoteOrMutateTheClaimOnRead() {
    when(repository.find(IDENTITY, "SOLDER")).thenReturn(emptyOwner());
    when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of());
    assertThat(shared.find("1145100050625", 5L, "SOLDER")).isNull();
    assertThat(shared.find("1145100050625", 6L, "SOLDER")).isNull();
    verify(repository, never()).transfer(anyString(), anyString(), anyLong(), anyLong());
    verify(repository, never()).lockOrInsert(anyString(), anyString(), anyLong());
  }

  @Test void dispatchTransfersAnEmptyClaimButCannotTakeAnotherPersonsActiveWork() {
    var current = owner(61L, "UNASSIGNED", null);
    when(repository.module(60L, "SOLDER")).thenReturn(current);
    when(repository.lockOrInsert(IDENTITY, "SOLDER", 61L)).thenReturn(51L);
    when(repository.find(IDENTITY, "SOLDER")).thenReturn(emptyOwner());
    when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of());
    shared.requireOwnership(60L, "SOLDER");
    verify(repository).transfer(IDENTITY, "SOLDER", 51L, 61L);

    clearInvocations(repository);
    when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of(owner(71L, "IN_PROGRESS", "DRAFT")));
    assertThatThrownBy(() -> shared.requireOwnership(60L, "SOLDER"))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("不能重复补录");
    verify(repository, never()).transfer(anyString(), anyString(), anyLong(), anyLong());
  }

  @Test void activeDraftAndSubmittedCancelledHistoryStillKeepTheirOwner() {
    for (var owner : List.of(owner(41L, "IN_PROGRESS", "DRAFT"),
        owner(41L, "UNASSIGNED", "DRAFT"), owner(41L, "UNASSIGNED", null),
        owner(41L, "CANCELLED", "SUBMITTED"), owner(41L, "CANCELLED", "APPROVED"))) {
      when(repository.find(IDENTITY, "SOLDER")).thenReturn(owner);
      when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of());
      assertThat(shared.find("1145100050625", 6L, "SOLDER")).isSameAs(owner);
    }
  }

  @Test void multipleActiveOwnersAndConflictingSubmittedClaimsStillFail() {
    when(repository.existing(IDENTITY, "SOLDER"))
        .thenReturn(List.of(owner(51L, "IN_PROGRESS", "DRAFT"), owner(61L, "IN_PROGRESS", "DRAFT")));
    assertThatThrownBy(() -> shared.find("1145100050625", 6L, "SOLDER"))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("重复办理");
    when(repository.existing(IDENTITY, "SOLDER")).thenReturn(List.of(owner(51L, "IN_PROGRESS", "DRAFT")));
    when(repository.find(IDENTITY, "SOLDER")).thenReturn(owner(41L, "CANCELLED", "SUBMITTED"));
    assertThatThrownBy(() -> shared.find("1145100050625", 6L, "SOLDER"))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("不一致");
  }

  private TechnicalDataSharedModuleRepository.Owner owner(long moduleId, String taskStatus, String versionStatus) {
    return new TechnicalDataSharedModuleRepository.Owner(moduleId, 40L, 4L, 6L, "1145100050625", "SOLDER",
        taskStatus, "PENDING", 888L, "技术员", versionStatus == null ? null : 90L, versionStatus,
        "COMMERCIAL", "210", "2026-10");
  }

  private TechnicalDataSharedModuleRepository.Owner emptyOwner() {
    return new TechnicalDataSharedModuleRepository.Owner(51L, 6L, 6L, 6L, "1145100050625", "SOLDER",
        "UNASSIGNED", "PENDING", null, null, null, null, "COMMERCIAL", "210", "2026-10");
  }
}
