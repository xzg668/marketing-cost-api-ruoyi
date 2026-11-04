package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.MakePartMaterialPriceResolveResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TechnicalDataPriceRequirementsTest {
  private final PricePrepareService preparation = mock(PricePrepareService.class);
  private final MakePartMaterialPriceResolveService prices = mock(MakePartMaterialPriceResolveService.class);
  private final MaterialMasterRawMapper materials = mock(MaterialMasterRawMapper.class);
  private final QuoteTechnicalDataRepository repository = mock(QuoteTechnicalDataRepository.class);
  private final TechnicalDataTaskRepository tasks = mock(TechnicalDataTaskRepository.class);
  private final TechnicalDataVersionContentCodec content = mock(TechnicalDataVersionContentCodec.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TechnicalDataPriceOwnership ownership = mock(TechnicalDataPriceOwnership.class);
  private final TechnicalDataPriceRequirements service = new TechnicalDataPriceRequirements(preparation, prices, materials,
      repository, tasks, content, new OaMessageCodec(new ObjectMapper().findAndRegisterModules()), jdbc, ownership);
  private final QuoteTechTask task = new QuoteTechTask();
  private final QuoteTechProduct product = new QuoteTechProduct();
  private final QuoteTechDataVersion draft = new QuoteTechDataVersion();

  @BeforeEach void setup() {
    task.setId(1L); task.setApplicableOrgCode("210"); task.setBusinessUnitType("COMMERCIAL"); task.setOaNo("OA");
    product.setId(2L); product.setAccountingMonth("2026-09"); draft.setId(3L); draft.setProductId(2L);
    when(repository.findVersion(3L)).thenReturn(Optional.of(draft));
    when(materials.selectByLatestBatchAndCodes(anyList(),isNull(),eq("COMMERCIAL"))).thenAnswer(call -> {
      var master = new MaterialMasterRaw(); master.setMaterialCode(((List<String>)call.getArgument(0)).getFirst());
      master.setMaterialName("料件"); master.setUnit("只"); return List.of(master);
    });
    when(prices.calculateMaterialUnitPrice(anyString(),eq("2026-09"),any(),any(),eq("OA"),eq("COMMERCIAL"),any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
        .thenAnswer(call -> MakePartMaterialPriceResolveResult.miss(call.getArgument(0),"MISSING_PRICE","缺价",null));
  }

  @Test void aggregatesRawScrapSolderAndPackageButNeverAuxiliary() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("MANUFACTURING"),module("SOLDER"),module("PACKAGE"),module("AUXILIARY")));
    var evidence = new ManufacturingNodeEvidence("制造件",null,BigDecimal.ONE,null,null,"原料",null,"MATCHED",
        List.of(new ManufacturingScrap("SCRAP","废料","只")));
    when(content.manufacturing(draft)).thenReturn(new Manufacturing(List.of(new RawMaterial("raw","parent",null,"MAKE","RAW",null,
        null,BigDecimal.ONE,"只",null,null,null,evidence)),null));
    when(content.solder(draft)).thenReturn(solder("RAW","SOLDER"));
    var packaging = new QuoteTechPackageItem(); packaging.setComponentMaterialNo("PACK");
    when(repository.findPackageItems(3L)).thenReturn(List.of(packaging));
    var result = service.checkModuleReferences(task,product);
    assertThat(result.issues()).isEmpty();
    assertThat(result.items()).extracting(TechnicalDataPriceRequirement::materialNo).containsExactly("PACK","RAW","SCRAP","SOLDER");
    assertThat(result.items().get(1).roles()).containsExactly("RAW","SOLDER");
    verifyNoInteractions(preparation);
    verify(repository,never()).findAuxItems(anyLong());
  }

  @Test void deletingLastReferenceRemovesDemandAndChangesFingerprint() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("SOLDER")));
    when(content.solder(draft)).thenReturn(solder("SOLDER"));
    var first=service.checkModuleReferences(task,product);
    when(content.solder(draft)).thenReturn(solder());
    var second=service.checkModuleReferences(task,product);
    assertThat(second.items()).isEmpty(); assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
  }

  @Test void anotherProductsSavedPriceIsSharedWithoutBecomingAnEffectivePrice() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("SOLDER")));
    when(content.solder(draft)).thenReturn(solder("PART"));
    var owner = new com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceOwner(
        "PART", 41L, 42L, 43L, "王工", "READY", "PENDING", 44L, "210", "COMMERCIAL", "只", "CNY");
    when(ownership.find("PART")).thenReturn(owner);
    var original = new QuoteTechDataVersion(); original.setId(44L); original.setProductId(42L); original.setVersionStatus("DRAFT");
    when(repository.findVersion(44L)).thenReturn(Optional.of(original));
    when(content.prices(original)).thenReturn(new Prices(List.of(new PriceItem("original", "PART", "210", "只", "CNY",
        "FIXED", new BigDecimal("12.5"), null, null, null, null, null, null))));
    var result = service.checkModuleReferences(task, product);
    assertThat(result.issues()).isEmpty();
    assertThat(result.items()).singleElement().satisfies(row -> {
      assertThat(row.status()).isEqualTo("SHARED");
      assertThat(row.requiresEntry()).isFalse();
      assertThat(row.unitPrice()).isNull();
      assertThat(row.message()).contains("已有资料", "43");
    });
    when(prices.calculateMaterialUnitPrice(anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MakePartMaterialPriceResolveResult.ok("PART", "FIXED", new BigDecimal("12.5"), "已确认补录价", null));
    assertThat(service.checkModuleReferences(task, product).items()).singleElement()
        .satisfies(row -> assertThat(row.status()).isEqualTo("AVAILABLE"));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void onlyOriginalOwnedPriceRemainsEditableAfterMonthlyPriceResolvesTheGap(boolean owned) {
    product.setLatestSubmittedVersionId(3L);
    var module = module("PRICE"); module.setId(4L); module.setModuleStatus("RETURNED");
    when(tasks.findModules(2L)).thenReturn(List.of(module));
    String key = new OaMessageCodec(new ObjectMapper()).canonicalHash(List.of("PART", "210", "COMMERCIAL", "只", "CNY"));
    when(content.prices(draft)).thenReturn(new Prices(List.of(new PriceItem(key, "PART", "210", "只", "CNY",
        "FIXED", new BigDecimal("13.75"), null, null, null, null, null, null))));
    when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("PART"), eq(4L), eq("210"), eq("COMMERCIAL"), eq("只"), eq("CNY")))
        .thenReturn(owned ? 1 : 0);
    when(prices.calculateMaterialUnitPrice(anyString(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MakePartMaterialPriceResolveResult.ok("PART", "MONTHLY_PRICE", new BigDecimal("13.75"), "已采用", null));
    var rows = service.check(task, product).items();
    if (owned) assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row.status()).isEqualTo("REVISION"); assertThat(row.requiresEntry()).isTrue();
      assertThat(row.unitPrice()).isEqualByComparingTo("13.75");
      assertThat(row.message()).contains("月价保持不变");
    });
    else assertThat(rows).isEmpty();
    assertThat(service.checkModuleReferences(task, product).items()).isEmpty();
    verify(preparation, never()).generate(any());
  }

  @Test void packagePriceCheckPreservesQuoteItemAndPriceOrganizationAcrossBusinessUnits() {
    task.setApplicableOrgCode("220");
    product.setOaFormItemId(42L);
    when(tasks.findModules(2L)).thenReturn(List.of(module("PACKAGE")));
    var packaging = new QuoteTechPackageItem(); packaging.setComponentMaterialNo("PACK");
    when(repository.findPackageItems(3L)).thenReturn(List.of(packaging));
    var master = new MaterialMasterRaw(); master.setMaterialCode("PACK"); master.setUnit("只");
    when(materials.selectByLatestBatchAndCodes(List.of("PACK"), null, "PLATE")).thenReturn(List.of(master));
    when(prices.calculateMaterialUnitPrice(eq("PACK"), eq("2026-09"), any(), any(), eq("OA"), eq("COMMERCIAL"),
        argThat(scope -> scope != null && Long.valueOf(42L).equals(scope.supplierReviews().oaFormItemId())
            && "220".equals(scope.supplierReviews().priceOrgCode())), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
        .thenReturn(MakePartMaterialPriceResolveResult.ok("PACK", "固定价", BigDecimal.TEN, "有效固定价", null));
    var result = service.checkModuleReferences(task, product);
    assertThat(result.issues()).isEmpty();
    assertThat(result.items()).singleElement().satisfies(item -> {
      assertThat(item.status()).isEqualTo("AVAILABLE");
      assertThat(item.organizationCode()).isEqualTo("220");
      assertThat(item.unitPrice()).isEqualByComparingTo(BigDecimal.TEN);
    });
  }

  @Test void sourceChangesAvailabilityWithoutCreatingAnotherPriceItem() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("SOLDER")));
    when(content.solder(draft)).thenReturn(solder("SOLDER"));
    var missing=service.checkModuleReferences(task,product);
    when(prices.calculateMaterialUnitPrice(anyString(),any(),any(),any(),any(),any(),any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
        .thenReturn(MakePartMaterialPriceResolveResult.ok("SOLDER","FIXED",BigDecimal.TEN,"公共固定价",null));
    var available=service.checkModuleReferences(task,product);
    assertThat(available.items().getFirst().itemKey()).isEqualTo(missing.items().getFirst().itemKey());
    assertThat(available.items().getFirst().status()).isEqualTo("AVAILABLE");
    assertThat(available.fingerprint()).isNotEqualTo(missing.fingerprint());
  }

  @Test void invalidPriceAndQueryFailureAreErrorsRatherThanMissing() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("SOLDER")));
    when(content.solder(draft)).thenReturn(solder("SOLDER"));
    for (var price : List.of(BigDecimal.ZERO,BigDecimal.ONE.negate())) {
      when(prices.calculateMaterialUnitPrice(anyString(),any(),any(),any(),any(),any(),any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class)))
          .thenReturn(MakePartMaterialPriceResolveResult.ok("SOLDER","FIXED",price,"价格异常",null));
      var result=service.checkModuleReferences(task,product);
      assertThat(result.items().getFirst().status()).isEqualTo("ERROR"); assertThat(result.issues()).isNotEmpty();
    }
    when(prices.calculateMaterialUnitPrice(anyString(),any(),any(),any(),any(),any(),any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class))).thenThrow(new IllegalStateException("查询失败"));
    assertThat(service.checkModuleReferences(task,product).items().getFirst().status()).isEqualTo("ERROR");
  }

  @Test void unitAmbiguityDoesNotInventApplicablePriceUnit() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("SOLDER")));
    when(content.solder(draft)).thenReturn(solder("SOLDER"));
    when(materials.selectByLatestBatchAndCodes(anyList(),isNull(),anyString())).thenReturn(List.of());
    assertThat(service.checkModuleReferences(task,product).issues()).anyMatch(value -> value.contains("采购单位"));
    verifyNoInteractions(prices);
  }

  @Test void failedResolutionWithAnAmountCannotClearThePriceGap() {
    when(tasks.findModules(2L)).thenReturn(List.of(module("SOLDER")));
    when(content.solder(draft)).thenReturn(solder("SOLDER"));
    var failed = MakePartMaterialPriceResolveResult.miss("SOLDER","ERROR","来源校验失败",null);
    failed.setUnitPrice(BigDecimal.TEN);
    when(prices.calculateMaterialUnitPrice(anyString(),any(),any(),any(),any(),any(),any(), org.mockito.ArgumentMatchers.any(com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService.Purpose.class))).thenReturn(failed);
    var result = service.checkModuleReferences(task,product);
    assertThat(result.items().getFirst().status()).isEqualTo("ERROR");
    assertThat(result.issues()).contains("SOLDER：来源校验失败");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"PENDING", "WAIT_IMPORT"})
  void supplierConfirmationDoesNotBecomeATechnicianPriceDemand(String reviewStatus) {
    var prepared = bomGaps();
    var scope = new com.sanhua.marketingcost.service.pricing.SupplierPriceScope(
        "COMMERCIAL", "OA", null, "2026-09", java.time.LocalDate.of(2026, 9, 1), "210", "GLASS", "FIXED");
    prepared.setSupplierPriceReviews(List.of(new com.sanhua.marketingcost.service.pricing.SupplierPriceReview(
        "scope", "fingerprint", scope, List.of(), reviewStatus, null, null, "待确认")));
    var result = service.check(task, product);
    assertThat(result.issues()).isEmpty();
    assertThat(result.items()).extracting(TechnicalDataPriceRequirement::materialNo).containsExactly("MISSING");
    verify(prices, never()).calculateMaterialUnitPrice(eq("GLASS"), any(), any(), any(), any(), any(), any(), any());
    assertThat(prepared.getGaps()).hasSize(2);
  }

  @Test void unexplainedWaitingGapStillBlocksDispatch() {
    bomGaps();
    when(prices.calculateMaterialUnitPrice(eq("GLASS"), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(MakePartMaterialPriceResolveResult.miss("GLASS", "ERROR", "来源状态异常", null));
    assertThat(service.check(task, product).issues()).contains("GLASS：来源状态异常");
  }

  private com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult bomGaps() {
    product.setMaterialNo("TOP");
    when(jdbc.queryForList(anyString(), nullable(Long.class), eq("2026-09")))
        .thenReturn(List.of(Map.of("current_bom_build_batch_id", "build")));
    var prepared = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareCalculationResult();
    var summary = new com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult();
    summary.setStatus("PARTIAL"); prepared.setSummary(summary);
    var missing = new PricePrepareGap(); missing.setGapMaterialCode("MISSING");
    missing.setGapType("MISSING_PRICE"); missing.setReasonCode("MISSING");
    var waiting = new PricePrepareGap(); waiting.setGapMaterialCode("GLASS");
    waiting.setGapType("MISSING_PRICE"); waiting.setReasonCode("WAITING"); waiting.setPriceType("FIXED");
    prepared.setGaps(List.of(missing, waiting));
    when(preparation.calculate(any())).thenReturn(prepared);
    return prepared;
  }

  private QuoteTechModule module(String type) {
    var value=new QuoteTechModule(); value.setModuleType(type); value.setCurrentVersionId(3L); return value;
  }
  private Solder solder(String... codes) {
    return new Solder(Arrays.stream(codes).map(code -> new SolderItem(code,code,null,BigDecimal.ONE,"只",null,null,null)).toList(),"MANUAL",null);
  }
}
