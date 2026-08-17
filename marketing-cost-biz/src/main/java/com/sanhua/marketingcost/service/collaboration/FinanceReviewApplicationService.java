package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewDecisionRequest;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewDetailResponse;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewListResponse;
import com.sanhua.marketingcost.dto.collaboration.FinanceReviewSubmitRequest;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ReviewAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** QCBP-19/20 财务本人审核入口；管理员通配权限不能代替指定财务责任人。 */
@Service
public class FinanceReviewApplicationService {
  private final CollaborationCurrentPrincipalProvider principalProvider;
  private final QuoteCollaborationReviewRepository reviewRepository;
  private final QuoteCollaborationTaskRepository taskRepository;
  private final QuoteCollaborationReviewItemMapper itemMapper;
  private final QuoteCollaborationReviewMapper reviewMapper;
  private final CollaborationReviewStateService reviewStateService;
  private final CollaborationDraftStateService draftStateService;
  private final CollaborationProductStateService productStateService;
  private final CollaborationMasterStateService masterStateService;
  private final QuotePriceDraftRepository draftRepository;
  private final QuoteCollaborationTaskMapper taskMapper;
  private final FinanceReviewPublicationService publicationService;
  private final ObjectMapper objectMapper;

  public FinanceReviewApplicationService(
      CollaborationCurrentPrincipalProvider principalProvider,
      QuoteCollaborationReviewRepository reviewRepository,
      QuoteCollaborationTaskRepository taskRepository,
      QuoteCollaborationReviewItemMapper itemMapper,
      QuoteCollaborationReviewMapper reviewMapper,
      CollaborationReviewStateService reviewStateService,
      CollaborationDraftStateService draftStateService,
      CollaborationProductStateService productStateService,
      CollaborationMasterStateService masterStateService,
      QuotePriceDraftRepository draftRepository,
      QuoteCollaborationTaskMapper taskMapper,
      FinanceReviewPublicationService publicationService,
      ObjectMapper objectMapper) {
    this.principalProvider = principalProvider;
    this.reviewRepository = reviewRepository;
    this.taskRepository = taskRepository;
    this.itemMapper = itemMapper;
    this.reviewMapper = reviewMapper;
    this.reviewStateService = reviewStateService;
    this.draftStateService = draftStateService;
    this.productStateService = productStateService;
    this.masterStateService = masterStateService;
    this.draftRepository = draftRepository;
    this.taskMapper = taskMapper;
    this.publicationService = publicationService;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public FinanceReviewListResponse mine(boolean completed) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    List<String> statuses = completed
        ? List.of("REJECTED", "APPROVED", "PUBLISHING", "EFFECTIVE", "FAILED")
        : List.of("PENDING", "PARTIAL");
    List<FinanceReviewListResponse.Item> items = reviewRepository.findReviewsByReviewer(
        principal.userId(), statuses, businessUnit()).stream().map(review -> {
          QuoteCollaborationTask master = taskRepository.findTaskById(
              review.getCollaborationId(), businessUnit()).orElseThrow();
          return new FinanceReviewListResponse.Item(review.getId(), review.getReviewNo(),
              master.getOaNo(), review.getReviewRound(), review.getReviewStatus(),
              review.getProductCount(), review.getPriceDraftCount(), review.getPassedItemCount(),
              review.getRejectedItemCount(), review.getSubmittedAt());
        }).toList();
    return new FinanceReviewListResponse(items.size(), items);
  }

  @Transactional(readOnly = true)
  public FinanceReviewDetailResponse detail(Long reviewId) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    QuoteCollaborationReview review = ownReview(reviewId, principal);
    QuoteCollaborationTask master = taskRepository.findTaskById(
        review.getCollaborationId(), businessUnit()).orElseThrow();
    List<QuoteCollaborationProductTask> products = taskRepository.findProductTasksByCollaboration(
        master.getId(), businessUnit());
    List<QuoteCollaborationReviewItem> items = itemMapper.selectFinanceItems(
        reviewId, principal.userId(), businessUnit());
    return new FinanceReviewDetailResponse(review.getId(), review.getReviewNo(), master.getOaNo(),
        review.getReviewRound(), review.getReviewStatus(), review.getSourceTaskVersion(),
        review.getProductCount(), review.getPriceDraftCount(), review.getPassedItemCount(),
        review.getRejectedItemCount(), !items.isEmpty()
            && items.stream().allMatch(item -> "PASSED".equals(item.getDecision())),
        products.stream().map(product -> new FinanceReviewDetailResponse.Product(
            product.getId(), product.getProductCode(), product.getProductName(),
            product.getProductSpec(), product.getProductModel(), product.getTaskStatus())).toList(),
        items.stream().map(this::item).toList());
  }

  @Transactional(readOnly = true)
  public FinanceReviewDetailResponse.ItemDetail itemDetail(Long reviewId, Long itemId) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    ownReview(reviewId, principal);
    QuoteCollaborationReviewItem item = itemMapper.selectFinanceItem(
        reviewId, itemId, principal.userId(), businessUnit());
    if (item == null) throw notFound("审核项不存在或不属于本人");
    QuoteCollaborationReview review = ownReview(reviewId, principal);
    Map<Long, QuoteCollaborationProductTask> products = taskRepository.findProductTasksByCollaboration(
        review.getCollaborationId(), businessUnit()).stream().collect(
            Collectors.toMap(QuoteCollaborationProductTask::getId, Function.identity()));
    QuoteCollaborationProductTask product = products.get(item.getProductTaskId());
    if (product == null) throw notFound("审核项所属产品不存在");
    return new FinanceReviewDetailResponse.ItemDetail(item.getId(), item.getProductTaskId(),
        product.getProductCode(), product.getProductName(), item.getItemType(),
        itemTypeLabel(item.getItemType()), item.getItemSummary(),
        json(item.getDifferenceSnapshotJson()), json(item.getValidationSnapshotJson()),
        item.getDecision(), item.getDecisionReason());
  }

  @Transactional
  public FinanceReviewDetailResponse decide(
      Long reviewId, Long itemId, FinanceReviewDecisionRequest request) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    QuoteCollaborationReview review = ownReview(reviewId, principal);
    String decision = request == null ? "" : String.valueOf(request.decision()).toUpperCase();
    if (!List.of("PASSED", "REJECTED").contains(decision)) {
      throw new IllegalArgumentException("审核结论只能是通过或退回");
    }
    String reason = request == null ? null : request.reason();
    if ("REJECTED".equals(decision) && !StringUtils.hasText(reason)) {
      throw new IllegalArgumentException("退回时必须填写明确修改原因");
    }
    QuoteCollaborationReviewItem current = itemMapper.selectFinanceItem(
        reviewId, itemId, principal.userId(), businessUnit());
    if (current == null) throw notFound("审核项不存在或不属于本人");
    if (!"PENDING".equals(current.getDecision())) {
      if (decision.equals(current.getDecision())) return detail(reviewId);
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "该审核项已经决定，不能重复修改");
    }
    int affected = itemMapper.decide(reviewId, itemId, decision,
        StringUtils.hasText(reason) ? reason.trim() : null,
        principal.userId(), principal.userName());
    if (affected != 1) throw new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "审核项状态已变化，请刷新后重试");
    reviewMapper.refreshDecisionCounts(reviewId, principal.userId(),
        principal.userId(), principal.userName());
    reviewStateService.transition(reviewId, review.getSourceTaskVersion(), review.getReviewStatus(),
        businessUnit(), ReviewAction.SAVE_PARTIAL, principal);
    return detail(reviewId);
  }

  @Transactional
  public FinanceReviewDetailResponse reject(Long reviewId, FinanceReviewSubmitRequest request) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    QuoteCollaborationReview review = ownReview(reviewId, principal);
    if (!List.of("PENDING", "PARTIAL").contains(review.getReviewStatus())) {
      if ("REJECTED".equals(review.getReviewStatus())) return detail(reviewId);
      throw new IllegalStateException("当前审核状态不能退回");
    }
    List<QuoteCollaborationReviewItem> items = itemMapper.selectFinanceItems(
        reviewId, principal.userId(), businessUnit());
    if (items.stream().anyMatch(item -> "PENDING".equals(item.getDecision()))) {
      throw new IllegalStateException("请先逐项审核全部内容，再统一退回");
    }
    List<QuoteCollaborationReviewItem> rejected = items.stream()
        .filter(item -> "REJECTED".equals(item.getDecision())).toList();
    if (rejected.isEmpty()) throw new IllegalStateException("至少选择一个明确问题项才能退回");
    QuoteCollaborationTask master = taskRepository.findTaskById(
        review.getCollaborationId(), businessUnit()).orElseThrow();
    Map<Long, QuoteCollaborationProductTask> products = taskRepository
        .findProductTasksByCollaboration(master.getId(), businessUnit()).stream()
        .collect(Collectors.toMap(QuoteCollaborationProductTask::getId, Function.identity()));

    for (QuoteCollaborationReviewItem item : rejected) {
      if (!"PRICE_DRAFT".equals(item.getItemType())) continue;
      QuoteCollaborationProductTask product = products.get(item.getProductTaskId());
      QuotePriceDraft draft = draftRepository.findById(item.getItemRefId(), scope(product))
          .orElseThrow(() -> notFound("被退回价格草稿不存在"));
      if ("SUBMITTED".equals(draft.getDraftStatus())) {
        draftStateService.transition(draft.getId(), draft.getDraftVersion(), scope(product),
            DraftAction.REJECT, principal);
      }
    }
    Set<Long> rejectedProducts = rejected.stream()
        .map(QuoteCollaborationReviewItem::getProductTaskId).collect(Collectors.toSet());
    for (Long productId : rejectedProducts) {
      QuoteCollaborationProductTask product = products.get(productId);
      if (product != null && "WAIT_FINANCE".equals(product.getTaskStatus())) {
        productStateService.transition(product.getId(), product.getTaskVersion(), scope(product),
            ProductAction.REJECT_TO_TECH, principal);
      }
    }
    reviewStateService.transition(reviewId, review.getSourceTaskVersion(), review.getReviewStatus(),
        businessUnit(), ReviewAction.SUBMIT_REJECTED, principal);
    QuoteCollaborationTask returned = masterStateService.transition(master.getId(),
        master.getTaskVersion(), businessUnit(), MasterAction.FINANCE_REJECT, principal);
    int detached = taskMapper.detachRejectedReview(returned.getId(), reviewId,
        rejectedProducts.size(), businessUnit(), principal.userId(), principal.userName());
    if (detached != 1) throw new IllegalStateException("退回审核任务时发生并发冲突");
    return detail(reviewId);
  }

  /** 阶段A提交后即使阶段B系统异常也保留正式发布结果，后续只重试复验。 */
  public FinanceReviewDetailResponse approve(Long reviewId, FinanceReviewSubmitRequest request) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    FinanceReviewPublicationService.PhaseResult phase = publicationService.approveAndPublish(
        reviewId, businessUnit(), principal);
    try {
      publicationService.recheckAndActivate(phase, businessUnit());
    } catch (FinanceReviewPublicationService.BusinessRecheckException exception) {
      publicationService.markBusinessGap(phase, businessUnit(), exception);
      throw exception;
    } catch (RuntimeException exception) {
      publicationService.markSystemFailure(phase, businessUnit(), exception.getMessage());
      throw exception;
    }
    return detail(reviewId);
  }

  public FinanceReviewDetailResponse retryRecheck(Long reviewId) {
    CollaborationPrincipal principal = principalProvider.currentFinanceReviewer();
    FinanceReviewPublicationService.PhaseResult phase = publicationService.retryRecheck(
        reviewId, businessUnit(), principal);
    try {
      publicationService.recheckAndActivate(phase, businessUnit());
    } catch (FinanceReviewPublicationService.BusinessRecheckException exception) {
      publicationService.markBusinessGap(phase, businessUnit(), exception);
      throw exception;
    } catch (RuntimeException exception) {
      publicationService.markSystemFailure(phase, businessUnit(), exception.getMessage());
      throw exception;
    }
    return detail(reviewId);
  }

  private QuoteCollaborationReview ownReview(Long reviewId, CollaborationPrincipal principal) {
    QuoteCollaborationReview review = reviewRepository.findReviewById(reviewId, businessUnit())
        .orElseThrow(() -> notFound("财务审核不存在"));
    if (!principal.userId().equals(review.getReviewerUserId())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH, "当前用户不是本审核单指定财务人员");
    }
    return review;
  }

  private FinanceReviewDetailResponse.Item item(QuoteCollaborationReviewItem item) {
    return new FinanceReviewDetailResponse.Item(item.getId(), item.getProductTaskId(),
        item.getItemType(), itemTypeLabel(item.getItemType()), item.getItemSummary(),
        item.getDecision(), item.getDecisionReason());
  }

  private JsonNode json(String value) {
    try { return StringUtils.hasText(value) ? objectMapper.readTree(value) : objectMapper.nullNode(); }
    catch (Exception exception) { throw new IllegalStateException("审核快照无法读取", exception); }
  }

  private static String itemTypeLabel(String type) {
    return switch (type == null ? "" : type) {
      case "BOM" -> "本次补录BOM";
      case "PACKAGE" -> "本次补录包装";
      case "PRICE_DRAFT" -> "本次补价";
      default -> "技术本次填写";
    };
  }

  private static CollaborationScope scope(QuoteCollaborationProductTask product) {
    if (product == null) throw notFound("审核项所属产品不存在");
    return new CollaborationScope(product.getBusinessUnitType(), product.getApplicableOrgCode());
  }

  private static String businessUnit() {
    return CollaborationScope.requireBusinessUnit(BusinessUnitContext.getCurrentBusinessUnitType());
  }

  private static CollaborationDomainException notFound(String message) {
    return new CollaborationDomainException(CollaborationDomainErrorCode.TASK_NOT_FOUND, message);
  }
}
