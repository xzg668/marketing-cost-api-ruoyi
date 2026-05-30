package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSaveResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest.PackageLineSelection;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest.PackageReferenceSelection;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementCollaborationSubmitRequest.SupplementLine;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureLineDto;
import com.sanhua.marketingcost.dto.quotebom.PackageComponentStructureReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTechnicianTaskResult;
import com.sanhua.marketingcost.entity.BomSupplementTask;
import com.sanhua.marketingcost.entity.BomSupplementTodo;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.mapper.BomSupplementTaskMapper;
import com.sanhua.marketingcost.mapper.BomSupplementTodoMapper;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import com.sanhua.marketingcost.service.PackageComponentStructureReadService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuoteBomSupplementCollaborationServiceImplTest {

  private QuoteProductBomPreparationService preparationService;
  private CollaborationTokenService tokenService;
  private PackageComponentStructureReadService packageReadService;
  private BomSupplementTaskMapper taskMapper;
  private BomSupplementTodoMapper todoMapper;
  private QuoteBomPreparationRecordMapper preparationRecordMapper;
  private QuoteBomStatusMapper statusMapper;
  private QuoteBomSupplementVersionMapper supplementVersionMapper;
  private QuoteBomSupplementDetailMapper supplementDetailMapper;
  private QuoteBomPackageReferenceMapper packageReferenceMapper;
  private QuoteBomPackageReferenceDetailMapper packageReferenceDetailMapper;
  private BusinessChangeLogMapper changeLogMapper;
  private QuoteBomSupplementCollaborationServiceImpl service;

  @BeforeEach
  void setUp() {
    preparationService = mock(QuoteProductBomPreparationService.class);
    tokenService = mock(CollaborationTokenService.class);
    packageReadService = mock(PackageComponentStructureReadService.class);
    taskMapper = mock(BomSupplementTaskMapper.class);
    todoMapper = mock(BomSupplementTodoMapper.class);
    preparationRecordMapper = mock(QuoteBomPreparationRecordMapper.class);
    statusMapper = mock(QuoteBomStatusMapper.class);
    supplementVersionMapper = mock(QuoteBomSupplementVersionMapper.class);
    supplementDetailMapper = mock(QuoteBomSupplementDetailMapper.class);
    packageReferenceMapper = mock(QuoteBomPackageReferenceMapper.class);
    packageReferenceDetailMapper = mock(QuoteBomPackageReferenceDetailMapper.class);
    changeLogMapper = mock(BusinessChangeLogMapper.class);
    service =
        new QuoteBomSupplementCollaborationServiceImpl(
            preparationService,
            tokenService,
            packageReadService,
            taskMapper,
            todoMapper,
            preparationRecordMapper,
            statusMapper,
            supplementVersionMapper,
            supplementDetailMapper,
            packageReferenceMapper,
            packageReferenceDetailMapper,
            changeLogMapper,
            new ObjectMapper());
    assignIds();
  }

  @Test
  void createTaskApiGeneratesScopedTokenAndMockTodoUrl() {
    QuoteProductBomPreparationPreview preview =
        preview(10L, 501L, "NON_BARE", List.of("NON_BARE_FULL_BOM"));
    when(preparationService.createTechnicianTask(List.of(10L)))
        .thenReturn(new QuoteProductBomTechnicianTaskResult(1, 1, 0, 0, List.of(preview), List.of()));
    when(taskMapper.selectById(501L)).thenReturn(task(501L, "QBP-501", "TODO_PENDING"));
    when(tokenService.generateToken(any(), any(), any(), any(Integer.class))).thenReturn(token("tok-501", 501L, 10L));
    when(todoMapper.selectOne(any())).thenReturn(null);

    QuoteProductBomTaskCreateResponse response =
        service.createTasks(new QuoteProductBomTaskCreateRequest(List.of(10L), 48));

    assertThat(response.createdTaskCount()).isEqualTo(1);
    assertThat(response.tasks()).hasSize(1);
    assertThat(response.tasks().get(0).collaborationUrl()).isEqualTo("/collaborate/bom-supplement?token=tok-501");
    verify(todoMapper).insert(any(BomSupplementTodo.class));
  }

  @Test
  void tokenCanOnlyAccessItsBoundTask() {
    when(tokenService.validateToken("tok-501")).thenReturn(token("tok-501", 501L, 10L));

    assertThatThrownBy(() -> service.submit("tok-501", 999L, nonBareRequest()))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("不能访问该任务");
  }

  @Test
  void submitNonBareSupplementCreatesVersionAndMovesToFinanceReview() {
    stubTaskAndRecord(record("NON_BARE", 0));
    when(supplementVersionMapper.selectOne(any())).thenReturn(null);
    when(statusMapper.selectById(101L)).thenReturn(status());

    BomSupplementCollaborationSaveResponse response = service.submit("tok-501", 501L, nonBareRequest());

    assertThat(response.taskStatus()).isEqualTo("FINANCE_REVIEW");
    assertThat(response.preparationStatus()).isEqualTo("TECH_SUBMITTED");
    assertThat(response.reviewStatus()).isEqualTo("PENDING");
    assertThat(response.savedSupplementLineCount()).isEqualTo(1);
    ArgumentCaptor<QuoteBomSupplementVersion> versionCaptor =
        ArgumentCaptor.forClass(QuoteBomSupplementVersion.class);
    verify(supplementVersionMapper).insert(versionCaptor.capture());
    assertThat(versionCaptor.getValue().getSupplementScope()).isEqualTo("NON_BARE_FULL_BOM");
    assertThat(versionCaptor.getValue().getVersionStatus()).isEqualTo("SUBMITTED");
    verify(supplementDetailMapper).insert(any(QuoteBomSupplementDetail.class));
    verify(taskMapper).updateById(any(BomSupplementTask.class));
    verify(preparationRecordMapper).updateById(any(QuoteBomPreparationRecord.class));
    verify(statusMapper).updateById(any(QuoteBomStatus.class));
  }

  @Test
  void submitBareBodyAndPackageReferenceWritesChangeLogForAdjustedFields() {
    stubTaskAndRecord(record("BARE", 1));
    when(supplementVersionMapper.selectOne(any())).thenReturn(null);
    when(packageReferenceMapper.selectOne(any())).thenReturn(null);
    when(statusMapper.selectById(101L)).thenReturn(status());
    when(packageReadService.readByReference("REF-001", "REF-001", "2026-05"))
        .thenReturn(packageFound("REF-001"));

    BomSupplementCollaborationSaveResponse response = service.submit("tok-501", 501L, bareRequest(BigDecimal.TEN));

    assertThat(response.savedSupplementLineCount()).isEqualTo(1);
    assertThat(response.savedPackageLineCount()).isEqualTo(1);
    assertThat(response.insertedChangeLogCount()).isEqualTo(1);
    ArgumentCaptor<BusinessChangeLog> logCaptor = ArgumentCaptor.forClass(BusinessChangeLog.class);
    verify(changeLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getFieldName()).isEqualTo("adjustedChildQtyPerTop");
    assertThat(logCaptor.getValue().getBeforeValue()).isEqualTo("1");
    assertThat(logCaptor.getValue().getAfterValue()).isEqualTo("10");
    verify(packageReferenceDetailMapper).insert(any(QuoteBomPackageReferenceDetail.class));
  }

  @Test
  void submitPackageReferenceDoesNotLogUnchangedAdjustedFields() {
    stubTaskAndRecord(record("BARE", 1));
    when(supplementVersionMapper.selectOne(any())).thenReturn(null);
    when(packageReferenceMapper.selectOne(any())).thenReturn(null);
    when(statusMapper.selectById(101L)).thenReturn(status());
    when(packageReadService.readByReference("REF-001", "REF-001", "2026-05"))
        .thenReturn(packageFound("REF-001"));

    BomSupplementCollaborationSaveResponse response = service.submit("tok-501", 501L, bareRequest(BigDecimal.ONE));

    assertThat(response.savedPackageLineCount()).isEqualTo(1);
    assertThat(response.insertedChangeLogCount()).isZero();
    verify(changeLogMapper, never()).insert(any(BusinessChangeLog.class));
  }

  @Test
  void reviewApprovesSubmittedTaskWithoutBuildingCostingRows() {
    QuoteBomPreparationRecord record = record("BARE", 1);
    record.setPreparationStatus("TECH_SUBMITTED");
    record.setReviewStatus("PENDING");
    BomSupplementTask task = task(501L, "QBP-501", "FINANCE_REVIEW");
    QuoteBomSupplementVersion version = version("SUBMITTED");
    QuoteBomPackageReference reference = reference("SUBMITTED");
    when(taskMapper.selectById(501L)).thenReturn(task);
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
    when(supplementVersionMapper.selectOne(any())).thenReturn(version);
    when(packageReferenceMapper.selectOne(any())).thenReturn(reference);
    when(statusMapper.selectById(101L)).thenReturn(status());

    var response =
        service.review(
            501L,
            new com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewRequest(
                2001L, "财务A", "确认完整 BOM"));

    assertThat(response.taskStatus()).isEqualTo("APPROVED");
    assertThat(response.preparationStatus()).isEqualTo("READY");
    assertThat(response.reviewStatus()).isEqualTo("APPROVED");
    assertThat(version.getVersionStatus()).isEqualTo("APPROVED");
    assertThat(version.getReuseValidUntil()).isNotNull();
    assertThat(reference.getReferenceStatus()).isEqualTo("APPROVED");
    assertThat(record.getCostingBuildBatchId()).isNull();
    verify(supplementVersionMapper).updateById(version);
    verify(packageReferenceMapper).updateById(reference);
  }

  @Test
  void returnForRevisionAllowsTechnicianToSubmitAgain() {
    QuoteBomPreparationRecord record = record("NON_BARE", 0);
    record.setPreparationStatus("TECH_SUBMITTED");
    record.setReviewStatus("PENDING");
    BomSupplementTask task = task(501L, "QBP-501", "FINANCE_REVIEW");
    QuoteBomSupplementVersion version = version("SUBMITTED");
    when(taskMapper.selectById(501L)).thenReturn(task);
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
    when(supplementVersionMapper.selectOne(any())).thenReturn(version);
    when(packageReferenceMapper.selectOne(any())).thenReturn(null);
    when(statusMapper.selectById(101L)).thenReturn(status());

    var returned =
        service.returnForRevision(
            501L,
            new com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewRequest(
                2001L, "财务A", "请补齐用量"));

    assertThat(returned.taskStatus()).isEqualTo("IN_PROGRESS");
    assertThat(returned.reviewStatus()).isEqualTo("RETURNED");
    assertThat(version.getVersionStatus()).isEqualTo("RETURNED");

    when(tokenService.validateToken("tok-501"))
        .thenReturn(token("tok-501", 501L, 10L, record.getQuoteProductCode()));
    var resubmitted = service.submit("tok-501", 501L, nonBareRequest());

    assertThat(resubmitted.taskStatus()).isEqualTo("FINANCE_REVIEW");
    assertThat(resubmitted.reviewStatus()).isEqualTo("PENDING");
    assertThat(version.getVersionStatus()).isEqualTo("SUBMITTED");
  }

  private void stubTaskAndRecord(QuoteBomPreparationRecord record) {
    when(tokenService.validateToken("tok-501"))
        .thenReturn(token("tok-501", 501L, 10L, record.getQuoteProductCode()));
    when(taskMapper.selectById(501L)).thenReturn(task(501L, "QBP-501", "TODO_PENDING"));
    when(preparationRecordMapper.selectOne(any())).thenReturn(record);
  }

  private void assignIds() {
    doAnswer(
            invocation -> {
              invocation.getArgument(0, QuoteBomSupplementVersion.class).setId(601L);
              return 1;
            })
        .when(supplementVersionMapper)
        .insert(any(QuoteBomSupplementVersion.class));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, QuoteBomSupplementDetail.class).setId(701L);
              return 1;
            })
        .when(supplementDetailMapper)
        .insert(any(QuoteBomSupplementDetail.class));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, QuoteBomPackageReference.class).setId(801L);
              return 1;
            })
        .when(packageReferenceMapper)
        .insert(any(QuoteBomPackageReference.class));
    doAnswer(
            invocation -> {
              invocation.getArgument(0, QuoteBomPackageReferenceDetail.class).setId(901L);
              return 1;
            })
        .when(packageReferenceDetailMapper)
        .insert(any(QuoteBomPackageReferenceDetail.class));
  }

  private BomSupplementCollaborationSubmitRequest nonBareRequest() {
    return new BomSupplementCollaborationSubmitRequest(
        1001L,
        "技术员A",
        "补录提交",
        List.of(
            new SupplementLine(
                1,
                1,
                "FIN-001",
                "MAT-001",
                "材料",
                "规格",
                "型号",
                "图号",
                "实体",
                "1201",
                "U9",
                "CE",
                "PURPOSE",
                "V1",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                "PCS",
                "/FIN-001/MAT-001/",
                1,
                "备注")),
        null);
  }

  private BomSupplementCollaborationSubmitRequest bareRequest(BigDecimal adjustedChildQtyPerTop) {
    PackageLineSelection line =
        new PackageLineSelection(901L, 902L, 1, true, null, null, null, null, adjustedChildQtyPerTop, null, null);
    PackageReferenceSelection reference =
        new PackageReferenceSelection("REF-001", "REF-001", "2026-05", List.of(line), "参考包装");
    BomSupplementCollaborationSubmitRequest body = nonBareRequest();
    return new BomSupplementCollaborationSubmitRequest(
        body.submittedBy(), body.submittedByName(), body.remark(), body.supplementLines(), reference);
  }

  private QuoteBomPreparationRecord record(String productType, int needPackage) {
    QuoteBomPreparationRecord record = new QuoteBomPreparationRecord();
    record.setId(201L);
    record.setQuoteBomStatusId(101L);
    record.setOaFormId(20L);
    record.setOaFormItemId(10L);
    record.setOaNo("OA-001");
    record.setQuoteProductCode("BARE".equals(productType) ? "BARE-001" : "FIN-001");
    record.setProductType(productType);
    record.setBareProductCode("BARE".equals(productType) ? "BARE-001" : null);
    record.setNeedPackage(needPackage);
    record.setCostPeriodMonth("2026-05");
    record.setPreparationStatus("NEED_TECH");
    record.setReviewStatus("NOT_SUBMITTED");
    record.setTaskId(501L);
    record.setActiveFlag(1);
    return record;
  }

  private QuoteBomStatus status() {
    QuoteBomStatus status = new QuoteBomStatus();
    status.setId(101L);
    status.setOaFormItemId(10L);
    status.setBomStatus("ENTRY_IN_PROGRESS");
    return status;
  }

  private BomSupplementTask task(Long id, String taskNo, String status) {
    BomSupplementTask task = new BomSupplementTask();
    task.setId(id);
    task.setTaskNo(taskNo);
    task.setProductCode("FIN-001");
    task.setTaskStatus(status);
    task.setMissingBomScope("NON_BARE_FULL_BOM");
    task.setTechnicianName("技术员A");
    return task;
  }

  private QuoteBomSupplementVersion version(String status) {
    QuoteBomSupplementVersion version = new QuoteBomSupplementVersion();
    version.setId(601L);
    version.setTaskId(501L);
    version.setVersionStatus(status);
    version.setActiveFlag(1);
    return version;
  }

  private QuoteBomPackageReference reference(String status) {
    QuoteBomPackageReference reference = new QuoteBomPackageReference();
    reference.setId(801L);
    reference.setTaskId(501L);
    reference.setReferenceFinishedCode("REF-001");
    reference.setSourceTopProductCode("REF-001");
    reference.setReferenceStatus(status);
    reference.setSelectedLineCount(1);
    reference.setActiveFlag(1);
    return reference;
  }

  private LpCollaborationToken token(String token, Long taskId, Long itemId) {
    return token(token, taskId, itemId, "FIN-001");
  }

  private LpCollaborationToken token(String token, Long taskId, Long itemId, String productCode) {
    LpCollaborationToken record = new LpCollaborationToken();
    record.setTokenId(301L);
    record.setToken(token);
    record.setTokenType("bom-supplement");
    record.setExpireTime(LocalDateTime.now().plusHours(72));
    record.setStatus("0");
    record.setRemark(
        "{\"taskId\":"
            + taskId
            + ",\"oaFormItemId\":"
            + itemId
            + ",\"oaNo\":\"OA-001\",\"quoteProductCode\":\""
            + productCode
            + "\"}");
    return record;
  }

  private QuoteProductBomPreparationPreview preview(
      Long itemId, Long taskId, String productType, List<String> scopes) {
    return new QuoteProductBomPreparationPreview(
        201L,
        101L,
        20L,
        itemId,
        "OA-001",
        "FIN-001",
        productType,
        null,
        false,
        "2026-05",
        "NEED_TECH",
        "NOT_SUBMITTED",
        false,
        true,
        false,
        null,
        false,
        0,
        null,
        null,
        false,
        0,
        taskId,
        null,
        null,
        null,
        null,
        LocalDate.now().plusMonths(6),
        scopes,
        List.of("缺 BOM"),
        null,
        List.of(),
        List.of());
  }

  private PackageComponentStructureReadResult packageFound(String referenceCode) {
    return new PackageComponentStructureReadResult(
        referenceCode, referenceCode, "2026-05", null, true, List.of(packageLine(referenceCode)), List.of());
  }

  private PackageComponentStructureLineDto packageLine(String referenceCode) {
    return new PackageComponentStructureLineDto(
        901L,
        902L,
        referenceCode,
        referenceCode,
        "2026-05",
        1,
        "PKG-PARENT",
        "包装父件",
        "规格",
        "型号",
        "图号",
        "虚拟",
        "15155",
        "PCS",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        1L,
        "/" + referenceCode + "/PKG-PARENT/",
        "PKG-CHILD",
        "纸箱",
        "规格",
        "型号",
        "图号",
        "实体",
        "15156",
        "PCS",
        BigDecimal.ONE,
        BigDecimal.ONE,
        BigDecimal.ONE,
        2L,
        "PKG-PARENT",
        "/" + referenceCode + "/PKG-PARENT/PKG-CHILD/",
        1);
  }
}
