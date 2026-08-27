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
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("QCBP-18 多技术提交与财务审核聚合")
class TechnicalSubmissionCoordinatorTest {
  @Mock QuoteCollaborationTaskRepository taskRepository;
  @Mock QuotePriceDraftRepository draftRepository;
  @Mock QuoteCollaborationReviewRepository reviewRepository;
  @Mock CollaborationDraftStateService draftStateService;
  @Mock CollaborationProductStateService productStateService;
  @Mock CollaborationMasterStateService masterStateService;
  @Mock QuoteCollaborationTaskMapper taskMapper;
  @Mock QuoteCollaborationReviewMapper reviewMapper;
  @Mock CollaborationStructuralDraftLifecycleService structuralDraftLifecycle;

  TechnicalSubmissionCoordinator coordinator;
  CollaborationPrincipal technician = new CollaborationPrincipal(
      21L, "技术甲", Set.of(CollaborationRole.TECHNICIAN));

  @BeforeEach
  void setUp() {
    coordinator = new TechnicalSubmissionCoordinator(taskRepository, draftRepository,
        reviewRepository, draftStateService, productStateService, masterStateService,
        taskMapper, reviewMapper, new ObjectMapper(), structuralDraftLifecycle);
  }

  @Test
  void waitsForOtherTechniciansWithoutCreatingReview() {
    QuoteCollaborationTask master = master("WAIT_TECH", 31L);
    QuoteCollaborationProductTask submitted = product(101L, "TECH_SUBMITTED");
    QuoteCollaborationProductTask waiting = product(102L, "PRICE_IN_PROGRESS");
    when(taskMapper.selectScopedForUpdate(1L, "COMMERCIAL")).thenReturn(master);
    when(taskRepository.findProductTasksByCollaboration(1L, "COMMERCIAL"))
        .thenReturn(List.of(submitted, waiting));

    coordinator.aggregateAfterSubmission(submitted, technician);

    verify(reviewRepository, never()).saveReview(any());
    verify(masterStateService, never()).transition(anyLong(), anyInt(), anyString(), any(), any());
  }

  @Test
  void finalTechnicianCreatesExactlyOneReviewAndRoutesAllSubmittedProducts() {
    QuoteCollaborationTask master = master("WAIT_TECH", 31L);
    QuoteCollaborationTask routed = master("WAIT_FINANCE", 31L);
    routed.setTaskVersion(4);
    QuoteCollaborationProductTask one = product(101L, "TECH_SUBMITTED");
    QuoteCollaborationProductTask two = product(102L, "TECH_SUBMITTED");
    when(taskMapper.selectScopedForUpdate(1L, "COMMERCIAL")).thenReturn(master);
    when(taskRepository.findProductTasksByCollaboration(1L, "COMMERCIAL"))
        .thenReturn(List.of(one, two));
    when(masterStateService.transition(anyLong(), anyInt(), anyString(), any(), any()))
        .thenReturn(routed);
    when(reviewMapper.selectMaxRound(1L)).thenReturn(0);
    when(draftRepository.findByProductTask(anyLong(), any())).thenReturn(List.of());
    when(taskRepository.findGaps(anyLong(), any())).thenReturn(List.of());
    when(reviewRepository.saveReview(any())).thenAnswer(invocation -> {
      QuoteCollaborationReview review = invocation.getArgument(0);
      review.setId(501L);
      return review;
    });
    when(taskMapper.attachCurrentReview(1L, 501L, 2, "COMMERCIAL", 21L, "技术甲"))
        .thenReturn(1);
    when(productStateService.transition(anyLong(), anyInt(), any(), any(), any()))
        .thenAnswer(invocation -> {
          Long id = invocation.getArgument(0);
          QuoteCollaborationProductTask updated = product(id, "WAIT_FINANCE");
          return new CollaborationProductStateService.ProductTransitionResult(
              updated, CollaborationNextAction.WAIT_FINANCE);
        });

    coordinator.aggregateAfterSubmission(two, technician);

    verify(reviewRepository).saveReview(any());
    verify(reviewRepository).saveReviewItems(any());
    verify(taskMapper).attachCurrentReview(1L, 501L, 2, "COMMERCIAL", 21L, "技术甲");
    verify(productStateService, times(2)).transition(anyLong(), anyInt(), any(), any(), any());
  }

  @Test
  void blocksSubmissionWhenFinanceReviewerIsNotConfigured() {
    QuoteCollaborationTask master = master("WAIT_TECH", null);
    QuoteCollaborationProductTask submitted = product(101L, "TECH_SUBMITTED");
    when(taskMapper.selectScopedForUpdate(1L, "COMMERCIAL")).thenReturn(master);

    assertThatThrownBy(() -> coordinator.aggregateAfterSubmission(submitted, technician))
        .isInstanceOf(CollaborationDomainException.class)
        .hasMessageContaining("未配置财务审核人");
    verify(reviewRepository, never()).saveReview(any());
  }

  private static QuoteCollaborationTask master(String status, Long financeId) {
    QuoteCollaborationTask task = new QuoteCollaborationTask();
    task.setId(1L);
    task.setBusinessUnitType("COMMERCIAL");
    task.setMasterStatus(status);
    task.setTaskVersion(3);
    task.setFinanceReviewerUserId(financeId);
    task.setFinanceReviewerName(financeId == null ? null : "财务甲");
    return task;
  }

  private static QuoteCollaborationProductTask product(Long id, String status) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setOriginCollaborationId(1L);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("C01");
    task.setProductCode("P" + id);
    task.setProductName("产品" + id);
    task.setTaskStatus(status);
    task.setTaskVersion(2);
    task.setActiveFlag(1);
    task.setNeedBom(1);
    task.setSupplementVersionId(id + 1000);
    task.setLastValidationStatus("PASSED");
    return task;
  }
}
