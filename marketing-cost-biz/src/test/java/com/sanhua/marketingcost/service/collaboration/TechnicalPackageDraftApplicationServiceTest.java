package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageCopyRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageDraftRequest;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("QCBP-12 裸品补包装与增量缺价")
class TechnicalPackageDraftApplicationServiceTest {

  private final QuoteCollaborationTaskRepository repository = mock(QuoteCollaborationTaskRepository.class);
  private final CollaborationCurrentPrincipalProvider principalProvider = mock(CollaborationCurrentPrincipalProvider.class);
  private final FormalBomReadService formalBomReadService = mock(FormalBomReadService.class);
  private final QuoteProductBomPreparationService preparationService = mock(QuoteProductBomPreparationService.class);
  private final QuoteBomPreparationRecordMapper preparationMapper = mock(QuoteBomPreparationRecordMapper.class);
  private final QuoteBomPackageReferenceMapper referenceMapper = mock(QuoteBomPackageReferenceMapper.class);
  private final QuoteBomPackageReferenceDetailMapper detailMapper = mock(QuoteBomPackageReferenceDetailMapper.class);
  private final PackageComponentSnapshotMapper snapshotMapper = mock(PackageComponentSnapshotMapper.class);
  private final PackageComponentSnapshotDetailMapper snapshotDetailMapper = mock(PackageComponentSnapshotDetailMapper.class);
  private final OaFormItemMapper oaFormItemMapper = mock(OaFormItemMapper.class);
  private final QuoteCollaborationProductTaskMapper productTaskMapper = mock(QuoteCollaborationProductTaskMapper.class);
  private final TechnicalRealPriceGapScanService priceScanService =
      mock(TechnicalRealPriceGapScanService.class);
  private final CollaborationProductStateService stateService = mock(CollaborationProductStateService.class);
  private final CollaborationPrincipal technician = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));
  private final TechnicalPackageDraftApplicationService service =
      new TechnicalPackageDraftApplicationService(
          repository, principalProvider, formalBomReadService, preparationService,
          preparationMapper, referenceMapper, detailMapper, snapshotMapper,
          snapshotDetailMapper, oaFormItemMapper, productTaskMapper,
          priceScanService, stateService, new CollaborationPortalAccessPolicy());

  private QuoteCollaborationProductTask task;
  private QuoteCollaborationQuoteLink owner;

  @BeforeEach
  void setUp() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("wang", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(principalProvider.currentTechnician()).thenReturn(technician);
    task = task();
    owner = owner();
    when(repository.findMineById(10L, 601L, "COMMERCIAL"))
        .thenAnswer(ignored -> Optional.of(task));
    when(repository.findLinksByProductTask(eq(10L), any(CollaborationScope.class)))
        .thenReturn(List.of(owner));
    when(repository.findGaps(eq(10L), any(CollaborationScope.class))).thenReturn(List.of());
    when(repository.findProductTaskById(eq(10L), any(CollaborationScope.class)))
        .thenAnswer(ignored -> Optional.of(task));
    when(formalBomReadService.read(any(com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext.class)))
        .thenReturn(new FormalBomReadResult(
            "BARE-1", "2026-08", "主制造", true,
            List.of(bodyLine()), null));
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void workspaceCombinesReadOnlyU9SummaryWithMultiLevelPackageTree() {
    task.setPackageReferenceId(88L);
    when(referenceMapper.selectById(88L)).thenReturn(draft(88L));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        line(1L, "PACK-ROOT", "VIRTUAL-PACK", "1"),
        line(2L, "VIRTUAL-PACK", "BOX", "2")));

    var result = service.workspace(10L);

    assertThat(result.u9Body().ready()).isTrue();
    assertThat(result.u9Body().lineCount()).isEqualTo(1);
    assertThat(result.u9Body().message()).contains("只读保留");
    assertThat(result.draft().tree()).hasSize(1);
    assertThat(result.draft().tree().get(0).materialCode()).isEqualTo("PACK-ROOT");
    assertThat(result.draft().tree().get(0).children().get(0).materialCode())
        .isEqualTo("VIRTUAL-PACK");
    assertThat(result.draft().tree().get(0).children().get(0).children().get(0).materialCode())
        .isEqualTo("BOX");
    assertThat(result.combinedBom().ready()).isTrue();
    assertThat(result.combinedBom().lines()).extracting(line -> line.source())
        .containsExactly("U9_BODY", "PACKAGE_DRAFT", "PACKAGE_DRAFT", "PACKAGE_DRAFT");
    assertThat(result.combinedBom().lines()).filteredOn(line -> "PACKAGE_DRAFT".equals(line.source()))
        .extracting(line -> line.parentCode() + "->" + line.materialCode())
        .containsExactly("BARE-1->PACK-ROOT", "PACK-ROOT->VIRTUAL-PACK", "VIRTUAL-PACK->BOX");
  }

  @Test
  void workspaceWithoutReferenceKeepsBodyVisibleAndGuidesSourceSelection() {
    var result = service.workspace(10L);

    assertThat(result.u9Body().ready()).isTrue();
    assertThat(result.draft()).isNull();
    assertThat(result.guidance()).contains("先从已审核报价产品或包装目件复制");
  }

  @Test
  void copiesApprovedQuotedProductWithoutWritingU9Body() {
    QuoteBomPackageReference source = draft(99L);
    source.setReferenceStatus("APPROVED");
    source.setPreparationId(77L);
    source.setOaFormItemId(222L);
    QuoteBomPreparationRecord sourcePreparation = new QuoteBomPreparationRecord();
    sourcePreparation.setPriceOrgCode("210");
    sourcePreparation.setMaterialOrganizationCode("COMMERCIAL");
    OaFormItem sourceItem = new OaFormItem();
    sourceItem.setBusinessUnitType("COMMERCIAL");
    QuoteProductBomPreparationPreview preview = readyPreparation();
    when(preparationService.prepareByOaFormItem(eq(111L), any())).thenReturn(preview);
    when(referenceMapper.selectById(99L)).thenReturn(source);
    when(preparationMapper.selectById(77L)).thenReturn(sourcePreparation);
    when(oaFormItemMapper.selectById(222L)).thenReturn(sourceItem);
    when(detailMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(line(91L, "PACK-ROOT", "BOX", "1")))
        .thenReturn(List.of(line(92L, "PACK-ROOT", "BOX", "1")));
    when(referenceMapper.insert(any(QuoteBomPackageReference.class))).thenAnswer(invocation -> {
      QuoteBomPackageReference value = invocation.getArgument(0);
      value.setId(88L);
      return 1;
    });
    when(detailMapper.insert(any(QuoteBomPackageReferenceDetail.class))).thenReturn(1);
    when(productTaskMapper.attachPackageDraft(eq(10L), eq(3), eq(77L), eq(88L),
        eq(601L), eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工")))
        .thenAnswer(invocation -> {
          task.setPackageReferenceId(88L);
          task.setPreparationId(77L);
          task.setTaskVersion(4);
          return 1;
        });
    when(referenceMapper.selectById(88L)).thenAnswer(ignored -> {
      QuoteBomPackageReference value = draft(88L);
      value.setReusedFromReferenceId(99L);
      return value;
    });

    var result = service.copy(10L,
        new TechnicalPackageCopyRequest(3, "QUOTED_PRODUCT", 99L));

    assertThat(result.taskVersion()).isEqualTo(4);
    assertThat(result.draft().packageReferenceId()).isEqualTo(88L);
    verify(formalBomReadService).read(any(com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext.class));
    verify(referenceMapper).insert(any(QuoteBomPackageReference.class));
    verify(detailMapper).insert(any(QuoteBomPackageReferenceDetail.class));
  }

  @Test
  void packageParentSourceCopiesSnapshotStructure() {
    PackageComponentSnapshot snapshot = new PackageComponentSnapshot();
    snapshot.setId(66L);
    snapshot.setPackageMaterialCode("PACK-ROOT");
    snapshot.setPackageMaterialName("包装总成");
    snapshot.setPeriodMonth("2026-08");
    snapshot.setStatus("NORMAL");
    snapshot.setPriceOrgCode("210");
    snapshot.setBusinessUnitType("COMMERCIAL");
    snapshot.setSourceTopProductCode("FINISHED-REF");
    when(snapshotMapper.selectById(66L)).thenReturn(snapshot);
    QuoteProductBomPreparationPreview preview = readyPreparation();
    when(preparationService.prepareByOaFormItem(eq(111L), any())).thenReturn(preview);
    PackageComponentSnapshotDetail snapshotLine = new PackageComponentSnapshotDetail();
    snapshotLine.setId(661L);
    snapshotLine.setSnapshotId(66L);
    snapshotLine.setChildMaterialCode("BOX");
    snapshotLine.setChildMaterialName("纸箱");
    snapshotLine.setQtyPerParent(BigDecimal.ONE);
    snapshotLine.setQtyPerTop(BigDecimal.ONE);
    snapshotLine.setChildParentBaseQty(BigDecimal.ONE);
    when(snapshotDetailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(snapshotLine));
    when(referenceMapper.insert(any(QuoteBomPackageReference.class))).thenAnswer(invocation -> {
      QuoteBomPackageReference value = invocation.getArgument(0);
      value.setId(88L);
      return 1;
    });
    List<QuoteBomPackageReferenceDetail> stored = new ArrayList<>();
    when(detailMapper.insert(any(QuoteBomPackageReferenceDetail.class))).thenAnswer(invocation -> {
      stored.add(invocation.getArgument(0));
      return 1;
    });
    when(detailMapper.selectList(any(Wrapper.class))).thenAnswer(ignored -> List.copyOf(stored));
    when(productTaskMapper.attachPackageDraft(any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(invocation -> {
          task.setPackageReferenceId(88L);
          task.setTaskVersion(4);
          return 1;
        });
    when(referenceMapper.selectById(88L)).thenAnswer(ignored -> draft(88L));

    var result = service.copy(10L,
        new TechnicalPackageCopyRequest(3, "PACKAGE_PARENT", 66L));

    assertThat(result.draft().lines()).extracting(line -> line.packageMaterialCode())
        .containsExactly("BOX");
  }

  @Test
  void rejectsCycleBeforeReplacingStoredDraft() {
    task.setPackageReferenceId(88L);
    when(referenceMapper.selectById(88L)).thenReturn(draft(88L));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    List<TechnicalPackageDraftRequest.Line> cycle = List.of(
        requestLine("A", "B", "1"), requestLine("B", "A", "1"));

    assertThatThrownBy(() -> service.save(10L,
        new TechnicalPackageDraftRequest(3, cycle)))
        .hasMessageContaining("闭环");
    verify(detailMapper, never()).delete(any(Wrapper.class));
  }

  @Test
  void saveSupportsQuantityAddReplaceAndDeleteAsOnePackageDraft() {
    task.setPackageReferenceId(88L);
    QuoteBomPackageReference reference = draft(88L);
    when(referenceMapper.selectById(88L)).thenReturn(reference);
    List<QuoteBomPackageReferenceDetail> before = List.of(
        line(1L, "PACK-ROOT", "BOX", "1"),
        line(2L, "PACK-ROOT", "CUSHION", "1"),
        line(3L, "PACK-ROOT", "LABEL", "1"));
    List<QuoteBomPackageReferenceDetail> stored = new ArrayList<>();
    when(detailMapper.selectList(any(Wrapper.class)))
        .thenReturn(before)
        .thenAnswer(ignored -> List.copyOf(stored));
    when(detailMapper.delete(any(Wrapper.class))).thenReturn(3);
    when(detailMapper.insert(any(QuoteBomPackageReferenceDetail.class))).thenAnswer(invocation -> {
      QuoteBomPackageReferenceDetail value = invocation.getArgument(0);
      value.setId((long) (100 + stored.size()));
      stored.add(value);
      return 1;
    });
    when(referenceMapper.updateById(any(QuoteBomPackageReference.class))).thenReturn(1);
    when(productTaskMapper.attachPackageDraft(eq(10L), eq(3), eq(77L), eq(88L),
        eq(601L), eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工")))
        .thenAnswer(invocation -> {
          task.setTaskVersion(4);
          return 1;
        });
    List<TechnicalPackageDraftRequest.Line> changed = List.of(
        requestLine(1L, "PACK-ROOT", "BOX", "2"),
        requestLine(2L, "PACK-ROOT", "TAPE", "1"),
        requestLine(null, "PACK-ROOT", "STRAP", "1"));

    var result = service.save(10L, new TechnicalPackageDraftRequest(3, changed));

    assertThat(result.taskVersion()).isEqualTo(4);
    assertThat(stored).extracting(QuoteBomPackageReferenceDetail::getPackageMaterialCode)
        .containsExactly("BOX", "TAPE", "STRAP")
        .doesNotContain("CUSHION", "LABEL");
    assertThat(stored.get(0).getQtyPerTop()).isEqualByComparingTo("2");
    assertThat(stored).allSatisfy(row -> assertThat(row.getEditedFlag()).isEqualTo(1));
  }

  @Test
  void quantityOnlyChangeWithValidOfficialPriceCreatesNoGapButStillAwaitsReview() {
    task.setPackageReferenceId(88L);
    when(referenceMapper.selectById(88L)).thenReturn(draft(88L));
    when(detailMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(line(1L, "PACK-ROOT", "BOX", "2")));
    when(priceScanService.scan(eq(task), eq(owner)))
        .thenReturn(CollaborationPriceScanResult.ready(1));
    when(productTaskMapper.applyPackagePriceScan(eq(10L), eq(3), eq(0), eq(0), eq("PASSED"),
        eq(601L), eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工")))
        .thenAnswer(invocation -> {
          task.setTaskVersion(4);
          task.setNeedPrice(0);
          task.setOpenGapCount(0);
          task.setLastValidationStatus("PASSED");
          return 1;
        });

    var result = service.checkPrice(10L, 3);

    assertThat(result.priceGapCount()).isZero();
    assertThat(result.status()).isEqualTo("READY_FOR_REVIEW");
    assertThat(result.message()).contains("仍需提交财务审核");
    verify(repository).synchronizeGaps(eq(10L), any(CollaborationScope.class),
        eq(List.of()), any(CollaborationActor.class));
    verify(stateService, never()).transition(any(), any(), any(), any(), any());
  }

  @Test
  void onlyNewUnpricedTapeBecomesGapAndTaskContinuesToPrice() {
    task.setPackageReferenceId(88L);
    when(referenceMapper.selectById(88L)).thenReturn(draft(88L));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        line(1L, "PACK-ROOT", "BOX", "1"),
        line(2L, "PACK-ROOT", "TAPE", "1")));
    when(priceScanService.scan(eq(task), eq(owner))).thenReturn(
        CollaborationPriceScanResult.gaps(2, List.of(
            new CollaborationPriceScanResult.PriceGap(
                "TAPE", "MISSING_PRICE", "MAINTAIN_PRICE", "封箱胶带当前没有有效价格",
                "lp_material_price_type", null, "PACKAGE_REFERENCE", 2L, "PACKAGE:2",
                "/BARE-1/PACK-ROOT/TAPE/", "封箱胶带", null, null,
                "PACKAGE_MATERIAL", BigDecimal.ONE, "卷", "2026-08", "210"))));
    when(productTaskMapper.applyPackagePriceScan(any(), any(), eq(1), eq(1), eq("NOT_CHECKED"),
        any(), any(), any(), any(), any())).thenAnswer(invocation -> {
          task.setTaskVersion(4);
          task.setNeedPrice(1);
          task.setOpenGapCount(1);
          return 1;
        });
    QuoteCollaborationProductTask priceTask = task();
    priceTask.setPackageReferenceId(88L);
    priceTask.setTaskVersion(5);
    priceTask.setTaskStatus("PRICE_IN_PROGRESS");
    when(stateService.transition(eq(10L), eq(4), any(CollaborationScope.class),
        eq(CollaborationActions.ProductAction.CONTINUE_PRICE_AFTER_PACKAGE), eq(technician)))
        .thenReturn(new CollaborationProductStateService.ProductTransitionResult(
            priceTask, CollaborationNextAction.SUPPLEMENT_PRICE));
    when(repository.findMineById(10L, 601L, "COMMERCIAL"))
        .thenReturn(Optional.of(task), Optional.of(priceTask));

    var result = service.checkPrice(10L, 3);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<GapUpsertCommand>> gaps = ArgumentCaptor.forClass(List.class);
    verify(repository).synchronizeGaps(eq(10L), any(CollaborationScope.class), gaps.capture(),
        any(CollaborationActor.class));
    assertThat(gaps.getValue()).singleElement().satisfies(gap -> {
      assertThat(gap.materialCode()).isEqualTo("TAPE");
      assertThat(gap.materialRole()).isEqualTo("PACKAGE_MATERIAL");
      assertThat(gap.reasonMessage()).contains("封箱胶带");
    });
    assertThat(result.priceGapCount()).isEqualTo(1);
    assertThat(result.taskVersion()).isEqualTo(5);
  }

  private QuoteCollaborationProductTask task() {
    QuoteCollaborationProductTask value = new QuoteCollaborationProductTask();
    value.setId(10L);
    value.setProductTaskNo("PT-10");
    value.setProductCode("BARE-1");
    value.setProductName("裸品阀");
    value.setAccountingMonth("2026-08");
    value.setBusinessUnitType("COMMERCIAL");
    value.setApplicableOrgCode("210");
    value.setPriceOrgCode("210");
    value.setMaterialOrgCode("COMMERCIAL");
    value.setPrimaryScope("BARE_PACKAGE");
    value.setNeedPackage(1);
    value.setTaskStatus("PACKAGE_IN_PROGRESS");
    value.setOriginalTechnicianUserId(601L);
    value.setCurrentAssigneeUserId(601L);
    value.setTaskVersion(3);
    return value;
  }

  private QuoteCollaborationQuoteLink owner() {
    QuoteCollaborationQuoteLink value = new QuoteCollaborationQuoteLink();
    value.setId(21L);
    value.setProductTaskId(10L);
    value.setOaNo("OA-12");
    value.setOaFormItemId(111L);
    value.setLinkType("OWNER");
    return value;
  }

  private QuoteBomPackageReference draft(Long id) {
    QuoteBomPackageReference value = new QuoteBomPackageReference();
    value.setId(id);
    value.setPreparationId(77L);
    value.setOaNo("OA-12");
    value.setOaFormItemId(111L);
    value.setQuoteProductCode("BARE-1");
    value.setBareProductCode("BARE-1");
    value.setReferenceFinishedCode("REF-FINISHED");
    value.setSourceTopProductCode("REF-FINISHED");
    value.setPeriodMonth("2026-08");
    value.setReferenceStatus("DRAFT");
    value.setActiveFlag(1);
    value.setEditedFlag(0);
    value.setRemark("SOURCE_MODE=QUOTED_PRODUCT");
    return value;
  }

  private QuoteBomPackageReferenceDetail line(
      Long id, String parent, String child, String quantity) {
    QuoteBomPackageReferenceDetail value = new QuoteBomPackageReferenceDetail();
    value.setId(id);
    value.setPackageReferenceId(88L);
    value.setPreparationId(77L);
    value.setOaNo("OA-12");
    value.setOaFormItemId(111L);
    value.setBareProductCode("BARE-1");
    value.setReferenceFinishedCode("REF-FINISHED");
    value.setSourceTopProductCode("REF-FINISHED");
    value.setLineNo(Math.toIntExact(id));
    value.setPackageParentCode(parent);
    value.setPackageParentName(parent + " name");
    value.setPackageParentUnit("件");
    value.setPackageQtyPerTop(BigDecimal.ONE);
    value.setPackageMaterialCode(child);
    value.setPackageMaterialName(child + " name");
    value.setPackageMaterialUnit("件");
    value.setChildQtyPerParent(new BigDecimal(quantity));
    value.setQtyPerTop(new BigDecimal(quantity));
    value.setUnit("件");
    value.setSelectedFlag(1);
    value.setEditedFlag(0);
    return value;
  }

  private TechnicalPackageDraftRequest.Line requestLine(
      String parent, String child, String quantity) {
    return requestLine(null, parent, child, quantity);
  }

  private TechnicalPackageDraftRequest.Line requestLine(
      Long draftLineId, String parent, String child, String quantity) {
    return new TechnicalPackageDraftRequest.Line(
        draftLineId, parent, parent, null, null, "件", BigDecimal.ONE,
        child, child, null, null, "件", new BigDecimal(quantity), null);
  }

  private QuoteBomSourceLineDto bodyLine() {
    return new QuoteBomSourceLineDto(
        1L, 1, 1, "BARE-1", "BARE-1", "BODY-1", "裸品本体",
        null, null, null, null, null, null, "件", "U9", null,
        "主制造", "V1", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
        "/BARE-1/BODY-1/", 1, 1L, 1L, 0, "210", "COMMERCIAL",
        "MANUFACTURED", null);
  }

  private QuoteProductBomPreparationPreview readyPreparation() {
    return new QuoteProductBomPreparationPreview(
        77L, 1L, 1L, 111L, "OA-12", "BARE-1", "BARE", "BARE-1",
        true, "2026-08", "READY", "NOT_SUBMITTED", true, false, false,
        "U9", true, 1, null, null, false, 0, null, null, null, null,
        List.of(), List.of(), null, List.of(bodyLine()), List.of());
  }
}
