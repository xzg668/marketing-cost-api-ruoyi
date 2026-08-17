package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationBatchStartResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartRequest;
import com.sanhua.marketingcost.dto.collaboration.QuoteCollaborationStartResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.dto.collaboration.QuoteTechnicianCandidatesResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-08 报价协作发起应用服务")
class QuoteRequestCollaborationApplicationServiceTest {
  private QuoteItemCollaborationProjectionService projectionService;
  private QuoteCollaborationTaskServiceImpl taskService;
  private QuoteCollaborationScanService scanService;
  private CollaborationTechnicianResolver technicianResolver;
  private OaFormMapper formMapper;
  private OaFormItemMapper itemMapper;
  private QuoteRequestCollaborationApplicationService service;

  @BeforeEach
  void setUp() {
    projectionService = mock(QuoteItemCollaborationProjectionService.class);
    taskService = mock(QuoteCollaborationTaskServiceImpl.class);
    scanService = mock(QuoteCollaborationScanService.class);
    technicianResolver = mock(CollaborationTechnicianResolver.class);
    formMapper = mock(OaFormMapper.class);
    itemMapper = mock(OaFormItemMapper.class);
    CollaborationCurrentActorProvider actorProvider = mock(CollaborationCurrentActorProvider.class);
    when(actorProvider.current()).thenReturn(new CollaborationActor(901L, "报价员"));
    service = new QuoteRequestCollaborationApplicationService(projectionService, taskService,
        scanService, technicianResolver, actorProvider, formMapper, itemMapper);
  }

  @Test
  void startsWithServerResolvedTechnicianAndReturnsLatestProjection() {
    QuoteItemCollaborationResponse before = projection(11L, "START_BOM_SUPPLEMENT", "V1", 601L, "王工");
    QuoteItemCollaborationResponse after = projection(11L, "VIEW_SUPPLEMENT", "V2", 601L, "王工");
    when(projectionService.project("OA-08", 11L)).thenReturn(before, after);
    when(taskService.start(any())).thenReturn(new QuoteCollaborationStartResult(
        CollaborationStartAction.CREATED, 21L, "QCPT-21", 31L, "WAIT_TECH",
        601L, "王工", CollaborationNextAction.SUPPLEMENT_BOM, 1, false, "已发起"));

    QuoteCollaborationStartResponse response = service.start("OA-08", 11L,
        new QuoteCollaborationStartRequest(null, "V1"));

    assertThat(response.resultAction()).isEqualTo("CREATED");
    assertThat(response.item().projectionVersion()).isEqualTo("V2");
    verify(taskService).start(org.mockito.ArgumentMatchers.argThat(command ->
        Long.valueOf(601L).equals(command.technicianUserId()) && "王工".equals(command.technicianName())));
  }

  @Test
  void rejectsChangedProjectionBeforeWriting() {
    when(projectionService.project("OA-08", 11L)).thenReturn(
        projection(11L, "START_BOM_SUPPLEMENT", "V2", 601L, "王工"));

    assertThatThrownBy(() -> service.start("OA-08", 11L,
        new QuoteCollaborationStartRequest(null, "V1")))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(CollaborationDomainErrorCode.TASK_VERSION_CONFLICT));
    verify(taskService, never()).start(any());
  }

  @Test
  void batchKeepsSuccessfulRowsWhenAnotherRowFails() {
    QuoteItemCollaborationResponse before = projection(11L, "START_PRICE_SUPPLEMENT", "V1", 601L, "王工");
    QuoteItemCollaborationResponse after = projection(11L, "VIEW_SUPPLEMENT", "V2", 601L, "王工");
    when(projectionService.project("OA-08", 11L)).thenReturn(before, after);
    when(projectionService.project("OA-08", 12L)).thenThrow(new IllegalArgumentException("产品不存在"));
    when(taskService.start(any())).thenReturn(new QuoteCollaborationStartResult(
        CollaborationStartAction.CREATED, 21L, "QCPT-21", 31L, "WAIT_TECH",
        601L, "王工", CollaborationNextAction.SUPPLEMENT_PRICE, 1, false, "已发起"));

    QuoteCollaborationBatchStartResponse response = service.batchStart("OA-08",
        new QuoteCollaborationBatchStartRequest(List.of(
            new QuoteCollaborationBatchStartRequest.Item(11L, null, "V1"),
            new QuoteCollaborationBatchStartRequest.Item(12L, null, "V1"))));

    assertThat(response.successCount()).isEqualTo(1);
    assertThat(response.failureCount()).isEqualTo(1);
    assertThat(response.results()).extracting("success").containsExactly(true, false);
    assertThat(response.results().get(1).message()).isEqualTo("产品不存在");
  }

  @Test
  void manualAssignmentValidatesSelectedUserAndCreatesTask() {
    OaForm form = form();
    OaFormItem item = item();
    com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult scan = scan();
    when(formMapper.selectList(any())).thenReturn(List.of(form));
    when(itemMapper.selectById(11L)).thenReturn(item);
    when(scanService.scanQuoteItem(11L)).thenReturn(scan);
    when(projectionService.project("OA-08", 11L)).thenReturn(
        projection(11L, "ASSIGN_TECHNICIAN", "V1", null, null),
        projection(11L, "VIEW_SUPPLEMENT", "V2", 602L, "李工"));
    when(technicianResolver.resolve(form, item, "COMMERCIAL", 602L)).thenReturn(
        new CollaborationTechnicianResolver.Resolution(602L, "李工", null));
    when(taskService.start(any())).thenReturn(new QuoteCollaborationStartResult(
        CollaborationStartAction.CREATED, 22L, "QCPT-22", 32L, "WAIT_TECH",
        602L, "李工", CollaborationNextAction.SUPPLEMENT_BOM, 1, false, "已发起"));

    QuoteCollaborationStartResponse response = service.start("OA-08", 11L,
        new QuoteCollaborationStartRequest(602L, "V1"));

    assertThat(response.item().assigneeUserId()).isEqualTo(602L);
    verify(taskService).start(org.mockito.ArgumentMatchers.argThat(command ->
        Long.valueOf(602L).equals(command.technicianUserId())
            && "李工".equals(command.technicianName())));
  }

  @Test
  void manualAssignmentRequiresTechnician() {
    when(projectionService.project("OA-08", 11L)).thenReturn(
        projection(11L, "ASSIGN_TECHNICIAN", "V1", null, null));

    assertThatThrownBy(() -> service.start("OA-08", 11L,
        new QuoteCollaborationStartRequest(null, "V1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("请选择技术负责人后再发起补录");
    verify(taskService, never()).start(any());
  }

  @Test
  void returnsCandidatesOnlyForAssignmentState() {
    OaForm form = form();
    OaFormItem item = item();
    com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult scan = scan();
    when(projectionService.project("OA-08", 11L)).thenReturn(
        projection(11L, "ASSIGN_TECHNICIAN", "V1", null, null));
    when(formMapper.selectList(any())).thenReturn(List.of(form));
    when(itemMapper.selectById(11L)).thenReturn(item);
    when(scanService.scanQuoteItem(11L)).thenReturn(scan);
    when(technicianResolver.candidates(form, item, "COMMERCIAL")).thenReturn(
        new CollaborationTechnicianResolver.CandidateList("RECOMMENDED", "已推荐", List.of(
            new CollaborationTechnicianResolver.Candidate(
                602L, "李工", "lig", "OA-602", "T602", true,
                "COMM-01", "商用技术"))));

    QuoteTechnicianCandidatesResponse response = service.technicianCandidates("OA-08", 11L);

    assertThat(response.matchStatus()).isEqualTo("RECOMMENDED");
    assertThat(response.candidates()).singleElement().satisfies(candidate -> {
      assertThat(candidate.userId()).isEqualTo(602L);
      assertThat(candidate.recommended()).isTrue();
    });
  }

  @Test
  void batchAssignsOnlyUnresolvedRowsAndPreservesServerResolvedAssignee() {
    OaForm form = form();
    OaFormItem unresolvedItem = item();
    var scan = scan();
    when(formMapper.selectList(any())).thenReturn(List.of(form));
    when(itemMapper.selectById(11L)).thenReturn(unresolvedItem);
    when(scanService.scanQuoteItem(11L)).thenReturn(scan);
    when(technicianResolver.resolve(form, unresolvedItem, "COMMERCIAL", 602L)).thenReturn(
        new CollaborationTechnicianResolver.Resolution(602L, "李工", null));
    when(projectionService.project("OA-08", 11L)).thenReturn(
        projection(11L, "ASSIGN_TECHNICIAN", "V11", null, null),
        projection(11L, "VIEW_SUPPLEMENT", "V12", 602L, "李工"));
    when(projectionService.project("OA-08", 12L)).thenReturn(
        projection(12L, "START_BOM_SUPPLEMENT", "V21", 601L, "王工"),
        projection(12L, "VIEW_SUPPLEMENT", "V22", 601L, "王工"));
    when(taskService.start(any())).thenReturn(new QuoteCollaborationStartResult(
        CollaborationStartAction.CREATED, 22L, "QCPT-22", 32L, "WAIT_TECH",
        602L, "技术员", CollaborationNextAction.SUPPLEMENT_BOM, 1, false, "已发起"));

    QuoteCollaborationBatchStartResponse response = service.batchStart("OA-08",
        new QuoteCollaborationBatchStartRequest(List.of(
            new QuoteCollaborationBatchStartRequest.Item(11L, 602L, "V11"),
            new QuoteCollaborationBatchStartRequest.Item(12L, null, "V21"))));

    assertThat(response.successCount()).isEqualTo(2);
    verify(taskService).start(org.mockito.ArgumentMatchers.argThat(command ->
        Long.valueOf(602L).equals(command.technicianUserId())
            && "李工".equals(command.technicianName())));
    verify(taskService).start(org.mockito.ArgumentMatchers.argThat(command ->
        Long.valueOf(601L).equals(command.technicianUserId())
            && "王工".equals(command.technicianName())));
  }

  private QuoteItemCollaborationResponse projection(Long itemId, String action, String version,
      Long assigneeId, String assigneeName) {
    return new QuoteItemCollaborationResponse(itemId, "NO_BOM", "无BOM", "PENDING_BOM",
        "待BOM补齐后检查", 0, assigneeId, assigneeName, "MISSING_BOM", "待补BOM",
        null, null, null, null, action, action, true,
        action.startsWith("START_") || action.startsWith("LINK_") || action.startsWith("APPLY_"),
        version, "需协作");
  }

  private OaForm form() {
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-08");
    return form;
  }

  private OaFormItem item() {
    OaFormItem item = new OaFormItem();
    item.setId(11L);
    item.setOaFormId(1L);
    return item;
  }

  private com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult scan() {
    return new com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult(
        11L, "OA-08", "2026-08", "P-1", "COMMERCIAL", "210", "210",
        CollaborationCodes.ProductForm.NORMAL,
        com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction.CREATE_COLLABORATION,
        CollaborationCodes.PrimaryScope.FULL_BOM, null, null, 0, null, null, null,
        com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult.pendingBom(
            "待补BOM"),
        List.of(), null, "需协作");
  }
}
