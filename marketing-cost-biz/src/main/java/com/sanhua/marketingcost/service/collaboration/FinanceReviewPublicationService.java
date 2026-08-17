package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.mapper.QuoteCollaborationGapMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationPriceScanGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.QuoteLinkAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** QCBP-21 发布Saga的事务阶段：A提交正式价；B复验并放行，失败可独立补偿。 */
@Service
public class FinanceReviewPublicationService {
  private static final CollaborationPrincipal SYSTEM = new CollaborationPrincipal(
      0L, "系统", Set.of(CollaborationRole.SYSTEM));

  private final QuoteCollaborationReviewRepository reviewRepository;
  private final QuoteCollaborationTaskRepository taskRepository;
  private final QuotePriceDraftRepository draftRepository;
  private final QuoteCollaborationReviewItemMapper itemMapper;
  private final QuoteCollaborationReviewMapper reviewMapper;
  private final QuoteCollaborationGapMapper gapMapper;
  private final QuoteCollaborationProductTaskMapper productMapper;
  private final QuoteCollaborationTaskMapper taskMapper;
  private final CollaborationReviewStateService reviewStateService;
  private final CollaborationMasterStateService masterStateService;
  private final CollaborationProductStateService productStateService;
  private final CollaborationDraftStateService draftStateService;
  private final FormalPriceDraftPublisher formalPublisher;
  private final QuoteCollaborationApprovedResultService approvedResultService;
  private final QuoteCollaborationPriceScanGateway priceScanGateway;
  private final TechnicalRealPriceGapScanService technicalPriceScanService;
  private final JdbcTemplate jdbc;

  public FinanceReviewPublicationService(
      QuoteCollaborationReviewRepository reviewRepository,
      QuoteCollaborationTaskRepository taskRepository,
      QuotePriceDraftRepository draftRepository,
      QuoteCollaborationReviewItemMapper itemMapper,
      QuoteCollaborationReviewMapper reviewMapper,
      QuoteCollaborationGapMapper gapMapper,
      QuoteCollaborationProductTaskMapper productMapper,
      QuoteCollaborationTaskMapper taskMapper,
      CollaborationReviewStateService reviewStateService,
      CollaborationMasterStateService masterStateService,
      CollaborationProductStateService productStateService,
      CollaborationDraftStateService draftStateService,
      FormalPriceDraftPublisher formalPublisher,
      QuoteCollaborationApprovedResultService approvedResultService,
      QuoteCollaborationPriceScanGateway priceScanGateway,
      TechnicalRealPriceGapScanService technicalPriceScanService,
      JdbcTemplate jdbc) {
    this.reviewRepository = reviewRepository;
    this.taskRepository = taskRepository;
    this.draftRepository = draftRepository;
    this.itemMapper = itemMapper;
    this.reviewMapper = reviewMapper;
    this.gapMapper = gapMapper;
    this.productMapper = productMapper;
    this.taskMapper = taskMapper;
    this.reviewStateService = reviewStateService;
    this.masterStateService = masterStateService;
    this.productStateService = productStateService;
    this.draftStateService = draftStateService;
    this.formalPublisher = formalPublisher;
    this.approvedResultService = approvedResultService;
    this.priceScanGateway = priceScanGateway;
    this.technicalPriceScanService = technicalPriceScanService;
    this.jdbc = jdbc;
  }

  @Transactional
  public PhaseResult approveAndPublish(
      Long reviewId, String businessUnit, CollaborationPrincipal finance) {
    QuoteCollaborationReview review = reviewRepository.findReviewById(reviewId, businessUnit)
        .orElseThrow(() -> notFound("财务审核不存在"));
    if (!finance.userId().equals(review.getReviewerUserId())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH, "当前用户不是指定财务审核人");
    }
    if ("EFFECTIVE".equals(review.getReviewStatus()) || "PUBLISHING".equals(review.getReviewStatus())) {
      return new PhaseResult(reviewId, review.getCollaborationId(), review.getPublishBatchNo(), finance);
    }
    if (!List.of("PENDING", "PARTIAL").contains(review.getReviewStatus())) {
      throw new IllegalStateException("当前审核状态不能执行统一通过");
    }
    List<QuoteCollaborationReviewItem> items = itemMapper.selectFinanceItems(
        reviewId, finance.userId(), businessUnit);
    if (items.isEmpty() || items.stream().anyMatch(item -> !"PASSED".equals(item.getDecision()))) {
      throw new IllegalStateException("所有审核项逐项通过后，才能统一审核通过并生效");
    }
    QuoteCollaborationTask master = taskRepository.findTaskById(
        review.getCollaborationId(), businessUnit).orElseThrow();
    Map<Long, QuoteCollaborationProductTask> products = taskRepository
        .findProductTasksByCollaboration(master.getId(), businessUnit).stream()
        .collect(Collectors.toMap(QuoteCollaborationProductTask::getId, Function.identity()));
    Set<Long> reviewProducts = items.stream().map(QuoteCollaborationReviewItem::getProductTaskId)
        .collect(Collectors.toSet());

    for (QuoteCollaborationReviewItem item : items) {
      if (!"PRICE_DRAFT".equals(item.getItemType())) continue;
      QuoteCollaborationProductTask product = products.get(item.getProductTaskId());
      QuotePriceDraft draft = draftRepository.findById(item.getItemRefId(), scope(product))
          .orElseThrow(() -> notFound("待发布价格草稿不存在"));
      if ("SUBMITTED".equals(draft.getDraftStatus())) {
        draftStateService.transition(draft.getId(), draft.getDraftVersion(), scope(product),
            DraftAction.APPROVE, finance);
      }
    }
    for (Long productId : reviewProducts) {
      QuoteCollaborationProductTask product = products.get(productId);
      if ("WAIT_FINANCE".equals(product.getTaskStatus())) {
        QuoteCollaborationProductTask updated = productStateService.transition(
            product.getId(), product.getTaskVersion(), scope(product),
            ProductAction.APPROVE_FOR_PUBLISHING, finance).task();
        products.put(productId, updated);
      }
    }
    review = reviewStateService.transition(reviewId, review.getSourceTaskVersion(),
        review.getReviewStatus(), businessUnit, ReviewAction.SUBMIT_APPROVED, finance);
    master = masterStateService.transition(master.getId(), master.getTaskVersion(), businessUnit,
        MasterAction.FINANCE_APPROVE, finance);
    review = reviewStateService.transition(reviewId, review.getSourceTaskVersion(),
        review.getReviewStatus(), businessUnit, ReviewAction.START_PUBLISHING, SYSTEM);

    String batchNo = review.getPublishBatchNo();
    if (batchNo == null) {
      batchNo = "QCP_" + UUID.randomUUID().toString().replace("-", "");
      if (reviewMapper.attachPublishBatch(reviewId, batchNo, finance.userId(), finance.userName()) != 1) {
        throw new IllegalStateException("发布批次生成发生并发冲突");
      }
    }
    for (Long productId : reviewProducts) {
      QuoteCollaborationProductTask product = products.get(productId);
      for (QuotePriceDraft draft : draftRepository.findByProductTask(productId, scope(product))) {
        if ("APPROVED".equals(draft.getDraftStatus())) {
          formalPublisher.publish(draft, scope(product), batchNo, finance);
        }
      }
    }
    return new PhaseResult(reviewId, master.getId(), batchNo, finance);
  }

  @Transactional
  public void recheckAndActivate(PhaseResult phase, String businessUnit) {
    QuoteCollaborationReview review = reviewRepository.findReviewById(phase.reviewId(), businessUnit)
        .orElseThrow(() -> notFound("发布审核不存在"));
    QuoteCollaborationTask master = taskRepository.findTaskById(phase.collaborationId(), businessUnit)
        .orElseThrow();
    List<QuoteCollaborationReviewItem> items = itemMapper.selectFinanceItems(
        review.getId(), phase.finance().userId(), businessUnit);
    Set<Long> productIds = items.stream().map(QuoteCollaborationReviewItem::getProductTaskId)
        .collect(Collectors.toSet());
    Map<Long, QuoteCollaborationProductTask> products = taskRepository
        .findProductTasksByCollaboration(master.getId(), businessUnit).stream()
        .collect(Collectors.toMap(QuoteCollaborationProductTask::getId, Function.identity()));
    for (Long productId : productIds) {
      QuoteCollaborationProductTask product = products.get(productId);
      recheckEveryLinkedQuote(product);
      gapMapper.resolvePublishedPriceGaps(productId, product.getBusinessUnitType(),
          product.getApplicableOrgCode(), phase.finance().userId(), phase.finance().userName());
      productMapper.clearPublishedPriceGaps(productId, product.getBusinessUnitType(),
          product.getApplicableOrgCode(), phase.finance().userId(), phase.finance().userName());
      product = taskRepository.findProductTaskById(productId, scope(product)).orElseThrow();
      if ("APPROVED_PUBLISHING".equals(product.getTaskStatus())) {
        productStateService.transition(productId, product.getTaskVersion(), scope(product),
            ProductAction.MARK_READY_FOR_COSTING, SYSTEM);
      }
      markLinksReady(productId, scope(product));
    }
    if (taskMapper.refreshReadyProductCount(master.getId(), businessUnit,
        SYSTEM.userId(), SYSTEM.userName()) != 1) {
      throw new IllegalStateException("刷新协作主任务可核算产品数失败");
    }
    reviewStateService.transition(review.getId(), review.getSourceTaskVersion(),
        review.getReviewStatus(), businessUnit, ReviewAction.MARK_EFFECTIVE, SYSTEM);
    for (Long productId : productIds) {
      QuoteCollaborationProductTask product = products.get(productId);
      if ("FULL_BOM".equals(product.getPrimaryScope())
          || "BARE_PACKAGE".equals(product.getPrimaryScope())) {
        approvedResultService.activate(new ApprovedResultActivationCommand(
            productId, review.getId(), product.getBusinessUnitType(),
            product.getApplicableOrgCode(), phase.finance().actor()));
      }
    }
    QuoteCollaborationTask current = taskRepository.findTaskById(master.getId(), businessUnit)
        .orElseThrow();
    masterStateService.transition(current.getId(), current.getTaskVersion(), businessUnit,
        MasterAction.MARK_READY_FOR_COSTING, SYSTEM);
  }

  @Transactional
  public void markSystemFailure(PhaseResult phase, String businessUnit, String message) {
    QuoteCollaborationReview review = reviewRepository.findReviewById(phase.reviewId(), businessUnit)
        .orElse(null);
    if (review != null && "PUBLISHING".equals(review.getReviewStatus())) {
      reviewStateService.transition(review.getId(), review.getSourceTaskVersion(),
          review.getReviewStatus(), businessUnit, ReviewAction.MARK_FAILED, SYSTEM);
    }
    QuoteCollaborationTask master = taskRepository.findTaskById(phase.collaborationId(), businessUnit)
        .orElse(null);
    if (master != null && "PUBLISHING".equals(master.getMasterStatus())) {
      for (QuoteCollaborationProductTask product : taskRepository.findProductTasksByCollaboration(
          master.getId(), businessUnit)) {
        if ("APPROVED_PUBLISHING".equals(product.getTaskStatus())) {
          productStateService.transition(product.getId(), product.getTaskVersion(), scope(product),
              ProductAction.FAIL_PUBLISH_OR_REPRICE, SYSTEM);
        }
      }
      masterStateService.transition(master.getId(), master.getTaskVersion(), businessUnit,
          MasterAction.MARK_PUBLISH_FAILED, SYSTEM);
    }
  }

  /** 业务仍缺价：保存真实缺口，退回原技术；正式发布记录保留且不重复发布。 */
  @Transactional
  public void markBusinessGap(
      PhaseResult phase, String businessUnit, BusinessRecheckException failure) {
    QuoteCollaborationReview review = reviewRepository.findReviewById(phase.reviewId(), businessUnit)
        .orElseThrow(() -> notFound("发布审核不存在"));
    QuoteCollaborationTask master = taskRepository.findTaskById(phase.collaborationId(), businessUnit)
        .orElseThrow();
    QuoteCollaborationProductTask product = taskRepository.findProductTasksByCollaboration(
        master.getId(), businessUnit).stream()
        .filter(row -> row.getId().equals(failure.productTaskId())).findFirst()
        .orElseThrow(() -> notFound("复验缺价所属产品不存在"));
    CollaborationScope scope = scope(product);
    taskRepository.synchronizeGaps(product.getId(), scope, failure.gaps(), SYSTEM.actor());
    gapMapper.clearPublishedDraftFromReopenedGaps(product.getId(), scope.businessUnitType(),
        scope.applicableOrgCode(), SYSTEM.userId(), SYSTEM.userName());
    if (productMapper.reopenBusinessPriceGaps(product.getId(), failure.gaps().size(),
        scope.businessUnitType(), scope.applicableOrgCode(), SYSTEM.userId(), SYSTEM.userName()) != 1) {
      throw new IllegalStateException("业务缺价回写产品任务失败");
    }
    product = taskRepository.findProductTaskById(product.getId(), scope).orElseThrow();
    product = productStateService.transition(product.getId(), product.getTaskVersion(), scope,
        ProductAction.FAIL_PUBLISH_OR_REPRICE, SYSTEM).task();
    productStateService.transition(product.getId(), product.getTaskVersion(), scope,
        ProductAction.RETURN_BUSINESS_GAP_TO_TECH, SYSTEM);
    if ("PUBLISHING".equals(review.getReviewStatus())) {
      reviewStateService.transition(review.getId(), review.getSourceTaskVersion(),
          review.getReviewStatus(), businessUnit, ReviewAction.MARK_FAILED, SYSTEM);
    }
    if ("PUBLISHING".equals(master.getMasterStatus())) {
      master = masterStateService.transition(master.getId(), master.getTaskVersion(), businessUnit,
          MasterAction.MARK_PUBLISH_FAILED, SYSTEM);
    }
    if ("PUBLISH_FAILED".equals(master.getMasterStatus())) {
      master = masterStateService.transition(master.getId(), master.getTaskVersion(), businessUnit,
          MasterAction.RETURN_BUSINESS_GAP_TO_TECH, SYSTEM);
    }
    if (taskMapper.detachRejectedReview(master.getId(), review.getId(), 1, businessUnit,
        SYSTEM.userId(), SYSTEM.userName()) != 1) {
      throw new IllegalStateException("业务缺价退回时解除当前审核失败");
    }
  }

  /** 阶段B系统异常只重试复验，不再发布正式价。 */
  @Transactional
  public PhaseResult retryRecheck(
      Long reviewId, String businessUnit, CollaborationPrincipal finance) {
    QuoteCollaborationReview review = reviewRepository.findReviewById(reviewId, businessUnit)
        .orElseThrow(() -> notFound("发布审核不存在"));
    if (!finance.userId().equals(review.getReviewerUserId())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH, "当前用户不是指定财务审核人");
    }
    if (!"FAILED".equals(review.getReviewStatus())) {
      throw new IllegalStateException("只有系统复验失败的审核才能重试");
    }
    QuoteCollaborationTask master = taskRepository.findTaskById(
        review.getCollaborationId(), businessUnit).orElseThrow();
    if (!"PUBLISH_FAILED".equals(master.getMasterStatus())) {
      throw new IllegalStateException("协作主任务不是可重试发布状态");
    }
    reviewStateService.transition(review.getId(), review.getSourceTaskVersion(),
        review.getReviewStatus(), businessUnit, ReviewAction.RETRY_PUBLISHING, SYSTEM);
    master = masterStateService.transition(master.getId(), master.getTaskVersion(), businessUnit,
        MasterAction.RETRY_PUBLISH, SYSTEM);
    for (QuoteCollaborationProductTask product : taskRepository.findProductTasksByCollaboration(
        master.getId(), businessUnit)) {
      if ("PUBLISH_OR_REPRICE_FAILED".equals(product.getTaskStatus())) {
        productStateService.transition(product.getId(), product.getTaskVersion(), scope(product),
            ProductAction.RETRY_PUBLISH_OR_REPRICE, SYSTEM);
      }
    }
    return new PhaseResult(reviewId, master.getId(), review.getPublishBatchNo(), finance);
  }

  private boolean formalExists(QuotePriceDraft draft) {
    String table = draft.getPublishedSourceTable();
    if (!Set.of("lp_price_fixed_item", "lp_price_linked_item", "lp_price_range_item").contains(table)) {
      return false;
    }
    Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id=?",
        Integer.class, draft.getPublishedSourceId());
    return count != null && count == 1;
  }

  private void recheckEveryLinkedQuote(QuoteCollaborationProductTask product) {
    List<QuoteCollaborationQuoteLink> links = taskRepository.findLinksByProductTask(
        product.getId(), scope(product)).stream()
        .filter(link -> Integer.valueOf(1).equals(link.getActiveFlag())).toList();
    if (links.isEmpty()) throw new IllegalStateException("产品任务没有活动报价关联，无法重新取价");
    for (QuoteCollaborationQuoteLink link : links) {
      CollaborationPriceScanResult result;
      if ("FULL_BOM".equals(product.getPrimaryScope())
          || "BARE_PACKAGE".equals(product.getPrimaryScope())) {
        result = technicalPriceScanService.scan(product, link, link.getAccountingMonth());
      } else {
        YearMonth month = YearMonth.parse(link.getAccountingMonth());
        result = priceScanGateway.check(new QuoteCollaborationScanContext(
            link.getOaFormId(), link.getOaFormItemId(), link.getOaNo(), link.getAccountingMonth(),
            product.getBusinessUnitType(), product.getProductCode(), product.getProductName(),
            product.getProductSpec(), product.getProductModel(), product.getPriceOrgCode(),
            product.getMaterialOrgCode(), month.atEndOfMonth(), LocalDateTime.now()));
      }
      if (result == null || result.status() == CollaborationPriceScanResult.Status.ERROR) {
        throw new IllegalStateException(result == null ? "重新取价没有返回结果" : result.message());
      }
      if (result.status() == CollaborationPriceScanResult.Status.GAPS) {
        List<GapUpsertCommand> gaps = result.gaps().stream()
            .map(gap -> CollaborationPriceGapCommandFactory.create(product.getProductCode(), gap))
            .toList();
        throw new BusinessRecheckException(product.getId(), gaps,
            "重新取价仍有" + gaps.size() + "项真实缺价");
      }
      if (result.status() != CollaborationPriceScanResult.Status.READY) {
        throw new IllegalStateException("重新取价尚未就绪：" + result.status());
      }
    }
  }

  private void markLinksReady(Long productId, CollaborationScope scope) {
    for (QuoteCollaborationQuoteLink link : taskRepository.findLinksByProductTask(productId, scope)) {
      if (!Integer.valueOf(1).equals(link.getActiveFlag()) || "READY".equals(link.getLinkStatus())) continue;
      String status = link.getLinkStatus();
      if ("WAIT_SOURCE".equals(status)) {
        link = taskRepository.transitionQuoteLinkStatus(link.getId(), status, "RECHECKING", scope,
            SYSTEM.actor());
      } else if ("FAILED".equals(status)) {
        link = taskRepository.transitionQuoteLinkStatus(link.getId(), status, "RECHECKING", scope,
            SYSTEM.actor());
      }
      if ("RECHECKING".equals(link.getLinkStatus())) {
        taskRepository.transitionQuoteLinkStatus(link.getId(), "RECHECKING", "READY", scope,
            SYSTEM.actor());
      }
    }
  }

  private static CollaborationScope scope(QuoteCollaborationProductTask product) {
    if (product == null) throw notFound("产品任务不存在");
    return new CollaborationScope(product.getBusinessUnitType(), product.getApplicableOrgCode());
  }

  private static CollaborationDomainException notFound(String message) {
    return new CollaborationDomainException(CollaborationDomainErrorCode.TASK_NOT_FOUND, message);
  }

  public record PhaseResult(Long reviewId, Long collaborationId, String batchNo,
                            CollaborationPrincipal finance) {}
  public static class BusinessRecheckException extends IllegalStateException {
    private final Long productTaskId;
    private final List<GapUpsertCommand> gaps;
    public BusinessRecheckException(
        Long productTaskId, List<GapUpsertCommand> gaps, String message) {
      super(message);
      this.productTaskId = productTaskId;
      this.gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }
    public Long productTaskId() { return productTaskId; }
    public List<GapUpsertCommand> gaps() { return gaps; }
  }
}
