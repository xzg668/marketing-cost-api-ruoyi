package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.scan.ApprovedSourceInspection;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationApprovedSourceInspector;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationPriceScanGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanErrorCode;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStage;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanStatus;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DisplayName("QCBP-05 报价产品协作只读扫描")
class QuoteCollaborationScanServiceImplTest {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-08-13T02:00:00Z"), BUSINESS_ZONE);

  private OaFormItemMapper itemMapper;
  private OaFormMapper formMapper;
  private QuoteCollaborationCurrentU9BomGateway u9Gateway;
  private QuoteProductTypeResolveService productTypeService;
  private QuoteCollaborationTaskRepository taskRepository;
  private QuoteCollaborationReviewRepository reviewRepository;
  private QuoteCollaborationApprovedSourceInspector sourceInspector;
  private QuoteCollaborationPriceScanGateway priceGateway;
  private QuoteCollaborationScanServiceImpl service;

  @BeforeEach
  void setUp() {
    itemMapper = mock(OaFormItemMapper.class);
    formMapper = mock(OaFormMapper.class);
    u9Gateway = mock(QuoteCollaborationCurrentU9BomGateway.class);
    productTypeService = mock(QuoteProductTypeResolveService.class);
    taskRepository = mock(QuoteCollaborationTaskRepository.class);
    reviewRepository = mock(QuoteCollaborationReviewRepository.class);
    sourceInspector = mock(QuoteCollaborationApprovedSourceInspector.class);
    priceGateway = mock(QuoteCollaborationPriceScanGateway.class);
    service =
        new QuoteCollaborationScanServiceImpl(
            itemMapper,
            formMapper,
            new QuoteBomContextResolver(),
            u9Gateway,
            productTypeService,
            taskRepository,
            reviewRepository,
            sourceInspector,
            priceGateway,
            FIXED_CLOCK);
    stubQuote();
    when(taskRepository.findActiveProductTaskByLockKey(any(), any()))
        .thenReturn(java.util.Optional.empty());
    when(reviewRepository.findValidResults(any(), any(), any(), any()))
        .thenReturn(List.of());
    when(reviewRepository.findLatestExpiredReference(any(), any(), any(), any()))
        .thenReturn(java.util.Optional.empty());
  }

  @Test
  @DisplayName("默认扫描使用当前核算月，不沿用旧准备月份或OA历史月份")
  void usesCurrentCostingMonth() {
    OaForm historicalForm = new OaForm();
    historicalForm.setId(27L);
    historicalForm.setOaNo("FI-SC-006-20260106-082");
    historicalForm.setProcessCode("FI-SC-006");
    historicalForm.setAccountingPeriodMonth("2026-01");
    historicalForm.setBusinessUnitType("COMMERCIAL");
    when(formMapper.selectById(27L)).thenReturn(historicalForm);
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(argThat(context -> "2026-08".equals(context.accountingMonth()))))
        .thenReturn(CollaborationPriceScanResult.ready(3));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.accountingMonth()).isEqualTo("2026-08");
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED);
  }

  @Test
  @DisplayName("一键核算显式月份优先于OA表头和旧准备月份")
  void requestedCostingMonthWins() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(argThat(context -> "2026-08".equals(context.accountingMonth()))))
        .thenReturn(CollaborationPriceScanResult.ready(3));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L, "2026-08");

    assertThat(result.accountingMonth()).isEqualTo("2026-08");
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED);
  }

  @Test
  @DisplayName("D-01：普通产品U9完整且价格齐全，无需协作")
  void d01ReturnsNoCollaborationRequired() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(any())).thenReturn(CollaborationPriceScanResult.ready(12));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.READY);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED);
    assertThat(result.requiredScope()).isNull();
    assertThat(result.authoritativeBomSource()).isEqualTo("U9");
    assertThat(result.price().gapCount()).isZero();
    assertThat(result.completedStages())
        .containsExactly(
            QuoteCollaborationScanStage.U9_CURRENT_BOM,
            QuoteCollaborationScanStage.PRODUCT_FORM,
            QuoteCollaborationScanStage.SAME_MONTH_ACTIVE_TASK,
            QuoteCollaborationScanStage.PRICE_PREPARATION);
    verifyNoApprovedResultLookup();
  }

  @Test
  @DisplayName("D-02：普通产品U9明确无BOM，只要求补完整BOM且不提前猜价格")
  void d02ReturnsFullBom() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.COLLABORATION_REQUIRED);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.CREATE_COLLABORATION);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.FULL_BOM);
    assertThat(result.price().status().name()).isEqualTo("PENDING_BOM");
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("D-03：普通产品U9完整但有3项真实底层缺价，只要求补价")
  void d03ReturnsPriceOnlyWithThreeRealGaps() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(any()))
        .thenReturn(
            CollaborationPriceScanResult.gaps(
                9,
                List.of(
                    priceGap("RAW-1", "LINKED"),
                    priceGap("SCRAP-1", "FIXED_PURCHASE"),
                    priceGap("PACK-1", null))));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.COLLABORATION_REQUIRED);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.CREATE_COLLABORATION);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.PRICE_ONLY);
    assertThat(result.price().gapCount()).isEqualTo(3);
    assertThat(result.price().gaps())
        .extracting(CollaborationPriceScanResult.PriceGap::existingOfficialPriceType)
        .containsExactly("LINKED", "FIXED_PURCHASE", null);
  }

  @Test
  @DisplayName("缺价格类型路由财务主数据维护且不创建技术协作")
  void missingPriceTypeRoutesToFinanceMaintenance() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(any())).thenReturn(CollaborationPriceScanResult.gaps(
        3, List.of(new CollaborationPriceScanResult.PriceGap(
            "NEW-1", "MISSING_PRICE_TYPE", "MAINTAIN_PRICE_TYPE", "缺价格类型",
            null, null))));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.COLLABORATION_REQUIRED);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.MAINTAIN_PRICE_TYPE);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.PRICE_ONLY);
    assertThat(result.message()).contains("财务", "价格类型");
  }

  @Test
  @DisplayName("D-04：裸品有U9本体但缺目标包装，进入补包装，不重补本体")
  void d04ReturnsBarePackage() {
    stubU9Available();
    stubProductType(QuoteProductType.BARE);

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.COLLABORATION_REQUIRED);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.BARE_PACKAGE);
    assertThat(result.authoritativeBomSource()).isEqualTo("U9_BODY");
    assertThat(result.price().status().name()).isEqualTo("PENDING_PACKAGE");
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("D-05：裸品未形成目标包装前不把参考包装价格误当成本料号价格")
  void d05DoesNotPrejudgeReferencePackagePrice() {
    stubU9Available();
    stubProductType(QuoteProductType.BARE);

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.BARE_PACKAGE);
    assertThat(result.price().gaps()).isEmpty();
    assertThat(result.message()).contains("补包装");
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("U9无BOM但主档为裸品时仍先补完整BOM，不误判为只补包装")
  void bareWithoutU9BodyStillRequiresFullBom() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.BARE);

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.FULL_BOM);
    assertThat(result.authoritativeBomSource()).isNull();
  }

  @Test
  @DisplayName("U9超时属于系统错误，不能创建技术业务任务")
  void u9TimeoutBlocksWithoutBusinessTask() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.timeout("U9查询超时"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertSystemBlocked(result, QuoteCollaborationScanErrorCode.U9_TIMEOUT);
    verifyNoInteractions(productTypeService, taskRepository, reviewRepository, sourceInspector, priceGateway);
  }

  @Test
  @DisplayName("U9组织不匹配属于系统错误，不能静默跨组织兜底")
  void u9OrganizationMismatchBlocks() {
    when(u9Gateway.read(any()))
        .thenReturn(CurrentU9BomResult.organizationMismatch("目标210，数据仅存在于220"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertSystemBlocked(result, QuoteCollaborationScanErrorCode.U9_ORGANIZATION_MISMATCH);
  }

  @Test
  @DisplayName("U9返回空对象或有BOM标志却没有明细时按数据异常阻断")
  void u9DataEmptyBlocks() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.dataEmpty("U9返回空数据"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertSystemBlocked(result, QuoteCollaborationScanErrorCode.U9_DATA_EMPTY);
  }

  @Test
  @DisplayName("主档缺失或形态未知不得误判裸品，也不得查询历史结果掩盖错误")
  void unknownProductFormBlocksBeforeHistory() {
    stubU9Available();
    stubProductType(QuoteProductType.DATA_MISSING);

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertSystemBlocked(result, QuoteCollaborationScanErrorCode.PRODUCT_FORM_DATA_MISSING);
    assertThat(result.productForm()).isEqualTo(CollaborationCodes.ProductForm.UNKNOWN);
    verifyNoInteractions(taskRepository, reviewRepository, sourceInspector, priceGateway);
  }

  @Test
  @DisplayName("U9已有BOM时始终以U9为权威，不查询历史补录结果")
  void u9BomAlwaysOverridesHistoricalSupplement() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(any())).thenReturn(CollaborationPriceScanResult.ready(6));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.authoritativeBomSource()).isEqualTo("U9");
    assertThat(result.approvedResultId()).isNull();
    verifyNoApprovedResultLookup();
    verifyNoInteractions(sourceInspector);
  }

  @Test
  @DisplayName("同月活动任务优先于半年结果，扫描只返回关联意图不写任务")
  void activeTaskWinsBeforeApprovedResult() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationProductTask active = activeTask(88L, PrimaryScope.FULL_BOM);
    when(taskRepository.findActiveProductTaskByLockKey(any(), any()))
        .thenReturn(java.util.Optional.of(active));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.WAITING_EXISTING_TASK);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.LINK_ACTIVE_TASK);
    assertThat(result.activeProductTaskId()).isEqualTo(88L);
    assertThat(result.completedStages()).doesNotContain(QuoteCollaborationScanStage.SIX_MONTH_APPROVED_RESULT);
    verifyNoInteractions(reviewRepository, sourceInspector, priceGateway);
    verify(taskRepository, never()).saveProductTask(any());
    verify(taskRepository, never()).saveQuoteLink(any());
  }

  @Test
  @DisplayName("进行中的FULL_BOM任务跨月份且当前只缺价格时仍复用，不并行新建PRICE_ONLY任务")
  void existingFullBomTaskIsReusedWhenCurrentScopeBecomesPriceOnly() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationProductTask active = activeTask(90L, PrimaryScope.FULL_BOM);
    active.setAccountingMonth("2026-07");
    when(taskRepository.findActiveProductTaskByLockKey(any(), any()))
        .thenReturn(java.util.Optional.of(active));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.LINK_ACTIVE_TASK);
    assertThat(result.activeProductTaskId()).isEqualTo(90L);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.PRICE_ONLY);
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("无正式料号新品优先按型号查找跨报价活动任务")
  void newProductWithoutCodeUsesModelKey() {
    stubNewProductQuote();
    QuoteCollaborationProductTask active = activeTask(89L, PrimaryScope.FULL_BOM);
    active.setProductCode(null);
    active.setProductModel("MODEL-N");
    active.setTemporaryProductKey("OA_FORM_ITEM:100");
    String lockKey = CollaborationActiveLockKeyFactory.create(
        null, "MODEL-N", "OA_FORM_ITEM:276",
        new CollaborationScope("COMMERCIAL", "210"));
    when(taskRepository.findActiveProductTaskByLockKey(
        eq(lockKey), eq(new CollaborationScope("COMMERCIAL", "210"))))
        .thenReturn(java.util.Optional.of(active));

    QuoteCollaborationScanResult result = service.scanQuoteItem(276L);

    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.LINK_ACTIVE_TASK);
    assertThat(result.activeProductTaskId()).isEqualTo(89L);
    verify(taskRepository).findActiveProductTaskByLockKey(
        lockKey, new CollaborationScope("COMMERCIAL", "210"));
    verifyNoInteractions(u9Gateway, productTypeService);
  }

  @Test
  @DisplayName("U9无BOM且半年结果来源可用时先验证来源，再按本次报价重新检查价格")
  void approvedFullBomResultIsInspectedThenRepriced() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationApprovedResult approved = approvedResult(99L, PrimaryScope.FULL_BOM);
    when(reviewRepository.findValidResults(any(), eq("FULL_BOM"), any(), any()))
        .thenReturn(List.of(approved));
    when(sourceInspector.inspect(eq(approved), any(), any()))
        .thenReturn(ApprovedSourceInspection.ready("ELECTRONIC_DRAWING", 18, "fingerprint-1"));
    when(priceGateway.check(any())).thenReturn(CollaborationPriceScanResult.ready(11));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.REUSABLE_RESULT);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.REUSE_APPROVED_RESULT);
    assertThat(result.approvedResultId()).isEqualTo(99L);
    assertThat(result.authoritativeBomSource()).isEqualTo("ELECTRONIC_DRAWING");
    assertThat(result.completedStages())
        .containsSubsequence(
            QuoteCollaborationScanStage.SIX_MONTH_APPROVED_RESULT,
            QuoteCollaborationScanStage.APPROVED_SOURCE,
            QuoteCollaborationScanStage.PRICE_PREPARATION);
    InOrder order =
        org.mockito.Mockito.inOrder(
            u9Gateway,
            productTypeService,
            taskRepository,
            reviewRepository,
            sourceInspector,
            priceGateway);
    order.verify(u9Gateway).read(any());
    order.verify(productTypeService).resolve("1008900001289", "COMMERCIAL");
    order.verify(taskRepository).findActiveProductTaskByLockKey(any(), any());
    order.verify(reviewRepository).findValidResults(any(), any(), any(), any());
    order.verify(sourceInspector).inspect(eq(approved), any(), any());
    order.verify(priceGateway).check(any());
  }

  @Test
  @DisplayName("半年BOM可复用但本次重新取价有缺口时只形成PRICE_ONLY")
  void reusableBomWithCurrentPriceGapsReturnsPriceOnly() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationApprovedResult approved = approvedResult(99L, PrimaryScope.FULL_BOM);
    when(reviewRepository.findValidResults(any(), eq("FULL_BOM"), any(), any()))
        .thenReturn(List.of(approved));
    when(sourceInspector.inspect(eq(approved), any(), any()))
        .thenReturn(ApprovedSourceInspection.ready("ELECTRONIC_DRAWING", 18, "fingerprint-1"));
    when(priceGateway.check(any()))
        .thenReturn(CollaborationPriceScanResult.gaps(10, List.of(priceGap("RAW-9", null))));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.CREATE_COLLABORATION);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.PRICE_ONLY);
    assertThat(result.approvedResultId()).isEqualTo(99L);
  }

  @Test
  @DisplayName("已审核结果来源读取异常按系统错误阻断，不伪装成新的技术缺口")
  void approvedSourceErrorBlocks() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationApprovedResult approved = approvedResult(99L, PrimaryScope.FULL_BOM);
    when(reviewRepository.findValidResults(any(), any(), any(), any()))
        .thenReturn(List.of(approved));
    when(sourceInspector.inspect(eq(approved), any(), any()))
        .thenReturn(ApprovedSourceInspection.error("电子图库读取超时"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertSystemBlocked(result, QuoteCollaborationScanErrorCode.APPROVED_SOURCE_ERROR);
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("D-08六个月边界时不再复用，但来源仍有效时返回最近结果供技术预填")
  void expiredResultBecomesPrefillReferenceAndReturnsToTechnicalFlow() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationApprovedResult expired = approvedResult(98L, PrimaryScope.FULL_BOM);
    expired.setValidUntil(LocalDateTime.of(2026, 8, 13, 10, 0));
    when(reviewRepository.findLatestExpiredReference(
        any(), eq("FULL_BOM"), any(), any())).thenReturn(java.util.Optional.of(expired));
    when(sourceInspector.inspect(eq(expired), any(), any()))
        .thenReturn(ApprovedSourceInspection.ready(
            "ELECTRONIC_DRAWING", 18, "fingerprint-1"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.CREATE_COLLABORATION);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.FULL_BOM);
    assertThat(result.approvedResultId()).isEqualTo(98L);
    assertThat(result.message()).contains("已到期", "重新确认");
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("六个月内电子图库结构指纹变化时立即停止复用并重新进入技术流程")
  void changedApprovedSourceCannotBeReused() {
    when(u9Gateway.read(any())).thenReturn(CurrentU9BomResult.notFound("U9无当前有效BOM"));
    stubProductType(QuoteProductType.NON_BARE);
    QuoteCollaborationApprovedResult approved = approvedResult(99L, PrimaryScope.FULL_BOM);
    when(reviewRepository.findValidResults(any(), eq("FULL_BOM"), any(), any()))
        .thenReturn(List.of(approved));
    when(sourceInspector.inspect(eq(approved), any(), any()))
        .thenReturn(ApprovedSourceInspection.invalid("电子图库BOM结构指纹已变化"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.CREATE_COLLABORATION);
    assertThat(result.requiredScope()).isEqualTo(PrimaryScope.FULL_BOM);
    assertThat(result.approvedResultId()).isNull();
    assertThat(result.message()).contains("结构指纹已变化", "重新确认");
    verifyNoInteractions(priceGateway);
  }

  @Test
  @DisplayName("价格服务异常或结构缺口不是技术补价，按系统错误阻断")
  void pricePreparationErrorDoesNotCreateFalsePriceTask() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(any()))
        .thenReturn(CollaborationPriceScanResult.error("价格准备只读计算失败"));

    QuoteCollaborationScanResult result = service.scanQuoteItem(275L);

    assertSystemBlocked(result, QuoteCollaborationScanErrorCode.PRICE_PREPARATION_ERROR);
    assertThat(result.requiredScope()).isNull();
  }

  @Test
  @DisplayName("相同输入重复扫描结果稳定，并且整个扫描链不写协作任务")
  void repeatedScanIsStableAndReadOnly() {
    stubU9Available();
    stubProductType(QuoteProductType.NON_BARE);
    when(priceGateway.check(any())).thenReturn(CollaborationPriceScanResult.ready(8));

    QuoteCollaborationScanResult first = service.scanQuoteItem(275L);
    QuoteCollaborationScanResult second = service.scanQuoteItem(275L);

    assertThat(second).isEqualTo(first);
    verify(u9Gateway, times(2)).read(any());
    verify(taskRepository, never()).saveTask(any());
    verify(taskRepository, never()).saveProductTask(any());
    verify(taskRepository, never()).saveQuoteLink(any());
    verify(reviewRepository, never()).saveApprovedResult(any());
  }

  @Test
  @DisplayName("扫描入口必须使用只读事务")
  void scanUsesReadOnlyTransaction() throws Exception {
    Transactional transactional =
        QuoteCollaborationScanServiceImpl.class
            .getMethod("scanQuoteItem", Long.class)
            .getAnnotation(Transactional.class);

    Assertions.assertNotNull(transactional);
    assertThat(transactional.readOnly()).isTrue();
    assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
  }

  private void stubQuote() {
    OaForm form = new OaForm();
    form.setId(27L);
    form.setOaNo("FI-SC-006-20260605-008");
    form.setProcessCode("FI-SC-006");
    form.setAccountingPeriodMonth("2026-08");
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setId(275L);
    item.setOaFormId(27L);
    item.setMaterialNo("1008900001289");
    item.setProductName("热力膨胀阀");
    item.setSpec("规格A");
    item.setSunlModel("RFKH11E-4.5-54A");
    item.setBusinessUnitType("COMMERCIAL");
    when(itemMapper.selectById(275L)).thenReturn(item);
    when(formMapper.selectById(27L)).thenReturn(form);
  }

  private void stubU9Available() {
    when(u9Gateway.read(any()))
        .thenReturn(CurrentU9BomResult.available("U9", "V6", "u9-batch-1", 18));
  }

  private void stubNewProductQuote() {
    OaForm form = new OaForm();
    form.setId(28L);
    form.setOaNo("FI-SC-006-20260813-NEW");
    form.setProcessCode("FI-SC-006");
    form.setAccountingPeriodMonth("2026-08");
    form.setBusinessUnitType("COMMERCIAL");
    OaFormItem item = new OaFormItem();
    item.setId(276L);
    item.setOaFormId(28L);
    item.setProductName("新品");
    item.setSpec("规格N");
    item.setSunlModel("MODEL-N");
    item.setBusinessUnitType("COMMERCIAL");
    when(itemMapper.selectById(276L)).thenReturn(item);
    when(formMapper.selectById(28L)).thenReturn(form);
  }

  private void stubProductType(QuoteProductType type) {
    when(productTypeService.resolve("1008900001289", "COMMERCIAL"))
        .thenReturn(
            new QuoteProductTypeResolveResult(
                "1008900001289",
                type,
                type == QuoteProductType.DATA_MISSING ? null : "110101",
                "制造件",
                "热力膨胀阀",
                "规格A",
                type == QuoteProductType.DATA_MISSING ? "料品主档缺失" : null));
  }

  private QuoteCollaborationProductTask activeTask(Long id, PrimaryScope scope) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setProductTaskNo("QCPT-20260813-ACTIVE");
    task.setProductCode("1008900001289");
    task.setAccountingMonth("2026-08");
    task.setPrimaryScope(scope.code());
    task.setTaskStatus("BOM_IN_PROGRESS");
    task.setCurrentAssigneeUserId(12L);
    task.setCurrentAssigneeName("王工");
    task.setActiveFlag(1);
    return task;
  }

  private QuoteCollaborationApprovedResult approvedResult(Long id, PrimaryScope scope) {
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setId(id);
    result.setResultNo("QCAR-20260813-0001");
    result.setResultType(scope.code());
    result.setProductCode("1008900001289");
    result.setApplicableOrgCode("210");
    result.setSourceObjectType("SUPPLEMENT_VERSION");
    result.setSourceObjectId(777L);
    result.setSourceSystem("ELECTRONIC_DRAWING");
    result.setStructureFingerprint("fingerprint-1");
    result.setValidFrom(LocalDateTime.of(2026, 8, 1, 0, 0));
    result.setValidUntil(LocalDateTime.of(2027, 1, 31, 23, 59));
    result.setResultStatus("ACTIVE");
    return result;
  }

  private CollaborationPriceScanResult.PriceGap priceGap(
      String materialCode, String existingOfficialPriceType) {
    return new CollaborationPriceScanResult.PriceGap(
        materialCode,
        "MISSING_PRICE",
        "MAINTAIN_PRICE",
        "当前没有有效正式价格",
        "lp_price_fixed_item",
        existingOfficialPriceType);
  }

  private void assertSystemBlocked(
      QuoteCollaborationScanResult result, QuoteCollaborationScanErrorCode errorCode) {
    assertThat(result.status()).isEqualTo(QuoteCollaborationScanStatus.SYSTEM_BLOCKED);
    assertThat(result.action()).isEqualTo(QuoteCollaborationScanAction.SYSTEM_BLOCKED);
    assertThat(result.errorCode()).isEqualTo(errorCode);
    assertThat(result.requiredScope()).isNull();
  }

  private void verifyNoApprovedResultLookup() {
    verify(reviewRepository, never()).findValidResults(any(), any(), any(), any());
  }
}
