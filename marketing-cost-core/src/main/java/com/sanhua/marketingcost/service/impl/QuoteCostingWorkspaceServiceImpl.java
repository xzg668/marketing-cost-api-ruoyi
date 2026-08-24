package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkspaceMapper;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceOptimisticLockException;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostingWorkspaceServiceImpl implements QuoteCostingWorkspaceService {

  private static final String INITIAL_STATUS = "NOT_STARTED";
  private static final String INITIAL_STEP = "PRODUCT_DETAIL";

  private final QuoteCostingWorkspaceMapper workspaceMapper;

  public QuoteCostingWorkspaceServiceImpl(QuoteCostingWorkspaceMapper workspaceMapper) {
    this.workspaceMapper = workspaceMapper;
  }

  @Override
  public Optional<QuoteCostingWorkspace> find(Long oaFormItemId, String periodMonth) {
    return Optional.ofNullable(
        workspaceMapper.selectByItemAndMonth(
            requiredId(oaFormItemId), normalizedMonth(periodMonth)));
  }

  @Override
  public List<QuoteCostingWorkspace> findAll(
      Collection<Long> oaFormItemIds,
      String periodMonth) {
    if (oaFormItemIds == null || oaFormItemIds.isEmpty()) {
      return List.of();
    }
    List<Long> normalizedItemIds =
        oaFormItemIds.stream().map(this::requiredId).distinct().toList();
    if (normalizedItemIds.isEmpty()) {
      return List.of();
    }
    List<QuoteCostingWorkspace> workspaces =
        workspaceMapper.selectByItemsAndMonth(normalizedItemIds, normalizedMonth(periodMonth));
    return workspaces == null ? List.of() : workspaces;
  }

  @Override
  @Transactional
  public QuoteCostingWorkspace getOrCreate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String businessUnitType) {
    Long normalizedItemId = requiredId(oaFormItemId);
    String normalizedPeriodMonth = normalizedMonth(periodMonth);
    LocalDateTime now = LocalDateTime.now();
    QuoteCostingWorkspace candidate = new QuoteCostingWorkspace();
    candidate.setOaNo(requiredText("oaNo", oaNo));
    candidate.setOaFormItemId(normalizedItemId);
    candidate.setProductCode(requiredText("productCode", productCode));
    candidate.setPeriodMonth(normalizedPeriodMonth);
    candidate.setBusinessUnitType(requiredText("businessUnitType", businessUnitType));
    candidate.setWorkspaceStatus(INITIAL_STATUS);
    candidate.setCurrentStep(INITIAL_STEP);
    candidate.setGapCount(0);
    candidate.setCarriedForwardPriceCount(0);
    candidate.setLockVersion(0);
    candidate.setCreatedAt(now);
    candidate.setUpdatedAt(now);
    workspaceMapper.insertIgnore(candidate);

    QuoteCostingWorkspace stored =
        workspaceMapper.selectByItemAndMonth(normalizedItemId, normalizedPeriodMonth);
    if (stored == null) {
      throw new IllegalStateException(
          "核算工作区创建后未找到：" + normalizedItemId + "/" + normalizedPeriodMonth);
    }
    return stored;
  }

  @Override
  @Transactional
  public QuoteCostingWorkspace lockOrCreate(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String periodMonth,
      String businessUnitType) {
    QuoteCostingWorkspace workspace =
        getOrCreate(oaNo, oaFormItemId, productCode, periodMonth, businessUnitType);
    QuoteCostingWorkspace locked =
        workspaceMapper.selectByItemAndMonthForUpdate(
            workspace.getOaFormItemId(), workspace.getPeriodMonth());
    if (locked == null) {
      throw new IllegalStateException(
          "核算工作区加锁后未找到：" + workspace.getOaFormItemId() + "/" + workspace.getPeriodMonth());
    }
    return locked;
  }

  @Override
  @Transactional
  public QuoteCostingWorkspace update(
      QuoteCostingWorkspace workspace,
      int expectedLockVersion) {
    if (workspace == null || workspace.getId() == null) {
      throw new IllegalArgumentException("workspace.id 不能为空");
    }
    if (expectedLockVersion < 0) {
      throw new IllegalArgumentException("expectedLockVersion 不能小于0");
    }
    int updated =
        workspaceMapper.updateWithVersion(workspace, expectedLockVersion, LocalDateTime.now());
    if (updated != 1) {
      throw new QuoteCostingWorkspaceOptimisticLockException(workspace.getId());
    }
    QuoteCostingWorkspace stored = workspaceMapper.selectById(workspace.getId());
    if (stored == null) {
      throw new IllegalStateException("核算工作区更新后未找到：" + workspace.getId());
    }
    return stored;
  }

  @Override
  @Transactional
  public int markItemStale(Long oaFormItemId, String periodMonth, String reasonCode) {
    return workspaceMapper.markItemStale(
        requiredId(oaFormItemId),
        normalizedMonth(periodMonth),
        requiredText("reasonCode", reasonCode),
        LocalDateTime.now());
  }

  @Override
  @Transactional
  public int markBomRuleWorkspacesStale(String businessUnitType, String reasonCode) {
    String normalizedBusinessUnit =
        StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : null;
    return workspaceMapper.markBomRuleWorkspacesStale(
        normalizedBusinessUnit,
        requiredText("reasonCode", reasonCode),
        LocalDateTime.now());
  }

  private Long requiredId(Long value) {
    if (value == null || value <= 0) {
      throw new IllegalArgumentException("oaFormItemId 必须大于0");
    }
    return value;
  }

  private String normalizedMonth(String value) {
    String normalized = requiredText("periodMonth", value);
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("periodMonth 必须为YYYY-MM：" + normalized, exception);
    }
  }

  private String requiredText(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }
}
