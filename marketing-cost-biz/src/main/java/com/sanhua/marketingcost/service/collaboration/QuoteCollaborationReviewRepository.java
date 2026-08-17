package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface QuoteCollaborationReviewRepository {

  QuoteCollaborationReview saveReview(QuoteCollaborationReview review);

  List<QuoteCollaborationReviewItem> saveReviewItems(
      List<QuoteCollaborationReviewItem> items);

  QuoteCollaborationApprovedResult saveApprovedResult(
      QuoteCollaborationApprovedResult result);

  Optional<QuoteCollaborationReview> findReviewById(Long id, String businessUnitType);

  Optional<QuoteCollaborationReview> findReviewByNo(String reviewNo, String businessUnitType);

  List<QuoteCollaborationReview> findReviewsByReviewer(
      Long reviewerUserId, List<String> statuses, String businessUnitType);

  QuoteCollaborationReview transitionReviewStatus(
      Long id,
      Integer expectedSourceTaskVersion,
      String expectedStatus,
      String nextStatus,
      String businessUnitType,
      CollaborationActor actor);

  List<QuoteCollaborationReviewItem> findReviewItems(
      Long reviewId, CollaborationScope scope);

  List<QuoteCollaborationApprovedResult> findValidResults(
      String productCode, String resultType, LocalDateTime effectiveAt, CollaborationScope scope);

  Optional<QuoteCollaborationApprovedResult> findApprovedResultById(
      Long id, CollaborationScope scope);

  Optional<QuoteCollaborationApprovedResult> findResultBySource(
      Long sourceProductTaskId,
      Long sourceReviewId,
      String resultType,
      CollaborationScope scope);

  Optional<QuoteCollaborationApprovedResult> findLatestExpiredReference(
      String productCode,
      String resultType,
      LocalDateTime effectiveAt,
      CollaborationScope scope);

  QuoteCollaborationApprovedResult invalidateApprovedResult(
      Long id,
      String expectedStatus,
      String reason,
      CollaborationScope scope,
      CollaborationActor actor,
      LocalDateTime invalidatedAt);
}
