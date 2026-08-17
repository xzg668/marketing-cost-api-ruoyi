package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
@DisplayName("QCBP-20/21 财务退回与发布复验分流")
class FinanceReviewApplicationServiceTest {
  @Mock CollaborationCurrentPrincipalProvider principalProvider;
  @Mock QuoteCollaborationReviewRepository reviewRepository;
  @Mock QuoteCollaborationTaskRepository taskRepository;
  @Mock QuoteCollaborationReviewItemMapper itemMapper;
  @Mock QuoteCollaborationReviewMapper reviewMapper;
  @Mock CollaborationReviewStateService reviewStateService;
  @Mock CollaborationDraftStateService draftStateService;
  @Mock CollaborationProductStateService productStateService;
  @Mock CollaborationMasterStateService masterStateService;
  @Mock QuotePriceDraftRepository draftRepository;
  @Mock QuoteCollaborationTaskMapper taskMapper;
  @Mock FinanceReviewPublicationService publicationService;

  private FinanceReviewApplicationService service;
  private final CollaborationPrincipal finance = new CollaborationPrincipal(
      31L, "财务甲", Set.of(CollaborationRole.FINANCE_REVIEWER));

  @BeforeEach
  void setUp() {
    var authentication = new UsernamePasswordAuthenticationToken("finance", null, List.of());
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(principalProvider.currentFinanceReviewer()).thenReturn(finance);
    service = new FinanceReviewApplicationService(principalProvider, reviewRepository,
        taskRepository, itemMapper, reviewMapper, reviewStateService, draftStateService,
        productStateService, masterStateService, draftRepository, taskMapper,
        publicationService, new ObjectMapper());
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void rejectReopensOnlyTheProductContainingRejectedItem() {
    QuoteCollaborationReview review = review("PARTIAL");
    QuoteCollaborationTask master = master("WAIT_FINANCE");
    QuoteCollaborationProductTask rejectedProduct = product(101L, "WAIT_FINANCE");
    QuoteCollaborationProductTask passedProduct = product(102L, "WAIT_FINANCE");
    QuoteCollaborationReviewItem rejected = item(1001L, 101L, 501L, "REJECTED");
    QuoteCollaborationReviewItem passed = item(1002L, 102L, 502L, "PASSED");
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setId(501L);
    draft.setDraftVersion(3);
    draft.setDraftStatus("SUBMITTED");

    when(reviewRepository.findReviewById(10L, "COMMERCIAL")).thenReturn(java.util.Optional.of(review));
    when(itemMapper.selectFinanceItems(10L, 31L, "COMMERCIAL"))
        .thenReturn(List.of(rejected, passed));
    when(taskRepository.findTaskById(1L, "COMMERCIAL"))
        .thenReturn(java.util.Optional.of(master));
    when(taskRepository.findProductTasksByCollaboration(1L, "COMMERCIAL"))
        .thenReturn(List.of(rejectedProduct, passedProduct));
    when(draftRepository.findById(501L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(java.util.Optional.of(draft));
    when(masterStateService.transition(anyLong(), anyInt(), anyString(), any(), any()))
        .thenReturn(master("WAIT_TECH"));
    when(taskMapper.detachRejectedReview(1L, 10L, 1, "COMMERCIAL", 31L, "财务甲"))
        .thenReturn(1);

    service.reject(10L, new FinanceReviewSubmitRequest("退回一项"));

    verify(draftStateService).transition(501L, 3,
        new CollaborationScope("COMMERCIAL", "210"),
        CollaborationActions.DraftAction.REJECT, finance);
    verify(productStateService, times(1)).transition(anyLong(), anyInt(), any(),
        any(), any());
    verify(productStateService).transition(101L, 2,
        new CollaborationScope("COMMERCIAL", "210"),
        CollaborationActions.ProductAction.REJECT_TO_TECH, finance);
    verify(productStateService, never()).transition(org.mockito.ArgumentMatchers.eq(102L),
        anyInt(), any(), any(), any());
  }

  @Test
  void businessGapReturnsToTechnicianAndIsNotRecordedAsSystemFailure() {
    FinanceReviewPublicationService.PhaseResult phase =
        new FinanceReviewPublicationService.PhaseResult(10L, 1L, "BATCH-1", finance);
    var failure = new FinanceReviewPublicationService.BusinessRecheckException(
        101L, List.of(), "仍有真实缺价");
    when(publicationService.approveAndPublish(10L, "COMMERCIAL", finance)).thenReturn(phase);
    org.mockito.Mockito.doThrow(failure).when(publicationService)
        .recheckAndActivate(phase, "COMMERCIAL");

    assertThatThrownBy(() -> service.approve(10L, new FinanceReviewSubmitRequest("通过")))
        .isSameAs(failure);

    verify(publicationService).markBusinessGap(phase, "COMMERCIAL", failure);
    verify(publicationService, never()).markSystemFailure(any(), anyString(), anyString());
  }

  @Test
  void systemFailureKeepsPublicationAndOnlyMarksPhaseBRetryable() {
    FinanceReviewPublicationService.PhaseResult phase =
        new FinanceReviewPublicationService.PhaseResult(10L, 1L, "BATCH-1", finance);
    RuntimeException failure = new RuntimeException("取价服务超时");
    when(publicationService.approveAndPublish(10L, "COMMERCIAL", finance)).thenReturn(phase);
    org.mockito.Mockito.doThrow(failure).when(publicationService)
        .recheckAndActivate(phase, "COMMERCIAL");

    assertThatThrownBy(() -> service.approve(10L, new FinanceReviewSubmitRequest("通过")))
        .isSameAs(failure);

    verify(publicationService).markSystemFailure(phase, "COMMERCIAL", "取价服务超时");
    verify(publicationService, never()).markBusinessGap(any(), anyString(), any());
  }

  private QuoteCollaborationReview review(String status) {
    QuoteCollaborationReview review = new QuoteCollaborationReview();
    review.setId(10L);
    review.setCollaborationId(1L);
    review.setReviewNo("REV-10");
    review.setReviewRound(1);
    review.setReviewStatus(status);
    review.setSourceTaskVersion(2);
    review.setReviewerUserId(31L);
    review.setProductCount(2);
    review.setPriceDraftCount(2);
    review.setPassedItemCount(1);
    review.setRejectedItemCount(1);
    return review;
  }

  private QuoteCollaborationTask master(String status) {
    QuoteCollaborationTask task = new QuoteCollaborationTask();
    task.setId(1L);
    task.setOaNo("OA-1");
    task.setBusinessUnitType("COMMERCIAL");
    task.setMasterStatus(status);
    task.setTaskVersion(4);
    return task;
  }

  private QuoteCollaborationProductTask product(Long id, String status) {
    QuoteCollaborationProductTask product = new QuoteCollaborationProductTask();
    product.setId(id);
    product.setOriginCollaborationId(1L);
    product.setProductCode("P-" + id);
    product.setProductName("产品" + id);
    product.setBusinessUnitType("COMMERCIAL");
    product.setApplicableOrgCode("210");
    product.setTaskStatus(status);
    product.setTaskVersion(2);
    return product;
  }

  private QuoteCollaborationReviewItem item(
      Long id, Long productId, Long refId, String decision) {
    QuoteCollaborationReviewItem item = new QuoteCollaborationReviewItem();
    item.setId(id);
    item.setReviewId(10L);
    item.setProductTaskId(productId);
    item.setItemType("PRICE_DRAFT");
    item.setItemRefId(refId);
    item.setItemSummary("补价");
    item.setDecision(decision);
    item.setDecisionReason("REJECTED".equals(decision) ? "价格不正确" : null);
    return item;
  }
}
