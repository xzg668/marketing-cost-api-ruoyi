package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.QuoteCollaborationGapMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationQuoteLinkMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationTaskMapper;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisQuoteCollaborationTaskRepository
    implements QuoteCollaborationTaskRepository {

  private final QuoteCollaborationTaskMapper taskMapper;
  private final QuoteCollaborationProductTaskMapper productTaskMapper;
  private final QuoteCollaborationQuoteLinkMapper quoteLinkMapper;
  private final QuoteCollaborationGapMapper gapMapper;
  private final CollaborationNumberGenerator numberGenerator;

  public MybatisQuoteCollaborationTaskRepository(
      QuoteCollaborationTaskMapper taskMapper,
      QuoteCollaborationProductTaskMapper productTaskMapper,
      QuoteCollaborationQuoteLinkMapper quoteLinkMapper,
      QuoteCollaborationGapMapper gapMapper,
      CollaborationNumberGenerator numberGenerator) {
    this.taskMapper = taskMapper;
    this.productTaskMapper = productTaskMapper;
    this.quoteLinkMapper = quoteLinkMapper;
    this.gapMapper = gapMapper;
    this.numberGenerator = numberGenerator;
  }

  @Override
  public QuoteCollaborationTask saveTask(QuoteCollaborationTask task) {
    requireEntity(task, "协作主任务");
    if (task.getCollaborationNo() == null || task.getCollaborationNo().isBlank()) {
      task.setCollaborationNo(numberGenerator.nextTaskNo());
    }
    if (task.getTaskVersion() == null) {
      task.setTaskVersion(1);
    }
    ensureOne(taskMapper.insert(task), "保存协作主任务");
    return task;
  }

  @Override
  public QuoteCollaborationProductTask saveProductTask(QuoteCollaborationProductTask task) {
    requireEntity(task, "产品协作任务");
    if (task.getProductTaskNo() == null || task.getProductTaskNo().isBlank()) {
      task.setProductTaskNo(numberGenerator.nextProductTaskNo());
    }
    if (task.getTaskVersion() == null) {
      task.setTaskVersion(1);
    }
    ensureOne(productTaskMapper.insert(task), "保存产品协作任务");
    return task;
  }

  @Override
  public QuoteCollaborationQuoteLink saveQuoteLink(QuoteCollaborationQuoteLink link) {
    requireEntity(link, "报价关联");
    ensureOne(quoteLinkMapper.insert(link), "保存报价关联");
    return link;
  }

  @Override
  public Optional<QuoteCollaborationTask> findTaskById(Long id, String businessUnitType) {
    return Optional.ofNullable(taskMapper.selectScopedById(
        requireId(id, "主任务ID"), CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public Optional<QuoteCollaborationTask> findTaskByNo(String taskNo, String businessUnitType) {
    return Optional.ofNullable(taskMapper.selectScopedByNo(
        CollaborationScope.requireText(taskNo, "主任务号"),
        CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public Optional<QuoteCollaborationTask> findLatestTaskByForm(
      Long oaFormId, String businessUnitType) {
    return Optional.ofNullable(taskMapper.selectLatestByForm(
        requireId(oaFormId, "报价表头ID"),
        CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public List<QuoteCollaborationTask> findTasksByReviewer(
      Long reviewerUserId, List<String> statuses, String businessUnitType) {
    return taskMapper.selectByReviewerAndStatuses(
        requireId(reviewerUserId, "财务审核人"),
        CollaborationScope.requireBusinessUnit(businessUnitType), requireStatuses(statuses));
  }

  @Override
  public Optional<QuoteCollaborationProductTask> findProductTaskById(
      Long id, CollaborationScope scope) {
    return Optional.ofNullable(productTaskMapper.selectScopedById(
        requireId(id, "产品任务ID"), scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public Optional<QuoteCollaborationProductTask> findProductTaskByNo(
      String taskNo, CollaborationScope scope) {
    return Optional.ofNullable(productTaskMapper.selectScopedByNo(
        CollaborationScope.requireText(taskNo, "产品任务号"),
        scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public Optional<QuoteCollaborationProductTask> findActiveProductTaskByLockKey(
      String activeLockKey, CollaborationScope scope) {
    return Optional.ofNullable(productTaskMapper.selectActiveByLockKey(
        CollaborationScope.requireText(activeLockKey, "活动锁"),
        scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public List<QuoteCollaborationProductTask> findProductTasksByAssignee(
      Long assigneeUserId, List<String> statuses, CollaborationScope scope) {
    return productTaskMapper.selectByAssigneeAndStatuses(
        requireId(assigneeUserId, "当前责任人"), scope.businessUnitType(),
        scope.applicableOrgCode(), requireStatuses(statuses));
  }

  @Override
  public List<QuoteCollaborationProductTask> findMineByTechnician(
      Long technicianUserId, String businessUnitType) {
    return productTaskMapper.selectMineByTechnician(
        requireId(technicianUserId, "技术人员"),
        CollaborationScope.requireBusinessUnit(businessUnitType));
  }

  @Override
  public Optional<QuoteCollaborationProductTask> findMineById(
      Long id, Long technicianUserId, String businessUnitType) {
    return Optional.ofNullable(productTaskMapper.selectMineById(
        requireId(id, "产品任务ID"), requireId(technicianUserId, "技术人员"),
        CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public Optional<QuoteCollaborationProductTask> findProductTaskByIdAndBusinessUnit(
      Long id, String businessUnitType) {
    return Optional.ofNullable(productTaskMapper.selectByIdAndBusinessUnit(
        requireId(id, "产品任务ID"),
        CollaborationScope.requireBusinessUnit(businessUnitType)));
  }

  @Override
  public List<QuoteCollaborationProductTask> findProductTasksByProductAndMonth(
      String productCode, String accountingMonth, CollaborationScope scope) {
    return productTaskMapper.selectByProductAndMonth(
        CollaborationScope.requireText(productCode, "产品料号"),
        CollaborationScope.requireText(accountingMonth, "核算月份"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public List<QuoteCollaborationProductTask> findProductTasksByCollaboration(
      Long collaborationId, String businessUnitType) {
    return productTaskMapper.selectByCollaboration(
        requireId(collaborationId, "主任务ID"),
        CollaborationScope.requireBusinessUnit(businessUnitType));
  }

  @Override
  public List<QuoteCollaborationQuoteLink> findActiveLinksByQuoteItem(
      Long oaFormItemId, CollaborationScope scope) {
    return quoteLinkMapper.selectActiveByQuoteItem(
        requireId(oaFormItemId, "报价产品行ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public List<QuoteCollaborationQuoteLink> findLinksByProductTask(
      Long productTaskId, CollaborationScope scope) {
    return quoteLinkMapper.selectByProductTask(
        requireId(productTaskId, "产品任务ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public Optional<QuoteCollaborationQuoteLink> findQuoteLinkById(
      Long id, CollaborationScope scope) {
    return Optional.ofNullable(quoteLinkMapper.selectScopedById(
        requireId(id, "报价关联ID"), scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public QuoteCollaborationTask transitionTaskStatus(
      Long id,
      Integer expectedVersion,
      String expectedStatus,
      String nextStatus,
      String businessUnitType,
      CollaborationActor actor) {
    String scope = CollaborationScope.requireBusinessUnit(businessUnitType);
    int affected = taskMapper.transitionStatusWithVersion(
        requireId(id, "主任务ID"), requireVersion(expectedVersion),
        CollaborationScope.requireText(expectedStatus, "源状态"),
        CollaborationScope.requireText(nextStatus, "目标状态"), scope,
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("协作主任务", id, expectedVersion);
    }
    return findTaskById(id, scope).orElseThrow(
        () -> new CollaborationPersistenceException("协作主任务迁移后无法读取：id=" + id));
  }

  @Override
  public QuoteCollaborationProductTask transitionProductTaskStatus(
      Long id,
      Integer expectedVersion,
      String expectedStatus,
      String nextStatus,
      Long assigneeUserId,
      String assigneeName,
      CollaborationScope scope,
      CollaborationActor actor) {
    int affected = productTaskMapper.transitionStatusWithVersion(
        requireId(id, "产品任务ID"), requireVersion(expectedVersion),
        CollaborationScope.requireText(expectedStatus, "源状态"),
        CollaborationScope.requireText(nextStatus, "目标状态"), assigneeUserId, assigneeName,
        scope.businessUnitType(), scope.applicableOrgCode(),
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("产品协作任务", id, expectedVersion);
    }
    return findProductTaskById(id, scope).orElseThrow(
        () -> new CollaborationPersistenceException("产品协作任务迁移后无法读取：id=" + id));
  }

  @Override
  public QuoteCollaborationQuoteLink transitionQuoteLinkStatus(
      Long id,
      String expectedStatus,
      String nextStatus,
      CollaborationScope scope,
      CollaborationActor actor) {
    Long linkId = requireId(id, "报价关联ID");
    int affected = quoteLinkMapper.transitionStatus(
        linkId, CollaborationScope.requireText(expectedStatus, "源状态"),
        CollaborationScope.requireText(nextStatus, "目标状态"),
        scope.businessUnitType(), scope.applicableOrgCode(),
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("报价关联", linkId, null);
    }
    return findQuoteLinkById(linkId, scope).orElseThrow(
        () -> new CollaborationPersistenceException("报价关联迁移后无法读取：id=" + linkId));
  }

  @Override
  @Transactional
  public List<QuoteCollaborationGap> synchronizeGaps(
      Long productTaskId,
      CollaborationScope scope,
      List<GapUpsertCommand> currentGaps,
      CollaborationActor actor) {
    Long taskId = requireId(productTaskId, "产品任务ID");
    if (findProductTaskById(taskId, scope).isEmpty()) {
      throw new CollaborationPersistenceException("产品任务不存在或不在当前业务范围：id=" + taskId);
    }
    List<GapUpsertCommand> commands = currentGaps == null ? List.of() : List.copyOf(currentGaps);
    Set<String> fingerprints = new HashSet<>();
    for (GapUpsertCommand command : commands) {
      if (!fingerprints.add(command.gapFingerprint())) {
        throw new IllegalArgumentException("同一次缺口同步存在重复指纹：" + command.gapFingerprint());
      }
      QuoteCollaborationGap existing = gapMapper.selectForUpdateByFingerprint(
          taskId, command.gapFingerprint(), scope.businessUnitType(), scope.applicableOrgCode());
      if (existing == null) {
        QuoteCollaborationGap gap = toGap(taskId, command, actor);
        ensureOne(gapMapper.insert(gap), "新增协作缺口");
      } else {
        applyGap(existing, command, actor);
        ensureOne(gapMapper.updateFromScan(
            existing, scope.businessUnitType(), scope.applicableOrgCode()), "更新协作缺口");
      }
    }
    gapMapper.markMissingAsObsolete(taskId, List.copyOf(fingerprints),
        scope.businessUnitType(), scope.applicableOrgCode(), LocalDateTime.now());
    return findGaps(taskId, scope);
  }

  @Override
  public List<QuoteCollaborationGap> findGaps(
      Long productTaskId, CollaborationScope scope) {
    return gapMapper.selectByProductTask(
        requireId(productTaskId, "产品任务ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public QuoteCollaborationProductTask updateValidationResult(
      Long productTaskId,
      Integer expectedVersion,
      String validationStatus,
      Long technicianUserId,
      CollaborationScope scope,
      CollaborationActor actor) {
    Long taskId = requireId(productTaskId, "产品任务ID");
    int affected = productTaskMapper.updateValidationResult(
        taskId, requireVersion(expectedVersion),
        CollaborationScope.requireText(validationStatus, "校验状态"),
        requireId(technicianUserId, "技术人员"), scope.businessUnitType(),
        scope.applicableOrgCode(), actor == null ? null : actor.userId(),
        actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("产品协作任务校验", taskId, expectedVersion);
    }
    return findProductTaskById(taskId, scope).orElseThrow(
        () -> new CollaborationPersistenceException("产品任务校验后无法读取：id=" + taskId));
  }

  @Override
  public void incrementOwnedProductCount(
      Long taskId, String businessUnitType, CollaborationActor actor) {
    ensureOne(taskMapper.incrementOwnedProductCount(
        requireId(taskId, "主任务ID"),
        CollaborationScope.requireBusinessUnit(businessUnitType),
        actor == null ? null : actor.userId(),
        actor == null ? null : actor.userName()), "更新主任务归属产品数");
  }

  private QuoteCollaborationGap toGap(
      Long productTaskId, GapUpsertCommand command, CollaborationActor actor) {
    QuoteCollaborationGap gap = new QuoteCollaborationGap();
    gap.setGapNo(numberGenerator.nextGapNo());
    gap.setProductTaskId(productTaskId);
    gap.setGapFingerprint(command.gapFingerprint());
    gap.setCreatedBy(actor == null ? null : actor.userId());
    gap.setCreatedByName(actor == null ? null : actor.userName());
    applyGap(gap, command, actor);
    return gap;
  }

  private void applyGap(
      QuoteCollaborationGap gap, GapUpsertCommand command, CollaborationActor actor) {
    gap.setGapCategory(command.gapCategory());
    gap.setGapType(command.gapType());
    gap.setSourceType(command.sourceType());
    gap.setSourceId(command.sourceId());
    gap.setBomNodeKey(command.bomNodeKey());
    gap.setBomPath(command.bomPath());
    gap.setBomQuantity(command.bomQuantity());
    gap.setBomUnit(command.bomUnit());
    gap.setAccountingMonth(command.accountingMonth());
    gap.setApplicableOrgCode(command.applicableOrgCode());
    gap.setMaterialCode(command.materialCode());
    gap.setMaterialName(command.materialName());
    gap.setMaterialSpec(command.materialSpec());
    gap.setMaterialModel(command.materialModel());
    gap.setMaterialRole(command.materialRole());
    gap.setSuggestedPriceType(command.suggestedPriceType());
    gap.setReasonCode(command.reasonCode());
    gap.setReasonMessage(command.reasonMessage());
    gap.setGapStatus(CollaborationCodes.GapStatus.OPEN.code());
    gap.setResolvedAt(null);
    gap.setResolvedBy(null);
    gap.setUpdatedBy(actor == null ? null : actor.userId());
    gap.setUpdatedByName(actor == null ? null : actor.userName());
  }

  private static void requireEntity(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + "不能为空");
    }
  }

  private static Long requireId(Long value, String name) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException(name + "必须为正数");
    }
    return value;
  }

  private static Integer requireVersion(Integer value) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException("预期版本必须为正数");
    }
    return value;
  }

  private static List<String> requireStatuses(List<String> statuses) {
    if (statuses == null || statuses.isEmpty() || statuses.stream().anyMatch(
        value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("状态集合不能为空");
    }
    return List.copyOf(statuses);
  }

  private static void ensureOne(int affected, String operation) {
    if (affected != 1) {
      throw new CollaborationPersistenceException(operation + "失败，影响行数=" + affected);
    }
  }
}
