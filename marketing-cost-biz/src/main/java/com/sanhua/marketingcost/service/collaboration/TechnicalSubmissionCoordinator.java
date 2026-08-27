package com.sanhua.marketingcost.service.collaboration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.DraftAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** QCBP-18：锁定技术草稿，并在整单最后一个产品提交时只生成一轮财务审核。 */
@Service
public class TechnicalSubmissionCoordinator {
  private static final Set<ProductTaskStatus> TECHNICALLY_FINISHED = Set.of(
      ProductTaskStatus.TECH_SUBMITTED, ProductTaskStatus.WAIT_FINANCE,
      ProductTaskStatus.APPROVED_PUBLISHING, ProductTaskStatus.PUBLISH_OR_REPRICE_FAILED,
      ProductTaskStatus.READY_FOR_COSTING, ProductTaskStatus.COSTING,
      ProductTaskStatus.COMPLETED, ProductTaskStatus.CANCELLED);
  private static final CollaborationPrincipal SYSTEM = new CollaborationPrincipal(
      0L, "系统", Set.of(CollaborationRole.SYSTEM));

  private final QuoteCollaborationTaskRepository taskRepository;
  private final QuotePriceDraftRepository draftRepository;
  private final QuoteCollaborationReviewRepository reviewRepository;
  private final CollaborationDraftStateService draftStateService;
  private final CollaborationProductStateService productStateService;
  private final CollaborationMasterStateService masterStateService;
  private final QuoteCollaborationTaskMapper taskMapper;
  private final QuoteCollaborationReviewMapper reviewMapper;
  private final ObjectMapper objectMapper;
  private final CollaborationStructuralDraftLifecycleService structuralDraftLifecycle;

  public TechnicalSubmissionCoordinator(
      QuoteCollaborationTaskRepository taskRepository,
      QuotePriceDraftRepository draftRepository,
      QuoteCollaborationReviewRepository reviewRepository,
      CollaborationDraftStateService draftStateService,
      CollaborationProductStateService productStateService,
      CollaborationMasterStateService masterStateService,
      QuoteCollaborationTaskMapper taskMapper,
      QuoteCollaborationReviewMapper reviewMapper,
      ObjectMapper objectMapper,
      CollaborationStructuralDraftLifecycleService structuralDraftLifecycle) {
    this.taskRepository = taskRepository;
    this.draftRepository = draftRepository;
    this.reviewRepository = reviewRepository;
    this.draftStateService = draftStateService;
    this.productStateService = productStateService;
    this.masterStateService = masterStateService;
    this.taskMapper = taskMapper;
    this.reviewMapper = reviewMapper;
    this.objectMapper = objectMapper;
    this.structuralDraftLifecycle = structuralDraftLifecycle;
  }

  @Transactional
  public void lockCurrentPriceDrafts(
      QuoteCollaborationProductTask task, List<QuoteCollaborationGap> gaps,
      CollaborationPrincipal technician) {
    CollaborationScope scope = scope(task);
    Set<Long> ids = new HashSet<>();
    for (QuoteCollaborationGap gap : gaps) {
      if ("PRICE".equals(gap.getGapCategory()) && "DRAFT_READY".equals(gap.getGapStatus())
          && gap.getCurrentPriceDraftId() != null) ids.add(gap.getCurrentPriceDraftId());
    }
    for (Long id : ids) {
      QuotePriceDraft draft = draftRepository.findById(id, scope).orElseThrow(() ->
          new IllegalStateException("缺价明细引用的价格草稿不存在：" + id));
      if (!"VALIDATED".equals(draft.getDraftStatus())
          || !"PASSED".equals(draft.getValidationStatus())) {
        throw new IllegalStateException("价格草稿尚未校验通过：" + draft.getMaterialCode());
      }
      draftStateService.transition(id, draft.getDraftVersion(), scope, DraftAction.SUBMIT, technician);
    }
  }

  @Transactional
  public QuoteCollaborationProductTask aggregateAfterSubmission(
      QuoteCollaborationProductTask submitted, CollaborationPrincipal technician) {
    structuralDraftLifecycle.submit(submitted, technician.actor());
    QuoteCollaborationTask master = taskMapper.selectScopedForUpdate(
        submitted.getOriginCollaborationId(), submitted.getBusinessUnitType());
    if (master == null) throw new IllegalStateException("产品所属协作主任务不存在");
    if (master.getFinanceReviewerUserId() == null) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH,
          "本报价单尚未配置财务审核人，不能提交；请先配置审核人");
    }
    List<QuoteCollaborationProductTask> products = taskRepository.findProductTasksByCollaboration(
        master.getId(), master.getBusinessUnitType());
    boolean waitingTechnician = products.stream().filter(product -> product.getActiveFlag() == 1)
        .map(product -> ProductTaskStatus.valueOf(product.getTaskStatus()))
        .anyMatch(status -> !TECHNICALLY_FINISHED.contains(status));
    if (waitingTechnician) return submitted;
    if (master.getCurrentReviewId() != null) {
      return taskRepository.findProductTaskById(submitted.getId(), scope(submitted)).orElse(submitted);
    }

    QuoteCollaborationTask routed = masterStateService.transition(
        master.getId(), master.getTaskVersion(), master.getBusinessUnitType(),
        MasterAction.ROUTE_TO_FINANCE, SYSTEM);
    int round = reviewMapper.selectMaxRound(master.getId()) + 1;
    List<QuoteCollaborationProductTask> submittedProducts = products.stream()
        .filter(product -> product.getActiveFlag() == 1)
        .filter(product -> ProductTaskStatus.TECH_SUBMITTED.name().equals(product.getTaskStatus()))
        .toList();
    List<QuotePriceDraft> drafts = submittedProducts.stream()
        .flatMap(product -> draftRepository.findByProductTask(product.getId(), scope(product)).stream())
        .filter(draft -> "SUBMITTED".equals(draft.getDraftStatus())).toList();

    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setCollaborationId(master.getId());
    review.setReviewRound(round);
    review.setReviewStatus("PENDING");
    review.setReviewerUserId(master.getFinanceReviewerUserId());
    review.setReviewerName(master.getFinanceReviewerName());
    review.setSourceTaskVersion(routed.getTaskVersion());
    review.setProductCount(submittedProducts.size());
    review.setPriceDraftCount(drafts.size());
    review.setPassedItemCount(0);
    review.setRejectedItemCount(0);
    review.setSubmittedAt(LocalDateTime.now());
    review.setCreatedBy(technician.userId());
    review.setCreatedByName(technician.userName());
    review.setUpdatedBy(technician.userId());
    review.setUpdatedByName(technician.userName());
    reviewRepository.saveReview(review);
    reviewRepository.saveReviewItems(reviewItems(review, submittedProducts, drafts));
    int attached = taskMapper.attachCurrentReview(master.getId(), review.getId(),
        submittedProducts.size(), master.getBusinessUnitType(),
        technician.userId(), technician.userName());
    if (attached != 1) throw new IllegalStateException("财务审核聚合发生并发冲突，请刷新后重试");

    QuoteCollaborationProductTask result = submitted;
    for (QuoteCollaborationProductTask product : submittedProducts) {
      QuoteCollaborationProductTask routedProduct = productStateService.transition(
          product.getId(), product.getTaskVersion(), scope(product),
          ProductAction.ROUTE_TO_FINANCE, SYSTEM).task();
      if (product.getId().equals(submitted.getId())) result = routedProduct;
    }
    return result;
  }

  private List<QuoteCollaborationReviewItem> reviewItems(
      QuoteCollaborationReview review, List<QuoteCollaborationProductTask> products,
      List<QuotePriceDraft> drafts) {
    Map<Long, QuotePriceDraft> byId = new LinkedHashMap<>();
    drafts.forEach(draft -> byId.put(draft.getId(), draft));
    List<QuoteCollaborationReviewItem> items = new ArrayList<>();
    for (QuoteCollaborationProductTask product : products) {
      if (enabled(product.getNeedBom())) items.add(item(review, product, "BOM",
          product.getSupplementVersionId(), product.getTaskVersion(), "本次补录完整BOM",
          Map.of("electronicBomFingerprint", value(product.getElectronicBomFingerprint()))));
      if (enabled(product.getNeedPackage())) items.add(item(review, product, "PACKAGE",
          product.getPackageReferenceId(), product.getTaskVersion(), "本次补录裸品包装",
          Map.of("packageReferenceId", product.getPackageReferenceId())));
      for (QuoteCollaborationGap gap : taskRepository.findGaps(product.getId(), scope(product))) {
        QuotePriceDraft draft = byId.get(gap.getCurrentPriceDraftId());
        if (draft == null || !"PRICE".equals(gap.getGapCategory())) continue;
        List<QuotePriceDraftField> fields = draftRepository.findFields(draft.getId(), scope(product));
        items.add(item(review, product, "PRICE_DRAFT", draft.getId(), draft.getDraftVersion(),
            draft.getMaterialCode() + " · " + draft.getPriceType(), priceSnapshot(draft, fields)));
      }
    }
    if (items.isEmpty()) throw new IllegalStateException("没有可供财务审核的技术补录内容");
    return items;
  }

  private QuoteCollaborationReviewItem item(
      QuoteCollaborationReview review, QuoteCollaborationProductTask product,
      String type, Long refId, Integer version, String summary, Map<String, Object> difference) {
    if (refId == null) throw new IllegalStateException(summary + "缺少结果引用");
    QuoteCollaborationReviewItem item = new QuoteCollaborationReviewItem();
    item.setReviewId(review.getId());
    item.setProductTaskId(product.getId());
    item.setItemType(type);
    item.setItemRefId(refId);
    item.setItemVersion(version);
    item.setItemSummary(summary);
    item.setDifferenceSnapshotJson(json(difference));
    item.setValidationSnapshotJson(json(Map.of(
        "status", value(product.getLastValidationStatus()),
        "validatedAt", value(product.getLastValidationAt()))));
    item.setDecision("PENDING");
    return item;
  }

  private Map<String, Object> priceSnapshot(
      QuotePriceDraft draft, List<QuotePriceDraftField> fields) {
    List<Map<String, Object>> values = fields.stream().map(field -> Map.<String, Object>of(
        "section", value(field.getSectionCode()), "row", value(field.getRowKey()),
        "code", value(field.getFieldCode()), "name", value(field.getFieldName()),
        "reference", value(field.getReferenceValueJson()), "target", value(field.getTargetValueJson()),
        "validation", value(field.getValidationStatus()))).toList();
    return Map.of("priceType", draft.getPriceType(),
        "referenceSourceType", value(draft.getReferenceSourceType()),
        "referenceSourceId", value(draft.getReferenceSourceId()), "fields", values);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("审核快照生成失败", exception);
    }
  }

  private static CollaborationScope scope(QuoteCollaborationProductTask task) {
    return new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode());
  }

  private static boolean enabled(Integer value) { return value != null && value == 1; }
  private static Object value(Object value) { return value == null ? "" : value; }
}
