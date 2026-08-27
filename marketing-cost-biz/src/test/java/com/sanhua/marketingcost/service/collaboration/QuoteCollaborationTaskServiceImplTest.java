package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-06 协作任务创建与多报价关联")
class QuoteCollaborationTaskServiceImplTest {

  private QuoteCollaborationScanService scanService;
  private QuoteCollaborationTaskRepository repository;
  private QuoteCollaborationReviewRepository reviewRepository;
  private OaFormItemMapper itemMapper;
  private OaFormMapper formMapper;
  private CollaborationTaskLogService taskLogService;
  private QuoteCollaborationTaskServiceImpl service;

  @BeforeEach
  void setUp() {
    scanService = mock(QuoteCollaborationScanService.class);
    repository = mock(QuoteCollaborationTaskRepository.class);
    reviewRepository = mock(QuoteCollaborationReviewRepository.class);
    itemMapper = mock(OaFormItemMapper.class);
    formMapper = mock(OaFormMapper.class);
    taskLogService = mock(CollaborationTaskLogService.class);
    service = new QuoteCollaborationTaskServiceImpl(
        scanService, repository, reviewRepository, itemMapper, formMapper,
        taskLogService);
    stubQuote(275L, 27L, "OA-001");
  }

  @Test
  @DisplayName("首次发起在一个事务模型中创建主任务、产品任务、OWNER关联和初始缺口")
  void createsOwnerTaskAndInitialGaps() {
    when(scanService.scanQuoteItem(275L)).thenReturn(fullBomScan(275L, "OA-001"));
    when(repository.findLatestTaskByForm(27L, "COMMERCIAL"))
        .thenReturn(Optional.empty());
    when(repository.findActiveProductTaskByLockKey(any(), any()))
        .thenReturn(Optional.empty());
    assignIds();

    QuoteCollaborationStartResult result = service.start(command(275L));

    assertThat(result.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(result.currentStatus()).isEqualTo("WAIT_TECH");
    assertThat(result.currentAssigneeName()).isEqualTo("王工");
    assertThat(result.nextAction()).isEqualTo(CollaborationNextAction.SUPPLEMENT_BOM);
    assertThat(result.idempotentReplay()).isFalse();
    verify(repository).saveTask(any());
    verify(repository).saveProductTask(any());
    verify(repository).saveQuoteLink(any());
    verify(repository).synchronizeGaps(anyLong(), any(), any(), any());
  }

  @Test
  @DisplayName("核算流水线指定的月份必须贯穿扫描和新建协作任务")
  void requestedAccountingMonthFlowsIntoCreatedTask() {
    when(scanService.scanQuoteItem(275L, "2026-08"))
        .thenReturn(fullBomScan(275L, "OA-001"));
    when(repository.findLatestTaskByForm(27L, "COMMERCIAL"))
        .thenReturn(Optional.empty());
    when(repository.findActiveProductTaskByLockKey(any(), any()))
        .thenReturn(Optional.empty());
    assignIds();
    QuoteCollaborationStartCommand command = new QuoteCollaborationStartCommand(
        275L, 601L, "王工", 701L, "财务审核员", "2026-08",
        new CollaborationActor(0L, "系统"));

    QuoteCollaborationStartResult result = service.startAutomatically(command);

    assertThat(result.action()).isEqualTo(CollaborationStartAction.CREATED);
    verify(scanService).scanQuoteItem(275L, "2026-08");
    verify(repository).saveProductTask(org.mockito.ArgumentMatchers.argThat(task ->
        "2026-08".equals(task.getAccountingMonth())));
    verify(repository).saveQuoteLink(org.mockito.ArgumentMatchers.argThat(link ->
        "2026-08".equals(link.getAccountingMonth())));
    verify(repository).synchronizeGaps(anyLong(), any(),
        org.mockito.ArgumentMatchers.argThat(gaps -> gaps.size() == 1
            && "2026-08".equals(gaps.getFirst().accountingMonth())
            && "210".equals(gaps.getFirst().applicableOrgCode())),
        any());
  }

  @Test
  @DisplayName("人工发起必须有真实操作人，不能伪装成系统账号")
  void manualStartRejectsSystemActor() {
    QuoteCollaborationStartCommand command = new QuoteCollaborationStartCommand(
        275L, 601L, "王工", null, null, new CollaborationActor(0L, "系统"));

    assertThatThrownBy(() -> service.start(command))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("当前操作人不能为空");
    verify(scanService, never()).scanQuoteItem(anyLong());
  }

  @Test
  @DisplayName("后台一键核算允许系统账号留痕，但仍创建明确技术负责人的唯一任务")
  void automaticStartAllowsSystemActorWithExplicitTechnician() {
    when(scanService.scanQuoteItem(275L)).thenReturn(fullBomScan(275L, "OA-001"));
    when(repository.findLatestTaskByForm(27L, "COMMERCIAL")).thenReturn(Optional.empty());
    when(repository.findActiveProductTaskByLockKey(any(), any())).thenReturn(Optional.empty());
    assignIds();
    QuoteCollaborationStartCommand command = new QuoteCollaborationStartCommand(
        275L, 601L, "王工", null, null, new CollaborationActor(0L, "系统"));

    QuoteCollaborationStartResult result = service.startAutomatically(command);

    assertThat(result.action()).isEqualTo(CollaborationStartAction.CREATED);
    verify(repository).saveProductTask(org.mockito.ArgumentMatchers.argThat(task ->
        Long.valueOf(0L).equals(task.getCreatedBy())
            && Long.valueOf(601L).equals(task.getCurrentAssigneeUserId())));
  }

  @Test
  @DisplayName("同一报价产品顺序重复点击返回原OWNER任务且不重复写任何对象")
  void repeatedOwnerStartReturnsExistingTask() {
    QuoteCollaborationTask master = master(10L, 27L, "OA-001");
    QuoteCollaborationProductTask task = productTask(20L, 10L);
    QuoteCollaborationQuoteLink owner = link(30L, 20L, 10L, 275L, "OWNER");
    when(scanService.scanQuoteItem(275L)).thenReturn(activeScan(275L, "OA-001", task));
    when(repository.findLatestTaskByForm(27L, "COMMERCIAL"))
        .thenReturn(Optional.of(master));
    when(repository.findProductTaskById(20L, scope())).thenReturn(Optional.of(task));
    when(repository.findActiveLinksByQuoteItem(275L, scope()))
        .thenReturn(List.of(owner));

    QuoteCollaborationStartResult result = service.start(command(275L));

    assertThat(result.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(result.idempotentReplay()).isTrue();
    assertThat(result.productTaskId()).isEqualTo(20L);
    verify(repository, never()).saveTask(any());
    verify(repository, never()).saveProductTask(any());
    verify(repository, never()).saveQuoteLink(any());
  }

  @Test
  @DisplayName("同月另一报价只新增ACTIVE_TASK_LINK并返回原技术责任人")
  void linksAnotherQuoteToExistingActiveTask() {
    stubQuote(276L, 28L, "OA-002");
    QuoteCollaborationProductTask task = productTask(20L, 10L);
    when(scanService.scanQuoteItem(276L)).thenReturn(activeScan(276L, "OA-002", task));
    when(repository.findLatestTaskByForm(28L, "COMMERCIAL"))
        .thenReturn(Optional.empty());
    when(repository.findProductTaskById(20L, scope())).thenReturn(Optional.of(task));
    when(repository.findActiveLinksByQuoteItem(276L, scope()))
        .thenReturn(List.of());
    assignIds();

    QuoteCollaborationStartResult result = service.start(command(276L));

    assertThat(result.action()).isEqualTo(CollaborationStartAction.LINKED_ACTIVE_TASK);
    assertThat(result.currentAssigneeName()).isEqualTo("王工");
    assertThat(result.message()).isEqualTo("该产品正在由王工处理，当前报价已关联结果");
    verify(repository, never()).saveProductTask(any());
    verify(repository).saveQuoteLink(any());
  }

  @Test
  @DisplayName("QCBP-08 关联同月已有任务时沿用原责任人，不要求当前报价重新指定技术员")
  void linksExistingTaskWithoutReassigningTechnician() {
    stubQuote(276L, 28L, "OA-002");
    QuoteCollaborationProductTask task = productTask(20L, 10L);
    when(scanService.scanQuoteItem(276L)).thenReturn(activeScan(276L, "OA-002", task));
    when(repository.findLatestTaskByForm(28L, "COMMERCIAL")).thenReturn(Optional.empty());
    when(repository.findProductTaskById(20L, scope())).thenReturn(Optional.of(task));
    when(repository.findActiveLinksByQuoteItem(276L, scope())).thenReturn(List.of());
    assignIds();

    QuoteCollaborationStartResult result = service.start(commandWithoutTechnician(276L));

    assertThat(result.action()).isEqualTo(CollaborationStartAction.LINKED_ACTIVE_TASK);
    assertThat(result.currentAssigneeUserId()).isEqualTo(601L);
    assertThat(result.currentAssigneeName()).isEqualTo("王工");
  }

  @Test
  @DisplayName("关联报价产品不能以报价行入口修改原OWNER技术草稿")
  void linkedQuoteCannotEditOwnerDraft() {
    QuoteCollaborationQuoteLink linked = link(
        31L, 20L, 11L, 276L, "ACTIVE_TASK_LINK");
    when(repository.findActiveLinksByQuoteItem(276L, scope()))
        .thenReturn(List.of(linked));

    assertThatThrownBy(() -> service.requireOwnerQuoteItem(20L, 276L, scope()))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code())
                .isEqualTo(CollaborationDomainErrorCode.QUOTE_LINK_READ_ONLY));
  }

  @Test
  @DisplayName("D-07半年结果复用只建READY关联，不建技术产品任务和技术缺口")
  void reusesApprovedResultWithoutTechnicalTask() {
    QuoteCollaborationApprovedResult approved = approvedResult(99L, 88L);
    QuoteCollaborationProductTask sourceTask = productTask(88L, 77L);
    sourceTask.setTaskStatus("READY_FOR_COSTING");
    sourceTask.setActiveFlag(0);
    when(scanService.scanQuoteItem(275L)).thenReturn(reuseReadyScan(275L, "OA-001", 99L));
    when(reviewRepository.findApprovedResultById(99L, scope())).thenReturn(Optional.of(approved));
    when(repository.findProductTaskById(88L, scope())).thenReturn(Optional.of(sourceTask));
    when(repository.findLatestTaskByForm(27L, "COMMERCIAL")).thenReturn(Optional.empty());
    when(repository.findActiveLinksByQuoteItem(275L, scope())).thenReturn(List.of());
    assignIds();

    QuoteCollaborationStartResult result = service.start(commandWithoutTechnician(275L));

    assertThat(result.action()).isEqualTo(CollaborationStartAction.REUSED_APPROVED_RESULT);
    assertThat(result.currentStatus()).isEqualTo("READY");
    assertThat(result.nextAction()).isEqualTo(CollaborationNextAction.NONE);
    verify(repository, never()).saveProductTask(any());
    verify(repository, never()).synchronizeGaps(anyLong(), any(), any(), any());
    verify(repository).saveQuoteLink(org.mockito.ArgumentMatchers.argThat(link ->
        "APPROVED_RESULT_REUSE".equals(link.getLinkType())
            && Long.valueOf(99L).equals(link.getApprovedResultId())
            && "READY".equals(link.getLinkStatus())
            && link.getLatestPricePrepareNo() == null));
  }

  @Test
  @DisplayName("半年BOM复用后本次真实缺价只新建当前PRICE_ONLY任务")
  void reusedBomWithCurrentPriceGapsCreatesPriceOnlyTask() {
    QuoteCollaborationApprovedResult approved = approvedResult(99L, 88L);
    when(scanService.scanQuoteItem(275L)).thenReturn(reuseWithPriceGapScan(275L, "OA-001", 99L));
    when(reviewRepository.findApprovedResultById(99L, scope())).thenReturn(Optional.of(approved));
    when(repository.findLatestTaskByForm(27L, "COMMERCIAL")).thenReturn(Optional.empty());
    when(repository.findActiveProductTaskByLockKey(any(), any())).thenReturn(Optional.empty());
    assignIds();

    QuoteCollaborationStartResult result = service.start(command(275L));

    assertThat(result.action()).isEqualTo(CollaborationStartAction.CREATED);
    assertThat(result.nextAction()).isEqualTo(CollaborationNextAction.SUPPLEMENT_PRICE);
    verify(repository).saveProductTask(org.mockito.ArgumentMatchers.argThat(task ->
        "PRICE_ONLY".equals(task.getPrimaryScope())
            && Integer.valueOf(0).equals(task.getNeedBom())
            && Integer.valueOf(1).equals(task.getNeedPrice())));
    verify(repository).saveQuoteLink(org.mockito.ArgumentMatchers.argThat(link ->
        "APPROVED_RESULT_REUSE".equals(link.getLinkType())
            && Long.valueOf(99L).equals(link.getApprovedResultId())
            && "WAIT_SOURCE".equals(link.getLinkStatus())));
  }

  private void assignIds() {
    AtomicLong ids = new AtomicLong(10L);
    when(repository.saveTask(any())).thenAnswer(invocation -> {
      QuoteCollaborationTask task = invocation.getArgument(0);
      task.setId(ids.getAndIncrement());
      return task;
    });
    when(repository.saveProductTask(any())).thenAnswer(invocation -> {
      QuoteCollaborationProductTask task = invocation.getArgument(0);
      task.setId(20L);
      task.setProductTaskNo("QCPT-001");
      return task;
    });
    when(repository.saveQuoteLink(any())).thenAnswer(invocation -> {
      QuoteCollaborationQuoteLink link = invocation.getArgument(0);
      link.setId(30L);
      return link;
    });
    when(repository.synchronizeGaps(anyLong(), any(), any(), any()))
        .thenReturn(List.of());
  }

  private void stubQuote(Long itemId, Long formId, String oaNo) {
    OaFormItem item = new OaFormItem();
    item.setId(itemId);
    item.setOaFormId(formId);
    item.setMaterialNo("1008900001289");
    item.setProductName("热力膨胀阀");
    item.setSpec("4.5");
    item.setSunlModel("RFKH11E-4.5-54A");
    item.setBusinessUnitType("COMMERCIAL");
    OaForm form = new OaForm();
    form.setId(formId);
    form.setOaNo(oaNo);
    form.setBusinessUnitType("COMMERCIAL");
    form.setSourceSystem("OA");
    when(itemMapper.selectById(itemId)).thenReturn(item);
    when(formMapper.selectById(formId)).thenReturn(form);
  }

  private QuoteCollaborationStartCommand command(Long itemId) {
    return new QuoteCollaborationStartCommand(
        itemId, 601L, "王工", 701L, "财务审核员", new CollaborationActor(901L, "报价员"));
  }

  private QuoteCollaborationStartCommand commandWithoutTechnician(Long itemId) {
    return new QuoteCollaborationStartCommand(
        itemId, null, null, null, null, new CollaborationActor(901L, "报价员"));
  }

  private QuoteCollaborationScanResult fullBomScan(Long itemId, String oaNo) {
    return scan(itemId, oaNo, QuoteCollaborationScanAction.CREATE_COLLABORATION, null, null);
  }

  private QuoteCollaborationScanResult reuseReadyScan(
      Long itemId, String oaNo, Long approvedResultId) {
    return new QuoteCollaborationScanResult(
        itemId, oaNo, "2026-08", "1008900001289", "COMMERCIAL", "210", "210",
        ProductForm.NORMAL, QuoteCollaborationScanStatus.REUSABLE_RESULT,
        QuoteCollaborationScanAction.REUSE_APPROVED_RESULT, null,
        "ELECTRONIC_DRAWING", null, 18, null, null, approvedResultId,
        CollaborationPriceScanResult.ready(8), List.of(), null,
        "已审核结果可复用，本次价格重新检查通过");
  }

  private QuoteCollaborationScanResult reuseWithPriceGapScan(
      Long itemId, String oaNo, Long approvedResultId) {
    return new QuoteCollaborationScanResult(
        itemId, oaNo, "2026-08", "1008900001289", "COMMERCIAL", "210", "210",
        ProductForm.NORMAL, QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        QuoteCollaborationScanAction.CREATE_COLLABORATION, PrimaryScope.PRICE_ONLY,
        "ELECTRONIC_DRAWING", null, 18, null, null, approvedResultId,
        CollaborationPriceScanResult.gaps(8, List.of(
            new CollaborationPriceScanResult.PriceGap(
                "RAW-1", "MISSING_PRICE", "MAINTAIN_PRICE",
                "本次报价无有效价格", "lp_price_fixed_item", null))),
        List.of(), null, "BOM已复用，本次报价存在1项真实缺价");
  }

  private QuoteCollaborationScanResult activeScan(
      Long itemId, String oaNo, QuoteCollaborationProductTask task) {
    return scan(
        itemId, oaNo, QuoteCollaborationScanAction.LINK_ACTIVE_TASK,
        task.getId(), task.getCurrentAssigneeName());
  }

  private QuoteCollaborationScanResult scan(
      Long itemId,
      String oaNo,
      QuoteCollaborationScanAction action,
      Long activeTaskId,
      String activeAssigneeName) {
    return new QuoteCollaborationScanResult(
        itemId, oaNo, "2026-08", "1008900001289", "COMMERCIAL", "210", "210",
        ProductForm.NORMAL,
        action == QuoteCollaborationScanAction.LINK_ACTIVE_TASK
            ? QuoteCollaborationScanStatus.WAITING_EXISTING_TASK
            : QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        action, PrimaryScope.FULL_BOM, null, null, 0, activeTaskId,
        activeAssigneeName, null, CollaborationPriceScanResult.pendingBom("等待补BOM"),
        List.of(), null, null);
  }

  private QuoteCollaborationTask master(Long id, Long formId, String oaNo) {
    QuoteCollaborationTask task = new QuoteCollaborationTask();
    task.setId(id);
    task.setOaFormId(formId);
    task.setOaNo(oaNo);
    task.setRoundNo(1);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setMasterStatus("WAIT_TECH");
    task.setTaskVersion(1);
    return task;
  }

  private QuoteCollaborationProductTask productTask(Long id, Long masterId) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setProductTaskNo("QCPT-001");
    task.setOriginCollaborationId(masterId);
    task.setAccountingMonth("2026-08");
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setProductCode("1008900001289");
    task.setPrimaryScope("FULL_BOM");
    task.setNeedBom(1);
    task.setTaskStatus("WAIT_TECH");
    task.setCurrentAssigneeUserId(601L);
    task.setCurrentAssigneeName("王工");
    task.setTaskVersion(1);
    task.setActiveFlag(1);
    task.setActiveLockKey(CollaborationActiveLockKeyFactory.create(
        task.getProductCode(), null, null, scope()));
    return task;
  }

  private QuoteCollaborationQuoteLink link(
      Long id, Long productTaskId, Long collaborationId, Long itemId, String type) {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setId(id);
    link.setProductTaskId(productTaskId);
    link.setCollaborationId(collaborationId);
    link.setOaFormItemId(itemId);
    link.setLinkType(type);
    link.setLinkStatus("WAIT_SOURCE");
    link.setActiveFlag(1);
    return link;
  }

  private QuoteCollaborationApprovedResult approvedResult(Long id, Long sourceTaskId) {
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setId(id);
    result.setResultNo("QCAR-" + id);
    result.setResultType("FULL_BOM");
    result.setSourceProductTaskId(sourceTaskId);
    result.setProductCode("1008900001289");
    result.setApplicableOrgCode("210");
    result.setResultStatus("ACTIVE");
    return result;
  }

  private CollaborationScope scope() {
    return new CollaborationScope("COMMERCIAL", "210");
  }
}
