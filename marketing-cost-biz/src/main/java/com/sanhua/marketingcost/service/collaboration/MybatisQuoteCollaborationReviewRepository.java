package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.entity.QuoteCollaborationReviewItem;
import com.sanhua.marketingcost.mapper.QuoteCollaborationApprovedResultMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationReviewMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisQuoteCollaborationReviewRepository
    implements QuoteCollaborationReviewRepository {

  private final QuoteCollaborationReviewMapper reviewMapper;
  private final QuoteCollaborationReviewItemMapper reviewItemMapper;
  private final QuoteCollaborationApprovedResultMapper approvedResultMapper;
  private final CollaborationNumberGenerator numberGenerator;

  public MybatisQuoteCollaborationReviewRepository(
      QuoteCollaborationReviewMapper reviewMapper,
      QuoteCollaborationReviewItemMapper reviewItemMapper,
      QuoteCollaborationApprovedResultMapper approvedResultMapper,
      CollaborationNumberGenerator numberGenerator) {
    this.reviewMapper = reviewMapper;
    this.reviewItemMapper = reviewItemMapper;
    this.approvedResultMapper = approvedResultMapper;
    this.numberGenerator = numberGenerator;
  }

  @Override
  public QuoteCollaborationReview saveReview(QuoteCollaborationReview review) {
    if (review == null) {
      throw new IllegalArgumentException("财务审核不能为空");
    }
    if (review.getReviewNo() == null || review.getReviewNo().isBlank()) {
      review.setReviewNo(numberGenerator.nextReviewNo());
    }
    ensureOne(reviewMapper.insert(review), "保存财务审核");
    return review;
  }

  @Override
  @Transactional
  public List<QuoteCollaborationReviewItem> saveReviewItems(
      List<QuoteCollaborationReviewItem> items) {
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("财务审核项不能为空");
    }
    List<QuoteCollaborationReviewItem> values = List.copyOf(items);
    for (QuoteCollaborationReviewItem item : values) {
      if (item == null) {
        throw new IllegalArgumentException("财务审核项不能包含空值");
      }
      ensureOne(reviewItemMapper.insert(item), "保存财务审核项");
    }
    return values;
  }

  @Override
  public QuoteCollaborationApprovedResult saveApprovedResult(
      QuoteCollaborationApprovedResult result) {
    if (result == null) {
      throw new IllegalArgumentException("审核结果不能为空");
    }
    if (result.getResultNo() == null || result.getResultNo().isBlank()) {
      result.setResultNo(numberGenerator.nextApprovedResultNo());
    }
    ensureOne(approvedResultMapper.insert(result), "保存审核结果");
    return result;
  }

  @Override
  public Optional<QuoteCollaborationReview> findReviewById(
      Long id, String businessUnitType) {
    return Optional.ofNullable(reviewMapper.selectScopedById(
        requireId(id, "审核ID"), CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public Optional<QuoteCollaborationReview> findReviewByNo(
      String reviewNo, String businessUnitType) {
    return Optional.ofNullable(reviewMapper.selectScopedByNo(
        CollaborationScope.requireText(reviewNo, "审核号"),
        CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public List<QuoteCollaborationReview> findReviewsByReviewer(
      Long reviewerUserId, List<String> statuses, String businessUnitType) {
    if (statuses == null || statuses.isEmpty()) {
      throw new IllegalArgumentException("审核状态集合不能为空");
    }
    return reviewMapper.selectByReviewerAndStatuses(
        requireId(reviewerUserId, "财务审核人"),
        CollaborationScope.requireBusinessUnit(businessUnitType), List.copyOf(statuses));
  }

  @Override
  public QuoteCollaborationReview transitionReviewStatus(
      Long id,
      Integer expectedSourceTaskVersion,
      String expectedStatus,
      String nextStatus,
      String businessUnitType,
      CollaborationActor actor) {
    Long reviewId = requireId(id, "审核ID");
    if (expectedSourceTaskVersion == null || expectedSourceTaskVersion <= 0) {
      throw new IllegalArgumentException("审核来源版本必须为正数");
    }
    String scope = CollaborationScope.requireBusinessUnit(businessUnitType);
    int affected = reviewMapper.transitionStatus(
        reviewId, expectedSourceTaskVersion,
        CollaborationScope.requireText(expectedStatus, "源状态"),
        CollaborationScope.requireText(nextStatus, "目标状态"), scope,
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException(
          "财务审核", reviewId, expectedSourceTaskVersion);
    }
    return findReviewById(reviewId, scope).orElseThrow(
        () -> new CollaborationPersistenceException("财务审核迁移后无法读取：id=" + reviewId));
  }

  @Override
  public List<QuoteCollaborationReviewItem> findReviewItems(
      Long reviewId, CollaborationScope scope) {
    return reviewItemMapper.selectByReview(requireId(reviewId, "审核ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public List<QuoteCollaborationApprovedResult> findValidResults(
      String productCode,
      String resultType,
      LocalDateTime effectiveAt,
      CollaborationScope scope) {
    if (effectiveAt == null) {
      throw new IllegalArgumentException("结果匹配时间不能为空");
    }
    return approvedResultMapper.selectValidResults(
        CollaborationScope.requireText(productCode, "产品料号"),
        CollaborationScope.requireText(resultType, "结果类型"), effectiveAt,
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public Optional<QuoteCollaborationApprovedResult> findApprovedResultById(
      Long id, CollaborationScope scope) {
    return Optional.ofNullable(approvedResultMapper.selectScopedById(
        requireId(id, "审核结果ID"), scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public Optional<QuoteCollaborationApprovedResult> findResultBySource(
      Long sourceProductTaskId,
      Long sourceReviewId,
      String resultType,
      CollaborationScope scope) {
    return Optional.ofNullable(approvedResultMapper.selectBySource(
        requireId(sourceProductTaskId, "来源产品任务ID"),
        requireId(sourceReviewId, "来源审核ID"),
        CollaborationScope.requireText(resultType, "结果类型"),
        scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public Optional<QuoteCollaborationApprovedResult> findLatestExpiredReference(
      String productCode,
      String resultType,
      LocalDateTime effectiveAt,
      CollaborationScope scope) {
    if (effectiveAt == null) {
      throw new IllegalArgumentException("结果匹配时间不能为空");
    }
    return Optional.ofNullable(approvedResultMapper.selectLatestExpiredReference(
        CollaborationScope.requireText(productCode, "产品料号"),
        CollaborationScope.requireText(resultType, "结果类型"), effectiveAt,
        scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public QuoteCollaborationApprovedResult invalidateApprovedResult(
      Long id,
      String expectedStatus,
      String reason,
      CollaborationScope scope,
      CollaborationActor actor,
      LocalDateTime invalidatedAt) {
    Long resultId = requireId(id, "审核结果ID");
    if (invalidatedAt == null) {
      throw new IllegalArgumentException("失效时间不能为空");
    }
    int affected = approvedResultMapper.invalidate(
        resultId, CollaborationScope.requireText(expectedStatus, "源状态"),
        CollaborationScope.requireText(reason, "失效原因"), invalidatedAt,
        scope.businessUnitType(), scope.applicableOrgCode(),
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("审核结果", resultId, null);
    }
    return findApprovedResultById(resultId, scope).orElseThrow(() ->
        new CollaborationPersistenceException("审核结果失效后无法读取：id=" + resultId));
  }

  private static Long requireId(Long value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + "必须为正数");
    }
    return value;
  }

  private static void ensureOne(int affected, String operation) {
    if (affected != 1) {
      throw new CollaborationPersistenceException(operation + "失败，影响行数=" + affected);
    }
  }
}
