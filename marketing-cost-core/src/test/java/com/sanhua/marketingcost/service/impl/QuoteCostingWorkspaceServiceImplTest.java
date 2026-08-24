package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkspaceMapper;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceOptimisticLockException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("报价核算当前工作区服务")
class QuoteCostingWorkspaceServiceImplTest {

  private QuoteCostingWorkspaceMapper mapper;
  private QuoteCostingWorkspaceServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(QuoteCostingWorkspaceMapper.class);
    service = new QuoteCostingWorkspaceServiceImpl(mapper);
  }

  @Test
  @DisplayName("首次创建只写一条初始工作区并按产品月份返回")
  void createsInitialWorkspace() {
    QuoteCostingWorkspace stored = workspace(9L, 180L, "2026-08", 0);
    when(mapper.selectByItemAndMonth(180L, "2026-08")).thenReturn(stored);

    QuoteCostingWorkspace result =
        service.getOrCreate(" OA-1 ", 180L, " P-1 ", "2026-08", " COMMERCIAL ");

    assertThat(result).isSameAs(stored);
    ArgumentCaptor<QuoteCostingWorkspace> captor =
        ArgumentCaptor.forClass(QuoteCostingWorkspace.class);
    verify(mapper).insertIgnore(captor.capture());
    assertThat(captor.getValue()).satisfies(row -> {
      assertThat(row.getOaNo()).isEqualTo("OA-1");
      assertThat(row.getProductCode()).isEqualTo("P-1");
      assertThat(row.getWorkspaceStatus()).isEqualTo("NOT_STARTED");
      assertThat(row.getCurrentStep()).isEqualTo("PRODUCT_DETAIL");
      assertThat(row.getGapCount()).isZero();
      assertThat(row.getLockVersion()).isZero();
    });
  }

  @Test
  @DisplayName("并发插入已存在时复用唯一工作区")
  void reusesWorkspaceWhenInsertIsIgnored() {
    QuoteCostingWorkspace stored = workspace(9L, 180L, "2026-08", 3);
    when(mapper.insertIgnore(any())).thenReturn(0);
    when(mapper.selectByItemAndMonth(180L, "2026-08")).thenReturn(stored);

    assertThat(service.getOrCreate("OA-1", 180L, "P-1", "2026-08", "COMMERCIAL"))
        .isSameAs(stored);
  }

  @Test
  @DisplayName("重算前创建并锁定唯一产品月份工作区")
  void locksUniqueWorkspaceForRebuild() {
    QuoteCostingWorkspace stored = workspace(9L, 180L, "2026-08", 3);
    QuoteCostingWorkspace locked = workspace(9L, 180L, "2026-08", 3);
    when(mapper.selectByItemAndMonth(180L, "2026-08")).thenReturn(stored);
    when(mapper.selectByItemAndMonthForUpdate(180L, "2026-08")).thenReturn(locked);

    assertThat(service.lockOrCreate("OA-1", 180L, "P-1", "2026-08", "COMMERCIAL"))
        .isSameAs(locked);

    verify(mapper).selectByItemAndMonthForUpdate(180L, "2026-08");
  }

  @Test
  @DisplayName("详情投影按同一月份一次批量读取工作区")
  void findsWorkspacesInOneBatch() {
    QuoteCostingWorkspace first = workspace(9L, 180L, "2026-08", 0);
    QuoteCostingWorkspace second = workspace(10L, 181L, "2026-08", 0);
    when(mapper.selectByItemsAndMonth(List.of(180L, 181L), "2026-08"))
        .thenReturn(List.of(first, second));

    assertThat(service.findAll(List.of(180L, 181L, 180L), "2026-08"))
        .containsExactly(first, second);
  }

  @Test
  @DisplayName("乐观锁更新成功后返回递增版本")
  void updatesWithExpectedVersion() {
    QuoteCostingWorkspace draft = workspace(9L, 180L, "2026-08", 2);
    draft.setWorkspaceStatus("READY");
    QuoteCostingWorkspace stored = workspace(9L, 180L, "2026-08", 3);
    stored.setWorkspaceStatus("READY");
    when(mapper.updateWithVersion(eq(draft), eq(2), any(LocalDateTime.class))).thenReturn(1);
    when(mapper.selectById(9L)).thenReturn(stored);

    assertThat(service.update(draft, 2)).satisfies(result -> {
      assertThat(result.getWorkspaceStatus()).isEqualTo("READY");
      assertThat(result.getLockVersion()).isEqualTo(3);
    });
  }

  @Test
  @DisplayName("旧乐观锁版本明确失败且提示刷新")
  void rejectsStaleVersion() {
    QuoteCostingWorkspace draft = workspace(9L, 180L, "2026-08", 2);
    when(mapper.updateWithVersion(eq(draft), eq(1), any(LocalDateTime.class))).thenReturn(0);

    assertThatThrownBy(() -> service.update(draft, 1))
        .isInstanceOf(QuoteCostingWorkspaceOptimisticLockException.class)
        .hasMessageContaining("刷新后重试");
  }

  @Test
  @DisplayName("月份格式必须严格为YYYY-MM")
  void rejectsInvalidMonth() {
    assertThatThrownBy(
            () -> service.getOrCreate("OA-1", 180L, "P-1", "2026-8", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("YYYY-MM");
  }

  @Test
  @DisplayName("替代选择变化只标记当前产品工作区待重算")
  void marksOneWorkspaceStale() {
    when(mapper.markItemStale(eq(180L), eq("2026-08"), eq("BOM_ALTERNATIVE_CHANGED"), any()))
        .thenReturn(1);

    assertThat(service.markItemStale(180L, "2026-08", "BOM_ALTERNATIVE_CHANGED"))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("规则变化只更新工作区状态不改写BOM和成本明细")
  void marksRuleWorkspacesStale() {
    when(mapper.markBomRuleWorkspacesStale(eq("COMMERCIAL"), eq("BOM_RULE_CHANGED"), any()))
        .thenReturn(7);

    assertThat(service.markBomRuleWorkspacesStale(" COMMERCIAL ", "BOM_RULE_CHANGED"))
        .isEqualTo(7);
  }

  private QuoteCostingWorkspace workspace(
      Long id, Long itemId, String periodMonth, int lockVersion) {
    QuoteCostingWorkspace workspace = new QuoteCostingWorkspace();
    workspace.setId(id);
    workspace.setOaNo("OA-1");
    workspace.setOaFormItemId(itemId);
    workspace.setProductCode("P-1");
    workspace.setPeriodMonth(periodMonth);
    workspace.setBusinessUnitType("COMMERCIAL");
    workspace.setWorkspaceStatus("NOT_STARTED");
    workspace.setCurrentStep("PRODUCT_DETAIL");
    workspace.setGapCount(0);
    workspace.setCarriedForwardPriceCount(0);
    workspace.setLockVersion(lockVersion);
    return workspace;
  }
}
