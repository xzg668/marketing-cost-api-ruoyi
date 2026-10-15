package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioResolveResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioResolveService;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.PriceMonthSelection;
import com.sanhua.marketingcost.service.pricing.SupplierPreferredPriceSelector;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class LinkedPriceEnsureServiceImplTest {
  private PriceLinkedCalcItemMapper calcItemMapper;
  private PriceLinkedItemMapper linkedItemMapper;
  private BomCostingRowMapper bomCostingRowMapper;
  private OaFormMapper oaFormMapper;
  private PriceLinkedCalcServiceImpl calcService;
  private SupplierSupplyRatioResolveService supplyRatioResolveService;
  private LinkedPriceEnsureServiceImpl service;
  private MaterialPriceMonthSelector monthSelector;
  private String availableMonth;
  private OriginalLinkedPriceRuntime originalRuntime;
  private FinanceEffectivePriceService effectivePrices;
  private FinanceEffectivePriceSelectionService effectiveSelection;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedCalcItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
  }

  @BeforeEach
  void setUp() {
    calcItemMapper = Mockito.mock(PriceLinkedCalcItemMapper.class);
    when(calcItemMapper.update(any(), any())).thenReturn(1);
    when(calcItemMapper.insertResults(any()))
        .thenAnswer(call -> ((List<?>) call.getArgument(0)).size());
    when(calcItemMapper.selectReusedResults(any())).thenAnswer(call -> call.getArgument(0));
    linkedItemMapper = Mockito.mock(PriceLinkedItemMapper.class);
    bomCostingRowMapper = Mockito.mock(BomCostingRowMapper.class);
    oaFormMapper = Mockito.mock(OaFormMapper.class);
    calcService = Mockito.mock(PriceLinkedCalcServiceImpl.class);
    supplyRatioResolveService = Mockito.mock(SupplierSupplyRatioResolveService.class);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(SupplierSupplyRatioResolveResult.miss("未命中供货比例"));
    availableMonth = "2026-05";
    monthSelector = Mockito.mock(MaterialPriceMonthSelector.class);
    when(monthSelector.select(any(), any(), any()))
        .thenAnswer(
            invocation -> new PriceMonthSelection(invocation.getArgument(2), availableMonth));
    originalRuntime = Mockito.mock(OriginalLinkedPriceRuntime.class);
    effectivePrices = Mockito.mock(FinanceEffectivePriceService.class);
    effectiveSelection = Mockito.mock(FinanceEffectivePriceSelectionService.class);
    service =
        new LinkedPriceEnsureServiceImpl(
            calcItemMapper,
            linkedItemMapper,
            bomCostingRowMapper,
            oaFormMapper,
            calcService,
            new SupplierPreferredPriceSelector(supplyRatioResolveService),
            monthSelector,
            originalRuntime,
            effectivePrices,
            effectiveSelection,
            org.mockito.Mockito.mock(
                com.sanhua.marketingcost.service.pricing.SupplierPriceSelectionService.class));
  }

  @Test
  void nativeReuseTracksSavedPriceVersionWithoutExecutingPublicFormula() {
    var source = linkedItem("MAT-1");
    source.setId(101L);
    source.setOrgCode("210");
    source.setSupplierCode("S1");
    source.setUnit("只");
    source.setSourceInputSnapshotJson("{}");
    source.setRuleRevision(1);
    source.setExpressionVersion(OriginalLinkedRuleSnapshot.VERSION);
    var existing = new PriceLinkedCalcItem();
    existing.setId(201L);
    existing.setItemCode("MAT-1");
    var stored = new java.util.ArrayList<PriceLinkedCalcItem>();
    stored.add(existing);
    when(calcItemMapper.selectList(any())).thenAnswer(call -> List.copyOf(stored));
    when(calcItemMapper.insertOrReuseResults(any())).thenAnswer(call -> {
      for (var row : (List<PriceLinkedCalcItem>) call.getArgument(0)) {
        row.setId(200L + stored.size());
        stored.add(row);
      }
      return ((List<?>) call.getArgument(0)).size();
    });

    Mockito.doAnswer(
            call -> {
              PriceLinkedCalcItem row = call.getArgument(0);
              row.setId(200L + stored.size());
              stored.add(row);
              return 1;
            })
        .when(calcItemMapper)
        .insert(any(PriceLinkedCalcItem.class));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(source));
    var effective = new FinanceEffectivePrice();
    effective.setOriginalId(101L);
    effective.setInputFingerprint("INPUT-A");
    var financial =
        Mockito.mock(
            PriceFactorContext.class,
            Mockito.withSettings().mockMaker(org.mockito.MockMakers.INLINE));
    when(originalRuntime.context(any(), any(), any(), any())).thenReturn(financial);
    when(effectivePrices.read(any(), any(), any())).thenReturn(Map.of(101L, effective));
    when(effectivePrices.require(any(FinanceEffectivePriceService.SourceRule.class), any()))
        .thenReturn(effective);
    when(effectiveSelection.select(any(), any(), any(), any()))
        .thenReturn(
            new FinanceEffectivePriceSelectionService.Selection(
                effective,
                new SupplierPriceSelectionService.Selection(
                    new SupplierPriceCandidate("INPUT-A", "S1", "S1", BigDecimal.TEN, null, null),
                    null,
                    "SINGLE",
                    "单一供应商",
                    null)));
    var response = new com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse();
    response.setResult(BigDecimal.TEN);
    when(originalRuntime.previewSaved(any(), any(), any(), any(), any(), any()))
        .thenReturn(response);
    Mockito.doAnswer(
            call -> {
              PriceLinkedCalcItem row = call.getArgument(0);
              row.setCalcStatus("OK");
              row.setPartUnitPrice(BigDecimal.TEN);
              return row;
            })
        .when(calcService)
        .applyEffectiveQuotePrice(any(), any(), any());
    var request =
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1"));
    var firstResult = service.ensure(request);
    assertThat(firstResult.getCreatedCount()).isOne();
    var firstVersion = firstResult.getItems().get("MAT-1");
    String first = firstVersion.getCalcFingerprint();
    assertThat(service.ensure(request).getSkippedCount()).isOne();
    effective.setInputFingerprint("INPUT-B");
    var revised = service.ensure(request);
    assertThat(revised.getCreatedCount()).isOne();
    assertThat(revised.getItems().get("MAT-1").getCalcFingerprint()).isNotEqualTo(first);
    assertThat(firstVersion.getCalcFingerprint()).isEqualTo(first);
    effective.setInputFingerprint("INPUT-A");
    assertThat(service.ensure(request).getItems().get("MAT-1")).isSameAs(firstVersion);
    verify(calcItemMapper, never()).update(any(), any());
    verify(calcService, Mockito.times(2)).applyEffectiveQuotePrice(any(), any(), any());
    verify(calcService, never()).calculateQuoteItemForEnsure(any(), any(), any());
  }

  @Test
  void legacySlotStillRejectsConcurrentRevisionChanges() {
    var existing = new PriceLinkedCalcItem();
    existing.setId(201L);
    existing.setItemCode("MAT-1");
    existing.setResultRevision(4L);
    when(calcItemMapper.selectList(any())).thenReturn(List.of(existing));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(calcItemMapper.update(any(), any())).thenReturn(0);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                service.ensure(
                    LinkedPriceEnsureRequest.quote(
                        "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1"))))
        .isInstanceOf(org.springframework.dao.ConcurrencyFailureException.class)
        .hasMessageContaining("旧结果不写回");
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> guard = ArgumentCaptor.forClass(Wrapper.class);
    verify(calcItemMapper).update(any(), guard.capture());
    assertThat(guard.getValue().getSqlSegment()).contains("calc_fingerprint");
  }

  @Test
  void ensureReturnsZeroWhenItemCodesEmpty() {
    var result =
        service.ensure(LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of()));

    assertThat(result.getRequestedCount()).isZero();
    assertThat(result.getCreatedCount()).isZero();
    assertThat(result.getFailedCount()).isZero();
    verifyNoInteractions(
        calcItemMapper, linkedItemMapper, bomCostingRowMapper, oaFormMapper, calcService);
  }

  @Test
  void legacyResultWithoutCompleteFingerprintMustBeRecalculated() {
    PriceLinkedCalcItem existing = new PriceLinkedCalcItem();
    existing.setOaNo("OA-001");
    existing.setItemCode("MAT-1");
    existing.setCalcScene(LinkedPriceCalcScene.QUOTE.getCode());
    existing.setPricingMonth("2026-05");
    existing.setBusinessUnitType("COMMERCIAL");
    existing.setPartUnitPrice(new BigDecimal("12.34"));
    existing.setCalcStatus("OK");
    existing.setSourcePriceRecordId(101L);
    when(calcItemMapper.selectList(any())).thenReturn(List.of(existing));
    PriceLinkedItem source = linkedItem("MAT-1");
    source.setId(101L);
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(source));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of());
    when(oaFormMapper.selectOne(any())).thenReturn(null);

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result.getSkippedCount()).isZero();
    assertThat(result.getCreatedCount()).isZero();
    assertThat(result.getUpdatedCount()).isOne();
    verify(calcService).calculateQuoteItemForEnsure(any(), any(), any());
    verify(calcItemMapper, never()).insert(any(PriceLinkedCalcItem.class));
    verify(calcItemMapper).update(any(PriceLinkedCalcItem.class), any());
  }

  @Test
  void ensureCreatesMissingResult() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem linkedItem = linkedItem("MAT-1");
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    OaForm oaForm = new OaForm();
    oaForm.setOaNo("OA-001");
    when(oaFormMapper.selectOne(any())).thenReturn(oaForm);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setPartAmount(new BigDecimal("25.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result.getCreatedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedCalcItem> captor = ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(captor.capture());
    PriceLinkedCalcItem saved = captor.getValue();
    assertThat(saved.getCalcScene()).isEqualTo("QUOTE");
    assertThat(saved.getFactorSource()).isEqualTo("OA_LOCKED");
    assertThat(saved.getPricingMonth()).isEqualTo("2026-05");
    assertThat(saved.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(saved.getBomQty()).isEqualByComparingTo("2.5");
  }

  @Test
  void ensureLocksConcurrentMaterialRowsInCanonicalCodeOrder() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectPricingCandidates(any()))
        .thenReturn(List.of(linkedItem("MAT-B"), linkedItem("MAT-A")));
    when(bomCostingRowMapper.selectList(any()))
        .thenReturn(List.of(bomRow("MAT-B"), bomRow("MAT-A")));
    when(oaFormMapper.selectOne(any())).thenReturn(new OaForm());
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(BigDecimal.ONE);
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    service.ensure(
        LinkedPriceEnsureRequest.quote(
            "OA-001", "COMMERCIAL", "2026-05", new LinkedHashSet<>(List.of("MAT-B", "MAT-A"))));

    ArgumentCaptor<List<PriceLinkedCalcItem>> captor = ArgumentCaptor.forClass(List.class);
    verify(calcItemMapper).insertResults(captor.capture());
    assertThat(captor.getValue())
        .extracting(PriceLinkedCalcItem::getItemCode)
        .containsExactly("MAT-A", "MAT-B");
  }

  @Test
  void quoteBatchesAreBoundedAndIncompleteWritesFail() {
    var codes = new java.util.TreeSet<String>();
    for (int i = 0; i < 205; i++) codes.add(String.format("MAT-%03d", i));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(call -> call.getArgument(0));
    var request = LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", codes);
    assertThat(service.ensure(request).getCreatedCount()).isEqualTo(205);
    ArgumentCaptor<List<PriceLinkedCalcItem>> batches = ArgumentCaptor.forClass(List.class);
    verify(calcItemMapper, Mockito.times(3)).insertResults(batches.capture());
    assertThat(batches.getAllValues()).extracting(List::size).containsExactly(100, 100, 5);
    Mockito.doReturn(99).when(calcItemMapper).insertResults(any());
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.ensure(request))
        .hasMessageContaining("未完整写入");
  }

  @Test
  void mixedNewAndExistingResultsKeepCanonicalWriteOrder() {
    var existing = new PriceLinkedCalcItem();
    existing.setId(201L);
    existing.setItemCode("MAT-C");
    when(calcItemMapper.selectList(any())).thenReturn(List.of(existing));
    var order = Mockito.inOrder(calcItemMapper);
    service.ensure(
        LinkedPriceEnsureRequest.quote(
            "OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-D", "MAT-C", "MAT-B", "MAT-A")));
    order
        .verify(calcItemMapper)
        .insertResults(
            Mockito.argThat(
                rows ->
                    rows.stream()
                        .map(PriceLinkedCalcItem::getItemCode)
                        .toList()
                        .equals(List.of("MAT-A", "MAT-B"))));
    order
        .verify(calcItemMapper)
        .update(Mockito.argThat(row -> row.getId().equals(existing.getId())), any());
    order
        .verify(calcItemMapper)
        .insert(Mockito.argThat((PriceLinkedCalcItem row) -> row.getItemCode().equals("MAT-D")));
  }

  @Test
  void ensureMarksFormulaFactorCarryForwardAsFinanceWarning() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(new OaForm());
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              calcItem.setTraceJson(
                  "{\"variableDetails\":[{\"source\":\"FINANCE_FACTOR_CARRIED_FORWARD\"}]}");
              return calcItem;
            });

    service.ensure(
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<PriceLinkedCalcItem> captor = ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(captor.capture());
    assertThat(captor.getValue().getCarriedForward()).isEqualTo(1);
    assertThat(captor.getValue().getWarningMessage()).contains("沿用历史月份", "财务关注");
  }

  @Test
  void calculateReturnsTransientResultWithoutCalcItemWrites() {
    PriceLinkedItem linkedItem = linkedItem("MAT-1");
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setPartAmount(new BigDecimal("25.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    List<PriceLinkedCalcItem> result =
        service.calculate(
            LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result)
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.getPartUnitPrice()).isEqualByComparingTo("10.000000");
              assertThat(item.getCalcStatus()).isEqualTo("OK");
              assertThat(item.getId()).isNull();
            });
    verify(calcItemMapper, never()).selectList(any());
    verify(calcItemMapper, never()).insert(any(PriceLinkedCalcItem.class));
    verify(calcItemMapper, never()).update(any(PriceLinkedCalcItem.class), any());
  }

  @Test
  void ensureQuoteSelectsImportTimeThenIdFromUnorderedRows() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    var older = linkedItem("MAT-1");
    older.setId(9L);
    older.setCreatedAt(LocalDateTime.of(2026, 5, 1, 0, 0));
    var latest = linkedItem("MAT-1");
    latest.setId(3L);
    latest.setCreatedAt(LocalDateTime.of(2026, 5, 2, 0, 0));
    var sameTime = linkedItem("MAT-1");
    sameTime.setId(2L);
    sameTime.setCreatedAt(latest.getCreatedAt());
    when(linkedItemMapper.selectPricingCandidates(any()))
        .thenReturn(List.of(older, latest, sameTime));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    service.ensure(
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-1")));

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectPricingCandidates(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("pricing_month <=", "source_kind", "business_unit_type")
        .doesNotContain("effective_from", "effective_to", "updated_at");
    verify(calcService).calculateQuoteItemForEnsure(any(), Mockito.same(latest), any());
  }

  @Test
  void ensureQuoteUsesLatestPriceMonthInsteadOfQuotaOrHistoricalImportTime() {
    availableMonth = "2026-06";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem currentLowQuota = linkedItem("MAT-1");
    currentLowQuota.setPricingMonth("2026-06");
    currentLowQuota.setSupplierCode("S1");
    currentLowQuota.setQuota(new BigDecimal("0.20"));
    currentLowQuota.setCreatedAt(LocalDateTime.of(2026, 6, 1, 8, 0));
    PriceLinkedItem priorHighQuota = linkedItem("MAT-1");
    priorHighQuota.setPricingMonth("2026-05");
    priorHighQuota.setSupplierCode("S2");
    priorHighQuota.setQuota(new BigDecimal("0.90"));
    // 历史价格月即使更晚补导，也不能压过更大的 pricing_month。
    priorHighQuota.setCreatedAt(LocalDateTime.of(2026, 6, 20, 8, 0));
    when(linkedItemMapper.selectPricingCandidates(any()))
        .thenReturn(List.of(currentLowQuota, priorHighQuota));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("S1", "供应商1", "0.80"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    service.ensure(
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectPricingCandidates(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("pricing_month <=")
        .doesNotContain("effective_from", "effective_to", "updated_at")
        .doesNotContain("quota DESC");

    ArgumentCaptor<PriceLinkedCalcItem> calcItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService)
        .calculateQuoteItemForEnsure(calcItemCaptor.capture(), linkedItemCaptor.capture(), any());
    assertThat(calcItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-06");
    assertThat(linkedItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-06");
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S1");
  }

  @Test
  void ensureQuoteSameMonthPrefersSupplierSupplyRatioMainSupplier() {
    availableMonth = "2026-06";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem highQuota = linkedItem("MAT-1");
    highQuota.setPricingMonth("2026-06");
    highQuota.setSupplierCode("S2");
    highQuota.setSupplierName("供应商2");
    highQuota.setQuota(new BigDecimal("0.70"));
    PriceLinkedItem lowQuota = linkedItem("MAT-1");
    lowQuota.setPricingMonth("2026-06");
    lowQuota.setSupplierCode("S1");
    lowQuota.setSupplierName("供应商1");
    lowQuota.setQuota(new BigDecimal("0.30"));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(highQuota, lowQuota));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("S1", "供应商1", "0.80"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    service.ensure(
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectPricingCandidates(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .contains("pricing_month <=", "source_kind", "business_unit_type")
        .doesNotContain("effective_from", "effective_to", "updated_at");

    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), linkedItemCaptor.capture(), any());
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S1");
  }

  @Test
  void ensureQuoteSingleSupplierPriceDoesNotQuerySupplyRatio() {
    availableMonth = "2026-06";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem onlySupplier = linkedItem("721250208");
    onlySupplier.setPricingMonth("2026-06");
    onlySupplier.setSupplierCode("S000219");
    onlySupplier.setSupplierName("丽水市丽凯制冷配件有限公司");
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(onlySupplier));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("721250208")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("0.636038"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote(
                "FI-SC-006-20260108-109", "COMMERCIAL", "2026-08", Set.of("721250208")));

    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), linkedItemCaptor.capture(), any());
    assertThat(linkedItemCaptor.getValue().getSupplierCode()).isEqualTo("S000219");
    org.mockito.Mockito.verifyNoInteractions(supplyRatioResolveService);
  }

  @Test
  void ensureQuoteIgnoresHistoricalMonthSupplierWhenLatestMonthHasOneSupplier() {
    availableMonth = "2026-07";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem latest = linkedItem("301990317");
    latest.setPricingMonth("2026-07");
    latest.setSupplierName("三花股份(江西)自控元器件有限公司");
    latest.setManualPrice(new BigDecimal("76.8584"));
    latest.setCreatedAt(LocalDateTime.of(2026, 7, 13, 17, 50));
    PriceLinkedItem history = linkedItem("301990317");
    history.setPricingMonth("2026-06");
    history.setSupplierName("联动表无自行添加");
    history.setManualPrice(new BigDecimal("78.1327"));
    history.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(latest, history));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("301990317")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              PriceLinkedItem selected = invocation.getArgument(1);
              calcItem.setPartUnitPrice(selected.getManualPrice());
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote(
                "FI-SC-006-20260108-109", "COMMERCIAL", "2026-08", Set.of("301990317")));

    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedItem> selected = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), selected.capture(), any());
    assertThat(selected.getValue()).isSameAs(latest);
    assertThat(selected.getValue().getManualPrice()).isEqualByComparingTo("76.8584");
    org.mockito.Mockito.verifyNoInteractions(supplyRatioResolveService);
  }

  @Test
  void ensureQuoteKeepsOtherSupplierAndUsesEachSuppliersLatestImport() {
    availableMonth = "2026-08";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem supplierA = linkedItem("MAT-1");
    supplierA.setId(81L);
    supplierA.setPricingMonth("2026-08");
    supplierA.setSupplierCode("SUP-A");
    supplierA.setSupplierName("供应商A");
    supplierA.setFormulaExpr("A1");
    supplierA.setCreatedAt(LocalDateTime.of(2026, 8, 5, 9, 0));
    PriceLinkedItem supplierBOld = linkedItem("MAT-1");
    supplierBOld.setId(82L);
    supplierBOld.setPricingMonth("2026-08");
    supplierBOld.setSupplierCode("SUP-B");
    supplierBOld.setSupplierName("供应商B");
    supplierBOld.setFormulaExpr("B1");
    supplierBOld.setCreatedAt(LocalDateTime.of(2026, 8, 5, 9, 0));
    PriceLinkedItem supplierBNew = linkedItem("MAT-1");
    supplierBNew.setId(83L);
    supplierBNew.setPricingMonth("2026-08");
    supplierBNew.setSupplierCode("SUP-B");
    supplierBNew.setSupplierName("供应商B");
    supplierBNew.setFormulaExpr("B2");
    supplierBNew.setCreatedAt(LocalDateTime.of(2026, 8, 15, 10, 0));
    when(linkedItemMapper.selectPricingCandidates(any()))
        .thenReturn(List.of(supplierBOld, supplierA, supplierBNew));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("SUP-B", "供应商B", "0.70"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(BigDecimal.TEN);
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-08", Set.of("MAT-1")));

    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedItem> selected = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService).calculateQuoteItemForEnsure(any(), selected.capture(), any());
    assertThat(selected.getValue()).isSameAs(supplierBNew);
    assertThat(selected.getValue().getFormulaExpr()).isEqualTo("B2");
  }

  @Test
  void ensureQuoteBlocksWhenMainSupplierHasNoLinkedFormula() {
    availableMonth = "2026-06";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem firstByDefault = linkedItem("MAT-1");
    firstByDefault.setPricingMonth("2026-06");
    firstByDefault.setSupplierCode("S2");
    firstByDefault.setSupplierName("供应商2");
    PriceLinkedItem secondByDefault = linkedItem("MAT-1");
    secondByDefault.setPricingMonth("2026-06");
    secondByDefault.setSupplierCode("S1");
    secondByDefault.setSupplierName("供应商1");
    when(linkedItemMapper.selectPricingCandidates(any()))
        .thenReturn(List.of(firstByDefault, secondByDefault));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(supplyRatioResolveService.resolve(any(), any(), any(), any(), any()))
        .thenReturn(mainSupplier("S9", "供应商9", "0.90"));
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getFailedItems().get(0).getReasonCode())
        .isEqualTo(SupplierPreferredPriceSelector.PRIMARY_SUPPLIER_PRICE_MISSING);
    assertThat(result.getFailedItems().get(0).getReason()).contains("主供应商无价格");
    verify(calcService, never()).calculateQuoteItemForEnsure(any(), any(), any());
  }

  @Test
  void ensureQuoteUsesPriorFormulaWhenCurrentMonthMissingAndKeepsQuoteMonth() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem priorFormula = linkedItem("MAT-1");
    priorFormula.setPricingMonth("2026-05");
    priorFormula.setSupplierCode("S2");
    priorFormula.setQuota(new BigDecimal("0.90"));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(priorFormula));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    service.ensure(
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1")));

    ArgumentCaptor<PriceLinkedCalcItem> calcItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(calcService)
        .calculateQuoteItemForEnsure(calcItemCaptor.capture(), linkedItemCaptor.capture(), any());
    assertThat(calcItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-06");
    assertThat(linkedItemCaptor.getValue().getPricingMonth()).isEqualTo("2026-05");
  }

  @Test
  void ensureQuoteIgnoresFormulaEffectiveDatesAndUsesLatestImportForSupplier() {
    availableMonth = "2026-06";
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    PriceLinkedItem oldImport = linkedItem("MAT-1");
    oldImport.setPricingMonth("2026-06");
    oldImport.setEffectiveFrom(LocalDate.of(2026, 6, 1));
    oldImport.setEffectiveTo(LocalDate.of(2026, 6, 15));
    oldImport.setSupplierCode("S1");
    oldImport.setFormulaExpr("OLD");
    oldImport.setId(61L);
    oldImport.setCreatedAt(LocalDateTime.of(2026, 6, 5, 8, 0));
    PriceLinkedItem newImport = linkedItem("MAT-1");
    newImport.setPricingMonth("2026-06");
    // 即使人工生效日期晚于取价日，正式表中更晚导入的版本仍然是当前版本。
    newImport.setEffectiveFrom(LocalDate.of(2026, 7, 1));
    newImport.setEffectiveTo(null);
    newImport.setSupplierCode("S1");
    newImport.setFormulaExpr("NEW");
    newImport.setId(62L);
    newImport.setCreatedAt(LocalDateTime.of(2026, 6, 15, 8, 0));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(oldImport, newImport));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-1")));
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("10.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-06", Set.of("MAT-1"));
    request.setPriceAsOfTime(LocalDateTime.of(2026, 6, 20, 12, 0));

    service.ensure(request);

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectPricingCandidates(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .doesNotContain("effective_from", "effective_to", "updated_at");

    ArgumentCaptor<PriceLinkedItem> linkedItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedItem.class);
    ArgumentCaptor<PriceLinkedCalcItem> calcItemCaptor =
        ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcService)
        .calculateQuoteItemForEnsure(calcItemCaptor.capture(), linkedItemCaptor.capture(), any());
    assertThat(calcItemCaptor.getValue().getPriceAsOfTime())
        .isEqualTo(LocalDateTime.of(2026, 6, 20, 12, 0));
    assertThat(linkedItemCaptor.getValue()).isSameAs(newImport);
    assertThat(linkedItemCaptor.getValue().getFormulaExpr()).isEqualTo("NEW");
    assertThat(calcItemCaptor.getValue().getSourceEffectiveFrom()).isNull();
    assertThat(calcItemCaptor.getValue().getSourceEffectiveTo()).isNull();
    assertThat(calcItemCaptor.getValue().getCarriedForward()).isZero();
  }

  @Test
  void ensureCreatesMonthlyAdjustResultWithoutOaContext() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(calcService.calculateMonthlyAdjustItemForEnsure(any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("11.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.monthlyAdjust("COMMERCIAL", "2026-05", Set.of("MAT-1")));

    assertThat(result.getCreatedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isZero();
    ArgumentCaptor<PriceLinkedCalcItem> captor = ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(captor.capture());
    PriceLinkedCalcItem saved = captor.getValue();
    assertThat(saved.getOaNo()).isNull();
    assertThat(saved.getCalcScene()).isEqualTo("MONTHLY_ADJUST");
    assertThat(saved.getFactorSource()).isEqualTo("MONTHLY_FACTOR");
    assertThat(saved.getPricingMonth()).isEqualTo("2026-05");
    assertThat(saved.getBusinessUnitType()).isEqualTo("COMMERCIAL");
    verify(bomCostingRowMapper, never()).selectList(any());
    verify(oaFormMapper, never()).selectOne(any());
  }

  @Test
  void ensureMonthlyAdjustDoesNotFilterLinkedFormulaByEffectiveDates() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem("MAT-1")));
    when(calcService.calculateMonthlyAdjustItemForEnsure(any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("11.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });

    var request =
        LinkedPriceEnsureRequest.monthlyAdjust(
            "COMMERCIAL", "2026-05", Set.of("MAT-1"), false, LocalDateTime.of(2026, 5, 31, 12, 0));
    service.ensure(request);

    ArgumentCaptor<Wrapper<PriceLinkedItem>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectPricingCandidates(queryCaptor.capture());
    assertThat(queryCaptor.getValue().getSqlSegment())
        .doesNotContain("effective_from", "effective_to", "updated_at");
  }

  @Test
  void ensureReportsCalculationFailure() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectPricingCandidates(any()))
        .thenReturn(List.of(linkedItem("MAT-FAIL")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of());
    when(oaFormMapper.selectOne(any())).thenReturn(null);
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setCalcStatus("FAILED");
              calcItem.setCalcMessage("变量 Cu 缺失");
              return calcItem;
            });

    var result =
        service.ensure(
            LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-FAIL")));

    assertThat(result.getCreatedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getFailedItems().get(0).getItemCode()).isEqualTo("MAT-FAIL");
    assertThat(result.getFailedItems().get(0).getReason()).isEqualTo("变量 Cu 缺失");
    assertThat(result.getFailedItems().get(0).getReasonCode()).isEqualTo("LINKED_RULE_ERROR");
    verify(calcItemMapper).insert(any(PriceLinkedCalcItem.class));
  }

  @Test
  void financeQuoteUsesIndependentFactorSourceAndCuOverride() {
    when(calcItemMapper.selectList(any())).thenReturn(List.of());
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(linkedItem("MAT-CU")));
    when(bomCostingRowMapper.selectList(any())).thenReturn(List.of(bomRow("MAT-CU")));
    when(oaFormMapper.selectOne(any())).thenReturn(new OaForm());
    when(calcService.calculateQuoteItemForEnsure(any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              PriceLinkedCalcItem calcItem = invocation.getArgument(0);
              calcItem.setPartUnitPrice(new BigDecimal("90.000000"));
              calcItem.setCalcStatus("OK");
              return calcItem;
            });
    LinkedPriceEnsureRequest request =
        LinkedPriceEnsureRequest.quote("OA-001", "COMMERCIAL", "2026-05", Set.of("MAT-CU"));
    request.setPriceScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setVariableOverrides(Map.of("Cu", new BigDecimal("90.000000")));

    var result = service.ensure(request);

    assertThat(result.getCreatedCount()).isEqualTo(1);
    ArgumentCaptor<Map<String, BigDecimal>> overrides = ArgumentCaptor.forClass(Map.class);
    verify(calcService)
        .calculateQuoteItemForEnsure(
            any(),
            any(),
            any(),
            overrides.capture(),
            org.mockito.ArgumentMatchers.eq("FINANCE_QUOTE_BASE"));
    assertThat(overrides.getValue()).containsOnlyKeys("Cu");
    ArgumentCaptor<PriceLinkedCalcItem> saved = ArgumentCaptor.forClass(PriceLinkedCalcItem.class);
    verify(calcItemMapper).insert(saved.capture());
    assertThat(saved.getValue().getFactorSource()).isEqualTo("FINANCE_QUOTE_BASE");
    ArgumentCaptor<Wrapper<PriceLinkedCalcItem>> query = ArgumentCaptor.forClass(Wrapper.class);
    verify(calcItemMapper).selectList(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("factor_source");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) query.getValue())
                .getParamNameValuePairs()
                .values())
        .contains("FINANCE_QUOTE_BASE");
  }

  @Test
  void partialNewMonthBlocksPriorFormulaAndClearsSuccessfulCache() {
    availableMonth = "2026-11";
    var prior = linkedItem("MAT-B");
    prior.setPricingMonth("2026-10");
    prior.setOrgCode("210");
    prior.setId(101L);
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(prior));
    var existing = new PriceLinkedCalcItem();
    existing.setId(500L);
    existing.setItemCode("MAT-B");
    existing.setSourcePriceRecordId(101L);
    existing.setCalcStatus("OK");
    existing.setPartUnitPrice(new BigDecimal("9.99"));
    when(calcItemMapper.selectList(any())).thenReturn(List.of(existing));
    var request = LinkedPriceEnsureRequest.quote("OA-1", "COMMERCIAL", "2026-11", Set.of("MAT-B"));
    request.setPriceOrgCode("210");
    var result = service.ensure(request);
    assertThat(result.getSkippedCount()).isZero();
    assertThat(result.getFailedCount()).isOne();
    assertThat(result.getFailedItems().getFirst().getReasonCode())
        .isEqualTo("MATERIAL_MONTH_PRICE_MISSING");
    assertThat(result.getFailedItems().getFirst().getReason())
        .contains("MAT-B", "210", "2026-11", "不沿用");
    var failed = result.getItems().get("MAT-B");
    assertThat(failed.getPartUnitPrice()).isNull();
    assertThat(failed.getSourcePriceRecordId()).isNull();
    assertThat(failed.getCalcStatus()).isEqualTo("FAILED");
    assertThat(failed.getId()).isEqualTo(existing.getId());
    verify(calcItemMapper).update(org.mockito.ArgumentMatchers.same(failed), any());
    verifyNoInteractions(calcService);
    var query = ArgumentCaptor.forClass(Wrapper.class);
    verify(linkedItemMapper).selectPricingCandidates(query.capture());
    assertThat(query.getValue().getSqlSegment()).contains("org_code", "rule_status");
  }

  @Test
  void readOnlyCheckAlsoRejectsPartialMonthWithoutWritingResults() {
    availableMonth = "2026-11";
    var prior = linkedItem("MAT-B");
    prior.setPricingMonth("2026-10");
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(prior));
    var result =
        service.calculate(
            LinkedPriceEnsureRequest.quote("OA-1", "COMMERCIAL", "2026-11", Set.of("MAT-B")));
    assertThat(result).hasSize(1);
    assertThat(result.getFirst().getFailureCode()).isEqualTo("MATERIAL_MONTH_PRICE_MISSING");
    assertThat(result.getFirst().getPartUnitPrice()).isNull();
    verifyNoInteractions(calcItemMapper, calcService);
  }

  @Test
  void newMonthRangeDoesNotReusePriorLinkedFormula() {
    availableMonth = "2026-11";
    var prior = linkedItem("MAT-B");
    prior.setPricingMonth("2026-10");
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(prior));
    when(monthSelector.hasRange("COMMERCIAL", "", "2026-11", "MAT-B")).thenReturn(true);
    service.calculate(
        LinkedPriceEnsureRequest.quote("OA-1", "COMMERCIAL", "2026-11", Set.of("MAT-B")));
    verify(calcService)
        .calculateQuoteItemForEnsure(any(), org.mockito.ArgumentMatchers.isNull(), any());
  }

  @Test
  void publishedRevisionWinsOverLaterUploadTimeAndSameSupplierOtherUnitIsAmbiguous() {
    var first = linkedItem("MAT-1");
    first.setSupplierCode("S1");
    first.setUnit("只");
    first.setRuleRevision(2);
    first.setRuleIdentityKey("same-key");
    first.setCreatedAt(LocalDateTime.of(2026, 5, 1, 0, 0));
    var older = linkedItem("MAT-1");
    older.setSupplierCode("S1");
    older.setUnit("只");
    older.setRuleRevision(1);
    older.setRuleIdentityKey("same-key");
    older.setCreatedAt(LocalDateTime.of(2026, 5, 20, 0, 0));
    when(linkedItemMapper.selectPricingCandidates(any())).thenReturn(List.of(older, first));
    service.calculate(
        LinkedPriceEnsureRequest.quote("OA-1", "COMMERCIAL", "2026-05", Set.of("MAT-1")));
    verify(calcService)
        .calculateQuoteItemForEnsure(any(), org.mockito.ArgumentMatchers.same(first), any());
    Mockito.clearInvocations(calcService);
    older.setRuleIdentityKey("other-unit");
    older.setUnit("米");
    var ambiguous =
        service.calculate(
            LinkedPriceEnsureRequest.quote("OA-1", "COMMERCIAL", "2026-05", Set.of("MAT-1")));
    assertThat(ambiguous.getFirst().getFailureCode()).isEqualTo("MATERIAL_RULE_AMBIGUOUS");
    verifyNoInteractions(calcService);
  }

  private PriceLinkedItem linkedItem(String materialCode) {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setMaterialCode(materialCode);
    item.setPricingMonth("2026-05");
    item.setBusinessUnitType("COMMERCIAL");
    item.setFormulaExpr("[Cu]+1");
    return item;
  }

  private SupplierSupplyRatioResolveResult mainSupplier(
      String supplierCode, String supplierName, String ratio) {
    SupplierSupplyRatioResolveResult result = new SupplierSupplyRatioResolveResult();
    result.setMatched(true);
    result.setSupplierCode(supplierCode);
    result.setSupplierName(supplierName);
    result.setSupplyRatio(new BigDecimal(ratio));
    return result;
  }

  private BomCostingRow bomRow(String materialCode) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("OA-001");
    row.setBusinessUnitType("COMMERCIAL");
    row.setMaterialCode(materialCode);
    row.setShapeAttr("采购件");
    row.setQtyPerTop(new BigDecimal("2.5"));
    return row;
  }
}
