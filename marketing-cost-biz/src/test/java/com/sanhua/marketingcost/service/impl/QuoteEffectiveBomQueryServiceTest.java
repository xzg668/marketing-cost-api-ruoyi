package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPrunerImpl;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIdentity;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionRepository;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomPolicyActionResolver;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomVariantHasher;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomBuilderImpl;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomCostingCandidate;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.ResolvedCustomerKey;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolution;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeRequest;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolver;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeResolver;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioResolution;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class QuoteEffectiveBomQueryServiceTest {

  private QuoteBomPreparationRecordMapper preparationMapper;
  private OaFormItemMapper itemMapper;
  private OaFormMapper formMapper;
  private QuoteBomMonthlySnapshotMapper monthlyMapper;
  private BomRawHierarchyMapper rawMapper;
  private PlateCommercialMakeBomExpansionService crossOrganizationExpansionService;
  private BomAlternativeGroupResolver groupResolver;
  private QuoteBomAlternativeSelectionRepository selectionRepository;
  private QuoteBomAlternativeSelectionService selectionService;
  private MaterialQuoteShapeResolver shapeResolver;
  private SupplierRatioShapeResolver supplierResolver;
  private EffectiveBomVariantHasher variantHasher;
  private QuoteBomContextResolver contextResolver;
  private QuoteBomStatusService quoteBomStatusService;
  private QuoteEffectiveBomApplicationServiceImpl service;
  private QuoteBomMonthlySnapshot snapshot;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    preparationMapper = mock(QuoteBomPreparationRecordMapper.class);
    itemMapper = mock(OaFormItemMapper.class);
    formMapper = mock(OaFormMapper.class);
    monthlyMapper = mock(QuoteBomMonthlySnapshotMapper.class);
    rawMapper = mock(BomRawHierarchyMapper.class);
    crossOrganizationExpansionService = mock(PlateCommercialMakeBomExpansionService.class);
    selectionRepository = mock(QuoteBomAlternativeSelectionRepository.class);
    selectionService = mock(QuoteBomAlternativeSelectionService.class);
    shapeResolver = mock(MaterialQuoteShapeResolver.class);
    supplierResolver = mock(SupplierRatioShapeResolver.class);
    variantHasher = mock(EffectiveBomVariantHasher.class);
    groupResolver = mock(BomAlternativeGroupResolver.class);
    contextResolver = mock(QuoteBomContextResolver.class);
    quoteBomStatusService = mock(QuoteBomStatusService.class);

    QuoteBomPreparationRecord preparation = preparation();
    OaFormItem item = item();
    OaForm form = form();
    when(preparationMapper.selectOne(any(Wrapper.class))).thenReturn(preparation);
    when(itemMapper.selectById(42L)).thenReturn(item);
    when(formMapper.selectById(7L)).thenReturn(form);
    when(contextResolver.resolveWithExistingCostPeriod(form, item, "2026-08"))
        .thenReturn(
            new QuoteBomContext(
                "2026-08",
                "P",
                new ResolvedCustomerKey(
                    "CUSTOMER-A", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null),
                "BOX",
                new QuoteDataOrganization("210", "COMMERCIAL")));
    when(groupResolver.resolve(any())).thenReturn(new BomAlternativeGroupResolution(List.of(), List.of()));
    when(shapeResolver.resolveAll(any()))
        .thenAnswer(
            invocation -> {
              List<MaterialQuoteShapeRequest> requests = invocation.getArgument(0);
              Map<String, MaterialQuoteShapeResolution> results = new LinkedHashMap<>();
              for (MaterialQuoteShapeRequest request : requests) {
                QuoteMaterialShape shape = QuoteMaterialShape.fromU9(request.sourceU9Shape());
                results.put(
                    request.materialCode(),
                    new MaterialQuoteShapeResolution(
                        request.materialOrgCode(),
                        request.materialCode(),
                        request.accountingMonth(),
                        request.sourceU9Shape(),
                        shape,
                        shape,
                        MaterialQuoteShapeSource.U9,
                        null,
                        null,
                        null,
                        null,
                        null));
              }
              return results;
            });
    when(supplierResolver.resolveAll(any())).thenReturn(Map.of());
    when(variantHasher.hash(any())).thenReturn("CURRENT-HASH");
    when(crossOrganizationExpansionService.expand(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new PlateCommercialMakeBomExpansionService.ExpansionResult(
                    invocation.getArgument(0), Map.of(), Map.of(), List.of()));

    service =
        new QuoteEffectiveBomApplicationServiceImpl(
            preparationMapper,
            itemMapper,
            formMapper,
            monthlyMapper,
            rawMapper,
            crossOrganizationExpansionService,
            groupResolver,
            new BomAlternativeBranchPrunerImpl(),
            selectionRepository,
            selectionService,
            shapeResolver,
            supplierResolver,
            new QuoteEffectiveBomBuilderImpl(
                new BomAlternativeBranchPrunerImpl(),
                new EffectiveBomPolicyActionResolver(new ObjectMapper())),
            variantHasher,
            contextResolver,
            quoteBomStatusService);
    snapshot = snapshot(42L);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void previewsOneDraftProductWithoutPersistenceSideEffects() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes()).extracting(node -> node.materialCode()).containsExactly("P", "A");
    assertThat(result.buildBatchId()).isNull();
    assertThat(result.sourceBomBatchId()).isEqualTo("RAW-202608");
    assertThat(result.exclusionSummary().excludedNodeCount()).isZero();
    verify(selectionRepository, never()).insert(any());
    verify(monthlyMapper, never()).insert(any(QuoteBomMonthlySnapshot.class));
    verify(monthlyMapper, never()).updateById(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void usesApprovedElectronicDrawingSnapshotOnlyAfterMonthlyU9NotFound() {
    QuoteBomMonthlySnapshot u9NotFound = snapshot(42L);
    u9NotFound.setId(500L);
    u9NotFound.setSnapshotIdentityKey("A".repeat(64));
    u9NotFound.setSyncStatus("NOT_FOUND");
    u9NotFound.setBomBatchId(null);

    QuoteBomMonthlySnapshot electronic = snapshot(42L);
    electronic.setId(601L);
    electronic.setBomSource("ELECTRONIC_DRAWING_BOM");
    electronic.setBomVersion("ED-88");
    electronic.setBomBatchId("SUPPLEMENT_VERSION:88");
    List<BomRawHierarchy> electronicRows = rawRows();
    electronicRows.forEach(row -> {
      row.setSourceType("E_DRAWING");
      row.setSourceImportBatchId("SUPPLEMENT_VERSION:88");
      row.setBuildBatchId("SUPPLEMENT_VERSION:88");
    });
    when(monthlyMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(u9NotFound), List.of(electronic));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(electronicRows);

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.monthlySnapshotId()).isEqualTo(601L);
    assertThat(result.sourceBomBatchId()).isEqualTo("SUPPLEMENT_VERSION:88");
    assertThat(result.nodes()).extracting(node -> node.materialCode()).containsExactly("P", "A");
  }

  @Test
  void shapeLessTopProductIsAPlanningRootAndDoesNotBlockItsBomTree() {
    List<BomRawHierarchy> rows = rawRows();
    rows.getFirst().setShapeAttr(null);
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rows);

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes())
        .extracting(node -> node.materialCode())
        .containsExactly("P", "A");
    assertThat(result.nodes().getFirst().sourceMaterialShape()).isNull();
    assertThat(result.nodes().getFirst().effectiveMaterialShape()).isEqualTo("MANUFACTURE");
    assertThat(result.nodes().getFirst().shapeResolutionSource()).isEqualTo("STRUCTURE_ROOT");
    ArgumentCaptor<List<MaterialQuoteShapeRequest>> requestCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(shapeResolver).resolveAll(requestCaptor.capture());
    assertThat(requestCaptor.getValue())
        .extracting(MaterialQuoteShapeRequest::materialCode)
        .containsExactly("A");
  }

  @Test
  void preparesServerSideCostingCandidateFromTheSameDraftEvaluation() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());

    QuoteEffectiveBomCostingCandidate candidate =
        service.prepareCostingCandidate("OA-QEB-11", 42L);

    assertThat(candidate.response().costPeriodMonth()).isEqualTo("2026-08");
    assertThat(candidate.candidateVariant().sourceBomBatchId()).isEqualTo("RAW-202608");
    assertThat(candidate.candidateVariant().buildResult().nodes())
        .extracting(node -> node.materialCode())
        .containsExactly("P", "A");
  }

  @Test
  void expandsPlatePurchaseNodeWithExistingCommercialMakeBomBeforeEffectiveBomBuild() {
    QuoteBomPreparationRecord platePreparation = preparation();
    platePreparation.setPriceOrgCode("220");
    platePreparation.setMaterialOrganizationCode("PLATE");
    when(preparationMapper.selectOne(any(Wrapper.class))).thenReturn(platePreparation);
    OaFormItem plateItem = item();
    plateItem.setBusinessUnitType("PLATE");
    OaForm plateForm = form();
    plateForm.setBusinessUnitType("PLATE");
    when(itemMapper.selectById(42L)).thenReturn(plateItem);
    when(formMapper.selectById(7L)).thenReturn(plateForm);
    doReturn(
            new QuoteBomContext(
                "2026-08",
                "P",
                new ResolvedCustomerKey(
                    "CUSTOMER-A", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null),
                "BOX",
                new QuoteDataOrganization("220", "PLATE")))
        .when(contextResolver)
        .resolveWithExistingCostPeriod(any(OaForm.class), any(OaFormItem.class), eq("2026-08"));
    snapshot.setPriceOrgCode("220");
    List<BomRawHierarchy> plateRows = rawRows();
    plateRows.getFirst().setPriceOrgCode("220");
    plateRows.get(1).setShapeAttr("采购件");
    plateRows.get(1).setPriceOrgCode("220");
    BomRawHierarchy commercialParent =
        rawNode(2L, "A", "P", 1, "/P/A/", "2", "制造件");
    commercialParent.setPriceOrgCode("210");
    BomRawHierarchy commercialRaw =
        rawNode(3L, "R", "A", 2, "/P/A/R/", "0.5", "采购件");
    commercialRaw.setPriceOrgCode("210");
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(plateRows);
    when(crossOrganizationExpansionService.expand(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlateCommercialMakeBomExpansionService.ExpansionResult(
                List.of(plateRows.getFirst(), commercialParent, commercialRaw),
                Map.of(),
                Map.of(),
                List.of()));

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes())
        .extracting(node -> node.materialCode())
        .containsExactly("P", "A", "R");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaterialQuoteShapeRequest>> requests =
        ArgumentCaptor.forClass(List.class);
    verify(shapeResolver).resolveAll(requests.capture());
    assertThat(requests.getValue())
        .filteredOn(request -> List.of("A", "R").contains(request.materialCode()))
        .allMatch(request -> "COMMERCIAL".equals(request.materialOrgCode()));
  }

  @Test
  void exposesCrossOrganizationExpansionGapAsAFirstStepBlockIssue() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());
    when(crossOrganizationExpansionService.expand(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlateCommercialMakeBomExpansionService.ExpansionResult(
                rawRows(), Map.of(), Map.of(), List.of("料号 A 在210没有有效制造BOM")));

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("BLOCKED");
    assertThat(result.blockIssues())
        .singleElement()
        .satisfies(
            issue -> {
              assertThat(issue.issueCode()).isEqualTo("CROSS_ORG_BOM_EXPANSION_FAILED");
              assertThat(issue.message()).contains("料号 A", "210", "制造BOM");
            });
    verifyNoInteractions(shapeResolver, supplierResolver);
  }

  @Test
  void costingCandidateUsesCurrentWorkbenchMonthInsteadOfStalePreparationMonth() {
    QuoteBomPreparationRecord stalePreparation = preparation();
    stalePreparation.setCostPeriodMonth("2026-07");
    when(preparationMapper.selectOne(any(Wrapper.class))).thenReturn(stalePreparation);
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());

    QuoteEffectiveBomCostingCandidate candidate =
        service.prepareCostingCandidate("OA-QEB-11", 42L);

    assertThat(candidate.response().costPeriodMonth()).isEqualTo("2026-08");
    verify(contextResolver)
        .resolveWithExistingCostPeriod(
            any(OaForm.class), any(OaFormItem.class), eq("2026-08"));
    verify(contextResolver, never())
        .resolveWithExistingCostPeriod(
            any(OaForm.class), any(OaFormItem.class), eq("2026-07"));
  }

  @Test
  void prepareCostingCandidatePersistsDefaultStandardEvidenceBeforeBuild() {
    List<BomRawHierarchy> rows = alternativeRows();
    BomAlternativeGroup group = alternativeGroup(rows.get(1), rows.get(2));
    QuoteBomAlternativeSelection persisted = new QuoteBomAlternativeSelection();
    persisted.setId(91L);
    persisted.setAlternativeGroupKey("GROUP-1");
    persisted.setSelectedMaterialCode("S");
    persisted.setSelectionStatus(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    persisted.setSelectionSource(QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD);
    persisted.setSelectionVersion(1);
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));
    when(selectionRepository.findCurrent(any(), eq("GROUP-1")))
        .thenReturn(null, persisted);

    QuoteEffectiveBomCostingCandidate candidate =
        service.prepareCostingCandidate("OA-QEB-11", 42L);

    assertThat(candidate.alternativeSelectionIdByGroupKey())
        .containsEntry("GROUP-1", 91L);
    verify(selectionService).synchronize(any(), eq(List.of(group)));
  }

  @Test
  void blockedDraftCannotBecomeAConfirmationCandidate() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    assertThatThrownBy(() -> service.prepareCostingCandidate("OA-QEB-11", 42L))
        .isInstanceOf(QuoteEffectiveBomQueryException.class)
        .extracting(exception -> ((QuoteEffectiveBomQueryException) exception).getCode())
        .isEqualTo("EFFECTIVE_BOM_BLOCKED");
    verifyNoInteractions(rawMapper, shapeResolver, supplierResolver);
  }

  @Test
  void reportsMissingMonthlySourceAsBlockedAndPreservesCustomerFallbackWarning() {
    doReturn(
            new QuoteBomContext(
                "2026-08",
                "P",
                new ResolvedCustomerKey(
                    "OA:OA-QEB-11",
                    ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK,
                    "客户信息缺失，本次BOM按OA单号隔离"),
                "BOX",
                new QuoteDataOrganization("210", "COMMERCIAL")))
        .when(contextResolver)
        .resolveWithExistingCostPeriod(
            any(OaForm.class), any(OaFormItem.class), eq("2026-08"));
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("BLOCKED");
    assertThat(result.customerKey()).isEqualTo("OA:OA-QEB-11");
    assertThat(result.blockIssues())
        .extracting(issue -> issue.issueCode())
        .containsExactly("MONTHLY_BOM_NOT_READY");
    assertThat(result.blockIssues().getFirst().message())
        .doesNotContain("同步BOM", "月度原始BOM");
    assertThat(result.warnings()).contains("客户信息缺失，本次BOM按OA单号隔离");
    verify(quoteBomStatusService).checkItemForCostRun("OA-QEB-11", 42L, "2026-08");
    verifyNoInteractions(rawMapper, shapeResolver, supplierResolver);
  }

  @Test
  void refreshRepairsMissingInternalMonthlyRelationFromExistingRawBom() {
    when(monthlyMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(), List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes())
        .extracting(node -> node.materialCode())
        .containsExactly("P", "A");
    verify(quoteBomStatusService).checkItemForCostRun("OA-QEB-11", 42L, "2026-08");
  }

  @Test
  void returnsBlockedIssuesInsteadOfPersistingAnInvalidDraft() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());
    doReturn(
            Map.of(
                "P",
                new MaterialQuoteShapeResolution(
                "COMMERCIAL",
                "P",
                "2026-08",
                "制造件",
                QuoteMaterialShape.MANUFACTURE,
                null,
                MaterialQuoteShapeSource.U9,
                null,
                null,
                null,
                null,
                "U9形态无法解析")))
        .when(shapeResolver)
        .resolveAll(any());

    QuoteEffectiveBomResponse result = service.rebuildPreview("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("BLOCKED");
    assertThat(result.blockIssues())
        .extracting(issue -> issue.issueCode())
        .contains("SHAPE_RESOLUTION_BLOCKED");
  }

  @Test
  void defaultsStandardOnlyInMemoryAndReportsAlternativeExclusion() {
    List<BomRawHierarchy> rows = alternativeRows();
    BomAlternativeGroup group = alternativeGroup(rows.get(1), rows.get(2));
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes()).extracting(node -> node.materialCode()).containsExactly("P", "S");
    assertThat(result.alternativeSelections()).singleElement().satisfies(selection -> {
      assertThat(selection.standardMaterialCode()).isEqualTo("S");
      assertThat(selection.selectedMaterialCode()).isEqualTo("S");
      assertThat(selection.selectionSource()).isEqualTo("AUTO_STANDARD_PREVIEW");
      assertThat(selection.persisted()).isFalse();
    });
    assertThat(result.exclusionSummary().excludedNodeCount()).isEqualTo(1);
    assertThat(result.exclusionSummary().reasonCounts())
        .containsEntry("ALTERNATIVE_UNSELECTED", 1);
    verify(selectionRepository, never()).insert(any());
  }

  @Test
  void previewsAnAlternativeBranchWithoutPersistingTheTemporaryChoice() {
    List<BomRawHierarchy> rows = alternativeRows();
    BomAlternativeGroup group = alternativeGroup(rows.get(1), rows.get(2));
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));

    QuoteEffectiveBomResponse result =
        service.previewAlternative("OA-QEB-11", 42L, "2026-08", "GROUP-1", "T");

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes()).extracting(node -> node.materialCode()).containsExactly("P", "T");
    assertThat(result.alternativeSelections()).singleElement().satisfies(selection -> {
      assertThat(selection.selectedMaterialCode()).isEqualTo("T");
      assertThat(selection.selectedChildType()).isEqualTo("ALTERNATIVE");
      assertThat(selection.selectionSource()).isEqualTo("UNSAVED_PREVIEW");
      assertThat(selection.persisted()).isFalse();
    });
    verify(selectionRepository, never()).insert(any());
    verify(monthlyMapper, never()).updateById(any(QuoteBomMonthlySnapshot.class));
  }

  @Test
  void blocksInsteadOfSilentlyReplacingAStaleSavedAlternativeChoice() {
    List<BomRawHierarchy> rows = alternativeRows();
    BomAlternativeGroup group = alternativeGroup(rows.get(1), rows.get(2));
    QuoteBomAlternativeSelection stale = new QuoteBomAlternativeSelection();
    stale.setId(91L);
    stale.setAlternativeGroupKey("GROUP-1");
    stale.setSelectedMaterialCode("OLD-T");
    stale.setSelectionStatus(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    stale.setSelectionSource(QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE);
    stale.setSelectionVersion(3);
    stale.setParentPath("/P/");
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));
    when(selectionRepository.findCurrent(any(), eq("GROUP-1"))).thenReturn(stale);

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("BLOCKED");
    assertThat(result.blockIssues())
        .extracting(issue -> issue.issueCode())
        .containsExactly("ALT_SOURCE_STALE");
    assertThat(result.alternativeSelections().getFirst().selectedMaterialCode())
        .isEqualTo("OLD-T");
    verifyNoInteractions(shapeResolver, supplierResolver);
  }

  @Test
  void exposesSupplierRatioEvidenceUsedBySpecialMaterialShapeRule() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());
    doAnswer(
            invocation -> {
              List<MaterialQuoteShapeRequest> requests = invocation.getArgument(0);
              Map<String, MaterialQuoteShapeResolution> results = new LinkedHashMap<>();
              for (MaterialQuoteShapeRequest request : requests) {
                if ("A".equals(request.materialCode())) {
                  results.put(
                      "A",
                      new MaterialQuoteShapeResolution(
                          "COMMERCIAL",
                          "A",
                          "2026-08",
                          request.sourceU9Shape(),
                          QuoteMaterialShape.PURCHASE,
                          null,
                          MaterialQuoteShapeSource.SUPPLIER_RATIO,
                          77L,
                          "POLICY-FP",
                          "{\"internalSupplierCodes\":[\"INTERNAL\"]}",
                          "{}",
                          "命中供货比例形态规则，等待主供应商解析"));
                  continue;
                }
                QuoteMaterialShape shape = QuoteMaterialShape.fromU9(request.sourceU9Shape());
                results.put(
                    request.materialCode(),
                    new MaterialQuoteShapeResolution(
                        "COMMERCIAL",
                        request.materialCode(),
                        "2026-08",
                        request.sourceU9Shape(),
                        shape,
                        shape,
                        MaterialQuoteShapeSource.U9,
                        null,
                        null,
                        null,
                        null,
                        null));
              }
              return results;
            })
        .when(shapeResolver)
        .resolveAll(any());
    when(supplierResolver.resolveAll(any()))
        .thenReturn(
            Map.of(
                "A",
                new SupplierRatioResolution(
                    "COMMERCIAL",
                    "210",
                    "A",
                    "2026-08",
                    QuoteMaterialShape.OUTSOURCE,
                    77L,
                    "POLICY-FP",
                    88L,
                    "EXTERNAL",
                    "外部供应商",
                    new BigDecimal("0.80"),
                    false,
                    "{\"internalSupplierCodes\":[\"INTERNAL\"]}",
                    "{}",
                    null)));

    QuoteEffectiveBomResponse result = service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.nodes())
        .filteredOn(node -> "A".equals(node.materialCode()))
        .singleElement()
        .satisfies(
            node -> {
              assertThat(node.effectiveMaterialShape()).isEqualTo("OUTSOURCE");
              assertThat(node.shapeResolutionSource()).isEqualTo("SUPPLIER_RATIO");
              assertThat(node.shapePolicyId()).isEqualTo(77L);
              assertThat(node.selectedSupplierRatioId()).isEqualTo(88L);
              assertThat(node.selectedSupplierCode()).isEqualTo("EXTERNAL");
              assertThat(node.selectedSupplyRatio()).isEqualByComparingTo("0.80");
            });
  }

  @Test
  void refreshTreatsMonthlySourceAsLiveDraftUntilStepTwo() {
    snapshot = snapshot(42L);
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());

    QuoteEffectiveBomResponse result =
        service.getEffectiveBom("OA-QEB-11", 42L);

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.buildBatchId()).isNull();
    assertThat(result.nodes()).isNotEmpty();
  }

  @Test
  void allowsAlternativePreviewForMonthlyBomBeforeStepTwo() {
    snapshot = snapshot(42L);
    List<BomRawHierarchy> rows = alternativeRows();
    BomAlternativeGroup group = alternativeGroup(rows.get(1), rows.get(2));
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));

    QuoteEffectiveBomResponse result =
        service.previewAlternative("OA-QEB-11", 42L, "2026-08", "GROUP-1", "T");

    assertThat(result.state()).isEqualTo("DRAFT");
    assertThat(result.nodes()).extracting(node -> node.materialCode()).containsExactly("P", "T");
  }

  @Test
  void oneItemQueryNeverLoadsOrProcessesTheOtherNinetyNineOaItems() {
    when(monthlyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshot));
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(rawRows());

    service.getEffectiveBom("OA-QEB-11", 42L);

    verify(itemMapper).selectById(42L);
    verify(itemMapper, never()).selectList(any());
  }

  @Test
  void rejectsPathThatDoesNotMatchTheRequestedOa() {
    QuoteBomPreparationRecord preparation = preparation();
    preparation.setOaNo("OA-OTHER");
    when(preparationMapper.selectOne(any(Wrapper.class))).thenReturn(preparation);

    assertThatThrownBy(() -> service.getEffectiveBom("OA-QEB-11", 42L))
        .isInstanceOf(QuoteEffectiveBomQueryException.class)
        .hasMessageContaining("不属于同一报价范围");
    verifyNoInteractions(monthlyMapper, rawMapper);
  }

  @Test
  void rejectsAUserFromAnotherBusinessUnitBeforeReadingMonthlyData() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            "other-bu", null, List.of(new SimpleGrantedAuthority("ingest:quote:list")));
    authentication.setDetails(Map.of("businessUnitType", "PLATE"));
    SecurityContextHolder.getContext().setAuthentication(authentication);

    assertThatThrownBy(() -> service.getEffectiveBom("OA-QEB-11", 42L))
        .isInstanceOf(QuoteEffectiveBomQueryException.class)
        .hasMessageContaining("不能访问该报价产品");
    verifyNoInteractions(monthlyMapper, rawMapper);
  }

  private static QuoteBomPreparationRecord preparation() {
    QuoteBomPreparationRecord row = new QuoteBomPreparationRecord();
    row.setId(100L);
    row.setOaFormId(7L);
    row.setOaFormItemId(42L);
    row.setOaNo("OA-QEB-11");
    row.setQuoteProductCode("P");
    row.setProductType("NON_BARE");
    row.setPriceOrgCode("210");
    row.setMaterialOrganizationCode("COMMERCIAL");
    row.setCostPeriodMonth("2026-08");
    row.setActiveFlag(1);
    return row;
  }

  private static OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(42L);
    item.setOaFormId(7L);
    item.setMaterialNo("P");
    item.setPackageMethod("BOX");
    item.setBusinessUnitType("COMMERCIAL");
    return item;
  }

  private static OaForm form() {
    OaForm form = new OaForm();
    form.setId(7L);
    form.setOaNo("OA-QEB-11");
    form.setCustomer("CUSTOMER-A");
    form.setAccountingPeriodMonth("2026-08");
    form.setApplyDate(LocalDate.of(2026, 8, 4));
    form.setBusinessUnitType("COMMERCIAL");
    return form;
  }

  private static QuoteBomMonthlySnapshot snapshot(Long sourceItemId) {
    QuoteBomMonthlySnapshot row = new QuoteBomMonthlySnapshot();
    row.setId(501L);
    row.setProductCode("P");
    row.setPriceOrgCode("210");
    row.setCustomerCode("CUSTOMER-A");
    row.setPackageMethod("BOX");
    row.setCostPeriodMonth("2026-08");
    row.setBomPurpose("主制造");
    row.setSyncStatus("SUCCESS");
    row.setSyncAt(LocalDateTime.of(2026, 8, 4, 9, 0));
    row.setSourceOaFormItemId(sourceItemId);
    row.setBomBatchId("RAW-202608");
    row.setActiveFlag(1);
    return row;
  }

  private static List<BomRawHierarchy> rawRows() {
    return List.of(
        rawNode(1L, "P", "P", 0, "/P/", "1", "制造件"),
        rawNode(2L, "A", "P", 1, "/P/A/", "2", "采购件"));
  }

  private static List<BomRawHierarchy> alternativeRows() {
    BomRawHierarchy root = rawNode(1L, "P", "P", 0, "/P/", "1", "制造件");
    BomRawHierarchy standard = rawNode(2L, "S", "P", 1, "/P/S/", "1", "采购件");
    standard.setChildType("STANDARD");
    standard.setAlternativeGroupKey("GROUP-1");
    BomRawHierarchy alternative = rawNode(3L, "T", "P", 1, "/P/T/", "1", "采购件");
    alternative.setChildType("ALTERNATIVE");
    alternative.setAlternativeGroupKey("GROUP-1");
    return List.of(root, standard, alternative);
  }

  private static BomAlternativeGroup alternativeGroup(
      BomRawHierarchy standard, BomRawHierarchy alternative) {
    return new BomAlternativeGroup(
        new BomAlternativeGroupIdentity(
            "210",
            "P",
            "PARENT-P",
            "P",
            "主制造",
            "V1",
            LocalDate.of(2026, 1, 1),
            null,
            1,
            null),
        "GROUP-1",
        List.of(
            alternativeCandidate(standard, BomChildType.STANDARD),
            alternativeCandidate(alternative, BomChildType.ALTERNATIVE)));
  }

  private static BomAlternativeCandidate alternativeCandidate(
      BomRawHierarchy row, BomChildType childType) {
    return new BomAlternativeCandidate(
        row.getId(),
        row.getMaterialCode(),
        row.getMaterialName(),
        row.getMaterialSpec(),
        childType,
        row.getQtyPerParent(),
        row.getPath(),
        row.getSourceImportBatchId(),
        row.getBuildBatchId());
  }

  private static BomRawHierarchy rawNode(
      Long id,
      String material,
      String parent,
      int level,
      String path,
      String quantity,
      String shape) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setPriceOrgCode("210");
    row.setTopProductCode("P");
    row.setParentCode(parent);
    row.setMaterialCode(material);
    row.setMaterialName("名称-" + material);
    row.setMaterialSpec("规格-" + material);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(id.intValue());
    row.setSourceLineKey("LINE-" + id);
    row.setQtyPerParent(new BigDecimal(quantity));
    row.setShapeAttr(shape);
    row.setSourceType("U9");
    row.setSourceImportBatchId("IMPORT-1");
    row.setBuildBatchId("RAW-202608");
    row.setBuiltAt(LocalDateTime.of(2026, 8, 4, 8, 0));
    row.setChildType("NORMAL");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }

}
