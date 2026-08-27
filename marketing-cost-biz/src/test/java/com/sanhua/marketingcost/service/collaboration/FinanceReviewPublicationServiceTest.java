package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.QuoteCollaborationGapMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationPriceScanGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("审核生效时 U9 与电子图库 BOM 选择")
class FinanceReviewPublicationServiceTest {
  @Mock QuoteCollaborationReviewRepository reviewRepository;
  @Mock QuoteCollaborationTaskRepository taskRepository;
  @Mock QuotePriceDraftRepository draftRepository;
  @Mock QuoteCollaborationReviewItemMapper itemMapper;
  @Mock QuoteCollaborationReviewMapper reviewMapper;
  @Mock QuoteCollaborationGapMapper gapMapper;
  @Mock QuoteCollaborationProductTaskMapper productMapper;
  @Mock QuoteCollaborationTaskMapper taskMapper;
  @Mock CollaborationReviewStateService reviewStateService;
  @Mock CollaborationMasterStateService masterStateService;
  @Mock CollaborationProductStateService productStateService;
  @Mock CollaborationDraftStateService draftStateService;
  @Mock FormalPriceDraftPublisher formalPublisher;
  @Mock QuoteCollaborationApprovedResultService approvedResultService;
  @Mock QuoteCollaborationPriceScanGateway priceScanGateway;
  @Mock TechnicalRealPriceGapScanService technicalPriceScanService;
  @Mock JdbcTemplate jdbc;
  @Mock ApprovalBomSourcePolicy bomSourcePolicy;
  @Mock CollaborationStructuralDraftLifecycleService structuralLifecycle;
  @Mock ApprovedElectronicBomRawSnapshotPublisher electronicBomSnapshotPublisher;

  private FinanceReviewPublicationService service;
  private final CollaborationPrincipal reviewer = new CollaborationPrincipal(
      31L, "审核人", Set.of(CollaborationRole.FINANCE_REVIEWER));

  @BeforeEach
  void setup() {
    service = new FinanceReviewPublicationService(
        reviewRepository, taskRepository, draftRepository, itemMapper, reviewMapper,
        gapMapper, productMapper, taskMapper, reviewStateService, masterStateService,
        productStateService, draftStateService, formalPublisher, approvedResultService,
        priceScanGateway, technicalPriceScanService, jdbc, bomSourcePolicy, structuralLifecycle,
        electronicBomSnapshotPublisher);
  }

  @Test
  void u9AvailableAtApprovalUsesU9AndDoesNotPublishSupplement() {
    Scenario s = recheckScenario();
    ApprovalBomSourcePolicy.LinkDecision source = new ApprovalBomSourcePolicy.LinkDecision(
        s.link(), context(s), CurrentU9BomResult.available("U9", "V2", null, 8), false);
    when(bomSourcePolicy.inspect(s.product(), List.of(s.link())))
        .thenReturn(new ApprovalBomSourcePolicy.Decision(false, List.of(source)));
    when(priceScanGateway.check(source.context())).thenReturn(CollaborationPriceScanResult.ready(6));

    service.recheckAndActivate(phase(), "COMMERCIAL");

    verify(priceScanGateway).check(source.context());
    verify(technicalPriceScanService, never()).scan(any(), any(), anyString());
    verify(structuralLifecycle).supersedeBomWithU9(s.product(), reviewer.actor());
    verify(structuralLifecycle, never()).approveBom(any(), any(), anyString());
    verify(approvedResultService, never()).activate(any());
  }

  @Test
  void explicitU9NotFoundPublishesApprovedSupplementForReuse() {
    Scenario s = recheckScenario();
    ApprovalBomSourcePolicy.LinkDecision source = new ApprovalBomSourcePolicy.LinkDecision(
        s.link(), context(s), CurrentU9BomResult.notFound("无BOM"), true);
    when(bomSourcePolicy.inspect(s.product(), List.of(s.link())))
        .thenReturn(new ApprovalBomSourcePolicy.Decision(true, List.of(source)));
    when(technicalPriceScanService.scan(s.product(), s.link(), "2026-08"))
        .thenReturn(CollaborationPriceScanResult.ready(6));

    service.recheckAndActivate(phase(), "COMMERCIAL");

    verify(technicalPriceScanService).scan(s.product(), s.link(), "2026-08");
    verify(priceScanGateway, never()).check(any());
    verify(structuralLifecycle).approveBom(s.product(), reviewer.actor(), "审核通过");
    verify(electronicBomSnapshotPublisher).publish(s.product());
    verify(approvedResultService).activate(any(ApprovedResultActivationCommand.class));
  }

  @Test
  void u9PreflightFailureHappensBeforeAnyFormalPublicationSideEffect() {
    QuoteCollaborationReview review = review("PENDING");
    QuoteCollaborationTask master = master("WAIT_FINANCE");
    QuoteCollaborationProductTask product = product();
    QuoteCollaborationQuoteLink link = link();
    QuoteCollaborationReviewItem item = item();
    when(reviewRepository.findReviewById(10L, "COMMERCIAL"))
        .thenReturn(java.util.Optional.of(review));
    when(itemMapper.selectFinanceItems(10L, 31L, "COMMERCIAL")).thenReturn(List.of(item));
    when(taskRepository.findTaskById(1L, "COMMERCIAL")).thenReturn(java.util.Optional.of(master));
    when(taskRepository.findProductTasksByCollaboration(1L, "COMMERCIAL"))
        .thenReturn(List.of(product));
    when(taskRepository.findLinksByProductTask(101L, scope())).thenReturn(List.of(link));
    when(bomSourcePolicy.inspect(product, List.of(link)))
        .thenThrow(new CollaborationDomainException(
            CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, "U9 查询超时"));

    assertThatThrownBy(() -> service.approveAndPublish(10L, "COMMERCIAL", reviewer))
        .hasMessageContaining("U9 查询超时");

    verify(formalPublisher, never()).publish(any(), any(), anyString(), any());
    verify(reviewStateService, never()).transition(anyLong(), anyInt(), anyString(), anyString(), any(), any());
  }

  private Scenario recheckScenario() {
    QuoteCollaborationReview review = review("PUBLISHING");
    QuoteCollaborationTask master = master("PUBLISHING");
    QuoteCollaborationProductTask product = product();
    QuoteCollaborationQuoteLink link = link();
    when(reviewRepository.findReviewById(10L, "COMMERCIAL"))
        .thenReturn(java.util.Optional.of(review));
    when(taskRepository.findTaskById(1L, "COMMERCIAL"))
        .thenReturn(java.util.Optional.of(master));
    when(itemMapper.selectFinanceItems(10L, 31L, "COMMERCIAL")).thenReturn(List.of(item()));
    when(taskRepository.findProductTasksByCollaboration(1L, "COMMERCIAL"))
        .thenReturn(List.of(product));
    when(taskRepository.findLinksByProductTask(101L, scope())).thenReturn(List.of(link));
    when(taskRepository.findProductTaskById(101L, scope()))
        .thenReturn(java.util.Optional.of(product));
    when(taskMapper.refreshReadyProductCount(1L, "COMMERCIAL", 0L, "系统")).thenReturn(1);
    return new Scenario(product, link);
  }

  private FinanceReviewPublicationService.PhaseResult phase() {
    return new FinanceReviewPublicationService.PhaseResult(10L, 1L, "BATCH", reviewer);
  }

  private QuoteCollaborationReview review(String status) {
    QuoteCollaborationReview value = new QuoteCollaborationReview();
    value.setId(10L);
    value.setCollaborationId(1L);
    value.setReviewStatus(status);
    value.setSourceTaskVersion(3);
    value.setReviewerUserId(31L);
    return value;
  }

  private QuoteCollaborationTask master(String status) {
    QuoteCollaborationTask value = new QuoteCollaborationTask();
    value.setId(1L);
    value.setBusinessUnitType("COMMERCIAL");
    value.setMasterStatus(status);
    value.setTaskVersion(4);
    return value;
  }

  private QuoteCollaborationProductTask product() {
    QuoteCollaborationProductTask value = new QuoteCollaborationProductTask();
    value.setId(101L);
    value.setOriginCollaborationId(1L);
    value.setProductCode("P-1");
    value.setProductName("产品");
    value.setProductSpec("S");
    value.setProductModel("M");
    value.setAccountingMonth("2026-08");
    value.setBusinessUnitType("COMMERCIAL");
    value.setApplicableOrgCode("210");
    value.setPriceOrgCode("210");
    value.setMaterialOrgCode("COMMERCIAL");
    value.setPrimaryScope("FULL_BOM");
    value.setTaskStatus("APPROVED_PUBLISHING");
    value.setTaskVersion(5);
    value.setSupplementVersionId(90L);
    return value;
  }

  private QuoteCollaborationQuoteLink link() {
    QuoteCollaborationQuoteLink value = new QuoteCollaborationQuoteLink();
    value.setId(201L);
    value.setProductTaskId(101L);
    value.setOaFormId(301L);
    value.setOaFormItemId(302L);
    value.setOaNo("OA-1");
    value.setAccountingMonth("2026-08");
    value.setLinkStatus("READY");
    value.setActiveFlag(1);
    return value;
  }

  private QuoteCollaborationReviewItem item() {
    QuoteCollaborationReviewItem value = new QuoteCollaborationReviewItem();
    value.setId(401L);
    value.setReviewId(10L);
    value.setProductTaskId(101L);
    value.setItemType("BOM");
    value.setDecision("PASSED");
    return value;
  }

  private QuoteCollaborationScanContext context(Scenario scenario) {
    return new QuoteCollaborationScanContext(301L, 302L, "OA-1", "2026-08", "COMMERCIAL",
        "P-1", "产品", "S", "M", "210", "COMMERCIAL",
        LocalDate.of(2026, 8, 25), LocalDateTime.of(2026, 8, 25, 10, 0));
  }

  private CollaborationScope scope() {
    return new CollaborationScope("COMMERCIAL", "210");
  }

  private record Scenario(
      QuoteCollaborationProductTask product,
      QuoteCollaborationQuoteLink link) {}
}
