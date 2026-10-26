package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.monthly.MonthlySupplementalFixedPriceSourceMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonthlySupplementalFixedPricePendingTest {
  private final MonthlySupplementalFixedPriceSourceMapper rows = mock(MonthlySupplementalFixedPriceSourceMapper.class);
  private final TechnicalDataCostingSources approvals = mock(TechnicalDataCostingSources.class);
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final MonthlySupplementalFixedPriceSource source = new MonthlySupplementalFixedPriceSource(rows, approvals,
      new TechnicalDataVersionContentCodec(json), new OaMessageCodec(json), new MonthlyPriceDocumentCodec(json));
  private final MonthlyMaterialPrices.Context context = new MonthlyMaterialPrices.Context("COMMERCIAL", "OA", 11L,
      "TOP", YearMonth.of(2026, 10), LocalDateTime.of(2026, 10, 25, 10, 0), "test", "210");
  private final MonthlyPriceRequest request = new MonthlyPriceRequest(
      new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "MISSING", null, "件", "CNY", TaxBasis.EXCLUDED, null),
      "210", NonCuConditions.empty(), BigDecimal.ONE);
  private String moduleStatus = "PENDING", productStatus = "PENDING", taskStatus = "IN_PROGRESS";
  private String error = "TECH_DATA_TASK_NOT_SUBMITTED", versionStatus;
  private long item = 11L, form = 10L;
  private int active = 1, editable = 1;
  private Long effective;
  private String priceJson;

  @Test void newlyDispatchedOwnPriceGapRemainsMissingWithoutAnyCandidate() {
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.MISSING);
  }

  @ParameterizedTest @ValueSource(strings = {"EDITING", "READY", "RETURNED"})
  void ownDraftRemainsMissingSoSavingAndSubmissionValidationCanProceed(String state) {
    moduleStatus = state; productStatus = "RETURNED".equals(state) ? "RETURNED" : "EDITING";
    taskStatus = "RETURNED".equals(state) ? "PARTIALLY_RETURNED" : "IN_PROGRESS";
    versionStatus = "DRAFT"; priceJson = "{\"items\":[]}";
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.MISSING);
  }

  @Test void anotherQuoteItemCannotTreatTheOwnersPendingPriceAsItsOwnEditableGap() {
    item = 12L;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @Test void anotherQuoteFormCannotTreatPendingPriceAsItsOwnEditableGap() {
    form = 12L;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @ParameterizedTest @ValueSource(strings = {"FROZEN", "SUBMITTED", "RETURNED"})
  void aNonDraftVersionDoesNotBecomeAnEditableGap(String state) {
    moduleStatus = "READY"; productStatus = "EDITING"; versionStatus = state;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @Test void disabledEditingDoesNotBecomeMissing() {
    editable = 0;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @Test void inactiveSourceDoesNotBecomeMissing() {
    active = 0;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @Test void finalVersionWaitingDoesNotBecomeMissing() {
    effective = 51L;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @Test void financeConfirmationStillWaits() {
    error = "TECH_DATA_FINANCE_CONFIRMATION_REQUIRED";
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.WAITING);
  }

  @ParameterizedTest @ValueSource(strings = {"TECH_DATA_SOURCE_SCOPE_MISMATCH", "TECH_DATA_CONTENT_FINGERPRINT_MISMATCH", "TECH_DATA_SOURCE_CHANGED"})
  void invalidSourcesAreNeverDowngradedToMissing(String failure) {
    error = failure;
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }

  @Test void malformedOwnDraftRemainsAnError() {
    moduleStatus = "EDITING"; productStatus = "EDITING"; versionStatus = "DRAFT";
    priceJson = "{\"unknown\":true}";
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }

  @Test void anIllegallyPublishedDraftRemainsAnError() {
    moduleStatus = "READY"; productStatus = "EDITING"; versionStatus = "DRAFT";
    var fixed = new PriceFixedItem(); fixed.setMaterialCode("MISSING");
    when(rows.fixed(eq("COMMERCIAL"), anyCollection())).thenReturn(List.of(fixed));
    assertProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT);
  }

  private void assertProblem(MonthlyAdoptionRetryPolicy.Failure expected) {
    Long versionId = versionStatus == null ? null : 50L;
    var owner = new MonthlySupplementalFixedPriceSourceMapper.Ownership("MISSING", 40L, 20L, 30L, item,
        "TOP", "PRICE", taskStatus, moduleStatus, 101L, "技术员", versionId, versionStatus, "COMMERCIAL", "210",
        "2026-10", form, null, active, active, productStatus, effective, 2, "210", "COMMERCIAL", "件", "CNY");
    var module = new QuoteTechModule(); module.setId(40L); module.setProductId(20L); module.setModuleType("PRICE");
    module.setRequiredFlag(1); module.setOaEditAllowed(editable); module.setAssigneeUserId(101L);
    module.setModuleStatus(moduleStatus); module.setCurrentVersionId(versionId);
    var version = new QuoteTechDataVersion(); version.setId(50L); version.setProductId(20L);
    version.setVersionStatus(versionStatus); version.setPriceItemsJson(priceJson);
    when(rows.currentForm(11L, "OA", "COMMERCIAL")).thenReturn(10L);
    when(rows.owners(eq("COMMERCIAL"), anyCollection())).thenReturn(List.of(owner));
    when(rows.modules(anyCollection())).thenReturn(List.of(module));
    when(rows.versions(anyCollection())).thenReturn(versionId == null ? List.of() : List.of(version));
    when(approvals.requireCapturedSource(any(), any(), any())).thenThrow(
        new EffectiveTechnicalDataException(error, 11L, "2026-10", List.of("PRICE"), "原补录尚未全部提交：价格"));
    var found = source.read(context, Set.of(request));
    assertThat(found.values()).isEmpty();
    assertThat(found.problems().get(request).reason()).isEqualTo(expected);
  }
}
