package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.BomSettlementRuleUpsertRequest;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.mapper.BomSettlementRuleMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomSettlementRuleController · 新 BOM 结算规则 CRUD")
class BomSettlementRuleControllerTest {

  private BomSettlementRuleMapper mapper;
  private BomSettlementRuleController controller;

  @BeforeEach
  void setUp() {
    mapper = mock(BomSettlementRuleMapper.class);
    controller = new BomSettlementRuleController(mapper);
  }

  @Test
  @DisplayName("GET /settlement-rules：读取 lp_bom_settlement_rule")
  void listReturnsMapperResult() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(1L);
    rule.setRuleCode("SPECIAL_PURCHASE_ROLLUP_MESH");
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(rule));

    CommonResult<List<BomSettlementRule>> result =
        controller.list(1, "SPECIAL_PURCHASE_ROLLUP", "ROLLUP_TO_PARENT", null, null);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).singleElement().satisfies(item -> {
      assertThat(item.getId()).isEqualTo(1L);
      assertThat(item.getRuleCode()).isEqualTo("SPECIAL_PURCHASE_ROLLUP_MESH");
    });
    verify(mapper).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("POST /settlement-rules：新增结算规则")
  void createInsertsViaMapper() {
    BomSettlementRuleUpsertRequest req = upsertRequest();

    CommonResult<BomSettlementRule> result = controller.create(req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getRuleCode()).isEqualTo("SPECIAL_PURCHASE_ROLLUP_MESH");
    assertThat(result.getData().getSettlementAction()).isEqualTo("ROLLUP_TO_PARENT");
    verify(mapper).insert(any(BomSettlementRule.class));
  }

  @Test
  @DisplayName("PUT /settlement-rules/{id}：id 不存在返 BAD_REQUEST")
  void updateMissingIdReturnsBadRequest() {
    when(mapper.selectById(eq(999L))).thenReturn(null);

    CommonResult<BomSettlementRule> result = controller.update(999L, upsertRequest());

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("BOM 结算规则不存在");
  }

  @Test
  @DisplayName("PUT /settlement-rules/{id}：覆盖字段并更新")
  void updateExistingAppliesFields() {
    BomSettlementRule existing = new BomSettlementRule();
    existing.setId(7L);
    existing.setPriority(100);
    when(mapper.selectById(7L)).thenReturn(existing);

    BomSettlementRuleUpsertRequest req = new BomSettlementRuleUpsertRequest();
    req.setPriority(5);
    req.setRemark("输出父件是结算粒度");

    CommonResult<BomSettlementRule> result = controller.update(7L, req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getPriority()).isEqualTo(5);
    assertThat(result.getData().getRemark()).isEqualTo("输出父件是结算粒度");
    verify(mapper).updateById(existing);
  }

  @Test
  @DisplayName("DELETE /settlement-rules/{id}：软删")
  void deleteReturnsAffectedGtZero() {
    when(mapper.deleteById(7L)).thenReturn(1);

    CommonResult<Boolean> result = controller.delete(7L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isTrue();
  }

  @Test
  @DisplayName("POST /settlement-rules/{id}/toggle：enabled 从 1 切到 0")
  void toggleFlipsEnabled() {
    BomSettlementRule existing = new BomSettlementRule();
    existing.setId(7L);
    existing.setEnabled(1);
    when(mapper.selectById(7L)).thenReturn(existing);

    CommonResult<BomSettlementRule> result = controller.toggle(7L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getEnabled()).isZero();
    verify(mapper).updateById(existing);
  }

  private BomSettlementRuleUpsertRequest upsertRequest() {
    BomSettlementRuleUpsertRequest req = new BomSettlementRuleUpsertRequest();
    req.setRuleCode("SPECIAL_PURCHASE_ROLLUP_MESH");
    req.setRuleName("特殊采购分类上卷：丝网");
    req.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    req.setSettlementAction("ROLLUP_TO_PARENT");
    req.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    req.setSubRefType("SPECIAL_ROLLUP_CHILD");
    req.setMatchConditionJson("{\"nodeConditions\":[]}");
    req.setMarkSubtreeCostRequired(1);
    req.setPriority(10);
    req.setEnabled(1);
    return req;
  }
}
