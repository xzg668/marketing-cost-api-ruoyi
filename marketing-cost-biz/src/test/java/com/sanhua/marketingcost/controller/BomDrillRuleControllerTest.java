package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.DrillRuleUpsertRequest;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import com.sanhua.marketingcost.mapper.BomStopDrillRuleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BomDrillRuleController} 单测 —— new + mock Mapper，不起 MockMvc，
 * 沿用项目 Controller 单测约定。验证路由 / 参数透传 / 错误响应。
 */
@DisplayName("BomDrillRuleController · 规则 CRUD 路由 / 参数透传")
class BomDrillRuleControllerTest {

  private BomStopDrillRuleMapper mapper;
  private BomDrillRuleController controller;

  @BeforeEach
  void setUp() {
    mapper = mock(BomStopDrillRuleMapper.class);
    controller = new BomDrillRuleController(mapper);
  }

  @Test
  @DisplayName("GET /drill-rules：参数透传，返回 Mapper 查询结果")
  void list_returnsMapperResult() {
    BomStopDrillRule r = new BomStopDrillRule();
    r.setId(1L);
    r.setMatchType("NAME_LIKE");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(r));

    CommonResult<List<BomStopDrillRule>> result = controller.list(1, "NAME_LIKE");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).hasSize(1);
    assertThat(result.getData().get(0).getId()).isEqualTo(1L);
    verify(mapper).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("POST /drill-rules：构造 entity 后委托给 Mapper.insert")
  void create_insertsViaMapper() {
    DrillRuleUpsertRequest req = new DrillRuleUpsertRequest();
    req.setMatchType("SHAPE_ATTR_EQ");
    req.setMatchValue("部品联动");
    req.setDrillAction("STOP_AND_COST_ROW");
    req.setMarkSubtreeCostRequired(0);
    req.setPriority(50);
    req.setEnabled(1);

    CommonResult<BomStopDrillRule> result = controller.create(req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getMatchType()).isEqualTo("SHAPE_ATTR_EQ");
    assertThat(result.getData().getMatchValue()).isEqualTo("部品联动");
    verify(mapper).insert(any(BomStopDrillRule.class));
  }

  @Test
  @DisplayName("PUT /drill-rules/{id}：id 不存在返 BAD_REQUEST")
  void update_missingId_returnsBadRequest() {
    when(mapper.selectById(eq(999L))).thenReturn(null);

    CommonResult<BomStopDrillRule> result =
        controller.update(999L, new DrillRuleUpsertRequest());

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("规则不存在");
  }

  @Test
  @DisplayName("PUT /drill-rules/{id}：id 存在 → 覆盖字段 + Mapper.updateById")
  void update_existing_appliesFields() {
    BomStopDrillRule existing = new BomStopDrillRule();
    existing.setId(7L);
    existing.setPriority(100);
    existing.setEnabled(1);
    when(mapper.selectById(7L)).thenReturn(existing);

    DrillRuleUpsertRequest req = new DrillRuleUpsertRequest();
    req.setPriority(5);

    CommonResult<BomStopDrillRule> result = controller.update(7L, req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPriority()).isEqualTo(5);
    verify(mapper).updateById(existing);
  }

  @Test
  @DisplayName("DELETE /drill-rules/{id}：Mapper.deleteById 返 true 即成功")
  void delete_returnsAffectedGtZero() {
    when(mapper.deleteById(7L)).thenReturn(1);

    CommonResult<Boolean> result = controller.delete(7L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isTrue();
  }

  @Test
  @DisplayName("POST /{id}/toggle：enabled 从 1 切到 0")
  void toggle_flipsEnabled() {
    BomStopDrillRule existing = new BomStopDrillRule();
    existing.setId(7L);
    existing.setEnabled(1);
    when(mapper.selectById(7L)).thenReturn(existing);

    CommonResult<BomStopDrillRule> result = controller.toggle(7L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getEnabled()).isZero();
    verify(mapper).updateById(existing);
  }

  @Test
  @DisplayName("POST /{id}/toggle：id 不存在返 BAD_REQUEST")
  void toggle_missingId_returnsBadRequest() {
    when(mapper.selectById(eq(999L))).thenReturn(null);
    CommonResult<BomStopDrillRule> result = controller.toggle(999L);
    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("规则不存在");
  }
}
