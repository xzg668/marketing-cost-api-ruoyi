package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.collaboration.QuoteItemCollaborationResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.IntegrationOutboxMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
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
    service = new QuoteItemCollaborationProjectionServiceImpl(
        formMapper, itemMapper, preparationRecordMapper, scanService, repository, resolver, outboxMapper);
    OaForm form = new OaForm();
    form.setId(1L); form.setOaNo("OA-08"); form.setBusinessUnitType("COMMERCIAL");
    item = new OaFormItem();
    item.setId(11L); item.setOaFormId(1L); item.setSeq(1); item.setMaterialNo("P-1");
    item.setTechnicianName("王工"); item.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectList(any())).thenReturn(List.of(form));
    when(itemMapper.selectById(11L)).thenReturn(item);
    when(itemMapper.selectList(any())).thenReturn(List.of(item));
    when(repository.findActiveLinksByQuoteItem(11L, scope())).thenReturn(List.of());
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
  @DisplayName("核算完成覆盖协作状态且唯一操作为查看核算结果")
  void completedCosting() {
    item.setCalcStatus("已核算");
    when(scanService.scanQuoteItem(11L)).thenReturn(readyScan());

    QuoteItemCollaborationResponse response = service.project("OA-08", 11L);

    assertThat(response.currentStatusLabel()).isEqualTo("核算完成");
    assertThat(response.priceStatus()).isEqualTo("READY");
    assertThat(response.nextAction()).isEqualTo("VIEW_COSTING_RESULT");
  }

  @Test
  @DisplayName("已有当前核算准备时继续原六步流程，不被新协作扫描反向阻断")
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

    assertThat(response.currentStatusLabel()).isEqualTo("核算中");
    assertThat(response.priceStatusLabel()).isEqualTo("在核算工作台确认");
    assertThat(response.nextAction()).isEqualTo("CONTINUE_COSTING");
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

  private QuoteCollaborationScanResult scan(PrimaryScope scope,
      QuoteCollaborationScanAction action, CollaborationPriceScanResult price) {
    return new QuoteCollaborationScanResult(11L, "OA-08", "2026-08", "P-1",
        "COMMERCIAL", "210", "210", scope == PrimaryScope.BARE_PACKAGE ? ProductForm.BARE : ProductForm.NORMAL,
        QuoteCollaborationScanStatus.COLLABORATION_REQUIRED, action, scope,
        scope == PrimaryScope.FULL_BOM ? null : "U9", null, scope == PrimaryScope.FULL_BOM ? 0 : 3,
        null, null, null, price, List.of(), null, "需协作");
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
  private CollaborationScope scope() { return new CollaborationScope("COMMERCIAL", "210"); }
}
