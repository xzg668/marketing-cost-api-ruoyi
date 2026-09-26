package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.service.electronicdrawing.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.AssembleCommand;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.HybridBom;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.Node;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("电子图库混合 BOM 合成持久化服务")
class ElectronicDrawingHybridBomServiceTest {
  private static final String FINGERPRINT = "a".repeat(64);

  @Mock private ElectronicDrawingWorkflowContextPort contextPort;
  @Mock private QuoteBomSupplementVersionMapper versionMapper;
  @Mock private QuoteBomSupplementDetailMapper detailMapper;
  @Mock private ElectronicDrawingSourceNodeRepository sourceNodeRepository;
  @Mock private MaterialMasterRawMapper materialMapper;
  @Mock private ElectronicDrawingHybridBomAssembler assembler;

  private ElectronicDrawingHybridBomService service;
  private ElectronicDrawingWorkContext context;
  private QuoteBomSupplementVersion version;
  private ElectronicDrawingSourceNode source;
  private HybridBom hybrid;

  @BeforeEach
  void setUp() {
    service = new ElectronicDrawingHybridBomService(
        contextPort, versionMapper, detailMapper,
        sourceNodeRepository, materialMapper, assembler,
        org.mockito.Mockito.mock(com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingBomSource.class));
    context = context(4);
    version = version();
    source = source(ElectronicDrawingSourceNode.MATCH_AUTO, "P");
    hybrid = hybrid();
  }

  @Test
  void persistsMixedTreeSourcePointersFingerprintAndShanghaiTimeAtomically() {
    arrangeComposition();
    when(detailMapper.selectList(any())).thenReturn(List.of());
    when(detailMapper.insertElectronicDrawingHybridBatch(anyList()))
        .thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size());
    when(versionMapper.updateElectronicDrawingCompositionFingerprint(
        eq(501L), eq(null), eq(FINGERPRINT), eq("COMMERCIAL"), any())).thenReturn(1);
    when(contextPort.touch(eq(context), eq(501L), eq(0L), eq("SYSTEM"), any()))
        .thenReturn(context(5));
    LocalDateTime before = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).minusSeconds(1);

    var result = service.compose(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.idempotent()).isFalse();
    assertThat(result.taskVersion()).isEqualTo(5);
    assertThat(result.quotationLeafCount()).isOne();
    assertThat(result.compositionFingerprint()).isEqualTo(FINGERPRINT);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QuoteBomSupplementDetail>> rows = ArgumentCaptor.forClass(List.class);
    verify(detailMapper).insertElectronicDrawingHybridBatch(rows.capture());
    assertThat(rows.getValue()).hasSize(2);
    assertThat(rows.getValue().getFirst()).satisfies(root -> {
      assertThat(root.getMaterialCode()).isEqualTo("TOP");
      assertThat(root.getLevel()).isZero();
      assertThat(root.getNodeSourceType()).isEqualTo("E_DRAWING");
      assertThat(root.getCreatedAt()).isAfterOrEqualTo(before);
    });
    assertThat(rows.getValue().get(1)).satisfies(leaf -> {
      assertThat(leaf.getMaterialCode()).isEqualTo("P");
      assertThat(leaf.getSourceElectronicNodeId()).isEqualTo(701L);
      assertThat(leaf.getMappingStatus()).isEqualTo(ElectronicDrawingSourceNode.MATCH_AUTO);
      assertThat(leaf.getManualFlag()).isZero();
      assertThat(leaf.getQtyPerTop()).isEqualByComparingTo("2");
      assertThat(leaf.getCreatedAt()).isAfterOrEqualTo(before);
    });
  }

  @Test
  void sameFingerprintAndExactDetailsAreIdempotentWithoutTaskVersionBump() {
    version.setCompositionFingerprint(FINGERPRINT);
    arrangeReadAndAssembly();
    when(detailMapper.selectList(any())).thenReturn(stored(version, hybrid.nodes()));

    var result = service.compose(101L, "COMMERCIAL", "210", "2026-08");

    assertThat(result.idempotent()).isTrue();
    assertThat(result.taskVersion()).isEqualTo(4);
    verify(detailMapper, never()).deleteElectronicDrawingHybridDraft(any());
    verify(detailMapper, never()).insertElectronicDrawingHybridBatch(anyList());
    verify(versionMapper, never()).updateElectronicDrawingCompositionFingerprint(
        any(), any(), any(), any(), any());
    verify(contextPort, never()).touch(any(), any(), any(), any(), any());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.NullSource
  @org.junit.jupiter.params.provider.ValueSource(strings = {"MODEL", "LONG-MODEL-ABCDEFGHIJKLMNOPQRSTUVWXYZ-ABCDEFGHIJKLMNOPQRSTUVWXYZ-0123456789"})
  void quoteWithoutMaterialUsesTheRealRootMatchingSelectedDrawingWithoutChangingQuoteIdentity(String model) {
    String quoteCode = com.sanhua.marketingcost.util.QuoteProductIdentityUtils.resolveCostingCode(null, model, "DRAW-TOP");
    context = new ElectronicDrawingWorkContext(101L, 4, 301L, 501L, 100L, 201L, "QCPT-101", "OA-101",
        quoteCode, null, null, null, model, null, "FULL_BOM", "2026-08", "210", "COMMERCIAL",
        "COMMERCIAL", "210", true, true, "BOM_IN_PROGRESS", ElectronicDrawingWorkflowStage.COMPOSING, null, null, null);
    version.setQuoteProductCode(quoteCode); version.setElectronicDrawingNo("DRAW-TOP");
    version.setCompositionFingerprint(FINGERPRINT);
    arrangeReadAndAssembly();
    when(materialMapper.selectByLatestBatchAndCodes(eq(java.util.Set.of(quoteCode)), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of());
    var root = material("TOP", "制造件"); root.setMaterialModel(model); root.setDrawingNo("DRAW-TOP");
    when(materialMapper.selectByDrawingIdentities(eq(java.util.Set.of("DRAW-TOP")), eq(null), eq("COMMERCIAL"), eq(1000)))
        .thenReturn(List.of(root));
    when(detailMapper.selectList(any())).thenReturn(stored(version, hybrid.nodes()));
    assertThat(service.compose(101L, "COMMERCIAL", "210", "2026-08").idempotent()).isTrue();
    ArgumentCaptor<AssembleCommand> command = ArgumentCaptor.forClass(AssembleCommand.class);
    verify(assembler).assemble(command.capture());
    assertThat(command.getValue().rootMaterial().materialCode()).isEqualTo("TOP");
    assertThat(version.getQuoteProductCode()).isEqualTo(quoteCode);
  }

  @Test
  void sameFingerprintWithMissingDetailsIsDetectedAsCorruption() {
    version.setCompositionFingerprint(FINGERPRINT);
    arrangeReadAndAssembly();
    when(detailMapper.selectList(any())).thenReturn(List.of());

    assertThatThrownBy(() -> service.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.PERSISTENCE_INVALID));
    verify(detailMapper, never()).deleteElectronicDrawingHybridDraft(any());
  }

  @Test
  void incompleteMaterialMappingStopsBeforeMaterialOrU9Read() {
    source = source(ElectronicDrawingSourceNode.MATCH_UNMATCHED, null);
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(501L)).thenReturn(version);
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(List.of(source));

    assertThatThrownBy(() -> service.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.MAPPING_INCOMPLETE));
    verifyNoInteractions(materialMapper, assembler, detailMapper);
    verify(contextPort, never()).touch(any(), any(), any(), any(), any());
  }

  @Test
  void missingOrDuplicateCurrentOrganizationMaterialStopsComposition() {
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(501L)).thenReturn(version);
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(List.of(source));
    when(materialMapper.selectByLatestBatchAndCodes(
        anyCollection(), eq(null), eq("COMMERCIAL"))).thenReturn(List.of(material("TOP", "制造件")));

    assertThatThrownBy(() -> service.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.MAPPING_INCOMPLETE));
    verifyNoInteractions(assembler, detailMapper);
    verify(contextPort, never()).touch(any(), any(), any(), any(), any());
  }

  @Test
  void taskConflictAfterBatchInsertFailsTheTransactionalCommand() {
    arrangeComposition();
    when(detailMapper.selectList(any())).thenReturn(List.of());
    when(detailMapper.insertElectronicDrawingHybridBatch(anyList())).thenReturn(2);
    when(versionMapper.updateElectronicDrawingCompositionFingerprint(
        eq(501L), eq(null), eq(FINGERPRINT), eq("COMMERCIAL"), any())).thenReturn(1);
    when(contextPort.touch(any(), any(), any(), any(), any()))
        .thenThrow(new ElectronicDrawingWorkflowRetryException("conflict"));

    assertThatThrownBy(() -> service.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.TASK_VERSION_CONFLICT));
    verify(detailMapper).insertElectronicDrawingHybridBatch(anyList());
  }

  @Test
  void approvedVersionCanNeverBeRebuilt() {
    version.setVersionStatus("APPROVED");
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(501L)).thenReturn(version);

    assertThatThrownBy(() -> service.compose(101L, "COMMERCIAL", "210", "2026-08"))
        .isInstanceOfSatisfying(ElectronicDrawingHybridBomException.class,
            error -> assertThat(error.code()).isEqualTo(
                ElectronicDrawingHybridBomException.SOURCE_VERSION_INVALID));
    verifyNoInteractions(sourceNodeRepository, materialMapper, assembler, detailMapper);
  }

  private void arrangeComposition() {
    arrangeReadAndAssembly();
  }

  private void arrangeReadAndAssembly() {
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectById(501L)).thenReturn(version);
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(List.of(source));
    when(materialMapper.selectByLatestBatchAndCodes(
        anyCollection(), eq(null), eq("COMMERCIAL")))
        .thenReturn(List.of(material("TOP", "制造件"), material("P", "采购件")));
    when(assembler.assemble(any(AssembleCommand.class))).thenReturn(hybrid);
  }

  private ElectronicDrawingWorkContext context(int revision) {
    return new ElectronicDrawingWorkContext(
        101L, revision, 301L, 501L, 100L, 201L, "QCPT-101", "OA-101",
        "TOP", null, "产品", null, null, null, "FULL_BOM", "2026-08",
        "210", "COMMERCIAL", "COMMERCIAL", "210", true, true,
        "BOM_IN_PROGRESS", ElectronicDrawingWorkflowStage.COMPOSING,
        null, "系统处理中", null);
  }

  private QuoteBomSupplementVersion version() {
    QuoteBomSupplementVersion value = new QuoteBomSupplementVersion();
    value.setId(501L);
    value.setPreparationId(301L);
    value.setTaskNo("QCPT-101");
    value.setOaNo("OA-101");
    value.setOaFormItemId(201L);
    value.setQuoteProductCode("TOP");
    value.setSupplementScope("NON_BARE_FULL_BOM");
    value.setPeriodMonth("2026-08");
    value.setMaterialOrgCode("COMMERCIAL");
    value.setBomSource("ELECTRONIC_DRAWING_EXCEL");
    value.setVersionNo(1);
    value.setVersionStatus("DRAFT");
    value.setActiveFlag(1);
    return value;
  }

  private ElectronicDrawingSourceNode source(String status, String code) {
    ElectronicDrawingSourceNode value = new ElectronicDrawingSourceNode();
    value.setId(701L);
    value.setSupplementVersionId(501L);
    value.setSourceRowNo(2);
    value.setSourceSequence("1");
    value.setDrawingCode("DRAW-P");
    value.setSourceName("采购件");
    value.setQty(new BigDecimal("2"));
    value.setMatchStatus(status);
    value.setResolvedMaterialCode(code);
    if (code != null) {
      value.setResolvedBy("SYSTEM");
      value.setResolvedAt(LocalDateTime.of(2026, 8, 30, 9, 0));
    }
    return value;
  }

  private MaterialMasterRaw material(String code, String nature) {
    MaterialMasterRaw value = new MaterialMasterRaw();
    value.setMaterialCode(code);
    value.setOrganizationCode("COMMERCIAL");
    value.setMaterialName("名称" + code);
    value.setMaterialSpec("规格" + code);
    value.setMaterialModel("型号" + code);
    value.setDrawingNo("图号" + code);
    value.setShapeAttr(nature);
    value.setMainCategoryCode("CAT");
    value.setProductionCategory("生产分类");
    value.setCostElement("成本要素");
    value.setUnit("件");
    value.setActiveFlag(1);
    return value;
  }

  private HybridBom hybrid() {
    Node root = new Node(
        "ROOT:TOP", null, 0, "TOP", "名称TOP", "规格TOP", "型号TOP", "图号TOP",
        "制造件", "CAT", "生产分类", "成本要素", null, null,
        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, "件", "/TOP/", 1,
        "E_DRAWING", null, null, null, null);
    Node leaf = new Node(
        "ED:701", "ROOT:TOP", 1, "P", "名称P", "规格P", "型号P", "DRAW-P",
        "采购件", "CAT", "生产分类", "成本要素", null, null,
        new BigDecimal("2"), new BigDecimal("2"), BigDecimal.ONE, "件",
        "/TOP/ED:701/", 2, "E_DRAWING", 701L, null, null,
        ElectronicDrawingSourceNode.MATCH_AUTO);
    return new HybridBom(List.of(root, leaf), FINGERPRINT, 0, 1, 0, 0);
  }

  private List<QuoteBomSupplementDetail> stored(
      QuoteBomSupplementVersion version, List<Node> nodes) {
    java.util.Map<String, Node> byKey = nodes.stream().collect(
        java.util.stream.Collectors.toMap(Node::nodeKey, java.util.function.Function.identity()));
    java.util.ArrayList<QuoteBomSupplementDetail> result = new java.util.ArrayList<>();
    for (int index = 0; index < nodes.size(); index++) {
      Node node = nodes.get(index);
      Node parent = node.parentNodeKey() == null ? null : byKey.get(node.parentNodeKey());
      QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
      detail.setSupplementVersionId(version.getId());
      detail.setPreparationId(version.getPreparationId());
      detail.setOaNo(version.getOaNo());
      detail.setOaFormItemId(version.getOaFormItemId());
      detail.setQuoteProductCode(version.getQuoteProductCode());
      detail.setSupplementScope(version.getSupplementScope());
      detail.setLineNo(index + 1);
      detail.setLevel(node.level());
      detail.setParentCode(parent == null ? null : parent.materialCode());
      detail.setMaterialCode(node.materialCode());
      detail.setMaterialName(node.materialName());
      detail.setMaterialSpec(node.materialSpec());
      detail.setMaterialModel(node.materialModel());
      detail.setDrawingNo(node.drawingNo());
      detail.setShapeAttr(node.shapeAttr());
      detail.setMainCategoryCode(node.mainCategoryCode());
      detail.setSourceCategory(node.sourceCategory());
      detail.setCostElementCode(node.costElementCode());
      detail.setBomPurpose(node.bomPurpose());
      detail.setBomVersion(node.bomVersion());
      detail.setQtyPerParent(node.qtyPerParent());
      detail.setQtyPerTop(node.qtyPerTop());
      detail.setParentBaseQty(node.parentBaseQty());
      detail.setUnit(node.unit());
      detail.setPath(node.path());
      detail.setSortSeq(node.sortSeq());
      detail.setSourceRawHierarchyId(node.sourceRawHierarchyId());
      detail.setSourceU9BomId(node.sourceU9BomId());
      detail.setNodeSourceType(node.nodeSourceType());
      detail.setSourceElectronicNodeId(node.sourceElectronicNodeId());
      detail.setMappingStatus(node.mappingStatus());
      detail.setManualFlag(ElectronicDrawingSourceNode.MATCH_MANUAL.equals(node.mappingStatus()) ? 1 : 0);
      detail.setRemark(node.nodeSourceType() + ":" + node.nodeKey());
      result.add(detail);
    }
    return List.copyOf(result);
  }
}
