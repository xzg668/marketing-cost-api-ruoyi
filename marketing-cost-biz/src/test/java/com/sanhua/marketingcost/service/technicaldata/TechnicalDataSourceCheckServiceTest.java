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
  private final TechnicalDataPublicSourceCheck publicSources = new TechnicalDataPublicSourceCheck(u9, salary, codec,
      mock(com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort.class),
      mock(TechnicalDataManufacturingSourceQuery.class), packaging, netLoss, mock(TechnicalDataSharedModules.class));
  private final TechnicalDataSourceCheckService service = new TechnicalDataSourceCheckService(publicSources, prices,
      codec, mock(QuoteTechnicalDataRepository.class), mock(TechnicalDataPriceRequirements.class), mock(TechnicalDataPriceOwnership.class));
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L, 2L, "OA-1", "2026-09", "COMMERCIAL",
      "PRODUCT", "产品", "SPEC", "MODEL", "210", "211", LocalDate.of(2026,9,15), LocalDateTime.of(2026,9,15,12,0));
  private QuoteCostingWorkspace workspace;

  @BeforeEach void prepare() {
    when(packaging.check(any(), any())).thenReturn(new TechnicalDataSourceFact(TechnicalDataModuleType.PACKAGE,
        TechnicalDataAvailability.UNCONFIRMED, "PACKAGE_PENDING", "由包装专用检查验证", null, context.scanAt()));
    workspace = new QuoteCostingWorkspace(); workspace.setCurrentBomBuildBatchId("BOM-1");
    when(u9.read(any())).thenReturn(CurrentU9BomResult.available("U9", "V1", "BOM-1", 2));
    when(salary.read(anyString(), anyInt(), anyString())).thenReturn(new TechnicalDataSalarySourceQuery.Source(List.of(), null));
    when(prices.calculate(any())).thenReturn(priceResult(List.of(), "READY"));
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

  @Test void unresolvedPriceTypesWaitForConfirmationBeforeAttemptingPriceCalculation() {
    workspace.setWorkspaceStatus("WAIT_PRICE_TYPE");
    assertThat(fact(TechnicalDataModuleType.PRICE).availability()).isEqualTo(TechnicalDataAvailability.UNCONFIRMED);
    assertThat(fact(TechnicalDataModuleType.PRICE).reasonCode()).isEqualTo("PRICE_BASIS_UNCONFIRMED");
    verifyNoInteractions(prices);
    workspace.setWorkspaceStatus("RUNNING");
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
