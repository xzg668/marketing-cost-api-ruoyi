package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataSharedModuleQueryTest {
  private final TechnicalDataSharedModules shared = mock(TechnicalDataSharedModules.class);
  private final QuoteTechnicalDataRepository versions = mock(QuoteTechnicalDataRepository.class);
  private final TechnicalDataSharedModuleQuery query = new TechnicalDataSharedModuleQuery(shared, versions,
      mock(TechnicalDataVersionContentCodec.class), mock(TechnicalDataDependencies.class), mock(TechnicalDataTaskRepository.class));
  private final TechnicalDataProductSource target = new TechnicalDataProductSource(20L, "QUOTE-B", 22L,
      "B", 1, "PRODUCT-X", "产品", null, null, null, null, null, null, null, "COMMERCIAL", "210", "COMMERCIAL");

  @Test void publicAvailableErrorOrUnconfirmedNeverQueriesSupplement() {
    for (var status : List.of(TechnicalDataAvailability.AVAILABLE, TechnicalDataAvailability.ERROR,
        TechnicalDataAvailability.UNCONFIRMED)) {
      assertThat(query.describe(target, "2026-09", List.of(fact(status)))).isEmpty();
    }
    verifyNoInteractions(shared, versions);
  }

  @Test void missingPublicShowsOriginalPersonWithoutReadingDraftContent() {
    when(shared.find("PRODUCT-X", 22L, "NET_LOSS")).thenReturn(owner("COMMERCIAL", "210", "2026-08", "RETURNED", "DRAFT"));
    var result = query.describe(target, "2026-09", List.of(fact(TechnicalDataAvailability.MISSING)));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().status()).isEqualTo("IN_PROGRESS");
    assertThat(result.getFirst().message()).contains("王工", "不能重复补录");
    assertThat(result.getFirst().sourceVersionId()).isNull();
    verifyNoInteractions(versions);
  }

  @Test void organizationAndBusinessUnitMismatchDoesNotExposeOriginalTaskOrPerson() {
    for (var source : List.of(owner("COMMERCIAL", "220", "2026-09", "APPROVED", "APPROVED"),
        owner("HOUSEHOLD", "210", "2026-09", "APPROVED", "APPROVED"))) {
      when(shared.find(anyString(), anyLong(), anyString())).thenReturn(source);
      var result = query.describe(target, "2026-09", List.of(fact(TechnicalDataAvailability.MISSING))).getFirst();
      assertThat(result.status()).isEqualTo("CONDITION_MISMATCH");
      assertThat(result.sourceTaskId()).isNull(); assertThat(result.assigneeName()).isNull();
    }
    verifyNoInteractions(versions);
  }

  @Test void annualRateCannotSilentlyUsePriorYearSupplement() {
    when(shared.find(anyString(), anyLong(), anyString())).thenReturn(owner("COMMERCIAL", "210", "2025-12", "APPROVED", "APPROVED"));
    var result = query.describe(target, "2026-09", List.of(fact(TechnicalDataAvailability.MISSING))).getFirst();
    assertThat(result.status()).isEqualTo("CONDITION_MISMATCH"); assertThat(result.message()).contains("年度");
    verifyNoInteractions(versions);
  }

  @Test void conflictingOriginalSourcesAreNotPresentedAsMissingOrAvailable() {
    when(shared.find(anyString(), anyLong(), anyString())).thenThrow(new IllegalStateException("duplicate sources"));
    var result = query.describe(target, "2026-09", List.of(fact(TechnicalDataAvailability.MISSING))).getFirst();
    assertThat(result.status()).isEqualTo("ERROR"); assertThat(result.sourceVersionId()).isNull();
    verifyNoInteractions(versions);
  }

  @Test void cancelledUnapprovedSourceDoesNotPreventFreshAssignment() {
    var source = owner("COMMERCIAL", "210", "2026-09", "EDITING", "DRAFT");
    when(shared.find(anyString(), anyLong(), anyString())).thenReturn(new TechnicalDataSharedModuleRepository.Owner(
        source.moduleId(), source.productId(), source.taskId(), source.quoteItemId(), source.materialNo(), source.moduleType(),
        "CANCELLED", source.moduleStatus(), source.assigneeId(), source.assigneeName(), source.versionId(), source.versionStatus(),
        source.businessUnit(), source.organization(), source.accountingMonth()));
    assertThat(query.describe(target, "2026-09", List.of(fact(TechnicalDataAvailability.MISSING)))).isEmpty();
  }

  private TechnicalDataSourceFact fact(TechnicalDataAvailability status) {
    return new TechnicalDataSourceFact(TechnicalDataModuleType.NET_LOSS, status, "PUBLIC_CHECK", "公共费率来源", null, LocalDateTime.now());
  }

  private TechnicalDataSharedModuleRepository.Owner owner(String business, String org, String month, String moduleStatus, String versionStatus) {
    return new TechnicalDataSharedModuleRepository.Owner(1L, 2L, 3L, 4L, "PRODUCT-X", "NET_LOSS", "IN_PROGRESS", moduleStatus,
        101L, "王工", 9L, versionStatus, business, org, month);
  }
}
