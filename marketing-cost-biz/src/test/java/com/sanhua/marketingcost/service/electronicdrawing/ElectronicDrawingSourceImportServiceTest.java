package com.sanhua.marketingcost.service.electronicdrawing;

import com.sanhua.marketingcost.service.electronicdrawing.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("电子图库源版本导入服务")
class ElectronicDrawingSourceImportServiceTest {
  private static final String DRAWING = "J40AH-40HY-03";
  private static final LocalDateTime ACQUIRED_AT = LocalDateTime.of(2026, 8, 30, 10, 30);

  @Mock private ElectronicDrawingExcelParser parser;
  @Mock private ElectronicDrawingWorkflowContextPort contextPort;
  @Mock private OaFormItemMapper oaFormItemMapper;
  @Mock private QuoteBomPreparationRecordMapper preparationMapper;
  @Mock private QuoteBomSupplementVersionMapper versionMapper;
  @Mock private ElectronicDrawingSourceNodeRepository sourceNodeRepository;

  private ElectronicDrawingSourceImportService service;
  private ElectronicDrawingWorkContext context;
  private OaFormItem quoteItem;
  private QuoteBomPreparationRecord preparation;
  private ElectronicDrawingExcelParseResult parsed;

  @BeforeEach
  void setUp() {
    service = new ElectronicDrawingSourceImportService(
        parser, contextPort, oaFormItemMapper, preparationMapper, versionMapper,
        sourceNodeRepository, new ElectronicDrawingProductLookup(oaFormItemMapper,
            org.mockito.Mockito.mock(com.sanhua.marketingcost.mapper.MaterialMasterRawMapper.class)));
    context = context(null);
    quoteItem = quoteItem();
    preparation = preparation();
    parsed = parsed();
  }

  @Test
  void rejectsRequestAndResponseDrawingMismatchBeforeDatabaseReadOrWrite() {
    assertThatThrownBy(() -> service.importSource(command(), acquired("OTHER-DRAWING")))
        .isInstanceOfSatisfying(ElectronicDrawingSourceImportException.class,
            error -> assertThat(error.getCode())
                .isEqualTo(ElectronicDrawingSourceImportException.DRAWING_MISMATCH));

    verifyNoInteractions(contextPort, preparationMapper, versionMapper, sourceNodeRepository);
  }

  @Test
  void rejectsQuoteDrawingMismatchWithoutCreatingVersionOrNodes() {
    quoteItem.setCustomerDrawing("OTHER-DRAWING");
    arrangeBinding();

    assertThatThrownBy(() -> service.importSource(command(), acquired(DRAWING)))
        .isInstanceOfSatisfying(ElectronicDrawingSourceImportException.class,
            error -> assertThat(error.getCode())
                .isEqualTo(ElectronicDrawingSourceImportException.DRAWING_MISMATCH));

    verifyNoInteractions(preparationMapper, versionMapper, sourceNodeRepository);
  }

  @Test
  void rejectsTaskQuoteAndPreparationBindingMismatchWithoutWriting() {
    arrangeBinding();
    preparation.setMaterialOrganizationCode("OTHER-ORG");
    when(parser.parse(eq("drawing.xlsx"), any())).thenReturn(parsed);
    when(preparationMapper.selectForElectronicDrawingImport(301L)).thenReturn(preparation);

    assertThatThrownBy(() -> service.importSource(command(), acquired(DRAWING)))
        .isInstanceOfSatisfying(ElectronicDrawingSourceImportException.class,
            error -> assertThat(error.getCode())
                .isEqualTo(ElectronicDrawingSourceImportException.BINDING_INVALID));

    verify(versionMapper, never()).insert(any(QuoteBomSupplementVersion.class));
    verifyNoInteractions(sourceNodeRepository);
  }

  @Test
  void rejectsParserIssuesBeforePreparationLockOrWrite() {
    arrangeBinding();
    ElectronicDrawingExcelParseResult invalid = new ElectronicDrawingExcelParseResult(
        "drawing.xlsx", null, List.of(), List.of(
            new ElectronicDrawingExcelParseResult.Issue(
                "PARENT_MISSING", 8, "1.1", "缺少父节点")));
    when(parser.parse(eq("drawing.xlsx"), any())).thenReturn(invalid);

    assertThatThrownBy(() -> service.importSource(command(), acquired(DRAWING)))
        .isInstanceOfSatisfying(ElectronicDrawingSourceImportException.class, error -> {
          assertThat(error.getCode()).isEqualTo(ElectronicDrawingSourceImportException.PARSE_INVALID);
          assertThat(error.getParseIssues()).hasSize(1);
        });

    verifyNoInteractions(preparationMapper, versionMapper, sourceNodeRepository);
  }

  @Test
  void createsBoundDraftAndPersistsOnlyOriginalSourceFields() {
    preparation.setProductType("BARE");
    arrangeValidNewSource(List.of());
    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(501L);
      return 1;
    });
    when(contextPort.attachSourceVersion(eq(context), eq(501L), any()))
        .thenReturn(context(501L));

    ElectronicDrawingSourceImportService.ImportResult result =
        service.importSource(command(), acquired(DRAWING));

    assertThat(result.supplementVersionId()).isEqualTo(501L);
    assertThat(result.versionNo()).isOne();
    assertThat(result.sourceNodeCount()).isOne();
    assertThat(result.reused()).isFalse();
    ArgumentCaptor<QuoteBomSupplementVersion> versionCaptor =
        ArgumentCaptor.forClass(QuoteBomSupplementVersion.class);
    verify(versionMapper).insert(versionCaptor.capture());
    assertThat(versionCaptor.getValue()).satisfies(version -> {
      assertThat(version.getTaskNo()).isEqualTo("QCPT-101");
      assertThat(version.getOaNo()).isEqualTo("OA-101");
      assertThat(version.getOaFormItemId()).isEqualTo(201L);
      assertThat(version.getQuoteProductCode()).isEqualTo("1053100052030");
      assertThat(version.getProductType()).isEqualTo("NON_BARE");
      assertThat(version.getSupplementScope()).isEqualTo("NON_BARE_FULL_BOM");
      assertThat(version.getElectronicDrawingNo()).isEqualTo(DRAWING);
      assertThat(version.getMaterialOrgCode()).isEqualTo("COMMERCIAL");
      assertThat(version.getPeriodMonth()).isEqualTo("2026-08");
      assertThat(version.getSourceAcquiredAt()).isEqualTo(ACQUIRED_AT);
      assertThat(version.getVersionStatus()).isEqualTo("DRAFT");
    });
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ElectronicDrawingSourceNode>> nodesCaptor =
        ArgumentCaptor.forClass(List.class);
    verify(sourceNodeRepository).insertAll(eq(501L), nodesCaptor.capture());
    assertThat(nodesCaptor.getValue()).singleElement().satisfies(node -> {
      assertThat(node.getSourceRowNo()).isEqualTo(2);
      assertThat(node.getSourceSequence()).isEqualTo("1");
      assertThat(node.getParentSourceSequence()).isNull();
      assertThat(node.getDrawingCode()).isEqualTo("S040A-29804");
      assertThat(node.getQty()).isEqualByComparingTo("1");
      assertThat(node.getReferenceWeight()).isEqualByComparingTo("6621.13");
      assertThat(node.getReferenceWeightUnit()).isEqualTo("g");
      assertThat(node.getMatchStatus()).isEqualTo(ElectronicDrawingSourceNode.MATCH_UNMATCHED);
      assertThat(node.getResolvedMaterialCode()).isNull();
    });
  }

  @Test
  void sameShaIsIdempotentAndDoesNotCreateOrReattach() {
    arrangeBinding();
    when(parser.parse(eq("drawing.xlsx"), any())).thenReturn(parsed);
    when(preparationMapper.selectForElectronicDrawingImport(301L)).thenReturn(preparation);
    QuoteBomSupplementVersion existing = existingVersion(501L, 1, "DRAFT");
    context = context(501L);
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(versionMapper.selectList(any())).thenReturn(List.of(existing));
    when(sourceNodeRepository.findByVersionId(501L)).thenReturn(List.of(storedNode()));

    ElectronicDrawingSourceImportService.ImportResult result =
        service.importSource(command(), acquired(DRAWING));

    assertThat(result.reused()).isTrue();
    assertThat(result.currentVersion()).isTrue();
    assertThat(result.sourceNodeCount()).isOne();
    verify(versionMapper, never()).insert(any(QuoteBomSupplementVersion.class));
    verify(sourceNodeRepository, never()).insertAll(any(), any());
    verify(contextPort, never()).attachSourceVersion(any(), any(), any());
  }

  @Test
  void sameFileWithNewWeightUnitCreatesVersionAndPreservesHistoricalSource() {
    var historical = existingVersion(500L, 1, "APPROVED");
    var historicalNode = storedNode();
    historicalNode.setSupplementVersionId(500L);
    historicalNode.setReferenceWeightUnit(null);
    context = context(500L);
    arrangeBinding();
    when(parser.parse(eq("drawing.xlsx"), any())).thenReturn(parsed);
    when(preparationMapper.selectForElectronicDrawingImport(301L)).thenReturn(preparation);
    when(versionMapper.selectList(any())).thenReturn(List.of(historical));
    when(sourceNodeRepository.findByVersionId(500L)).thenReturn(List.of(historicalNode));
    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(501L);
      return 1;
    });
    when(contextPort.attachSourceVersion(eq(context), eq(501L), any())).thenReturn(context(501L));

    var result = service.importSource(command(), acquired(DRAWING));

    assertThat(result.reused()).isFalse();
    assertThat(result.versionNo()).isEqualTo(2);
    assertThat(result.supplementVersionId()).isEqualTo(501L);
    assertThat(historical.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(historicalNode.getReferenceWeightUnit()).isNull();
    assertThat(historicalNode.getReferenceWeight()).isEqualByComparingTo("6621.13");
    verify(versionMapper, never()).updateById(any(QuoteBomSupplementVersion.class));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<ElectronicDrawingSourceNode>> nodes = ArgumentCaptor.forClass(List.class);
    verify(sourceNodeRepository).insertAll(eq(501L), nodes.capture());
    assertThat(nodes.getValue()).singleElement().satisfies(node -> {
      assertThat(node.getReferenceWeight()).isEqualByComparingTo("6621.13");
      assertThat(node.getReferenceWeightUnit()).isEqualTo("g");
    });
  }

  @Test
  void changedShaCreatesNextVersionWithoutOverwritingPublishedVersion() {
    QuoteBomSupplementVersion approved = existingVersion(500L, 1, "APPROVED");
    context = context(500L);
    arrangeValidNewSource(List.of(approved));
    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(501L);
      return 1;
    });
    when(contextPort.attachSourceVersion(eq(context), eq(501L), any()))
        .thenReturn(context(501L));

    ElectronicDrawingSourceImportService.ImportResult result =
        service.importSource(command(), acquired(DRAWING));

    assertThat(result.versionNo()).isEqualTo(2);
    assertThat(approved.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(approved.getActiveFlag()).isOne();
    verify(versionMapper, never()).updateById(any(QuoteBomSupplementVersion.class));
  }

  @Test
  void taskVersionConflictFailsAfterSourceWriteSoOuterTransactionCanRollBack() {
    arrangeValidNewSource(List.of());
    when(versionMapper.insert(any(QuoteBomSupplementVersion.class))).thenAnswer(invocation -> {
      QuoteBomSupplementVersion version = invocation.getArgument(0);
      version.setId(501L);
      return 1;
    });
    when(contextPort.attachSourceVersion(eq(context), eq(501L), any()))
        .thenThrow(new ElectronicDrawingWorkflowRetryException("conflict"));

    assertThatThrownBy(() -> service.importSource(command(), acquired(DRAWING)))
        .isInstanceOfSatisfying(ElectronicDrawingSourceImportException.class,
            error -> assertThat(error.getCode())
                .isEqualTo(ElectronicDrawingSourceImportException.TASK_VERSION_CONFLICT));
    verify(sourceNodeRepository).insertAll(eq(501L), any());
  }

  private void arrangeBinding() {
    when(contextPort.load(101L, "COMMERCIAL", "210", "2026-08")).thenReturn(context);
    when(oaFormItemMapper.selectById(201L)).thenReturn(quoteItem);
  }

  private void arrangeValidNewSource(List<QuoteBomSupplementVersion> latestVersions) {
    arrangeBinding();
    when(parser.parse(eq("drawing.xlsx"), any())).thenReturn(parsed);
    when(preparationMapper.selectForElectronicDrawingImport(301L)).thenReturn(preparation);
    when(versionMapper.selectList(any())).thenReturn(List.of(), latestVersions);
  }

  private ElectronicDrawingSourceImportService.ImportCommand command() {
    return new ElectronicDrawingSourceImportService.ImportCommand(
        101L, "COMMERCIAL", "210", DRAWING, "2026-08");
  }

  private ElectronicDrawingExcelAcquisitionPort.AcquiredExcel acquired(String responseDrawing) {
    byte[] bytes = "valid-test-content".getBytes(StandardCharsets.UTF_8);
    return new ElectronicDrawingExcelAcquisitionPort.AcquiredExcel(
        bytes, "drawing.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        bytes.length, sha256(bytes), responseDrawing, "request-1", "MOCK", ACQUIRED_AT);
  }

  private ElectronicDrawingWorkContext context(Long sourceVersionId) {
    return new ElectronicDrawingWorkContext(
        101L, 4, 301L, sourceVersionId, 200L, 201L, "QCPT-101", "OA-101",
        "1053100052030", null, "产品", null, null, null, "FULL_BOM", "2026-08",
        "210", "COMMERCIAL", "COMMERCIAL", "210", true, true,
        "BOM_IN_PROGRESS", ElectronicDrawingWorkflowStage.PARSING,
        null, "系统处理中", null);
  }

  private OaFormItem quoteItem() {
    OaFormItem value = new OaFormItem();
    value.setId(201L);
    value.setOaFormId(200L);
    value.setMaterialNo("1053100052030");
    value.setCustomerDrawing(DRAWING);
    value.setBusinessUnitType("COMMERCIAL");
    return value;
  }

  private QuoteBomPreparationRecord preparation() {
    QuoteBomPreparationRecord value = new QuoteBomPreparationRecord();
    value.setId(301L);
    value.setOaFormId(200L);
    value.setOaFormItemId(201L);
    value.setOaNo("OA-101");
    value.setQuoteProductCode("1053100052030");
    value.setMaterialOrganizationCode("COMMERCIAL");
    value.setProductType("NON_BARE");
    value.setCostPeriodMonth("2026-08");
    value.setActiveFlag(1);
    return value;
  }

  private ElectronicDrawingExcelParseResult parsed() {
    return new ElectronicDrawingExcelParseResult(
        "drawing.xlsx", "Sheet", List.of(new ElectronicDrawingExcelParseResult.SourceNode(
            "1", null, 1, "S040A-29804", "040A板换部件", "316L+T2",
            "/", "/", BigDecimal.ONE, new BigDecimal("6621.13"), "g", "自制", 2)), List.of());
  }

  private ElectronicDrawingSourceNode storedNode() {
    ElectronicDrawingExcelParseResult.SourceNode source = parsed.nodes().getFirst();
    ElectronicDrawingSourceNode node = new ElectronicDrawingSourceNode();
    node.setSupplementVersionId(501L);
    node.setSourceRowNo(source.sourceRowNumber());
    node.setSourceSequence(source.sourceSequence());
    node.setParentSourceSequence(source.parentSourceSequence());
    node.setDrawingCode(source.drawingCode());
    node.setSourceName(source.sourceName());
    node.setQty(source.quantity());
    node.setMaterial(source.sourceMaterial());
    node.setImportanceClass(source.importanceClass());
    node.setHsfRiskClass(source.hsfRiskClass());
    node.setReferenceWeight(source.referenceWeight());
    node.setReferenceWeightUnit(source.referenceWeightUnit());
    node.setSourceRemark(source.remark());
    node.setMatchStatus(ElectronicDrawingSourceNode.MATCH_UNMATCHED);
    return node;
  }

  private QuoteBomSupplementVersion existingVersion(Long id, int versionNo, String status) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(id);
    version.setPreparationId(301L);
    version.setVersionNo(versionNo);
    version.setVersionStatus(status);
    version.setActiveFlag(1);
    version.setSourceFileSha256(acquired(DRAWING).sha256());
    return version;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }
}
