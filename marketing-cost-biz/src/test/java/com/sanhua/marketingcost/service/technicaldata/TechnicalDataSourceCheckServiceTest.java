package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.priceprepare.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.PricePrepareService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowStage;
import com.sanhua.marketingcost.service.quotebom.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TechnicalDataSourceCheckServiceTest {
  private final CurrentU9BomGateway u9 = mock(CurrentU9BomGateway.class);
  private final TechnicalDataSalarySourceQuery salary = mock(TechnicalDataSalarySourceQuery.class);
  private final PricePrepareService prices = mock(PricePrepareService.class);
  private final TechnicalDataPackageSourceCheck packaging = mock(TechnicalDataPackageSourceCheck.class);
  private final com.sanhua.marketingcost.service.NetLossRateQuery netLoss = mock(com.sanhua.marketingcost.service.NetLossRateQuery.class);
  private final OaMessageCodec codec = new OaMessageCodec(new ObjectMapper().findAndRegisterModules());
  private final ElectronicDrawingWorkflowContextPort drawings = mock(ElectronicDrawingWorkflowContextPort.class);
  private final TechnicalDataSharedModules shared = mock(TechnicalDataSharedModules.class);
  private final ElectronicDrawingSourceNodeRepository drawingSources = mock(ElectronicDrawingSourceNodeRepository.class);
  private final TechnicalDataManufacturingSourceQuery manufacturing = mock(TechnicalDataManufacturingSourceQuery.class);
  private final com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper versions = mock(com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper.class);
  private final com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper savedDetails = mock(com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper.class);
  private final com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope scope =
      new com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomReadScope(versions, savedDetails, mock(com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.class));
  private final TechnicalDataPublicSourceCheck publicSources = new TechnicalDataPublicSourceCheck(u9, salary, codec,
      drawings, manufacturing, packaging, netLoss, shared, drawingSources, scope, mock(com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingReusedManufacturing.class));
  private final TechnicalDataSourceCheckService service = new TechnicalDataSourceCheckService(publicSources, prices,
      codec, mock(QuoteTechnicalDataRepository.class), mock(TechnicalDataPriceRequirements.class), mock(TechnicalDataPriceOwnership.class));
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L, 2L, "OA-1", "2026-09", "COMMERCIAL",
      "PRODUCT", "产品", "SPEC", "MODEL", "210", "211", LocalDate.of(2026,9,15), LocalDateTime.of(2026,9,15,12,0));
  private QuoteCostingWorkspace workspace;

  @BeforeEach void prepare() {
    when(drawingSources.findByVersionId(41L)).thenReturn(List.of(new ElectronicDrawingSourceNode()));
    when(packaging.check(any(), any())).thenReturn(new TechnicalDataSourceFact(TechnicalDataModuleType.PACKAGE,
        TechnicalDataAvailability.UNCONFIRMED, "PACKAGE_PENDING", "由包装专用检查验证", null, context.scanAt()));
    workspace = new QuoteCostingWorkspace(); workspace.setCurrentBomBuildBatchId("BOM-1");
    when(u9.read(any())).thenReturn(CurrentU9BomResult.available("U9", "V1", "BOM-1", 2));
    when(salary.read(anyString(), anyInt(), anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(List.of(), null));
    when(prices.calculate(any())).thenReturn(priceResult(List.of(), "READY"));
  }

  @Test void monthlyRepriceUsesChosenPriorDrawingAndChecksSourcesOnlyOnce() {
    var version = new QuoteBomSupplementVersion();
    version.setId(41L); version.setOaFormItemId(2L); version.setQuoteProductCode("PRODUCT");
    version.setMaterialOrgCode("211"); version.setVersionStatus("APPROVED"); version.setActiveFlag(1);
    version.setPeriodMonth("2026-08"); version.setReuseValidUntil(LocalDate.of(2027,1,31));
    version.setCompositionFingerprint("composition-41");
    when(versions.selectById(41L)).thenReturn(version);
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA-1"); form.setBusinessUnitType("COMMERCIAL");
    var item = new OaFormItem(); item.setId(2L); item.setOaFormId(1L);
    var input = new com.sanhua.marketingcost.service.reprice.MonthlyRepriceBomService.Input(form,item,
        new com.sanhua.marketingcost.service.ingest.QuoteBomContext("2026-09","PRODUCT",
            new com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey("CUSTOMER",
                com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER,null),
            "",context.organization()));
    var bom = new QuoteBomStatus(); bom.setSyncRecordId(50L); bom.setCostPeriodMonth("2026-09");
    bom.setBomSource("ELECTRONIC_DRAWING_BOM"); bom.setSyncBatchId("SUPPLEMENT_VERSION:41");
    var calculation = new com.sanhua.marketingcost.service.reprice.MonthlyRepriceCalculation(
        "MRP1130-test", "a".repeat(64),input,bom,context.scanAt());
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("no U9"));
    when(manufacturing.inspect(any())).thenReturn(new TechnicalDataManufacturingSourceQuery.Assessment(
        41L,"2026-08","verified",List.of(),null));
    var parent=new QuoteBomSupplementDetail(); parent.setMaterialCode("MAKE");parent.setShapeAttr("制造件");parent.setLevel(1);parent.setPath("/PRODUCT/MAKE/");
    var child=new QuoteBomSupplementDetail(); child.setMaterialCode("RAW");child.setParentCode("MAKE");child.setLevel(2);child.setPath("/PRODUCT/MAKE/RAW/");
    when(savedDetails.selectList(any())).thenReturn(List.of(parent,child));
    scope.use(calculation, () -> {
      for(int i=0;i<3;i++) {
        var facts=publicSources.checkDataSources(context);
        assertThat(facts).filteredOn(row -> row.moduleType()==TechnicalDataModuleType.MANUFACTURING)
            .allSatisfy(row -> assertThat(row.availability()).as(row.reason()).isEqualTo(TechnicalDataAvailability.AVAILABLE));
      }
      return null;
    });
    verifyNoInteractions(u9,drawings);
    verify(versions,times(1)).selectById(41L);
    verifyNoInteractions(manufacturing,drawingSources);
    verify(savedDetails,times(1)).selectList(any());
    when(savedDetails.selectList(any())).thenReturn(List.of(parent));
    scope.use(calculation, () -> {
      assertThat(publicSources.checkDataSources(context)).filteredOn(row -> row.moduleType()==TechnicalDataModuleType.MANUFACTURING)
          .allSatisfy(row -> assertThat(row.availability()).isEqualTo(TechnicalDataAvailability.MISSING));
      return null;
    });
  }

  @Test void noOriginalBomRequiresProductDrawingAuxiliaryAndSolderEvenWhenReferenceDataExists() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    when(salary.read(anyString(), anyInt(), anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(
        List.of(wage(1,"SALARY_DIRECT","2.5"),wage(2,"SALARY_INDIRECT","0.08")),1L));
    var requirements = new TechnicalDataModuleRequirementEvaluator().evaluate(service.check(context, workspace).facts());
    assertThat(requirements).filteredOn(TechnicalDataModuleRequirement::required)
        .extracting(TechnicalDataModuleRequirement::moduleType).containsExactly("PROFILE","DRAWING_BOM","AUXILIARY","SOLDER");
    assertThat(requirements).filteredOn(row -> row.moduleType().equals("PACKAGE")).extracting(TechnicalDataModuleRequirement::availability)
        .containsExactly(TechnicalDataAvailability.UNCONFIRMED);
  }

  @Test void u9FailuresAndEmptyResponsesNeverCreateMissingBomTasks() {
    for (var result : new CurrentU9BomResult[]{CurrentU9BomResult.timeout("超时"),CurrentU9BomResult.organizationMismatch("组织不同"),null}) {
      when(u9.read(any())).thenReturn(result);
      assertThat(service.check(context,workspace).facts()).filteredOn(row -> row.moduleType()==TechnicalDataModuleType.PROFILE)
          .extracting(TechnicalDataSourceFact::availability).containsExactly(TechnicalDataAvailability.ERROR);
    }
  }

  @Test void composedDrawingWithoutU9IsAvailableAndDoesNotHideOtherSupplementGaps() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    for (String stage : List.of(ElectronicDrawingWorkflowStage.COMPOSED, ElectronicDrawingWorkflowStage.PUBLISHED)) {
      when(drawings.load(2L, "COMMERCIAL", "210", "2026-09")).thenReturn(drawing(stage, 41L, "composition-41"));
      var requirements = new TechnicalDataModuleRequirementEvaluator().evaluate(service.check(context, workspace).facts());
      assertThat(requirements).filteredOn(row -> row.moduleType().equals("DRAWING_BOM"))
          .allSatisfy(row -> {
            assertThat(row.required()).isFalse();
            assertThat(row.availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
            assertThat(row.sourceReference()).contains("41", "composition-41");
          });
      assertThat(requirements).filteredOn(TechnicalDataModuleRequirement::required)
          .extracting(TechnicalDataModuleRequirement::moduleType).contains("PROFILE", "AUXILIARY", "SOLDER");
    }
  }

  @Test void receivedDrawingExistsBeforeMatchingOrCompositionAndManufacturingGapsRemainRequired() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    when(manufacturing.inspect(any())).thenReturn(new TechnicalDataManufacturingSourceQuery.Assessment(
        41L, "2026-09", "raw-gap", List.of(new TechnicalDataManufacturingSourceQuery.Node(
            221L, "G1:R4", "G1:R2", "接管", "T-JG-0478", "TP2 Y2 Φ6×1.0", BigDecimal.ONE,
            null, null, "9990000051108", TechnicalDataManufacturingSourceQuery.State.MISSING_RAW,
            "缺少原材料关系", List.of())), null));
    workspace.setCurrentBomBuildBatchId(null);
    for (String stage : List.of(ElectronicDrawingWorkflowStage.RECEIVED, ElectronicDrawingWorkflowStage.MAPPING_PENDING,
        ElectronicDrawingWorkflowStage.VALIDATION_FAILED, ElectronicDrawingWorkflowStage.COMPOSED)) {
      when(drawings.load(2L, "COMMERCIAL", "210", "2026-09")).thenReturn(drawing(stage, 41L, null));
      var requirements = new TechnicalDataModuleRequirementEvaluator().evaluate(service.check(context, workspace).facts());
      assertThat(requirements).filteredOn(row -> row.moduleType().equals("DRAWING_BOM"))
          .allSatisfy(row -> {
            assertThat(row.required()).isFalse();
            assertThat(row.availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
            assertThat(row.reasonCode()).isEqualTo("DRAWING_SOURCE_AVAILABLE");
          });
      assertThat(requirements).filteredOn(row -> row.moduleType().equals("MANUFACTURING"))
          .allSatisfy(row -> {
            assertThat(row.required()).isTrue();
            assertThat(row.reasonCode()).isEqualTo("MANUFACTURING_RAW_MISSING");
          });
      assertThat(requirements).filteredOn(row -> row.moduleType().equals("PRICE"))
          .extracting(TechnicalDataModuleRequirement::reasonCode).containsExactly("PRICE_BOM_NOT_READY");
    }
    verifyNoInteractions(prices);
  }

  @Test void publicationAdapterReadyStatusIsAvailableWithPersistedPublishedStage() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    var published = new ElectronicDrawingWorkContext(2L, 1, 3L, 41L, 1L, 2L, "TASK", "OA-1", "PRODUCT", null,
        "产品", "SPEC", "MODEL", null, "FULL_BOM", "2026-09", "210", "211", "COMMERCIAL", "210",
        true, true, "READY_FOR_COSTING", "PUBLISHED", null, null, "composition-41");
    when(drawings.load(2L, "COMMERCIAL", "210", "2026-09")).thenReturn(published);
    assertThat(published.published()).isTrue();
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);

    // Having source details does not require the adapter to declare a published, calculable BOM.
    when(drawings.load(2L, "COMMERCIAL", "210", "2026-09")).thenReturn(drawing("PUBLISHED", 41L, "old-fingerprint"));
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
  }

  @Test void missingDrawingStillRequiresSupplementAndLookupFailureIsAnError() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    when(drawings.load(2L, "COMMERCIAL", "210", "2026-09")).thenReturn(drawing(null, null, null));
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    when(drawings.load(2L, "COMMERCIAL", "210", "2026-09")).thenThrow(new IllegalStateException("database failure"));
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }

  @Test void emptyDrawingVersionCannotClaimTheDetailsExistEvenWithAnOldCompositionFingerprint() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    when(drawings.load(2L, "COMMERCIAL", "210", "2026-09"))
        .thenReturn(drawing(ElectronicDrawingWorkflowStage.COMPOSED, 41L, "old-fingerprint"));
    when(drawingSources.findByVersionId(41L)).thenReturn(List.of());
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).reason()).contains("没有明细");
    when(drawingSources.findByVersionId(41L)).thenThrow(new IllegalStateException("source lookup failure"));
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }

  @Test void readyDrawingDoesNotBypassExistingTechnicalAssignmentApproval() {
    when(u9.read(any())).thenReturn(CurrentU9BomResult.notFound("无原始 BOM"));
    when(shared.find("PRODUCT", 2L, "DRAWING_BOM"))
        .thenReturn(new TechnicalDataSharedModuleRepository.Owner(8L, 7L, 6L, 2L, "PRODUCT", "DRAWING_BOM",
            "IN_PROGRESS", "READY", 9L, "技术员", 10L, "DRAFT", "COMMERCIAL", "210", "2026-09"));
    when(drawings.load(2L, "COMMERCIAL", "210", "2026-09"))
        .thenReturn(drawing(ElectronicDrawingWorkflowStage.COMPOSED, 41L, "composition-41"));
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
  }

  @Test void publicU9RemainsFirstAndDrawingCannotMaskAnUnknownU9Result() {
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    when(u9.read(any())).thenReturn(CurrentU9BomResult.timeout("U9 查询超时"));
    assertThat(fact(TechnicalDataModuleType.DRAWING_BOM).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
    verifyNoInteractions(drawings);
  }

  private ElectronicDrawingWorkContext drawing(String stage, Long sourceId, String fingerprint) {
    return new ElectronicDrawingWorkContext(2L, 1, 3L, sourceId, 1L, 2L, "TASK", "OA-1", "PRODUCT", null,
        "产品", "SPEC", "MODEL", null, "FULL_BOM", "2026-09", "210", "211", "COMMERCIAL", "210",
        true, true, "NEED_TECH", stage, null, null, fingerprint);
  }

  @Test void confirmedSingleWageIncludingZeroIsUsableWithoutInventingSecondWage() {
    for (String type : List.of("SALARY_DIRECT","SALARY_INDIRECT")) {
      when(salary.read(anyString(),anyInt(),anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(List.of(wage(1,type,"0")),5L));
      assertThat(fact(TechnicalDataModuleType.SALARY).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    }
    verify(salary, times(2)).read("PRODUCT",2026,"COMMERCIAL");
  }

  @Test void absentOrSingleUnconfirmedWageDoesNotBecomeAMissingTask() {
    assertThat(fact(TechnicalDataModuleType.SALARY).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
    when(salary.read(anyString(),anyInt(),anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(List.of(wage(1,"SALARY_DIRECT","2.5")),null));
    assertThat(fact(TechnicalDataModuleType.SALARY).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
  }

  @Test void bothWagesConfirmedAbsentProduceOnlySalaryGapWhenU9Exists() {
    when(salary.read(anyString(),anyInt(),anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(List.of(),7L));
    assertThat(service.check(context,workspace).facts()).filteredOn(row -> row.availability()==TechnicalDataAvailability.MISSING)
        .extracting(TechnicalDataSourceFact::moduleType).containsExactly(TechnicalDataModuleType.SALARY);
  }

  @Test void duplicateWageTypeAndInvalidMoneyRemainErrors() {
    for (var values : List.of(List.of(wage(1,"SALARY_DIRECT","-1")),List.of(wage(1,"SALARY_DIRECT","1"),wage(2,"SALARY_DIRECT","2")))) {
      when(salary.read(anyString(),anyInt(),anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(values,7L));
      assertThat(fact(TechnicalDataModuleType.SALARY).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
    }
  }

  @Test void existingCandidatePriceIsAvailableAndUsesExactProductMonthScope() {
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    var request = org.mockito.ArgumentCaptor.forClass(PricePrepareGenerateRequest.class);
    verify(prices).calculate(request.capture());
    assertThat(request.getValue().getOaFormItemId()).isEqualTo(2L);
    assertThat(request.getValue().getPeriodMonth()).isEqualTo("2026-09");
    assertThat(request.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
  }

  @Test void genuineMissingPriceDiffersFromConflictOrCalculationFailure() {
    PricePrepareGap gap = new PricePrepareGap(); gap.setGapType("MISSING_PRICE"); gap.setGapMaterialCode("X");
    when(prices.calculate(any())).thenReturn(priceResult(List.of(gap),"MISSING_PRICE"));
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    gap.setReasonCode("PRICE_SOURCE_CONFLICT");
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
    when(prices.calculate(any())).thenReturn(priceResult(List.of(gap),"FAILED"));
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }

  @Test void unknownMaterialBasisDoesNotRunOrPretendPriceLookup() {
    workspace.setCurrentBomBuildBatchId(null);
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
    verifyNoInteractions(prices);
  }

  @Test void confirmedMissingPriceSurvivesOtherMaterialsStructureAndSupplierFailures() {
    var missing = new PricePrepareGap(); missing.setGapMaterialCode("301021669");
    missing.setGapType("MISSING_PRICE"); missing.setReasonCode("MISSING");
    var weight = new PricePrepareGap(); weight.setGapMaterialCode("301240292");
    weight.setGapType("MISSING_STRUCTURE"); weight.setMessage("缺制造件净重");
    var supplier = new PricePrepareGap(); supplier.setGapMaterialCode("301200309");
    supplier.setGapType("PRICE_SOURCE_ERROR"); supplier.setReasonCode("RAW_PRICE_ERROR");
    supplier.setMessage("有效报价对应供应商已明确退出供货");
    when(prices.calculate(any())).thenReturn(priceResult(List.of(weight,supplier,missing),"MISSING_PRICE"));
    var price = fact(TechnicalDataModuleType.PRICE);
    assertThat(price.availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    assertThat(price.reason()).contains("1 项");
    assertThat(new TechnicalDataModuleRequirementEvaluator().evaluate(List.of(price)))
        .filteredOn(TechnicalDataModuleRequirement::required).extracting(TechnicalDataModuleRequirement::moduleType)
        .containsExactly("PRICE");
    when(prices.calculate(any())).thenReturn(priceResult(List.of(weight,supplier),"MISSING_PRICE"));
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"WAITING","INVALID_INPUT","INVALID_SOURCE","SOURCE_CONFLICT"})
  void aPriceLookupBlockDoesNotBecomeTechnicalMissingPrice(String reason) {
    var blocked=new PricePrepareGap();blocked.setGapMaterialCode("SUPPLIER");
    blocked.setGapType("MISSING_PRICE");blocked.setReasonCode(reason);
    when(prices.calculate(any())).thenReturn(priceResult(List.of(blocked),"MISSING_PRICE"));
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
  }

  @Test void originalTechnicalBlockEscapesJoinedTransactionInsteadOfBecomingUnexpectedRollback() throws Exception {
    var failure = new com.sanhua.marketingcost.service.EffectiveTechnicalDataException(
        "TECH_DATA_TASK_NOT_SUBMITTED", 2L, "2026-09", List.of("SOLDER"), "原补录尚未全部提交：焊料");
    when(prices.calculate(any())).thenThrow(failure);
    var connection = mock(java.sql.Connection.class);
    when(connection.getAutoCommit()).thenReturn(true);
    var dataSource = mock(javax.sql.DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
    var attributes = new org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource();
    attributes.addTransactionalMethod("calculate", new org.springframework.transaction.interceptor.DefaultTransactionAttribute());
    var factory = new org.springframework.aop.framework.ProxyFactory(prices);
    factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager, attributes));
    var checked = new TechnicalDataSourceCheckService(publicSources, (PricePrepareService) factory.getProxy(),
        codec, mock(QuoteTechnicalDataRepository.class), mock(TechnicalDataPriceRequirements.class), mock(TechnicalDataPriceOwnership.class));

    assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(manager)
        .execute(status -> checked.check(context, workspace))).isSameAs(failure);
    verify(connection).rollback();
    verify(connection, never()).commit();
  }

  @Test void historicalTypeGapStillChecksPricesWithDefaultPriority() {
    workspace.setWorkspaceStatus("WAIT_PRICE_TYPE");
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    verify(prices).calculate(any());
  }

  @Test void netLossPublicPositiveAndMissingAreIndependentOfBomWhileInvalidRatesRemainErrors() {
    var positive = new com.sanhua.marketingcost.service.NetLossRateQuery.Source("AVAILABLE", "NET_LOSS_SOURCE_AVAILABLE", "已有公共费率",
        1L, "PRODUCT", "产品", "MODEL", "BARE", "211", 2026, "COMMERCIAL", 2L, new BigDecimal("0.003"));
    when(netLoss.lookup("PRODUCT", "MODEL", "211", 2026, "COMMERCIAL")).thenReturn(positive);
    assertThat(fact(TechnicalDataModuleType.NET_LOSS).availability()).isEqualTo(TechnicalDataAvailability.AVAILABLE);
    var zero = new com.sanhua.marketingcost.service.NetLossRateQuery.Source("ERROR", "NET_LOSS_RATE_INVALID", "公共费率必须大于0",
        1L, "PRODUCT", "产品", "MODEL", "BARE", "211", 2026, "COMMERCIAL", 2L, BigDecimal.ZERO);
    when(netLoss.lookup("PRODUCT", "MODEL", "211", 2026, "COMMERCIAL")).thenReturn(zero);
    assertThat(fact(TechnicalDataModuleType.NET_LOSS).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
    var missing = new com.sanhua.marketingcost.service.NetLossRateQuery.Source("MISSING", "NET_LOSS_BARE_MISSING", "没有裸品",
        1L, "PRODUCT", "产品", "MODEL", null, "211", 2026, "COMMERCIAL", null, null);
    when(netLoss.lookup("PRODUCT", "MODEL", "211", 2026, "COMMERCIAL")).thenReturn(missing);
    assertThat(fact(TechnicalDataModuleType.NET_LOSS).availability()).isEqualTo(TechnicalDataAvailability.MISSING);
    when(netLoss.lookup("PRODUCT", "MODEL", "211", 2026, "COMMERCIAL")).thenThrow(new IllegalStateException("SQL unavailable"));
    assertThat(fact(TechnicalDataModuleType.NET_LOSS).availability()).isEqualTo(TechnicalDataAvailability.ERROR);
  }

  private TechnicalDataSourceFact fact(TechnicalDataModuleType type) {
    return service.check(context, workspace).facts().stream().filter(row -> row.moduleType()==type).findFirst().orElseThrow();
  }
  private CmsCostSourceEffective wage(long id,String type,String amount) {
    var row=new CmsCostSourceEffective();row.setId(id);row.setSourceType(type);row.setAmountYuan(new BigDecimal(amount));row.setPeriod("2026-01");return row;
  }
  private PricePrepareCalculationResult priceResult(List<PricePrepareGap> gaps,String status) {
    var result=new PricePrepareCalculationResult();var summary=new PricePrepareGenerateResult();summary.setStatus(gaps.isEmpty()?"SUCCESS":"PARTIAL");result.setSummary(summary);
    var item=new PricePrepareItem();item.setMaterialCode("X");item.setUnitPrice(new BigDecimal("12"));item.setPriceSource("FIXED");item.setStatus(status);
    result.setItems(List.of(item));result.setGaps(gaps);return result;
  }
}
