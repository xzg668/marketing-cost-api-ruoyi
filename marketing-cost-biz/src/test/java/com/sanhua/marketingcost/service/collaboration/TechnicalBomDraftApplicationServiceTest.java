package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomCandidateRow;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomReferenceRequest;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.TechnicalBomCandidateMapper;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import java.math.BigDecimal;
import java.time.LocalDate;
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

@DisplayName("QCBP-10 U9相似BOM和独立树草稿")
class TechnicalBomDraftApplicationServiceTest {
  private final QuoteCollaborationTaskRepository repository = mock(QuoteCollaborationTaskRepository.class);
  private final CollaborationCurrentPrincipalProvider principalProvider = mock(CollaborationCurrentPrincipalProvider.class);
  private final TechnicalBomCandidateMapper candidateMapper = mock(TechnicalBomCandidateMapper.class);
  private final BomRawHierarchyMapper rawMapper = mock(BomRawHierarchyMapper.class);
  private final MaterialMasterRawMapper materialMapper = mock(MaterialMasterRawMapper.class);
  private final QuoteBomSupplementVersionMapper versionMapper = mock(QuoteBomSupplementVersionMapper.class);
  private final QuoteBomSupplementDetailMapper detailMapper = mock(QuoteBomSupplementDetailMapper.class);
  private final QuoteCollaborationProductTaskMapper productTaskMapper = mock(QuoteCollaborationProductTaskMapper.class);
  private final QuoteProductBomPreparationService preparationService = mock(QuoteProductBomPreparationService.class);
  private final CollaborationPrincipal wang = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));
  private final TechnicalBomDraftApplicationService service = new TechnicalBomDraftApplicationService(
      repository, principalProvider, candidateMapper, rawMapper, materialMapper, versionMapper,
      detailMapper, productTaskMapper, preparationService);
  private QuoteCollaborationProductTask task;

  @BeforeEach
  void setUp() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("wang", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(principalProvider.currentTechnician()).thenReturn(wang);
    task = task(10L, "P-TARGET");
    stubOwned(task);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void searchAlwaysUsesTaskOrganizationAndRanksExactModelSpecFirst() {
    TechnicalBomCandidateRow exact = candidate("REF-1", "S1", "M1", 190);
    TechnicalBomCandidateRow similar = candidate("REF-2", "S1", "M2", 90);
    when(candidateMapper.selectCandidates(eq("210"), eq("COMMERCIAL"), any(),
        eq("阀"), eq("S1"), eq("M1"), eq(null), eq(null), eq(30)))
        .thenReturn(List.of(exact, similar));

    var result = service.search(10L, "阀", "S1", "M1");

    assertThat(result.candidates()).extracting(candidate -> candidate.productCode())
        .containsExactly("REF-1", "REF-2");
    assertThat(result.candidates().get(0).matchReason()).isEqualTo("规格、型号相同");
    verify(candidateMapper).selectCandidates(eq("210"), eq("COMMERCIAL"), any(),
        eq("阀"), eq("S1"), eq("M1"), eq(null), eq(null), eq(30));
  }

  @Test
  void noCandidateReturnsEmptyInsteadOfCrossOrganizationFallback() {
    when(candidateMapper.selectCandidates(anyString(), anyString(), any(), any(), any(), any(),
        any(), any(), anyInt())).thenReturn(List.of());

    assertThat(service.search(10L, null, "不存在", "不存在").candidates()).isEmpty();
    verify(candidateMapper).selectCandidates(eq("210"), eq("COMMERCIAL"), any(),
        any(), any(), any(), any(), any(), anyInt());
  }

  @Test
  void copiedDraftChangesTargetRootButNeverWritesReferenceBom() {
    List<BomRawHierarchy> reference = referenceRows();
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(reference);
    when(materialMapper.selectByLatestBatchAndCodes(any(), eq(null), eq("COMMERCIAL")))
        .thenReturn(masters("P-TARGET", "REF-1", "C-1"));
    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(900L);
      return 1;
    });
    List<QuoteBomSupplementDetail> stored = new java.util.ArrayList<>();
    when(detailMapper.insert(any(QuoteBomSupplementDetail.class))).thenAnswer(invocation -> {
      stored.add(invocation.getArgument(0));
      return 1;
    });
    when(productTaskMapper.attachBomDraft(eq(10L), eq(3), eq(77L), eq(900L), eq(601L),
        eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工"))).thenReturn(1);
    QuoteCollaborationProductTask refreshed = task(10L, "P-TARGET");
    refreshed.setTaskVersion(4);
    refreshed.setSupplementVersionId(900L);
    when(repository.findMineById(10L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task), Optional.of(refreshed));
    when(detailMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(stored));
    when(versionMapper.selectById(900L)).thenReturn(version(900L));
    savedDetails = ArgumentCaptor.forClass(QuoteBomSupplementDetail.class);

    var response = service.copyReference(10L,
        new TechnicalBomReferenceRequest(3, "REF-1", "主制造", null, null, null, null, null));

    verify(detailMapper, org.mockito.Mockito.times(2)).insert(savedDetails.capture());
    List<QuoteBomSupplementDetail> inserted = savedDetails.getAllValues();
    assertThat(inserted.get(0).getMaterialCode()).isEqualTo("P-TARGET");
    assertThat(inserted.get(0).getSourceRawHierarchyId()).isEqualTo(1L);
    assertThat(inserted.get(1).getMaterialCode()).isEqualTo("C-1");
    assertThat(inserted.get(0).getShapeAttr()).isEqualTo("制造件");
    assertThat(inserted.get(1).getShapeAttr()).isEqualTo("采购件");
    assertThat(inserted).allSatisfy(detail -> assertThat(detail.getSourceCategory()).isNull());
    assertThat(response.taskVersion()).isEqualTo(4);
    assertThat(service.workspace(10L).currentStep()).isEqualTo(2);
    assertThat(reference.get(0).getMaterialCode()).isEqualTo("REF-1");
    verify(rawMapper, never()).insert(any(BomRawHierarchy.class));
    verify(rawMapper, never()).updateById(any(BomRawHierarchy.class));
  }

  private ArgumentCaptor<QuoteBomSupplementDetail> savedDetails;

  @Test
  void cycleOrOrphanAndDuplicateSiblingAreRejectedBeforePersistence() {
    task.setSupplementVersionId(900L);
    when(versionMapper.selectById(900L)).thenReturn(version(900L));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    List<TechnicalBomDraftRequest.Node> orphan = List.of(
        node("ROOT", null, "P-TARGET", "MANUFACTURE"),
        node("C1", "MISSING", "C-1", "PURCHASE"));
    assertThatThrownBy(() -> service.save(10L, new TechnicalBomDraftRequest(3, orphan)))
        .hasMessageContaining("孤儿节点");

    List<TechnicalBomDraftRequest.Node> duplicate = List.of(
        node("ROOT", null, "P-TARGET", "MANUFACTURE"),
        node("C1", "ROOT", "C-1", "PURCHASE"),
        node("C2", "ROOT", "C-1", "PURCHASE"));
    assertThatThrownBy(() -> service.save(10L, new TechnicalBomDraftRequest(3, duplicate)))
        .hasMessageContaining("重复物料");
    verify(detailMapper, never()).insert(any(QuoteBomSupplementDetail.class));
  }

  @Test
  void purchasePartCannotHaveChildrenAndUnknownFormalCodeIsRejected() {
    task.setSupplementVersionId(900L);
    when(versionMapper.selectById(900L)).thenReturn(version(900L));
    when(detailMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(materialMapper.selectByLatestBatchAndCodes(any(), eq(null), eq("COMMERCIAL")))
        .thenReturn(masters("P-TARGET", "C-1", "C-2"));
    List<TechnicalBomDraftRequest.Node> purchaseWithChild = List.of(
        node("ROOT", null, "P-TARGET", "MANUFACTURE"),
        node("C1", "ROOT", "C-1", "PURCHASE"),
        node("C2", "C1", "C-2", "PURCHASE"));
    assertThatThrownBy(() -> service.save(10L,
        new TechnicalBomDraftRequest(3, purchaseWithChild)))
        .hasMessageContaining("采购件不能继续挂下级");

    when(materialMapper.selectByLatestBatchAndCodes(any(), eq(null), eq("COMMERCIAL")))
        .thenReturn(masters("P-TARGET"));
    List<TechnicalBomDraftRequest.Node> unknown = List.of(
        node("ROOT", null, "P-TARGET", "MANUFACTURE"),
        node("C1", "ROOT", "NOT-IN-U9", "PURCHASE"));
    assertThatThrownBy(() -> service.save(10L, new TechnicalBomDraftRequest(3, unknown)))
        .hasMessageContaining("未在当前U9组织找到");
  }

  @Test
  void newTemporaryMaterialRequiresNameSpecModelDrawingAndNature() {
    QuoteCollaborationProductTask newTask = task(11L, null);
    newTask.setTemporaryProductKey("NEW-PRODUCT-11");
    stubOwned(newTask);
    assertThatThrownBy(() -> service.createNew(11L,
        new TechnicalBomReferenceRequest(3, null, null, "MANUFACTURE",
            "新品", "S1", "M1", null)))
        .hasMessageContaining("名称、规格、型号/图号和物料性质必须填写");
    verify(versionMapper, never()).insert(any(QuoteBomSupplementVersion.class));
  }

  @Test
  void copyingForProductWithoutCodeUsesTargetFieldsInsteadOfReferenceRootFields() {
    QuoteCollaborationProductTask newTask = task(13L, null);
    newTask.setTemporaryProductKey("NEW-PRODUCT-13");
    stubOwned(newTask);
    when(rawMapper.selectList(any(Wrapper.class))).thenReturn(referenceRows());
    when(materialMapper.selectByLatestBatchAndCodes(any(), eq(null), eq("COMMERCIAL")))
        .thenReturn(masters("REF-1", "C-1"));

    assertThatThrownBy(() -> service.copyReference(13L,
        new TechnicalBomReferenceRequest(3, "REF-1", "主制造", "MANUFACTURE",
            "当前新品", "TARGET-SPEC", "TARGET-MODEL", null)))
        .hasMessageContaining("名称、规格、型号/图号和物料性质必须填写");

    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(913L);
      return 1;
    });
    List<QuoteBomSupplementDetail> stored = new java.util.ArrayList<>();
    when(detailMapper.insert(any(QuoteBomSupplementDetail.class))).thenAnswer(invocation -> {
      stored.add(invocation.getArgument(0));
      return 1;
    });
    when(productTaskMapper.attachBomDraft(eq(13L), eq(3), eq(77L), eq(913L), eq(601L),
        eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工"))).thenReturn(1);
    QuoteCollaborationProductTask refreshed = task(13L, null);
    refreshed.setTemporaryProductKey("NEW-PRODUCT-13");
    refreshed.setTaskVersion(4);
    refreshed.setSupplementVersionId(913L);
    when(repository.findMineById(13L, 601L, "COMMERCIAL"))
        .thenReturn(Optional.of(newTask), Optional.of(newTask), Optional.of(refreshed));
    when(detailMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(stored));

    var response = service.copyReference(13L,
        new TechnicalBomReferenceRequest(3, "REF-1", "主制造", "MANUFACTURE",
            "当前新品", "TARGET-SPEC", "TARGET-MODEL", "TARGET-DRAWING"));

    QuoteBomSupplementDetail root = stored.get(0);
    assertThat(root.getMaterialName()).isEqualTo("当前新品");
    assertThat(root.getMaterialSpec()).isEqualTo("TARGET-SPEC");
    assertThat(root.getMaterialModel()).isEqualTo("TARGET-MODEL");
    assertThat(root.getDrawingNo()).isEqualTo("TARGET-DRAWING");
    assertThat(root.getMaterialCode()).isEqualTo("TMP-ROOT-13");
    assertThat(response.referenceProductCode()).isEqualTo("REF-1");
  }

  @Test
  void manufactureWithoutChildCanSaveDraftButCannotBeExportReady() {
    QuoteCollaborationProductTask newTask = task(12L, null);
    newTask.setTemporaryProductKey("NEW-PRODUCT-12");
    stubOwned(newTask);
    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(901L);
      return 1;
    });
    List<QuoteBomSupplementDetail> stored = new java.util.ArrayList<>();
    when(detailMapper.insert(any(QuoteBomSupplementDetail.class))).thenAnswer(invocation -> {
      stored.add(invocation.getArgument(0));
      return 1;
    });
    when(productTaskMapper.attachBomDraft(eq(12L), eq(3), eq(77L), eq(901L), eq(601L),
        eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工"))).thenReturn(1);
    QuoteCollaborationProductTask refreshed = task(12L, null);
    refreshed.setTemporaryProductKey("NEW-PRODUCT-12");
    refreshed.setTaskVersion(4);
    refreshed.setSupplementVersionId(901L);
    when(repository.findMineById(12L, 601L, "COMMERCIAL"))
        .thenReturn(Optional.of(newTask), Optional.of(refreshed));
    ArgumentCaptor<QuoteBomSupplementDetail> detail = ArgumentCaptor.forClass(QuoteBomSupplementDetail.class);
    when(detailMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(stored));

    var response = service.createNew(12L,
        new TechnicalBomReferenceRequest(3, null, null, "MANUFACTURE",
            "新品", "S1", "M1", "D1"));

    verify(detailMapper).insert(detail.capture());
    assertThat(response.exportReady()).isFalse();
    assertThat(response.issues()).extracting(issue -> issue.code()).containsExactly("CHILD_REQUIRED");
  }

  @Test
  void savingReordersSiblingsAndDeletesOmittedBranchFromIndependentDraft() {
    task.setSupplementVersionId(900L);
    List<QuoteBomSupplementDetail> stored = new java.util.ArrayList<>(List.of(
        storedDetail(1, 0, "P-TARGET", "/P-TARGET/", null),
        storedDetail(2, 1, "OLD-BRANCH", "/P-TARGET/OLD-BRANCH/", "P-TARGET"),
        storedDetail(3, 1, "C-1", "/P-TARGET/C-1/", "P-TARGET")));
    when(versionMapper.selectById(900L)).thenReturn(version(900L));
    when(versionMapper.updateById(any(QuoteBomSupplementVersion.class))).thenReturn(1);
    when(detailMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(stored));
    when(detailMapper.delete(any(Wrapper.class))).thenAnswer(invocation -> {
      int size = stored.size();
      stored.clear();
      return size;
    });
    when(detailMapper.insert(any(QuoteBomSupplementDetail.class))).thenAnswer(invocation -> {
      stored.add(invocation.getArgument(0));
      return 1;
    });
    when(materialMapper.selectByLatestBatchAndCodes(any(), eq(null), eq("COMMERCIAL")))
        .thenReturn(masters("P-TARGET", "C-1", "C-2"));
    when(productTaskMapper.attachBomDraft(eq(10L), eq(3), eq(77L), eq(900L), eq(601L),
        eq("COMMERCIAL"), eq("210"), eq(601L), eq("王工"))).thenReturn(1);
    QuoteCollaborationProductTask refreshed = task(10L, "P-TARGET");
    refreshed.setSupplementVersionId(900L);
    refreshed.setTaskVersion(4);
    when(repository.findMineById(10L, 601L, "COMMERCIAL"))
        .thenReturn(Optional.of(task), Optional.of(refreshed));

    List<TechnicalBomDraftRequest.Node> request = List.of(
        new TechnicalBomDraftRequest.Node("N1", null, "P-TARGET", "目标产品", "S", "M", "D",
            "MANUFACTURE", BigDecimal.ONE, "件", 1, false),
        new TechnicalBomDraftRequest.Node("NEW-B", "N1", "C-2", "C-2", "S", "M", "D",
            "PURCHASE", new BigDecimal("2"), "件", 1, true),
        new TechnicalBomDraftRequest.Node("N3", "N1", "C-1", "C-1", "S", "M", "D",
            "PURCHASE", new BigDecimal("3"), "件", 2, true));

    var response = service.save(10L, new TechnicalBomDraftRequest(3, request));

    assertThat(stored).extracting(QuoteBomSupplementDetail::getMaterialCode)
        .containsExactly("P-TARGET", "C-2", "C-1")
        .doesNotContain("OLD-BRANCH");
    assertThat(stored).extracting(QuoteBomSupplementDetail::getPath)
        .containsExactly("/P-TARGET/", "/P-TARGET/C-2/", "/P-TARGET/C-1/");
    assertThat(response.flatNodes()).extracting(node -> node.materialCode())
        .containsExactly("P-TARGET", "C-2", "C-1");
  }

  private void stubOwned(QuoteCollaborationProductTask ownedTask) {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setId(100L + ownedTask.getId());
    link.setProductTaskId(ownedTask.getId());
    link.setLinkType("OWNER");
    link.setOaNo("FI-SC-TEST");
    link.setOaFormItemId(300L + ownedTask.getId());
    when(repository.findMineById(ownedTask.getId(), 601L, "COMMERCIAL"))
        .thenReturn(Optional.of(ownedTask));
    when(repository.findLinksByProductTask(ownedTask.getId(),
        new CollaborationScope("COMMERCIAL", "210"))).thenReturn(List.of(link));
  }

  private static QuoteCollaborationProductTask task(Long id, String productCode) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setProductTaskNo("QCPT-" + id);
    task.setProductCode(productCode);
    task.setTemporaryProductKey(productCode == null ? "NEW-" + id : null);
    task.setProductName("目标产品");
    task.setProductSpec("S1");
    task.setProductModel("M1");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setPriceOrgCode("210");
    task.setMaterialOrgCode("COMMERCIAL");
    task.setNeedBom(1);
    task.setTaskStatus("BOM_IN_PROGRESS");
    task.setTaskVersion(3);
    task.setCurrentAssigneeUserId(601L);
    task.setPreparationId(77L);
    return task;
  }

  private static TechnicalBomCandidateRow candidate(
      String code, String spec, String model, int score) {
    TechnicalBomCandidateRow row = new TechnicalBomCandidateRow();
    row.setProductCode(code);
    row.setProductName("参考" + code);
    row.setProductSpec(spec);
    row.setProductModel(model);
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setBomNodeCount(2);
    row.setMatchScore(score);
    return row;
  }

  private static List<BomRawHierarchy> referenceRows() {
    BomRawHierarchy root = raw(1L, "REF-1", "REF-1", "REF-1", 0, "/REF-1/", "制造件");
    BomRawHierarchy child = raw(2L, "REF-1", "REF-1", "C-1", 1, "/REF-1/C-1/", "采购件");
    child.setQtyPerParent(new BigDecimal("2"));
    child.setSourceU9RowId(22L);
    return List.of(root, child);
  }

  private static BomRawHierarchy raw(
      Long id, String top, String parent, String material, int level, String path, String nature) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(material);
    row.setMaterialName(material);
    row.setMaterialSpec("S");
    row.setLevel(level);
    row.setPath(path);
    row.setShapeAttr(nature);
    row.setSourceCategory("产品系列分类（不能作为物料性质）");
    row.setIsLeaf(level == 0 ? 0 : 1);
    row.setPriceOrgCode("210");
    row.setSourceType("U9");
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }

  private static List<MaterialMasterRaw> masters(String... codes) {
    return java.util.Arrays.stream(codes).map(code -> {
      MaterialMasterRaw row = new MaterialMasterRaw();
      row.setMaterialCode(code);
      row.setMaterialName(code);
      row.setMaterialSpec("S");
      row.setMaterialModel("M");
      row.setDrawingNo("D");
      row.setUnit("件");
      row.setShapeAttr(code.startsWith("C-") ? "采购件" : "制造件");
      row.setProductionCategory("产品系列分类（不能作为物料性质）");
      return row;
    }).toList();
  }

  private static QuoteBomSupplementVersion version(Long id) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(id);
    version.setQuoteProductCode("P-TARGET");
    return version;
  }

  private static QuoteBomSupplementDetail storedDetail(
      int lineNo, int level, String materialCode, String path, String parentCode) {
    QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
    detail.setLineNo(lineNo);
    detail.setLevel(level);
    detail.setMaterialCode(materialCode);
    detail.setMaterialName(materialCode);
    detail.setMaterialSpec("S");
    detail.setMaterialModel("M");
    detail.setDrawingNo("D");
    detail.setSourceCategory(level == 0 ? "制造件" : "采购件");
    detail.setQtyPerParent(BigDecimal.ONE);
    detail.setQtyPerTop(BigDecimal.ONE);
    detail.setUnit("件");
    detail.setSortSeq(lineNo);
    detail.setPath(path);
    detail.setParentCode(parentCode);
    return detail;
  }

  private static TechnicalBomDraftRequest.Node node(
      String id, String parent, String code, String nature) {
    return new TechnicalBomDraftRequest.Node(id, parent, code, code, "S", "M", "D",
        nature, BigDecimal.ONE, "件", 1, true);
  }
}
