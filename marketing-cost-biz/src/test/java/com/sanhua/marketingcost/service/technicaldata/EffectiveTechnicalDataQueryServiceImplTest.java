package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechAuxItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechPackageItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSalaryItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EffectiveTechnicalDataQueryServiceImplTest {
  private static final Long ITEM_ID = 1053100052030L;
  private static final String MONTH = "2026-08";

  private QuoteTechProductMapper productMapper;
  private QuoteTechTaskMapper taskMapper;
  private QuoteTechModuleMapper moduleMapper;
  private QuoteTechDataVersionMapper versionMapper;
  private QuoteTechPackageItemMapper packageMapper;
  private QuoteTechAuxItemMapper auxMapper;
  private QuoteTechSalaryItemMapper salaryMapper;
  private TechnicalDataVersionContentCodec contentCodec;
  private EffectiveTechnicalDataQueryServiceImpl service;
  private TechnicalDataCostingSources costingSources;
  private TechnicalPriceCostingSources priceSources;

  private QuoteTechProduct product;
  private QuoteTechDataVersion v2;
  private List<QuoteTechModule> modules;
  private List<QuoteTechPackageItem> packages;
  private List<QuoteTechAuxItem> auxiliaries;
  private List<QuoteTechSalaryItem> salaries;

  @BeforeEach
  void setUp() {
    productMapper = mock(QuoteTechProductMapper.class);
    taskMapper = mock(QuoteTechTaskMapper.class);
    moduleMapper = mock(QuoteTechModuleMapper.class);
    versionMapper = mock(QuoteTechDataVersionMapper.class);
    packageMapper = mock(QuoteTechPackageItemMapper.class);
    auxMapper = mock(QuoteTechAuxItemMapper.class);
    salaryMapper = mock(QuoteTechSalaryItemMapper.class);
    contentCodec = mock(TechnicalDataVersionContentCodec.class);
    costingSources = mock(TechnicalDataCostingSources.class);
    priceSources = mock(TechnicalPriceCostingSources.class);
    when(costingSources.select(any(), any())).thenReturn(selection(List.of()));
    service = new EffectiveTechnicalDataQueryServiceImpl(
        productMapper,
        taskMapper,
        moduleMapper,
        versionMapper,
        packageMapper,
        auxMapper,
        salaryMapper,
        contentCodec,
        new com.sanhua.marketingcost.integration.oa.OaMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()),
        mock(TechnicalDataAuxiliaryClassificationService.class), costingSources, priceSources);
    product = product();
    v2 = version();
    modules = modules();
    packages = List.of(packageItem());
    auxiliaries = List.of(auxItem());
    salaries = List.of(salaryItem());
    stubValidV2();
  }

  @Test
  void selectsOnlyEffectiveV2ForExactProductAndMonth() {
    EffectiveTechnicalDataInput input = service.resolve(ITEM_ID, MONTH);

    assertThat(input.versionId()).isEqualTo(202L);
    assertThat(input.versionNo()).isEqualTo(2);
    assertThat(input.accountingMonth()).isEqualTo(MONTH);
    assertThat(input.salaryTotalAmount()).isEqualByComparingTo("18.90");
    assertThat(input.auxiliaryTotalAmount()).isEqualByComparingTo("0.704");
    assertThat(input.sourceType())
        .isEqualTo(EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION);
    assertThat(input.packageItems()).singleElement()
        .extracting(EffectiveTechnicalDataInput.PackageLine::id).isEqualTo(301L);
    assertThat(input.auxiliaryItems()).singleElement()
        .extracting(EffectiveTechnicalDataInput.AuxiliaryLine::id).isEqualTo(302L);
    assertThat(input.salaryItems()).singleElement()
        .extracting(EffectiveTechnicalDataInput.SalaryLine::id).isEqualTo(303L);
    verify(productMapper).selectActiveCandidatesByItemAndMonth(ITEM_ID, MONTH);
    verify(versionMapper).selectById(202L);
    verify(versionMapper, never()).selectById(201L);
  }

  @Test
  void noTechnicalProductFallsBackWithoutReadingAnyDraftOrCmsReplacement() {
    when(productMapper.selectActiveCandidatesByItemAndMonth(ITEM_ID, MONTH)).thenReturn(List.of());

    assertThat(service.resolve(ITEM_ID, MONTH)).isNull();

    verify(moduleMapper, never()).selectByProductId(101L);
    verify(versionMapper, never()).selectById(202L);
  }

  @Test
  void requiredModulesWithoutEffectiveVersionFailExplicitly() {
    product.setEffectiveVersionId(null);

    assertError("TECH_DATA_EFFECTIVE_VERSION_MISSING", "PACKAGE", () ->
        service.resolve(ITEM_ID, MONTH));
    verify(versionMapper, never()).selectById(201L);
  }

  @Test
  void danglingPointerFailsInsteadOfReadingLatestSubmittedVersion() {
    when(versionMapper.selectById(202L)).thenReturn(null);

    assertError("TECH_DATA_EFFECTIVE_POINTER_DANGLING", null, () ->
        service.resolve(ITEM_ID, MONTH));
  }

  @Test
  void submittedOrOtherProductVersionCannotBeUsedThroughEffectivePointer() {
    v2.setVersionStatus(QuoteTechDataVersion.STATUS_SUBMITTED);
    assertError("TECH_DATA_EFFECTIVE_VERSION_INVALID", null, () ->
        service.resolve(ITEM_ID, MONTH));

    v2.setVersionStatus(QuoteTechDataVersion.STATUS_APPROVED);
    v2.setProductId(999L);
    assertError("TECH_DATA_EFFECTIVE_VERSION_INVALID", null, () ->
        service.resolve(ITEM_ID, MONTH));
  }

  @Test
  void missingModuleDuplicateProductAndDamagedContentAllFailDeterministically() {
    when(moduleMapper.selectByProductId(101L)).thenReturn(modules.subList(0, 3));
    assertError("TECH_DATA_MODULE_MISSING", "SALARY", () ->
        service.resolve(ITEM_ID, MONTH));

    when(moduleMapper.selectByProductId(101L)).thenReturn(modules);
    when(productMapper.selectActiveCandidatesByItemAndMonth(ITEM_ID, MONTH))
        .thenReturn(List.of(product, product()));
    assertError("TECH_DATA_DUPLICATE_ACTIVE_PRODUCT", null, () ->
        service.resolve(ITEM_ID, MONTH));

    when(productMapper.selectActiveCandidatesByItemAndMonth(ITEM_ID, MONTH))
        .thenReturn(List.of(product));
    when(contentCodec.fingerprint(v2, List.of(), packages, auxiliaries, salaries))
        .thenReturn("corrupted");
    assertError("TECH_DATA_CONTENT_FINGERPRINT_MISMATCH", null, () ->
        service.resolve(ITEM_ID, MONTH));
  }

  @Test
  void missingRequiredDetailAndMismatchedTotalFailBeforeCosting() {
    when(salaryMapper.selectByVersionId(202L)).thenReturn(List.of());
    assertError("TECH_DATA_DETAIL_MISSING", "SALARY", () ->
        service.resolve(ITEM_ID, MONTH));

    when(salaryMapper.selectByVersionId(202L)).thenReturn(salaries);
    v2.setAuxiliaryTotalAmount(new BigDecimal("0.703"));
    assertError("TECH_DATA_TOTAL_MISMATCH", "AUXILIARY", () ->
        service.resolve(ITEM_ID, MONTH));
  }

  @Test
  void currentMonthIsPartOfSelectionKeyAndNeverSubstitutedByOtherMonth() {
    when(productMapper.selectActiveCandidatesByItemAndMonth(ITEM_ID, "2026-09"))
        .thenReturn(List.of());

    assertThat(service.resolve(ITEM_ID, "2026-09")).isNull();

    verify(productMapper).selectActiveCandidatesByItemAndMonth(ITEM_ID, "2026-09");
    verify(versionMapper, never()).selectById(201L);
  }

  @Test
  void currentPublicSourcesReplaceAnUnfinishedOldTask() {
    product.setContentSchemaVersion(2);
    product.setEffectiveVersionId(null);
    product.setProductStatus("DRAFT");
    assertThat(service.resolve(ITEM_ID, MONTH)).isNull();
    verify(taskMapper, never()).selectById(any());
    verify(moduleMapper, never()).selectByProductId(any());
    when(costingSources.select(ITEM_ID, MONTH)).thenReturn(selection(List.of(
        new TechnicalDataCostingSources.Issue("NET_LOSS", "TECH_DATA_SOURCE_NOT_CONFIRMED", "公共费率异常"))));
    assertError("TECH_DATA_SOURCE_NOT_CONFIRMED", "NET_LOSS", () -> service.resolve(ITEM_ID, MONTH));
  }

  @Test
  void nineModuleVersionRequiresConfirmedCurrentSources() {
    product.setContentSchemaVersion(2);
    v2.setContentSchemaVersion(2);
    List<QuoteTechModule> nineModules = TechnicalDataModuleType.orderedCodes().stream()
        .map(type -> {
          QuoteTechModule value = module(type, true);
          value.setSourceAvailability("MISSING");
          return value;
        }).toList();
    when(moduleMapper.selectByProductId(101L)).thenReturn(nineModules);
    when(contentCodec.schemaVersion(v2)).thenReturn(2);

    when(costingSources.select(ITEM_ID, MONTH)).thenReturn(selection(List.of(
        new TechnicalDataCostingSources.Issue("SOLDER", "TECH_DATA_SOURCE_NOT_CONFIRMED", "焊料来源未确认"))));
    assertError("TECH_DATA_SOURCE_NOT_CONFIRMED", "SOLDER", () -> service.resolve(ITEM_ID, MONTH));
  }

  @Test
  void priceOnlyResultKeepsItsOriginalApprovedSourceAcrossQuotes() {
    product.setContentSchemaVersion(2);
    product.setEffectiveVersionId(null);
    var original = new TechnicalDataCostingSources.Source("PRICE", new QuoteTechTask(), product, v2, 201L);
    var evidence = new EffectiveTechnicalDataInput.PriceSource("SHARED", "FIXED", 99L,
        product.getId(), v2.getId(), 201L, v2.getContentFingerprint());
    when(priceSources.select(any())).thenReturn(List.of(new TechnicalPriceCostingSources.Selected(evidence, original)));
    var input = service.resolve(ITEM_ID, MONTH);
    assertThat(input.versionId()).isEqualTo(v2.getId());
    assertThat(input.priceSources()).containsExactly(evidence);
    assertThat(input.materialItems()).isEmpty();
    assertThat(input.moduleSources()).extracting(EffectiveTechnicalDataInput.ModuleSource::moduleType).containsExactly("PRICE");
    when(productMapper.selectActiveCandidatesByItemAndMonth(ITEM_ID, MONTH)).thenReturn(List.of());
    assertThat(service.resolve(ITEM_ID, MONTH).priceSources()).containsExactly(evidence);
    when(priceSources.select(any())).thenThrow(new EffectiveTechnicalDataException(
        "TECH_DATA_FINANCE_CONFIRMATION_REQUIRED", ITEM_ID, MONTH, List.of("PRICE"), "原任务尚未确认"));
    assertError("TECH_DATA_FINANCE_CONFIRMATION_REQUIRED", "PRICE", () -> service.resolve(ITEM_ID, MONTH));
  }

  private TechnicalDataCostingSources.Selection selection(List<TechnicalDataCostingSources.Issue> issues) {
    return new TechnicalDataCostingSources.Selection(new com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext(
        1L, ITEM_ID, "OA", MONTH, "COMMERCIAL", "PRODUCT", null, null, null, "210", "COMMERCIAL",
        java.time.LocalDate.now(), java.time.LocalDateTime.now()), java.util.Map.of(), List.of(), issues);
  }

  private void stubValidV2() {
    when(productMapper.selectActiveCandidatesByItemAndMonth(ITEM_ID, MONTH))
        .thenReturn(List.of(product));
    when(moduleMapper.selectByProductId(101L)).thenReturn(modules);
    QuoteTechTask task = new QuoteTechTask();
    task.setId(11L);
    task.setTaskStatus("APPROVED");
    task.setReviewStatus("PASSED");
    when(taskMapper.selectById(11L)).thenReturn(task);
    when(versionMapper.selectById(202L)).thenReturn(v2);
    when(packageMapper.selectByVersionId(202L)).thenReturn(packages);
    when(auxMapper.selectByVersionId(202L)).thenReturn(auxiliaries);
    when(salaryMapper.selectByVersionId(202L)).thenReturn(salaries);
    when(contentCodec.readReferenceSnapshot("{\"schemaVersion\":1,\"modules\":[]}"))
        .thenReturn(List.of());
    when(contentCodec.fingerprint(v2, List.of(), packages, auxiliaries, salaries))
        .thenReturn("v2-fingerprint");
  }

  private QuoteTechProduct product() {
    QuoteTechProduct value = new QuoteTechProduct();
    value.setId(101L);
    value.setTaskId(11L);
    value.setOaFormItemId(ITEM_ID);
    value.setAccountingMonth(MONTH);
    value.setProductStatus("APPROVED");
    value.setCurrentEditVersionId(203L);
    value.setLatestSubmittedVersionId(203L);
    value.setEffectiveVersionId(202L);
    value.setActiveFlag(1);
    return value;
  }

  private QuoteTechDataVersion version() {
    QuoteTechDataVersion value = new QuoteTechDataVersion();
    value.setId(202L);
    value.setProductId(101L);
    value.setVersionNo(2);
    value.setVersionStatus(QuoteTechDataVersion.STATUS_APPROVED);
    value.setPackageTotalAmount(new BigDecimal("3.50"));
    value.setAuxiliaryTotalAmount(new BigDecimal("0.704"));
    value.setSalaryTotalAmount(new BigDecimal("18.90"));
    value.setContentFingerprint("v2-fingerprint");
    value.setReferenceSnapshotJson("{\"schemaVersion\":1,\"modules\":[]}");
    return value;
  }

  private List<QuoteTechModule> modules() {
    return List.of(
        module("PROFILE", true),
        module("PACKAGE", true),
        module("AUXILIARY", true),
        module("SALARY", true));
  }

  private QuoteTechModule module(String type, boolean required) {
    QuoteTechModule value = new QuoteTechModule();
    value.setProductId(101L);
    value.setModuleType(type);
    value.setRequiredFlag(required ? 1 : 0);
    value.setModuleStatus("APPROVED");
    return value;
  }

  private QuoteTechPackageItem packageItem() {
    QuoteTechPackageItem value = new QuoteTechPackageItem();
    value.setId(301L);
    value.setVersionId(202L);
    value.setLineNo(1);
    value.setComponentMaterialNo("PKG-BOX-041");
    value.setComponentName("外包装箱");
    value.setStandardQuantity(BigDecimal.ONE);
    value.setStandardUnit("只");
    value.setReferenceUnitPrice(new BigDecimal("3.50"));
    value.setAmount(new BigDecimal("3.50"));
    return value;
  }

  private QuoteTechAuxItem auxItem() {
    QuoteTechAuxItem value = new QuoteTechAuxItem();
    value.setId(302L);
    value.setVersionId(202L);
    value.setLineNo(1);
    value.setSubjectCode("0201");
    value.setSubjectName("辅助焊料类");
    value.setAuxiliaryName("银基焊环");
    value.setStandardQuantity(new BigDecimal("0.00022"));
    value.setStandardUnit("KG");
    value.setReferenceUnitPrice(new BigDecimal("3200"));
    value.setLossRate(BigDecimal.ZERO);
    value.setAmount(new BigDecimal("0.704"));
    return value;
  }

  private QuoteTechSalaryItem salaryItem() {
    QuoteTechSalaryItem value = new QuoteTechSalaryItem();
    value.setId(303L);
    value.setVersionId(202L);
    value.setLineNo(1);
    value.setProcessCode("OP-1");
    value.setProcessName("装配");
    value.setLaborType("DIRECT");
    value.setStandardHours(new BigDecimal("0.42"));
    value.setHourlyRate(new BigDecimal("45"));
    value.setPersonCoefficient(BigDecimal.ONE);
    value.setAmount(new BigDecimal("18.90"));
    return value;
  }

  private void assertError(String code, String module, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(EffectiveTechnicalDataException.class, error -> {
          assertThat(error.errorCode()).isEqualTo(code);
          assertThat(error.oaFormItemId()).isEqualTo(ITEM_ID);
          assertThat(error.accountingMonth()).isEqualTo(MONTH);
          if (module != null) assertThat(error.modules()).contains(module);
        });
  }
}
