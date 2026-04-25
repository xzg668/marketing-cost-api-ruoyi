package com.sanhua.marketingcost.mapper.bom;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import com.sanhua.marketingcost.mapper.BomStopDrillRuleMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link BomStopDrillRuleMapper} 真实 DB CRUD + 软删（@TableLogic）验证。 */
@Tag("integration")
@DisplayName("BomStopDrillRuleMapper · bom_stop_drill_rule CRUD + @TableLogic")
class BomStopDrillRuleMapperTest extends BomMapperTestBase {

  /** 用随机后缀的 match_value 避免和 V40 初始规则 / 其他用例冲突 */
  private final String matchValueTag = "TEST_" + UUID.randomUUID().toString().substring(0, 8);

  @Autowired private BomStopDrillRuleMapper mapper;

  @AfterEach
  void cleanUp() throws Exception {
    // 硬删：软删后下次插入仍然在表里，影响下个用例的计数；用 SQL 直接清
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM bom_stop_drill_rule WHERE match_value LIKE '%" + matchValueTag + "%'");
    }
  }

  @Test
  @DisplayName("insert：写入后 id 回填，createdAt / updatedAt 自动填充")
  void testInsert() {
    BomStopDrillRule rule = newRule("NAME_LIKE", matchValueTag);

    int affected = mapper.insert(rule);

    assertThat(affected).isEqualTo(1);
    assertThat(rule.getId()).isNotNull().isPositive();
    BomStopDrillRule loaded = mapper.selectById(rule.getId());
    assertThat(loaded.getCreatedAt()).isNotNull();
    assertThat(loaded.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("selectById：持久化的字段（drill_action / priority / enabled）读出值对齐")
  void testSelectById() {
    BomStopDrillRule rule = newRule("SHAPE_ATTR_EQ", matchValueTag + "_S");
    rule.setPriority(30);
    rule.setEnabled(1);
    mapper.insert(rule);

    BomStopDrillRule loaded = mapper.selectById(rule.getId());

    assertThat(loaded.getMatchType()).isEqualTo("SHAPE_ATTR_EQ");
    assertThat(loaded.getMatchValue()).isEqualTo(matchValueTag + "_S");
    assertThat(loaded.getDrillAction()).isEqualTo("STOP_AND_COST_ROW");
    assertThat(loaded.getPriority()).isEqualTo(30);
    assertThat(loaded.getEnabled()).isEqualTo(1);
    assertThat(loaded.getDeleted()).as("软删默认 0").isEqualTo(0);
  }

  @Test
  @DisplayName("updateById：改 priority 后重新查出值已更新")
  void testUpdate() {
    BomStopDrillRule rule = newRule("NAME_LIKE", matchValueTag + "_U");
    rule.setPriority(100);
    mapper.insert(rule);

    BomStopDrillRule patch = new BomStopDrillRule();
    patch.setId(rule.getId());
    patch.setPriority(5);
    int affected = mapper.updateById(patch);

    assertThat(affected).isEqualTo(1);
    assertThat(mapper.selectById(rule.getId()).getPriority()).isEqualTo(5);
  }

  @Test
  @DisplayName("deleteById：@TableLogic 生效，MP 查询不再返回，DB 里真行仍在（deleted=1）")
  void testLogicDelete() throws Exception {
    BomStopDrillRule rule = newRule("CATEGORY_EQ", matchValueTag + "_D");
    mapper.insert(rule);
    Long id = rule.getId();

    int affected = mapper.deleteById(id);
    assertThat(affected).isEqualTo(1);

    // 1) MP 层：selectById 应过滤 deleted=1 → 返回 null
    assertThat(mapper.selectById(id)).as("软删后 MP 不应再能查到该行").isNull();

    // 2) MP 层：按 match_value 查也不返回
    long cntViaMp =
        mapper.selectCount(new QueryWrapper<BomStopDrillRule>().eq("match_value", rule.getMatchValue()));
    assertThat(cntViaMp).as("软删后 MP 层计数为 0").isZero();

    // 3) DB 层：用原生 SQL 绕过 @TableLogic，行其实还在，deleted=1
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs =
            stmt.executeQuery(
                "SELECT deleted FROM bom_stop_drill_rule WHERE id = " + id)) {
      assertThat(rs.next()).as("DB 里原始行仍应存在").isTrue();
      assertThat(rs.getInt("deleted")).as("软删标记应置 1").isEqualTo(1);
    }
  }

  // ============================ 辅助 ============================

  private BomStopDrillRule newRule(String matchType, String matchValue) {
    BomStopDrillRule rule = new BomStopDrillRule();
    rule.setMatchType(matchType);
    rule.setMatchValue(matchValue);
    rule.setDrillAction("STOP_AND_COST_ROW");
    rule.setMarkSubtreeCostRequired(1);
    rule.setPriority(100);
    rule.setEnabled(1);
    return rule;
  }
}
