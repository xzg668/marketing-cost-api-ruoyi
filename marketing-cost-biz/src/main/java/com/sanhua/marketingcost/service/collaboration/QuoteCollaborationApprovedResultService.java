package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.config.ApprovedResultReuseProperties;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationReview;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductForm;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkType;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ResultStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ResultType;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ReviewStatus;
import com.sanhua.marketingcost.service.collaboration.scan.ApprovedResultSourceSnapshot;
import com.sanhua.marketingcost.service.collaboration.scan.ApprovedResultSourceSnapshotReader;
import com.sanhua.marketingcost.service.collaboration.scan.CurrentU9BomResult;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationCurrentU9BomGateway;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanContext;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 生成、读取和手工失效半年可复用结果；价格批次和成本版本不属于本聚合。 */
@Service
public class QuoteCollaborationApprovedResultService {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private final QuoteCollaborationTaskRepository taskRepository;
  private final QuoteCollaborationReviewRepository reviewRepository;
  private final ApprovedResultSourceSnapshotReader sourceReader;
  private final QuoteCollaborationCurrentU9BomGateway u9BomGateway;
  private final ApprovedResultFingerprints fingerprints;
  private final ApprovedResultReuseProperties properties;
  private final Clock clock;

  @Autowired
  public QuoteCollaborationApprovedResultService(
      QuoteCollaborationTaskRepository taskRepository,
      QuoteCollaborationReviewRepository reviewRepository,
      ApprovedResultSourceSnapshotReader sourceReader,
      QuoteCollaborationCurrentU9BomGateway u9BomGateway,
      ApprovedResultFingerprints fingerprints,
      ApprovedResultReuseProperties properties) {
    this(taskRepository, reviewRepository, sourceReader, u9BomGateway,
        fingerprints, properties, Clock.system(BUSINESS_ZONE));
  }

  QuoteCollaborationApprovedResultService(
      QuoteCollaborationTaskRepository taskRepository,
      QuoteCollaborationReviewRepository reviewRepository,
      ApprovedResultSourceSnapshotReader sourceReader,
      QuoteCollaborationCurrentU9BomGateway u9BomGateway,
      ApprovedResultFingerprints fingerprints,
      ApprovedResultReuseProperties properties,
      Clock clock) {
    this.taskRepository = taskRepository;
    this.reviewRepository = reviewRepository;
    this.sourceReader = sourceReader;
    this.u9BomGateway = u9BomGateway;
    this.fingerprints = fingerprints;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public QuoteCollaborationApprovedResult activate(ApprovedResultActivationCommand command) {
    requireActivation(command);
    QuoteCollaborationProductTask task = loadProductTask(command);
    CollaborationScope scope = scope(task);
    QuoteCollaborationReview review = reviewRepository.findReviewById(
        command.sourceReviewId(), task.getBusinessUnitType()).orElseThrow(() ->
        new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "财务审核不存在"));
    validateEffectiveReview(task, review);
    ResultType resultType = resultType(task);
    Optional<QuoteCollaborationApprovedResult> existing = reviewRepository.findResultBySource(
        task.getId(), review.getId(), resultType.code(), scope);
    if (existing.isPresent()) {
      return existing.get();
    }

    ApprovedResultSourceSnapshot source = readSource(task, resultType);
    if (source == null || source.status() != ApprovedResultSourceSnapshot.Status.READY) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          source == null ? "正式BOM或包装来源读取没有返回结果"
              : firstText(source.message(), "正式BOM或包装来源尚未就绪"));
    }
    LocalDateTime effectiveAt = review.getEffectiveAt();
    validatePolicy();
    QuoteCollaborationApprovedResult result = new QuoteCollaborationApprovedResult();
    result.setResultType(resultType.code());
    result.setSourceProductTaskId(task.getId());
    result.setSourceReviewId(review.getId());
    result.setProductCode(requireText(task.getProductCode(), "产品料号"));
    result.setProductForm(task.getProductForm());
    result.setApplicableOrgCode(task.getApplicableOrgCode());
    result.setSourceObjectType(source.sourceObjectType());
    result.setSourceObjectId(sourceObjectId(task, resultType));
    result.setSourceSystem(source.sourceSystem());
    result.setSourceVersionText(source.sourceVersionText());
    result.setStructureFingerprint(source.structureFingerprint());
    if (resultType == ResultType.BARE_PACKAGE) {
      result.setU9ContextFingerprint(readCurrentBareU9Context(task, effectiveAt));
    }
    result.setValidityPolicyCode(properties.getPolicyCode().trim());
    result.setValidityMonths(properties.getValidityMonths());
    result.setValidFrom(effectiveAt);
    result.setValidUntil(effectiveAt.plusMonths(properties.getValidityMonths()));
    result.setResultStatus(ResultStatus.ACTIVE.code());
    LocalDateTime now = LocalDateTime.now(clock);
    result.setCreatedBy(command.actor().userId());
    result.setCreatedByName(command.actor().userName());
    result.setCreatedAt(now);
    result.setUpdatedBy(command.actor().userId());
    result.setUpdatedByName(command.actor().userName());
    result.setUpdatedAt(now);
    try {
      return reviewRepository.saveApprovedResult(result);
    } catch (DataIntegrityViolationException conflict) {
      return reviewRepository.findResultBySource(
          task.getId(), review.getId(), resultType.code(), scope).orElseThrow(() -> conflict);
    }
  }

  @Transactional
  public QuoteCollaborationApprovedResult invalidate(
      Long resultId,
      String reason,
      CollaborationScope scope,
      CollaborationActor actor) {
    if (resultId == null || resultId <= 0) {
      throw new IllegalArgumentException("审核结果ID必须为正数");
    }
    String invalidReason = requireText(reason, "失效原因");
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw new IllegalArgumentException("当前操作人不能为空");
    }
    QuoteCollaborationApprovedResult current = reviewRepository.findApprovedResultById(
        resultId, scope).orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "审核结果不存在或不在当前业务范围"));
    if (!ResultStatus.ACTIVE.code().equals(current.getResultStatus())) {
      return current;
    }
    return reviewRepository.invalidateApprovedResult(
        resultId, ResultStatus.ACTIVE.code(), invalidReason, scope, actor,
        LocalDateTime.now(clock));
  }

  private QuoteCollaborationProductTask loadProductTask(ApprovedResultActivationCommand command) {
    Long productTaskId = command.sourceProductTaskId();
    if (productTaskId == null || productTaskId <= 0) {
      throw new IllegalArgumentException("来源产品任务ID必须为正数");
    }
    CollaborationScope commandScope = new CollaborationScope(
        command.businessUnitType(), command.applicableOrgCode());
    return taskRepository.findProductTaskById(productTaskId, commandScope).orElseThrow(() ->
        new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "来源产品任务不存在"));
  }

  private ApprovedResultSourceSnapshot readSource(
      QuoteCollaborationProductTask task, ResultType resultType) {
    if (resultType == ResultType.FULL_BOM) {
      return sourceReader.readFullBom(task.getSupplementVersionId(), task.getProductCode());
    }
    return sourceReader.readBarePackage(
        task.getPackageReferenceId(), task.getProductCode(),
        task.getPriceOrgCode(), task.getMaterialOrgCode());
  }

  private String readCurrentBareU9Context(
      QuoteCollaborationProductTask task, LocalDateTime effectiveAt) {
    QuoteCollaborationQuoteLink owner = taskRepository.findLinksByProductTask(
        task.getId(), scope(task)).stream()
        .filter(link -> QuoteLinkType.OWNER.code().equals(link.getLinkType()))
        .findFirst()
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
            "裸品包装任务缺少原报价关联，无法复验U9本体"));
    QuoteCollaborationScanContext context = new QuoteCollaborationScanContext(
        owner.getOaFormId(), owner.getOaFormItemId(), owner.getOaNo(), task.getAccountingMonth(),
        task.getBusinessUnitType(), task.getProductCode(), task.getProductName(),
        task.getProductSpec(), task.getProductModel(), task.getPriceOrgCode(),
        task.getMaterialOrgCode(), effectiveAt.toLocalDate(), effectiveAt);
    CurrentU9BomResult u9 = u9BomGateway.read(context);
    if (u9 == null || u9.status() != CurrentU9BomResult.Status.AVAILABLE) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "裸品当前U9本体BOM不可用，不能生成包装复用结果");
    }
    return fingerprints.u9Context(context, u9);
  }

  private ResultType resultType(QuoteCollaborationProductTask task) {
    if (PrimaryScope.FULL_BOM.code().equals(task.getPrimaryScope())) {
      return ResultType.FULL_BOM;
    }
    if (PrimaryScope.BARE_PACKAGE.code().equals(task.getPrimaryScope())) {
      return ResultType.BARE_PACKAGE;
    }
    throw new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
        "补价任务不生成半年BOM或包装结果");
  }

  private Long sourceObjectId(QuoteCollaborationProductTask task, ResultType resultType) {
    Long value = resultType == ResultType.FULL_BOM
        ? task.getSupplementVersionId() : task.getPackageReferenceId();
    if (value == null || value <= 0) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "产品任务缺少正式来源对象");
    }
    return value;
  }

  private void validateEffectiveReview(
      QuoteCollaborationProductTask task, QuoteCollaborationReview review) {
    if (!task.getOriginCollaborationId().equals(review.getCollaborationId())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
          "财务审核不属于当前产品任务");
    }
    if (!ReviewStatus.EFFECTIVE.code().equals(review.getReviewStatus())
        || review.getEffectiveAt() == null) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "财务审核尚未生效，不能生成半年复用结果");
    }
  }

  private void validatePolicy() {
    if (properties.getValidityMonths() <= 0
        || !StringUtils.hasText(properties.getPolicyCode())) {
      throw new IllegalStateException("审核结果复用策略配置无效");
    }
  }

  private CollaborationScope scope(QuoteCollaborationProductTask task) {
    return new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode());
  }

  private void requireActivation(ApprovedResultActivationCommand command) {
    if (command == null || command.sourceReviewId() == null || command.sourceReviewId() <= 0) {
      throw new IllegalArgumentException("来源审核ID必须为正数");
    }
    if (command.actor() == null || command.actor().userId() == null
        || command.actor().userId() <= 0) {
      throw new IllegalArgumentException("当前操作人不能为空");
    }
  }

  private String requireText(String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(name + "不能为空");
    }
    return value.trim();
  }

  private String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }
}
