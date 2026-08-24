package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.PricePrepareCurrentStateService;
import com.sanhua.marketingcost.service.ProductCostingCollaborationService.CoordinationCommand;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("T11 产品核算缺口协作编排")
class ProductCostingCollaborationServiceImplTest {

  private OaFormMapper formMapper;
  private OaFormItemMapper itemMapper;
  private QuoteCollaborationScanService scanService;
  private CollaborationTechnicianResolver technicianResolver;
  private QuoteCollaborationTaskServiceImpl taskService;
  private QuoteCollaborationTaskRepository repository;
  private SysUserService userService;
  private PricePrepareCurrentStateService priceStateService;
  private ProductCostingCollaborationServiceImpl service;

  @BeforeEach
  void setUp() {
    formMapper = mock(OaFormMapper.class);
    itemMapper = mock(OaFormItemMapper.class);
    scanService = mock(QuoteCollaborationScanService.class);
    technicianResolver = mock(CollaborationTechnicianResolver.class);
    taskService = mock(QuoteCollaborationTaskServiceImpl.class);
    repository = mock(QuoteCollaborationTaskRepository.class);
    userService = mock(SysUserService.class);
    priceStateService = mock(PricePrepareCurrentStateService.class);
    service = new ProductCostingCollaborationServiceImpl(
        formMapper, itemMapper, scanService, technicianResolver, taskService,
        repository, userService, priceStateService);
    OaForm form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setId(11L);
    item.setOaFormId(1L);
    item.setMaterialNo("P-1");
    when(itemMapper.selectById(11L)).thenReturn(item);
    when(formMapper.selectOne(any())).thenReturn(form);
    SysUser actor = new SysUser();
    actor.setUserId(8L);
    actor.setUserName("quote-user");
    actor.setNickName("报价员");
    when(userService.findByUsername("quote-user")).thenReturn(actor);
  }

  @Test
  @DisplayName("无BOM且负责人唯一命中时自动创建一次协作任务")
  void missingBomCreatesAssignedTask() {
    when(scanService.scanQuoteItem(11L, "2026-08")).thenReturn(scan(PrimaryScope.FULL_BOM));
    when(technicianResolver.resolve(any(), any(), eq("COMMERCIAL"), eq(null)))
        .thenReturn(new CollaborationTechnicianResolver.Resolution(21L, "技术甲", null));
    when(taskService.startAutomatically(any())).thenReturn(started(101L, "WAIT_TECH", "技术甲"));
    QuoteCollaborationGap gap = activeGap("BOM", "2026-08");
    when(repository.findGaps(101L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of(gap));

    var result = service.coordinate(command("WAIT_BOM", null));

    assertThat(result.productTaskId()).isEqualTo(101L);
    assertThat(result.assigneeName()).isEqualTo("技术甲");
    assertThat(result.gapFactPersisted()).isTrue();
    verify(taskService).startAutomatically(any());
    verify(priceStateService, never()).discardPromotedFailedAttempt(any());
  }

  @Test
  @DisplayName("负责人无法唯一匹配时不猜人也不生成无主任务")
  void unresolvedTechnicianRequiresManualAssignment() {
    when(scanService.scanQuoteItem(11L, "2026-08")).thenReturn(scan(PrimaryScope.FULL_BOM));
    when(technicianResolver.resolve(any(), any(), eq("COMMERCIAL"), eq(null)))
        .thenReturn(new CollaborationTechnicianResolver.Resolution(
            null, null, "存在多名同优先级技术人员"));

    var result = service.coordinate(command("WAIT_BOM", null));

    assertThat(result.status()).isEqualTo("TECHNICIAN_UNASSIGNED");
    assertThat(result.message()).contains("多名");
    verify(taskService, never()).startAutomatically(any());
  }

  @Test
  @DisplayName("价格缺口成为协作唯一事实后清理未引用的失败价格候选")
  void promotedPriceGapDiscardsTransientAttempt() {
    when(scanService.scanQuoteItem(11L, "2026-08")).thenReturn(scan(PrimaryScope.PRICE_ONLY));
    when(technicianResolver.resolve(any(), any(), eq("COMMERCIAL"), eq(null)))
        .thenReturn(new CollaborationTechnicianResolver.Resolution(21L, "技术甲", null));
    when(taskService.startAutomatically(any())).thenReturn(started(102L, "WAIT_TECH", "技术甲"));
    when(repository.findGaps(102L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of(activeGap("PRICE", "2026-08")));
    when(priceStateService.discardPromotedFailedAttempt("PPR-FAILED"))
        .thenReturn(true);

    var result = service.coordinate(command("WAIT_PRICE", "PPR-FAILED"));

    assertThat(result.gapFactPersisted()).isTrue();
    assertThat(result.transientAttemptDiscarded()).isTrue();
    verify(priceStateService).discardPromotedFailedAttempt("PPR-FAILED");
  }

  @Test
  @DisplayName("缺价格类型只提示财务维护且不误派技术任务")
  void missingPriceTypeRoutesToFinanceWithoutTechnicalTask() {
    when(scanService.scanQuoteItem(11L, "2026-08")).thenReturn(priceTypeScan());

    var result = service.coordinate(command("WAIT_PRICE_TYPE", null));

    assertThat(result.productTaskId()).isNull();
    assertThat(result.status()).isEqualTo("EXTERNAL_MAINTENANCE");
    assertThat(result.assigneeName()).isEqualTo("财务报价");
    assertThat(result.message()).contains("价格类型");
    verify(technicianResolver, never()).resolve(any(), any(), any(), any());
    verify(taskService, never()).startAutomatically(any());
  }

  private CoordinationCommand command(String status, String prepareNo) {
    return new CoordinationCommand(
        "OA-1", 11L, "2026-08", status, "GAP", "quote-user", prepareNo);
  }

  private QuoteCollaborationScanResult scan(PrimaryScope scope) {
    CollaborationPriceScanResult price = scope == PrimaryScope.PRICE_ONLY
        ? CollaborationPriceScanResult.gaps(1, List.of(
            new CollaborationPriceScanResult.PriceGap(
                "M-1", "MISSING_PRICE", "MAINTAIN_PRICE", "缺价格", "lp_price_fixed",
                null, "PRICE_PREPARE", 9L, "ROW:9", "/P-1/M-1/", "物料1", null,
                null, "NORMAL", null, null, "2026-08", "210")))
        : CollaborationPriceScanResult.pendingBom("待补BOM");
    return new QuoteCollaborationScanResult(
        11L, "OA-1", "2026-08", "P-1", "COMMERCIAL", "210", "320",
        ProductForm.NORMAL, QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        QuoteCollaborationScanAction.CREATE_COLLABORATION, scope, null, null, 0,
        null, null, null, price, List.of(), null, "存在资料缺口");
  }

  private QuoteCollaborationScanResult priceTypeScan() {
    CollaborationPriceScanResult price = CollaborationPriceScanResult.gaps(1, List.of(
        new CollaborationPriceScanResult.PriceGap(
            "M-1", "MISSING_PRICE_TYPE", "MAINTAIN_PRICE_TYPE", "缺价格类型", null,
            null, "PRICE_PREPARE", 9L, "ROW:9", "/P-1/M-1/", "物料1", null,
            null, "NORMAL", null, null, "2026-08", "210")));
    return new QuoteCollaborationScanResult(
        11L, "OA-1", "2026-08", "P-1", "COMMERCIAL", "210", "320",
        ProductForm.NORMAL, QuoteCollaborationScanStatus.COLLABORATION_REQUIRED,
        QuoteCollaborationScanAction.MAINTAIN_PRICE_TYPE, PrimaryScope.PRICE_ONLY,
        "U9", "BOM-1", 1, null, null, null, price, List.of(), null,
        "BOM已准备，存在1项缺价格类型，请财务维护物料价格类型");
  }

  private QuoteCollaborationStartResult started(Long taskId, String status, String assignee) {
    return new QuoteCollaborationStartResult(
        CollaborationStartAction.CREATED, taskId, "PT-1", 201L, status, 21L,
        assignee, CollaborationNextAction.SUPPLEMENT_BOM, 1, false, "已创建协作");
  }

  private QuoteCollaborationGap activeGap(String category, String month) {
    QuoteCollaborationGap gap = new QuoteCollaborationGap();
    gap.setGapCategory(category);
    gap.setGapType("PRICE".equals(category) ? "MISSING_PRICE" : "MISSING_BOM");
    gap.setGapStatus("OPEN");
    gap.setAccountingMonth(month);
    return gap;
  }
}
