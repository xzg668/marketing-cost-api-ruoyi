package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 首次确认冻结客户场景月度卡片，后续 OA 只绑定已冻结构建。 */
@Service
public class QuoteBomMonthlyFreezeServiceImpl
    implements QuoteBomMonthlyFreezeService {

  private static final String FREEZE_STATUS_DRAFT = "DRAFT";
  private static final String FREEZE_STATUS_FROZEN = "FROZEN";

  private final QuoteBomMonthlyFreezeRepository repository;
  private final QuoteEffectiveBomPersistenceService persistenceService;
  private final QuoteEffectiveBomRepository effectiveBomRepository;
  private final Clock clock;

  @Autowired
  public QuoteBomMonthlyFreezeServiceImpl(
      QuoteBomMonthlyFreezeRepository repository,
      QuoteEffectiveBomPersistenceService persistenceService,
      QuoteEffectiveBomRepository effectiveBomRepository) {
    this(
        repository,
        persistenceService,
        effectiveBomRepository,
        Clock.systemDefaultZone());
  }

  QuoteBomMonthlyFreezeServiceImpl(
      QuoteBomMonthlyFreezeRepository repository,
      QuoteEffectiveBomPersistenceService persistenceService,
      Clock clock) {
    this(repository, persistenceService, null, clock);
  }

  QuoteBomMonthlyFreezeServiceImpl(
      QuoteBomMonthlyFreezeRepository repository,
      QuoteEffectiveBomPersistenceService persistenceService,
      QuoteEffectiveBomRepository effectiveBomRepository,
      Clock clock) {
    this.repository = repository;
    this.persistenceService = persistenceService;
    this.effectiveBomRepository = effectiveBomRepository;
    this.clock = clock;
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteBomMonthlyFreezeResult stage(
      QuoteBomMonthlyFreezeCommand command) {
    validateCommand(command);
    QuoteBomMonthlyFreezeKey key = command.key();
    QuoteBomMonthlySnapshot snapshot =
        repository
            .findActiveSuccessForUpdate(key)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "未找到可暂存的当月有效BOM卡片，请先完成BOM同步"));
    QuoteBomStatus status =
        repository
            .findStatusForUpdate(command.oaFormItemId())
            .orElseThrow(
                () -> new IllegalStateException("当前OA产品行BOM状态不存在"));
    validateStatusContext(status, key);
    String freezeStatus = normalize(snapshot.getFreezeStatus());
    if (FREEZE_STATUS_FROZEN.equals(freezeStatus)) {
      throw new IllegalStateException("月度BOM已正式冻结，不能覆盖暂存结果");
    }
    if (freezeStatus != null && !FREEZE_STATUS_DRAFT.equals(freezeStatus)) {
      throw new IllegalStateException(
          "月度BOM卡片冻结状态非法: " + snapshot.getFreezeStatus());
    }
    EffectiveBomVariantInput candidate = command.candidateVariant();
    if (candidate == null) {
      throw new IllegalArgumentException("暂存计价BOM必须提供当前候选树");
    }
    validateCandidate(candidate, key, snapshot);
    QuoteEffectiveBomPersistenceResult persisted =
        persistenceService.persistConfirmed(
            new QuoteEffectiveBomPersistenceRequest(
                snapshot.getId(),
                command.frozenBy(),
                command.alternativeSelectionIdByGroupKey(),
                candidate));
    LocalDateTime now = LocalDateTime.now(clock);
    if (repository.stageDraft(
            snapshot.getId(),
            persisted.buildBatchId(),
            persisted.variantHash(),
            now)
        != 1) {
      throw new IllegalStateException("本次计价BOM暂存失败，事务已回滚");
    }
    bindStatus(
        status,
        command,
        snapshot.getId(),
        persisted.buildBatchId(),
        now);
    if (effectiveBomRepository != null) {
      effectiveBomRepository.deleteUnreferencedByOriginMonthlySnapshotId(
          snapshot.getId());
    }
    return new QuoteBomMonthlyFreezeResult(
        snapshot.getId(),
        persisted.buildBatchId(),
        persisted.variantHash(),
        false,
        persisted.reused(),
        null);
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteBomMonthlyFreezeResult freeze(
      QuoteBomMonthlyFreezeCommand command) {
    validateCommand(command);
    QuoteBomMonthlyFreezeKey key = command.key();
    QuoteBomMonthlySnapshot snapshot =
        repository
            .findActiveSuccessForUpdate(key)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "未找到可冻结的当月有效BOM卡片，请先完成BOM同步"));
    QuoteBomStatus status =
        repository
            .findStatusForUpdate(command.oaFormItemId())
            .orElseThrow(
                () -> new IllegalStateException("当前OA产品行BOM状态不存在"));
    validateStatusContext(status, key);

    String freezeStatus = normalize(snapshot.getFreezeStatus());
    if (freezeStatus == null || FREEZE_STATUS_DRAFT.equals(freezeStatus)) {
      return freezeDraft(command, snapshot, status);
    }
    if (FREEZE_STATUS_FROZEN.equals(freezeStatus)) {
      return reuseFrozen(command, snapshot, status);
    }
    throw new IllegalStateException(
        "月度BOM卡片冻结状态非法: " + snapshot.getFreezeStatus());
  }

  private QuoteBomMonthlyFreezeResult freezeDraft(
      QuoteBomMonthlyFreezeCommand command,
      QuoteBomMonthlySnapshot snapshot,
      QuoteBomStatus status) {
    EffectiveBomVariantInput candidate = command.candidateVariant();
    if (candidate == null) {
      throw new IllegalArgumentException("首次冻结必须提供当前最终有效BOM候选树");
    }
    validateCandidate(candidate, command.key(), snapshot);

    QuoteEffectiveBomPersistenceResult persisted =
        persistenceService.persistConfirmed(
            new QuoteEffectiveBomPersistenceRequest(
                snapshot.getId(),
                command.frozenBy(),
                command.alternativeSelectionIdByGroupKey(),
                candidate));
    LocalDateTime now = LocalDateTime.now(clock);
    int frozen =
        repository.freezeDraft(
            snapshot.getId(),
            persisted.buildBatchId(),
            persisted.variantHash(),
            command.frozenBy(),
            now);
    if (frozen != 1) {
      throw new IllegalStateException("月度BOM卡片冻结失败，事务已回滚");
    }
    bindStatus(status, command, snapshot.getId(), persisted.buildBatchId(), now);
    return new QuoteBomMonthlyFreezeResult(
        snapshot.getId(),
        persisted.buildBatchId(),
        persisted.variantHash(),
        false,
        persisted.reused(),
        now);
  }

  private QuoteBomMonthlyFreezeResult reuseFrozen(
      QuoteBomMonthlyFreezeCommand command,
      QuoteBomMonthlySnapshot snapshot,
      QuoteBomStatus status) {
    String buildBatchId = requireText(
        snapshot.getEffectiveBuildBatchId(), "已冻结月度卡片缺少最终构建编号");
    String variantHash = requireText(
        snapshot.getEffectiveVariantHash(), "已冻结月度卡片缺少结果指纹");
    LocalDateTime now = LocalDateTime.now(clock);
    bindStatus(status, command, snapshot.getId(), buildBatchId, now);
    return new QuoteBomMonthlyFreezeResult(
        snapshot.getId(),
        buildBatchId,
        variantHash,
        true,
        true,
        snapshot.getFrozenAt());
  }

  private void bindStatus(
      QuoteBomStatus status,
      QuoteBomMonthlyFreezeCommand command,
      Long snapshotId,
      String buildBatchId,
      LocalDateTime now) {
    int updated =
        repository.bindStatus(
            status.getId(),
            command.oaFormItemId(),
            snapshotId,
            buildBatchId,
            now);
    if (updated != 1) {
      throw new IllegalStateException("当前OA产品行最终BOM绑定失败，事务已回滚");
    }
  }

  private static void validateCommand(QuoteBomMonthlyFreezeCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("月度BOM冻结请求不能为空");
    }
    if (command.key() == null) {
      throw new IllegalArgumentException("月度BOM冻结键不能为空");
    }
    if (command.oaFormItemId() == null || command.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("OA产品行ID必须为正数");
    }
    if (command.frozenBy() == null || command.frozenBy() <= 0) {
      throw new IllegalArgumentException("冻结人ID必须为正数");
    }
  }

  private static void validateStatusContext(
      QuoteBomStatus status, QuoteBomMonthlyFreezeKey key) {
    requireEqual(status.getProductCode(), key.productCode(), "OA状态产品料号");
    requireEqual(
        status.getCustomerCode(), key.resolvedCustomerKey(), "OA状态客户隔离键");
    requireEqual(
        normalizePackage(status.getPackageMethod()),
        key.packageMethod(),
        "OA状态包装方式");
    requireEqual(
        status.getCostPeriodMonth(), key.costPeriodMonth(), "OA状态核算月份");
  }

  private static void validateCandidate(
      EffectiveBomVariantInput candidate,
      QuoteBomMonthlyFreezeKey key,
      QuoteBomMonthlySnapshot snapshot) {
    requireEqual(candidate.costPeriodMonth(), key.costPeriodMonth(), "候选树核算月份");
    requireEqual(candidate.topProductCode(), key.productCode(), "候选树顶层产品");
    requireEqual(candidate.priceOrgCode(), key.priceOrgCode(), "候选树U9组织");
    requireEqual(
        normalizePackage(candidate.packageMethod()),
        key.packageMethod(),
        "候选树包装方式");
    requireEqual(
        candidate.sourceBomBatchId(), snapshot.getBomBatchId(), "候选树原始BOM批次");
  }

  private static void requireEqual(String actual, String expected, String field) {
    if (!java.util.Objects.equals(normalize(actual), normalize(expected))) {
      throw new IllegalArgumentException(
          field + "与本次月度卡片不一致，拒绝冻结");
    }
  }

  private static String requireText(String value, String message) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw new IllegalStateException(message);
    }
    return normalized;
  }

  private static String normalizePackage(String value) {
    String normalized = normalize(value);
    return normalized == null || "/".equals(normalized) ? "" : normalized;
  }

  private static String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
