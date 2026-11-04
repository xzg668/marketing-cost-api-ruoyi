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
      mock(TechnicalDataVersionContentCodec.class), mock(TechnicalDataDependencies.class), mock(TechnicalDataTaskRepository.class),
      mock(com.sanhua.marketingcost.mapper.OaFormItemMapper.class), mock(com.sanhua.marketingcost.mapper.OaFormMapper.class),
      mock(com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver.class), mock(TechnicalDataPriceRequirements.class));
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
    assertThat(result.getFirst().status()).isEqualTo("SAVED");
    assertThat(result.getFirst().message()).contains("王工", "已有资料", "无需重复补录");
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

  @Test void sharedSubmittedModuleDoesNotBlockOtherUnfilledModules() {
    var checkedAt = LocalDateTime.now();
    var requirements = List.of(
        new TechnicalDataModuleRequirement("PROFILE", true, "MISSING", "缺产品资料", TechnicalDataAvailability.MISSING, null, checkedAt),
        new TechnicalDataModuleRequirement("NET_LOSS", true, "MISSING", "缺净损失率", TechnicalDataAvailability.MISSING, null, checkedAt));
    var sources = List.of(new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSharedModuleInfo(
        "NET_LOSS", "SUBMITTED", 3L, 2L, 9L, "saved", "王工", "已有资料"));
    var result = TechnicalDataSharedModuleQuery.applySharedSources(requirements, sources);
    assertThat(result).filteredOn(TechnicalDataModuleRequirement::required)
        .extracting(TechnicalDataModuleRequirement::moduleType).containsExactly("PROFILE");
    assertThat(result.getLast().reasonCode()).isEqualTo("SHARED_MODULE_SUBMITTED");
    assertThat(result.getLast().sourceReference()).contains("3", "9", "saved");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PROFILE", "DRAWING_BOM", "MANUFACTURING", "PACKAGE", "AUXILIARY", "SOLDER", "SALARY", "NET_LOSS"})
  void everySavedSupplementModuleIsSharedWithoutReadingOrPublishingDraftContent(String type) {
    var base = owner("COMMERCIAL", "210", "2026-09", "READY", "DRAFT");
    var source = new TechnicalDataSharedModuleRepository.Owner(base.moduleId(), base.productId(), base.taskId(),
        base.quoteItemId(), base.materialNo(), type, base.taskStatus(), base.moduleStatus(), base.assigneeId(),
        base.assigneeName(), base.versionId(), base.versionStatus(), base.businessUnit(), base.organization(), base.accountingMonth());
    when(shared.find("PRODUCT-X", 22L, type)).thenReturn(source);
    var fact = new TechnicalDataSourceFact(TechnicalDataModuleType.valueOf(type), TechnicalDataAvailability.MISSING,
        "MISSING", "缺资料", null, LocalDateTime.now());
    var sources = query.describe(target, "2026-09", List.of(fact));
    assertThat(sources).singleElement().satisfies(row -> {
      assertThat(row.status()).isEqualTo("SAVED"); assertThat(row.message()).contains("已有资料");
      assertThat(row.sourceTaskId()).isEqualTo(base.taskId()); assertThat(row.sourceVersionId()).isNull();
    });
    var mapped = TechnicalDataSharedModuleQuery.applySharedSources(new TechnicalDataModuleRequirementEvaluator().evaluate(List.of(fact)), sources);
    assertThat(mapped).filteredOn(row -> type.equals(row.moduleType())).singleElement()
        .satisfies(row -> assertThat(row.required()).isFalse());
    verifyNoInteractions(versions);
  }

  private TechnicalDataSourceFact fact(TechnicalDataAvailability status) {
    return new TechnicalDataSourceFact(TechnicalDataModuleType.NET_LOSS, status, "PUBLIC_CHECK", "公共费率来源", null, LocalDateTime.now());
  }

  private TechnicalDataSharedModuleRepository.Owner owner(String business, String org, String month, String moduleStatus, String versionStatus) {
    return new TechnicalDataSharedModuleRepository.Owner(1L, 2L, 3L, 4L, "PRODUCT-X", "NET_LOSS", "IN_PROGRESS", moduleStatus,
        101L, "王工", 9L, versionStatus, business, org, month);
  }
}
