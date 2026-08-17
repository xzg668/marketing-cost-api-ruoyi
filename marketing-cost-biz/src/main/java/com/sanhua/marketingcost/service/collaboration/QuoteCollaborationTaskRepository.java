package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import java.util.List;
import java.util.Optional;

public interface QuoteCollaborationTaskRepository {

  QuoteCollaborationTask saveTask(QuoteCollaborationTask task);

  QuoteCollaborationProductTask saveProductTask(QuoteCollaborationProductTask task);

  QuoteCollaborationQuoteLink saveQuoteLink(QuoteCollaborationQuoteLink link);

  Optional<QuoteCollaborationTask> findTaskById(Long id, String businessUnitType);

  Optional<QuoteCollaborationTask> findTaskByNo(String taskNo, String businessUnitType);

  Optional<QuoteCollaborationTask> findLatestTaskByForm(
      Long oaFormId, String businessUnitType);

  List<QuoteCollaborationTask> findTasksByReviewer(
      Long reviewerUserId, List<String> statuses, String businessUnitType);

  Optional<QuoteCollaborationProductTask> findProductTaskById(
      Long id, CollaborationScope scope);

  Optional<QuoteCollaborationProductTask> findProductTaskByNo(
      String taskNo, CollaborationScope scope);

  Optional<QuoteCollaborationProductTask> findActiveProductTaskByLockKey(
      String activeLockKey, CollaborationScope scope);

  List<QuoteCollaborationProductTask> findProductTasksByAssignee(
      Long assigneeUserId, List<String> statuses, CollaborationScope scope);

  List<QuoteCollaborationProductTask> findMineByTechnician(
      Long technicianUserId, String businessUnitType);

  Optional<QuoteCollaborationProductTask> findMineById(
      Long id, Long technicianUserId, String businessUnitType);

  List<QuoteCollaborationProductTask> findProductTasksByProductAndMonth(
      String productCode, String accountingMonth, CollaborationScope scope);

  List<QuoteCollaborationProductTask> findProductTasksByCollaboration(
      Long collaborationId, String businessUnitType);

  List<QuoteCollaborationQuoteLink> findActiveLinksByQuoteItem(
      Long oaFormItemId, CollaborationScope scope);

  List<QuoteCollaborationQuoteLink> findLinksByProductTask(
      Long productTaskId, CollaborationScope scope);

  Optional<QuoteCollaborationQuoteLink> findQuoteLinkById(
      Long id, CollaborationScope scope);

  QuoteCollaborationTask transitionTaskStatus(
      Long id,
      Integer expectedVersion,
      String expectedStatus,
      String nextStatus,
      String businessUnitType,
      CollaborationActor actor);

  QuoteCollaborationProductTask transitionProductTaskStatus(
      Long id,
      Integer expectedVersion,
      String expectedStatus,
      String nextStatus,
      Long assigneeUserId,
      String assigneeName,
      CollaborationScope scope,
      CollaborationActor actor);

  QuoteCollaborationQuoteLink transitionQuoteLinkStatus(
      Long id,
      String expectedStatus,
      String nextStatus,
      CollaborationScope scope,
      CollaborationActor actor);

  List<QuoteCollaborationGap> synchronizeGaps(
      Long productTaskId,
      CollaborationScope scope,
      List<GapUpsertCommand> currentGaps,
      CollaborationActor actor);

  List<QuoteCollaborationGap> findGaps(Long productTaskId, CollaborationScope scope);

  QuoteCollaborationProductTask updateValidationResult(
      Long productTaskId,
      Integer expectedVersion,
      String validationStatus,
      Long technicianUserId,
      CollaborationScope scope,
      CollaborationActor actor);

  void incrementOwnedProductCount(Long taskId, String businessUnitType, CollaborationActor actor);
}
