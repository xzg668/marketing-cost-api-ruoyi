package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@DisplayName("QCBP-09 技术本人任务应用服务")
class TechnicalTaskApplicationServiceTest {
  private final QuoteCollaborationTaskRepository repository =
      mock(QuoteCollaborationTaskRepository.class);
  private final CollaborationCurrentPrincipalProvider principalProvider =
      mock(CollaborationCurrentPrincipalProvider.class);
  private final CollaborationNextActionCalculator nextActionCalculator =
      mock(CollaborationNextActionCalculator.class);
  private final CollaborationProductStateService stateService =
      mock(CollaborationProductStateService.class);
  private final QuotePriceDraftRepository draftRepository = mock(QuotePriceDraftRepository.class);
  private final TechnicalTaskValidator validator = new TechnicalTaskValidator(draftRepository);
  private final CollaborationTaskLogService taskLogService = mock(CollaborationTaskLogService.class);
  private final QuoteCollaborationReviewItemMapper reviewItemMapper =
      mock(QuoteCollaborationReviewItemMapper.class);
  private final TechnicalSubmissionCoordinator submissionCoordinator =
      mock(TechnicalSubmissionCoordinator.class);
  private final TechnicalTaskApplicationService service = new TechnicalTaskApplicationService(
      repository, principalProvider, nextActionCalculator, stateService, validator,
      submissionCoordinator, taskLogService, reviewItemMapper,
      new CollaborationPortalAccessPolicy());
  private final CollaborationPrincipal wang = new CollaborationPrincipal(
      601L, "王工", Set.of(CollaborationRole.TECHNICIAN));

  @BeforeEach
  void setContext() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken("wang", null, List.of());
    authentication.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    when(principalProvider.currentTechnician()).thenReturn(wang);
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void mineUsesOnlyCurrentUserAndLoginBusinessUnit() {
    QuoteCollaborationProductTask task = task(11L, "WAIT_TECH", 601L);
    when(repository.findMineByTechnician(601L, "COMMERCIAL")).thenReturn(List.of(task));
    when(nextActionCalculator.calculate(task, wang))
        .thenReturn(CollaborationNextAction.SUPPLEMENT_BOM);

    assertThat(service.mine().items()).extracting(item -> item.taskId()).containsExactly(11L);
    verify(repository).findMineByTechnician(601L, "COMMERCIAL");
  }

  @Test
  void changingTaskIdCannotReadOtherTechniciansTask() {
    when(repository.findMineById(22L, 601L, "COMMERCIAL")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.detail(22L))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code()).isEqualTo(CollaborationDomainErrorCode.TASK_NOT_FOUND));
  }

  @Test
  void submittedTaskRemainsReadableButEveryWriteIsRejected() {
    QuoteCollaborationProductTask task = task(11L, "TECH_SUBMITTED", null);
    when(repository.findMineById(11L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(repository.findGaps(11L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(repository.findLinksByProductTask(11L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(nextActionCalculator.calculate(task, wang))
        .thenReturn(CollaborationNextAction.WAIT_FINANCE);

    assertThat(service.detail(11L).editable()).isFalse();
    assertThatThrownBy(() -> service.start(11L, 3))
        .isInstanceOfSatisfying(CollaborationDomainException.class,
            error -> assertThat(error.code())
                .isEqualTo(CollaborationDomainErrorCode.STATE_TRANSITION_INVALID));
  }

  @Test
  void completedContentShowsValidationAsTheOnlyNextStepBeforeSubmit() {
    QuoteCollaborationProductTask task = task(12L, "BOM_IN_PROGRESS", 601L);
    task.setSupplementVersionId(99L);
    task.setElectronicBomFingerprint("a".repeat(64));
    task.setLastValidationStatus("NOT_CHECKED");
    when(repository.findMineById(12L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(repository.findGaps(12L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(repository.findLinksByProductTask(12L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(nextActionCalculator.calculate(task, wang))
        .thenReturn(CollaborationNextAction.VERIFY_ELECTRONIC_BOM);

    assertThat(service.detail(12L))
        .extracting(response -> response.nextAction(), response -> response.nextActionLabel(),
            response -> response.guidance())
        .containsExactly("VALIDATE_COMPLETENESS", "检查完整性",
            "内容已齐，请先校验完整性，再提交财务审核。");
  }

  @Test
  void passedValidationShowsSubmitGuidanceAndNotAnEmptyState() {
    QuoteCollaborationProductTask task = task(13L, "BOM_IN_PROGRESS", 601L);
    task.setSupplementVersionId(99L);
    task.setElectronicBomFingerprint("a".repeat(64));
    task.setLastValidationStatus("PASSED");
    when(repository.findMineById(13L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(repository.findGaps(13L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(repository.findLinksByProductTask(13L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(nextActionCalculator.calculate(task, wang))
        .thenReturn(CollaborationNextAction.SUBMIT_FINANCE_REVIEW);

    assertThat(service.detail(13L).guidance())
        .isEqualTo("校验已通过，可以提交财务审核。");
  }

  @Test
  void validatedPriceDraftsShowCompletenessCheckEvenWhileBusinessGapsRemainOpen() {
    QuoteCollaborationProductTask task = task(15L, "PRICE_IN_PROGRESS", 601L);
    task.setNeedBom(0);
    task.setNeedPrice(1);
    task.setOpenGapCount(2);
    task.setLastValidationStatus("NOT_CHECKED");
    QuoteCollaborationGap first = readyPriceGap(151L);
    QuoteCollaborationGap second = readyPriceGap(152L);
    when(repository.findMineById(15L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(repository.findGaps(15L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of(first, second));
    when(repository.findLinksByProductTask(15L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(nextActionCalculator.calculate(task, wang))
        .thenReturn(CollaborationNextAction.SUPPLEMENT_PRICE);

    assertThat(service.detail(15L))
        .extracting(response -> response.nextAction(), response -> response.nextActionLabel(),
            response -> response.guidance())
        .containsExactly("VALIDATE_COMPLETENESS", "检查完整性",
            "内容已齐，请先校验完整性，再提交财务审核。");
  }

  @Test
  void returnedTaskShowsLatestFinanceReasonsToTheOriginalTechnician() {
    QuoteCollaborationProductTask task = task(14L, "RETURNED_TO_TECH", 601L);
    task.setLastValidationStatus("NOT_CHECKED");
    QuoteCollaborationReviewItem rejected = new QuoteCollaborationReviewItem();
    rejected.setItemType("BOM");
    rejected.setDecisionReason("紫铜管用量单位不正确");
    when(repository.findMineById(14L, 601L, "COMMERCIAL")).thenReturn(Optional.of(task));
    when(repository.findGaps(14L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(repository.findLinksByProductTask(14L, new CollaborationScope("COMMERCIAL", "210")))
        .thenReturn(List.of());
    when(reviewItemMapper.selectLatestRejectedByProductTask(14L, "COMMERCIAL"))
        .thenReturn(List.of(rejected));
    when(nextActionCalculator.calculate(task, wang))
        .thenReturn(CollaborationNextAction.REVISE_RETURNED_ITEMS);

    assertThat(service.detail(14L).returnIssues())
        .singleElement()
        .satisfies(issue -> {
          assertThat(issue.itemTypeLabel()).isEqualTo("BOM");
          assertThat(issue.reason()).isEqualTo("紫铜管用量单位不正确");
        });
  }

  private static QuoteCollaborationProductTask task(Long id, String status, Long assignee) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(id);
    task.setProductTaskNo("QCPT-" + id);
    task.setProductCode("P-" + id);
    task.setProductName("产品" + id);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setPrimaryScope("FULL_BOM");
    task.setTaskStatus(status);
    task.setTaskVersion(3);
    task.setOriginalTechnicianUserId(601L);
    task.setOriginalTechnicianName("王工");
    task.setCurrentAssigneeUserId(assignee);
    task.setCurrentAssigneeName(assignee == null ? null : "王工");
    task.setNeedBom(1);
    task.setNeedPackage(0);
    task.setNeedPrice(0);
    task.setOpenGapCount(0);
    return task;
  }

  private static QuoteCollaborationGap readyPriceGap(Long id) {
    QuoteCollaborationGap gap = new QuoteCollaborationGap();
    gap.setId(id);
    gap.setProductTaskId(15L);
    gap.setGapCategory("PRICE");
    gap.setGapStatus("DRAFT_READY");
    gap.setCurrentPriceDraftId(id + 1000);
    return gap;
  }
}
