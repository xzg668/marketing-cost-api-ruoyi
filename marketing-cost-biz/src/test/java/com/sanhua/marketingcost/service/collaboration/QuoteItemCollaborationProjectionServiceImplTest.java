package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.IntegrationOutboxMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanErrorCode;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-08 报价产品协作状态投影")
class QuoteItemCollaborationProjectionServiceImplTest {
  private QuoteCollaborationScanService scanService;
  private QuoteCollaborationTaskRepository repository;
  private CollaborationTechnicianResolver resolver;
  private QuoteBomPreparationRecordMapper preparationRecordMapper;
  private QuoteCostingWorkspaceService workspaceService;
  private QuoteCostRunVersionMapper costRunVersionMapper;
  private OaFormItem item;
  private QuoteItemCollaborationProjectionServiceImpl service;

  @BeforeEach
  void setUp() {
    OaFormMapper formMapper = mock(OaFormMapper.class);
    OaFormItemMapper itemMapper = mock(OaFormItemMapper.class);
    preparationRecordMapper = mock(QuoteBomPreparationRecordMapper.class);
    scanService = mock(QuoteCollaborationScanService.class);
    repository = mock(QuoteCollaborationTaskRepository.class);
    resolver = mock(CollaborationTechnicianResolver.class);
    IntegrationOutboxMapper outboxMapper = mock(IntegrationOutboxMapper.class);
    workspaceService = mock(QuoteCostingWorkspaceService.class);
    costRunVersionMapper = mock(QuoteCostRunVersionMapper.class);
    service = new QuoteItemCollaborationProjectionServiceImpl(
        formMapper, itemMapper, preparationRecordMapper, scanService, repository, resolver,
        outboxMapper, workspaceService, costRunVersionMapper);
    OaForm form = new OaForm();
    form.setId(1L); form.setOaNo("OA-08"); form.setBusinessUnitType("COMMERCIAL");
    item = new OaFormItem();
    item.setId(11L); item.setOaFormId(1L); item.setSeq(1); item.setMaterialNo("P-1");
    item.setTechnicianName("王工"); item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectList(any())).thenReturn(List.of(form));
    when(itemMapper.selectById(11L)).thenReturn(item);
    when(itemMapper.selectList(any())).thenReturn(List.of(item));
    when(repository.findActiveLinksByQuoteItem(11L, scope())).thenReturn(List.of());
    when(workspaceService.find(anyLong(), anyString())).thenReturn(Optional.empty());
  }

  @Test
  @DisplayName("缺BOM显示无BOM、待补BOM和唯一发起补录操作")
  void missingBom() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.FULL_BOM,
        QuoteCollaborationScanAction.CREATE_COLLABORATION,
        CollaborationPriceScanResult.pendingBom("待补BOM")));
    when(resolver.resolve(any(OaForm.class), org.mockito.ArgumentMatchers.eq(item),
        org.mockito.ArgumentMatchers.eq("COMMERCIAL"), org.mockito.ArgumentMatchers.isNull())).thenReturn(
        new CollaborationTechnicianResolver.Resolution(601L, "王工", null));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.bomStatusLabel()).isEqualTo("无BOM");
    assertThat(response.priceStatusLabel()).isEqualTo("待BOM补齐后检查");
    assertThat(response.currentStatusLabel()).isEqualTo("待补BOM");
    assertThat(response.nextAction()).isEqualTo("START_BOM_SUPPLEMENT");
    assertThat(response.nextActionLabel()).isEqualTo("发起补录");
    assertThat(response.batchSelectable()).isTrue();
  }

  @Test
  @DisplayName("裸品只引导补包装，不把U9本体判成无BOM")
  void barePackage() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.BARE_PACKAGE,
        QuoteCollaborationScanAction.CREATE_COLLABORATION,
        CollaborationPriceScanResult.pendingPackage("待补包装")));
    when(resolver.resolve(any(OaForm.class), org.mockito.ArgumentMatchers.eq(item),
        org.mockito.ArgumentMatchers.eq("COMMERCIAL"), org.mockito.ArgumentMatchers.isNull())).thenReturn(
        new CollaborationTechnicianResolver.Resolution(601L, "王工", null));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.bomStatusLabel()).isEqualTo("U9本体BOM已有（裸品）");
    assertThat(response.nextAction()).isEqualTo("START_PACKAGE_SUPPLEMENT");
    assertThat(response.nextActionLabel()).isEqualTo("补包装");
  }

  @Test
  @DisplayName("有BOM真实缺价只显示缺价数量和补明细价格")
  void missingPrice() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.PRICE_ONLY,
        QuoteCollaborationScanAction.CREATE_COLLABORATION,
        CollaborationPriceScanResult.gaps(3, List.of(
            new CollaborationPriceScanResult.PriceGap("RAW-1", "MISSING", "MAINTAIN", "无价格", "price", null),
            new CollaborationPriceScanResult.PriceGap("SCRAP-1", "MISSING", "MAINTAIN", "无价格", "price", null)))));
    when(resolver.resolve(any(OaForm.class), org.mockito.ArgumentMatchers.eq(item),
        org.mockito.ArgumentMatchers.eq("COMMERCIAL"), org.mockito.ArgumentMatchers.isNull())).thenReturn(
        new CollaborationTechnicianResolver.Resolution(601L, "王工", null));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.bomStatusLabel()).isEqualTo("U9有此BOM");
    assertThat(response.priceStatusLabel()).isEqualTo("2项明细缺价");
    assertThat(response.nextAction()).isEqualTo("START_PRICE_SUPPLEMENT");
  }

  @Test
  @DisplayName("缺价格类型显示财务报价且不提供技术补价操作")
  void missingPriceType() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.PRICE_ONLY,
        QuoteCollaborationScanAction.MAINTAIN_PRICE_TYPE,
        CollaborationPriceScanResult.gaps(3, List.of(
            new CollaborationPriceScanResult.PriceGap(
                "NEW-1", "MISSING_PRICE_TYPE", "MAINTAIN_PRICE_TYPE",
                "缺价格类型", null, null)))));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.priceStatusLabel()).isEqualTo("缺价格类型");
    assertThat(response.assigneeName()).isEqualTo("财务报价");
    assertThat(response.currentStatus()).isEqualTo("MISSING_PRICE_TYPE");
    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_GAP");
    verify(resolver, never()).resolve(any(), any(), any(), any());
  }

  @Test
  @DisplayName("负责人未匹配时提供指定负责人操作而不是形成页面死路")
  void unresolvedTechnicianCanBeAssigned() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.FULL_BOM,
        QuoteCollaborationScanAction.CREATE_COLLABORATION,
        CollaborationPriceScanResult.pendingBom("待补BOM")));
    when(resolver.resolve(any(OaForm.class), org.mockito.ArgumentMatchers.eq(item),
        org.mockito.ArgumentMatchers.eq("COMMERCIAL"), org.mockito.ArgumentMatchers.isNull())).thenReturn(
        new CollaborationTechnicianResolver.Resolution(
            null, null, "未匹配到技术负责人，请手工指定"));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatus()).isEqualTo("TECHNICIAN_UNASSIGNED");
    assertThat(response.currentStatusLabel()).isEqualTo("待指定负责人");
    assertThat(response.nextAction()).isEqualTo("ASSIGN_TECHNICIAN");
    assertThat(response.nextActionLabel()).isEqualTo("指定技术负责人");
    assertThat(response.actionEnabled()).isTrue();
    assertThat(response.batchSelectable()).isTrue();
  }

  @Test
  @DisplayName("技术已提交后只显示待财务审核和查看补录内容")
  void waitingFinance() {
    QuoteCollaborationProductTask task = task("WAIT_FINANCE");
    QuoteCollaborationQuoteLink link = link(task.getId(), "WAIT_SOURCE");
    when(scanService.scanQuoteItem(11L)).thenReturn(activeScan(task));
    when(repository.findActiveLinksByQuoteItem(11L, scope())).thenReturn(List.of(link));
    when(repository.findProductTaskById(task.getId(), scope())).thenReturn(Optional.of(task));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("待财务审核");
    assertThat(response.assigneeName()).isEqualTo("王工");
    assertThat(response.nextAction()).isEqualTo("VIEW_SUPPLEMENT");
    assertThat(response.nextActionLabel()).isEqualTo("查看补录内容");
  }

  @Test
  @DisplayName("同月已有任务但本报价尚未关联时唯一操作是关联现有任务")
  void activeTaskMustBeLinkedBeforeViewing() {
    QuoteCollaborationProductTask task = task("WAIT_TECH");
    when(scanService.scanQuoteItem(11L)).thenReturn(activeScan(task));
    when(repository.findProductTaskById(task.getId(), scope())).thenReturn(Optional.of(task));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("同月同产品已有补录任务");
    assertThat(response.nextAction()).isEqualTo("LINK_EXISTING_TASK");
    assertThat(response.nextActionLabel()).isEqualTo("关联现有任务");
  }

  @Test
  @DisplayName("当前月核算完成后唯一下一步为查看结果")
  void completedCosting() {
    item.setCalcStatus("已核算");
    item.setConfirmedCostVersionId(33L);
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(33L);
    version.setPricingMonth("2026-08");
    when(costRunVersionMapper.selectById(33L)).thenReturn(version);
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("核算完成");
    assertThat(response.priceStatus()).isEqualTo("READY");
    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_RESULT");
    assertThat(response.nextActionLabel()).isEqualTo("查看结果");
  }

  @Test
  @DisplayName("历史月成功结果在当前月显示重新核算且不覆盖历史结果")
  void previousMonthResultRequiresRecalculation() {
    item.setCalcStatus("已核算");
    item.setConfirmedCostVersionId(33L);
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(33L);
    version.setPricingMonth("2026-07");
    when(costRunVersionMapper.selectById(33L)).thenReturn(version);
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("待重新核算");
    assertThat(response.nextAction()).isEqualTo("RESTART_COSTING");
    assertThat(response.nextActionLabel()).isEqualTo("重新核算");
    assertThat(response.message()).contains("2026-07", "2026-08", "原核算结果仍可查看");
  }

  @Test
  @DisplayName("已有当前核算准备时显示可直接核算，不再依赖人工确认")
  void existingPreparationContinuesCosting() {
    QuoteBomPreparationRecord preparation = new QuoteBomPreparationRecord();
    preparation.setId(23L);
    preparation.setOaFormItemId(11L);
    preparation.setQuoteProductCode("P-1");
    preparation.setActiveFlag(1);
    preparation.setCostPeriodMonth("2026-08");
    preparation.setPreparationStatus("READY");
    when(preparationRecordMapper.selectOne(any())).thenReturn(preparation);
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.PRICE_ONLY,
        QuoteCollaborationScanAction.SYSTEM_BLOCKED,
        CollaborationPriceScanResult.error("价格类型待六步核算确认")));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("可核算");
    assertThat(response.priceStatusLabel()).isEqualTo("核算资料已准备");
    assertThat(response.nextAction()).isEqualTo("START_COSTING");
    assertThat(response.nextActionLabel()).isEqualTo("核算本产品");
  }

  @Test
  @DisplayName("历史月份核算准备不能把当前月份误显示为继续核算")
  void stalePreparationDoesNotContinueCurrentMonth() {
    QuoteBomPreparationRecord stale = new QuoteBomPreparationRecord();
    stale.setId(24L);
    stale.setOaFormItemId(11L);
    stale.setQuoteProductCode("P-1");
    stale.setActiveFlag(1);
    stale.setCostPeriodMonth("2026-07");
    stale.setPreparationStatus("READY");
    when(preparationRecordMapper.selectOne(any())).thenReturn(stale);
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("已就绪");
    assertThat(response.nextAction()).isEqualTo("START_COSTING");
  }

  @Test
  @DisplayName("有BOM但当月价格准备检查异常时允许仅重建当前产品后继续核算")
  void pricePreparationErrorCanPrepareCurrentProduct() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.PRICE_ONLY,
        QuoteCollaborationScanAction.SYSTEM_BLOCKED,
        CollaborationPriceScanResult.error("当前重算核算月为 2026-08，请按当前月执行价格准备"),
        QuoteCollaborationScanErrorCode.PRICE_PREPARATION_ERROR));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.bomStatusLabel()).isEqualTo("U9有此BOM");
    assertThat(response.priceStatusLabel()).isEqualTo("价格检查失败");
    assertThat(response.currentStatus()).isEqualTo("PRICE_PREPARATION_REQUIRED");
    assertThat(response.currentStatusLabel()).isEqualTo("价格待处理");
    assertThat(response.nextAction()).isEqualTo("RETRY_COSTING");
    assertThat(response.nextActionLabel()).isEqualTo("重试本产品");
    assertThat(response.actionEnabled()).isTrue();
    assertThat(response.batchSelectable()).isFalse();
  }

  @Test
  @DisplayName("U9等真实系统检查故障允许重试当前产品")
  void infrastructureErrorRemainsBlocked() {
    when(scanService.scanQuoteItem(11L)).thenReturn(scan(PrimaryScope.PRICE_ONLY,
        QuoteCollaborationScanAction.SYSTEM_BLOCKED,
        CollaborationPriceScanResult.error("U9连接超时"),
        QuoteCollaborationScanErrorCode.U9_TIMEOUT));

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("系统检查未通过");
    assertThat(response.nextAction()).isEqualTo("RETRY_COSTING");
    assertThat(response.nextActionLabel()).isEqualTo("重试本产品");
    assertThat(response.actionEnabled()).isTrue();
  }

  @Test
  @DisplayName("工作区缺价格时唯一下一步为查看缺口")
  void workspacePriceGapCanBeViewed() {
    QuoteCostingWorkspace workspace = workspace("WAIT_PRICE");
    workspace.setGapCount(4);
    workspace.setLastErrorMessage("缺少 4 项正式价格");
    when(workspaceService.find(11L, "2026-08")).thenReturn(Optional.of(workspace));
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("缺价格");
    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_GAP");
    assertThat(response.nextActionLabel()).isEqualTo("查看缺口");
    assertThat(response.message()).isEqualTo("缺少 4 项正式价格");
  }

  @Test
  @DisplayName("财务基准价缺失时处理人必须精确显示财务报价")
  void financeBasePriceGapRoutesOnlyToFinance() {
    QuoteCostingWorkspace workspace = workspace("WAIT_PRICE");
    workspace.setLastErrorCode("FINANCE_BASE_PRICE_MISSING");
    workspace.setLastErrorMessage("缺少财务基准价格");
    when(workspaceService.find(11L, "2026-08")).thenReturn(Optional.of(workspace));
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.assigneeName()).isEqualTo("财务报价");
    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_GAP");
  }

  @Test
  @DisplayName("保留历史成功结果时仍优先展示当前月价格缺口")
  void currentWorkspaceGapTakesPriorityOverHistoricalResult() {
    item.setCalcStatus("已核算");
    item.setConfirmedCostVersionId(33L);
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(33L);
    version.setPricingMonth("2026-08");
    when(costRunVersionMapper.selectById(33L)).thenReturn(version);
    QuoteCostingWorkspace workspace = workspace("WAIT_PRICE");
    workspace.setGapCount(2);
    workspace.setLastErrorMessage("缺少 2 项正式价格");
    when(workspaceService.find(11L, "2026-08")).thenReturn(Optional.of(workspace));
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("缺价格");
    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_GAP");
    assertThat(response.nextActionLabel()).isEqualTo("查看缺口");
    assertThat(response.message()).isEqualTo("缺少 2 项正式价格");
  }

  @Test
  @DisplayName("工作区系统失败时只重试当前产品")
  void workspaceSystemFailureCanRetry() {
    when(workspaceService.find(11L, "2026-08"))
        .thenReturn(Optional.of(workspace("SYSTEM_FAILED")));
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.nextAction()).isEqualTo("RETRY_COSTING");
    assertThat(response.nextActionLabel()).isEqualTo("重试本产品");
  }

  @Test
  @DisplayName("工作区执行中只允许查看进度")
  void runningWorkspaceCanViewProgress() {
    when(workspaceService.find(11L, "2026-08"))
        .thenReturn(Optional.of(workspace("RUNNING")));
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_PROGRESS");
    assertThat(response.nextActionLabel()).isEqualTo("查看进度");
  }

  @Test
  @DisplayName("主动刷新重新计算当前投影而不是改变业务结果")
  void refreshSummaryRebuildsProjection() {
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    assertThat(service.refreshSummary("OA-08").items()).hasSize(1);

    verify(scanService, times(1)).scanQuoteItem(11L);
  }

  private QuoteCollaborationScanResult scan(PrimaryScope scope,
      QuoteCollaborationScanAction action, CollaborationPriceScanResult price) {
    return scan(scope, action, price, null);
  }
  private QuoteCollaborationScanResult scan(PrimaryScope scope,
      QuoteCollaborationScanAction action, CollaborationPriceScanResult price,
      QuoteCollaborationScanErrorCode errorCode) {
    return new QuoteCollaborationScanResult(11L, "OA-08", "2026-08", "P-1",
        "COMMERCIAL", "210", "210", scope == PrimaryScope.BARE_PACKAGE ? ProductForm.BARE : ProductForm.NORMAL,
        QuoteCollaborationScanStatus.COLLABORATION_REQUIRED, action, scope,
        scope == PrimaryScope.FULL_BOM ? null : "U9", null, scope == PrimaryScope.FULL_BOM ? 0 : 3,
        null, null, null, price, List.of(), errorCode, "需协作");
  }
  private QuoteCollaborationScanResult readyScan() {
    return new QuoteCollaborationScanResult(11L, "OA-08", "2026-08", "P-1",
        "COMMERCIAL", "210", "210", ProductForm.NORMAL, QuoteCollaborationScanStatus.READY,
        QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED, null, "U9", null, 3,
        null, null, null, CollaborationPriceScanResult.ready(3), List.of(), null, "已就绪");
  }
  private QuoteCollaborationScanResult activeScan(QuoteCollaborationProductTask task) {
    return new QuoteCollaborationScanResult(11L, "OA-08", "2026-08", "P-1",
        "COMMERCIAL", "210", "210", ProductForm.NORMAL,
        QuoteCollaborationScanStatus.WAITING_EXISTING_TASK, QuoteCollaborationScanAction.LINK_ACTIVE_TASK,
        PrimaryScope.FULL_BOM, null, null, 0, task.getId(), "王工", null,
        CollaborationPriceScanResult.pendingBom("待补BOM"), List.of(), null, "处理中");
  }
  private QuoteCollaborationProductTask task(String status) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(21L); task.setProductTaskNo("QCPT-21"); task.setTaskStatus(status);
    task.setTaskVersion(2); task.setCurrentAssigneeUserId(601L); task.setCurrentAssigneeName("王工");
    return task;
  }
  private QuoteCollaborationQuoteLink link(Long taskId, String status) {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setId(31L); link.setProductTaskId(taskId); link.setLinkStatus(status); return link;
  }
  private QuoteCostingWorkspace workspace(String status) {
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setOaFormItemId(11L);
    workspace.setPeriodMonth("2026-08");
    workspace.setWorkspaceStatus(status);
    return workspace;
  }
  private CollaborationScope scope() { return new CollaborationScope("COMMERCIAL", "210"); }
}
