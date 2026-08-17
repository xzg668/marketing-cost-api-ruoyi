package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerifyRequest;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.integration.drawing.ElectronicBomFetchResult;
import com.sanhua.marketingcost.integration.drawing.ElectronicDrawingBomGateway;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.collaboration.ElectronicBomStructureValidator.ValidationResult;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("QCBP-11 电子图库回取编排")
class ElectronicBomVerificationServiceTest {
  private final QuoteCollaborationTaskRepository repository = mock(QuoteCollaborationTaskRepository.class);
  private final CollaborationCurrentPrincipalProvider principalProvider = mock(CollaborationCurrentPrincipalProvider.class);
  private final TechnicalBomDraftApplicationService draftService = mock(TechnicalBomDraftApplicationService.class);
  private final ElectronicDrawingBomGateway gateway = mock(ElectronicDrawingBomGateway.class);
  private final ElectronicBomStructureValidator validator = mock(ElectronicBomStructureValidator.class);
  private final ElectronicBomVerificationPersistenceService persistence = mock(ElectronicBomVerificationPersistenceService.class);
  private final TechnicalRealPriceGapScanService priceScan = mock(TechnicalRealPriceGapScanService.class);
  private final CollaborationPrincipal wang = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));
  private final ElectronicBomVerificationService service = new ElectronicBomVerificationService(
      repository, principalProvider, draftService, gateway, validator, persistence, priceScan);
  private QuoteCollaborationProductTask task;

  @BeforeEach
  void setUp() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("wang", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(principalProvider.currentTechnician()).thenReturn(wang);
    task = task(3, "BOM_IN_PROGRESS");
    when(repository.findMineById(10L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(repository.findLinksByProductTask(eq(10L), any())).thenReturn(List.of(owner()));
    when(draftService.exportSnapshot(10L)).thenReturn(
        new TechnicalBomDraftApplicationService.ElectronicBomTemplateSnapshot(
            10L, 3, "P-1", null, "产品", "S", "M", "COMMERCIAL", "210", null));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void notFoundBecomesStructuredFailureOnSameTaskAndNeverRunsPriceScan() {
    when(gateway.fetchCurrentBom(any())).thenReturn(ElectronicBomFetchResult.failure(
        ElectronicBomFetchResult.Status.NOT_FOUND, "未找到"));
    QuoteCollaborationProductTask failed = task(5, "TECH_VALIDATION_FAILED");
    when(persistence.persistFailure(eq(10L), eq(3), eq(wang), any(), any()))
        .thenReturn(new ElectronicBomVerificationPersistenceService.FailureResult(failed, 1));

    var response = service.verify(10L, new ElectronicBomVerifyRequest(3, "主制造", null));

    assertThat(response.verified()).isFalse();
    assertThat(response.taskVersion()).isEqualTo(5);
    assertThat(response.issues()).extracting(issue -> issue.code())
        .containsExactly("ELECTRONIC_BOM_NOT_FOUND");
    verify(priceScan, never()).scan(any(), any());
    verify(persistence, never()).persistVerifiedBom(any(), any(), any(), any(), any());
  }

  @Test
  void disabledOrUnavailableIntegrationNeverTurnsInfrastructureFailureIntoBusinessGap() {
    when(gateway.fetchCurrentBom(any())).thenReturn(ElectronicBomFetchResult.failure(
        ElectronicBomFetchResult.Status.INTEGRATION_DISABLED, "电子图库BOM查询接口尚未启用"));

    var response = service.verify(10L, new ElectronicBomVerifyRequest(3, "主制造", null));

    assertThat(response.verified()).isFalse();
    assertThat(response.status()).isEqualTo("ELECTRONIC_BOM_UNAVAILABLE");
    assertThat(response.taskVersion()).isEqualTo(3);
    assertThat(response.issues()).extracting(issue -> issue.code())
        .containsExactly("ELECTRONIC_BOM_INTEGRATION_DISABLED");
    verify(persistence, never()).persistFailure(any(), any(), any(), any(), any());
    verify(persistence, never()).persistVerifiedBom(any(), any(), any(), any(), any());
    verify(priceScan, never()).scan(any(), any());
  }

  @Test
  void successSavesFingerprintBeforeRealPriceScanAndRoutesGapsToPriceStep() {
    ElectronicBomFetchResult fetched = fetched();
    ValidatedElectronicBom bom = bom();
    when(gateway.fetchCurrentBom(any())).thenReturn(fetched);
    when(validator.validate(eq(fetched), eq("P-1"), eq("COMMERCIAL"), eq("主制造"), any()))
        .thenReturn(new ValidationResult(bom, List.of()));
    QuoteCollaborationProductTask verifiedTask = task(4, "BOM_IN_PROGRESS");
    verifiedTask.setElectronicBomFingerprint("F".repeat(64));
    when(persistence.persistVerifiedBom(eq(10L), eq(3), eq(wang), any(), eq(bom)))
        .thenReturn(new ElectronicBomVerificationPersistenceService.VerifiedResult(
            verifiedTask, "F".repeat(64), 2));
    CollaborationPriceScanResult scanResult = CollaborationPriceScanResult.gaps(1, List.of(
        new CollaborationPriceScanResult.PriceGap("C-1", "MISSING_PRICE", "MAINTAIN_PRICE",
            "当前无价格", "lp_price", null)));
    when(priceScan.scan(any(), any())).thenReturn(scanResult);
    QuoteCollaborationProductTask priceTask = task(6, "PRICE_IN_PROGRESS");
    priceTask.setElectronicBomFingerprint("F".repeat(64));
    when(persistence.persistPriceScan(eq(10L), eq(4), eq(wang), any(), eq(scanResult)))
        .thenReturn(new ElectronicBomVerificationPersistenceService.PriceScanResult(
            priceTask, 1, true));

    var response = service.verify(10L, new ElectronicBomVerifyRequest(3, "主制造", null));

    assertThat(response.verified()).isTrue();
    assertThat(response.status()).isEqualTo("VERIFIED_WITH_PRICE_GAPS");
    assertThat(response.priceGapCount()).isEqualTo(1);
    assertThat(response.taskVersion()).isEqualTo(6);
    verify(persistence).persistVerifiedBom(eq(10L), eq(3), eq(wang), any(), eq(bom));
    verify(priceScan).scan(any(), any());
    verify(persistence).persistPriceScan(eq(10L), eq(4), eq(wang), any(), eq(scanResult));
  }

  @Test
  void priceSystemFailureDoesNotCreateFakeBusinessGapOrPermitFalseCompletion() {
    ElectronicBomFetchResult fetched = fetched();
    ValidatedElectronicBom bom = bom();
    when(gateway.fetchCurrentBom(any())).thenReturn(fetched);
    when(validator.validate(any(), any(), any(), any(), any()))
        .thenReturn(new ValidationResult(bom, List.of()));
    QuoteCollaborationProductTask verifiedTask = task(4, "BOM_IN_PROGRESS");
    verifiedTask.setElectronicBomFingerprint("F".repeat(64));
    when(persistence.persistVerifiedBom(any(), any(), any(), any(), any()))
        .thenReturn(new ElectronicBomVerificationPersistenceService.VerifiedResult(
            verifiedTask, "F".repeat(64), 2));
    when(priceScan.scan(any(), any()))
        .thenReturn(CollaborationPriceScanResult.error("价格服务不可用"));

    var response = service.verify(10L, new ElectronicBomVerifyRequest(3, "主制造", null));

    assertThat(response.status()).isEqualTo("BOM_VERIFIED_PRICE_CHECK_FAILED");
    assertThat(response.priceScanStatus()).isEqualTo("ERROR");
    assertThat(response.issues()).extracting(issue -> issue.code())
        .containsExactly("PRICE_SCAN_FAILED");
    verify(persistence, never()).persistPriceScan(any(), any(), any(), any(), any());
  }

  private QuoteCollaborationProductTask task(int version, String status) {
    QuoteCollaborationProductTask value = new QuoteCollaborationProductTask();
    value.setId(10L);
    value.setProductTaskNo("PT-10");
    value.setOriginCollaborationId(1L);
    value.setProductCode("P-1");
    value.setProductName("产品");
    value.setProductSpec("S");
    value.setProductModel("M");
    value.setAccountingMonth("2026-08");
    value.setBusinessUnitType("COMMERCIAL");
    value.setApplicableOrgCode("210");
    value.setMaterialOrgCode("COMMERCIAL");
    value.setPriceOrgCode("210");
    value.setNeedBom(1);
    value.setTaskStatus(status);
    value.setTaskVersion(version);
    value.setCurrentAssigneeUserId(601L);
    value.setOriginalTechnicianUserId(601L);
    value.setSupplementVersionId(90L);
    return value;
  }

  private QuoteCollaborationQuoteLink owner() {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setId(20L);
    link.setProductTaskId(10L);
    link.setLinkType("OWNER");
    link.setOaFormId(30L);
    link.setOaFormItemId(31L);
    link.setOaNo("OA-1");
    return link;
  }

  private ElectronicBomFetchResult fetched() {
    return new ElectronicBomFetchResult(ElectronicBomFetchResult.Status.FOUND, null,
        "ELECTRONIC_DRAWING", "P-1", "COMMERCIAL", "主制造", "V1", "ACTIVE",
        LocalDate.of(2026, 1, 1), null,
        OffsetDateTime.parse("2026-08-13T10:00:00+08:00"), List.of());
  }

  private ValidatedElectronicBom bom() {
    return new ValidatedElectronicBom("ELECTRONIC_DRAWING", "P-1", "COMMERCIAL",
        "主制造", "V1", "ACTIVE", LocalDate.of(2026, 1, 1), null,
        OffsetDateTime.parse("2026-08-13T10:00:00+08:00"), List.of());
  }
}
