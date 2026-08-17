package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.QuotePriceDraftFieldMapper;
import com.sanhua.marketingcost.mapper.QuotePriceDraftMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MybatisQuotePriceDraftRepository implements QuotePriceDraftRepository {

  private final QuotePriceDraftMapper draftMapper;
  private final QuotePriceDraftFieldMapper fieldMapper;
  private final CollaborationNumberGenerator numberGenerator;

  public MybatisQuotePriceDraftRepository(
      QuotePriceDraftMapper draftMapper,
      QuotePriceDraftFieldMapper fieldMapper,
      CollaborationNumberGenerator numberGenerator) {
    this.draftMapper = draftMapper;
    this.fieldMapper = fieldMapper;
    this.numberGenerator = numberGenerator;
  }

  @Override
  public QuotePriceDraft saveDraft(QuotePriceDraft draft) {
    if (draft == null) {
      throw new IllegalArgumentException("价格草稿不能为空");
    }
    if (draft.getDraftNo() == null || draft.getDraftNo().isBlank()) {
      draft.setDraftNo(numberGenerator.nextPriceDraftNo());
    }
    if (draft.getDraftVersion() == null) {
      draft.setDraftVersion(1);
    }
    ensureOne(draftMapper.insert(draft), "保存价格草稿");
    return draft;
  }

  @Override
  @Transactional
  public List<QuotePriceDraftField> saveFields(List<QuotePriceDraftField> fields) {
    if (fields == null || fields.isEmpty()) {
      throw new IllegalArgumentException("价格草稿字段不能为空");
    }
    List<QuotePriceDraftField> values = List.copyOf(fields);
    for (QuotePriceDraftField field : values) {
      if (field == null) {
        throw new IllegalArgumentException("价格草稿字段不能包含空值");
      }
      ensureOne(fieldMapper.insert(field), "保存价格草稿字段");
    }
    return values;
  }

  @Override
  public Optional<QuotePriceDraft> findById(Long id, CollaborationScope scope) {
    return Optional.ofNullable(draftMapper.selectScopedById(
        requireId(id, "价格草稿ID"), scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public Optional<QuotePriceDraft> findByNo(String draftNo, CollaborationScope scope) {
    return Optional.ofNullable(draftMapper.selectScopedByNo(
        CollaborationScope.requireText(draftNo, "价格草稿号"),
        scope.businessUnitType(), scope.applicableOrgCode()));
  }

  @Override
  public List<QuotePriceDraft> findByProductTask(
      Long productTaskId, CollaborationScope scope) {
    return draftMapper.selectByProductTask(requireId(productTaskId, "产品任务ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public List<QuotePriceDraftField> findFields(
      Long priceDraftId, CollaborationScope scope) {
    return fieldMapper.selectByDraft(requireId(priceDraftId, "价格草稿ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public List<QuotePriceDraft> findByPublishedSource(
      String sourceTable, Long sourceId, CollaborationScope scope) {
    return draftMapper.selectByPublishedSource(
        CollaborationScope.requireText(sourceTable, "正式来源表"),
        requireId(sourceId, "正式来源记录ID"),
        scope.businessUnitType(), scope.applicableOrgCode());
  }

  @Override
  public QuotePriceDraft transitionStatus(
      Long id,
      Integer expectedVersion,
      String expectedStatus,
      String nextStatus,
      CollaborationScope scope,
      CollaborationActor actor) {
    int affected = draftMapper.transitionStatusWithVersion(
        requireId(id, "价格草稿ID"), requireVersion(expectedVersion),
        CollaborationScope.requireText(expectedStatus, "源状态"),
        CollaborationScope.requireText(nextStatus, "目标状态"),
        scope.businessUnitType(), scope.applicableOrgCode(),
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("价格草稿", id, expectedVersion);
    }
    return findById(id, scope).orElseThrow(
        () -> new CollaborationPersistenceException("价格草稿更新后无法读取：id=" + id));
  }

  @Override
  public QuotePriceDraft updateEditable(
      QuotePriceDraft draft, Integer expectedVersion, CollaborationScope scope) {
    if (draft == null || draft.getId() == null) {
      throw new IllegalArgumentException("价格草稿及ID不能为空");
    }
    int affected = draftMapper.updateEditableContent(
        draft, requireVersion(expectedVersion), scope.businessUnitType(), scope.applicableOrgCode());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("价格草稿", draft.getId(), expectedVersion);
    }
    return findById(draft.getId(), scope).orElseThrow(
        () -> new CollaborationPersistenceException("价格草稿保存后无法读取：id=" + draft.getId()));
  }

  @Override
  public QuotePriceDraft changeReference(
      QuotePriceDraft draft, Integer expectedVersion, CollaborationScope scope) {
    if (draft == null || draft.getId() == null) {
      throw new IllegalArgumentException("价格草稿及ID不能为空");
    }
    int affected = draftMapper.changeReference(
        draft, requireVersion(expectedVersion), scope.businessUnitType(), scope.applicableOrgCode());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("价格草稿", draft.getId(), expectedVersion);
    }
    return findById(draft.getId(), scope).orElseThrow(
        () -> new CollaborationPersistenceException("更换参考后无法读取价格草稿：id=" + draft.getId()));
  }

  @Override
  public QuotePriceDraft updateValidation(
      Long id, Integer expectedVersion, String validationStatus, String validationMessage,
      CollaborationScope scope, CollaborationActor actor) {
    Long draftId = requireId(id, "价格草稿ID");
    int affected = draftMapper.updateValidationResult(draftId, requireVersion(expectedVersion),
        CollaborationScope.requireText(validationStatus, "校验状态"), validationMessage,
        scope.businessUnitType(), scope.applicableOrgCode(),
        actor == null ? null : actor.userId(), actor == null ? null : actor.userName());
    if (affected != 1) {
      throw new CollaborationOptimisticLockException("价格草稿", draftId, expectedVersion);
    }
    return findById(draftId, scope).orElseThrow(
        () -> new CollaborationPersistenceException("价格草稿校验后无法读取：id=" + draftId));
  }

  @Override
  @Transactional
  public List<QuotePriceDraftField> replaceEditableFields(
      Long priceDraftId, List<QuotePriceDraftField> fields, CollaborationScope scope) {
    Long id = requireId(priceDraftId, "价格草稿ID");
    fieldMapper.deleteEditableByDraft(id, scope.businessUnitType(), scope.applicableOrgCode());
    return saveFields(fields);
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

  private static void ensureOne(int affected, String operation) {
    if (affected != 1) {
      throw new CollaborationPersistenceException(operation + "失败，影响行数=" + affected);
    }
  }
}
