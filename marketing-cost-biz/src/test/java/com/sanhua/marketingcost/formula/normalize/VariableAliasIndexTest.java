package com.sanhua.marketingcost.formula.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 变量别名索引单元测试 —— 覆盖最长匹配、中文别名、冲突 fail-fast。
 */
class VariableAliasIndexTest {

  /** 工厂：最短形式快速构造一条 PriceVariable */
  private static PriceVariable variable(String code, String aliasesJson) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(code);
    v.setStatus("active");
    v.setAliasesJson(aliasesJson);
    return v;
  }

  /** 工具：用给定变量列表构造一个 VariableAliasIndex 并触发 init */
  @SuppressWarnings("unchecked")
  private static VariableAliasIndex build(List<PriceVariable> variables) {
    PriceVariableMapper mapper = mock(PriceVariableMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(variables);
    VariableAliasIndex idx = new VariableAliasIndex(mapper);
    idx.init();
    return idx;
  }

  /** (1) 中文别名命中：电解铜 → Cu */
  @Test
  @DisplayName("中文别名命中：电解铜 → Cu")
  void chineseAliasMatches() {
    VariableAliasIndex idx = build(List.of(
        variable("Cu", "[\"电解铜\",\"铜\"]"),
        variable("Zn", "[\"电解锌\",\"锌\"]")));
    Optional<VariableAliasIndex.Match> m = idx.match("电解铜", 0);
    assertThat(m).isPresent();
    assertThat(m.get().variableCode()).isEqualTo("Cu");
    assertThat(m.get().length()).isEqualTo(3);
  }

  /** (2) 中文多字别名：下料重量 → blank_weight */
  @Test
  @DisplayName("中文多字别名命中：下料重量 → blank_weight")
  void multiCharAliasMatches() {
    VariableAliasIndex idx = build(List.of(
        variable("blank_weight", "[\"下料重量\",\"下料重\"]")));
    Optional<VariableAliasIndex.Match> m = idx.match("下料重量*0.5", 0);
    assertThat(m).isPresent();
    assertThat(m.get().variableCode()).isEqualTo("blank_weight");
    // "下料重量" 比 "下料重" 长，最长优先命中 4 字符
    assertThat(m.get().length()).isEqualTo(4);
  }

  /** (3) 最长优先：1#Cu 不被误匹配为 Cu */
  @Test
  @DisplayName("最长优先：'1#Cu' 优先于裸 'Cu'")
  void longestMatchWins() {
    VariableAliasIndex idx = build(List.of(
        variable("Cu", "[\"电解铜\"]"),
        variable("Cu_hq", "[\"1#Cu\"]")));
    Optional<VariableAliasIndex.Match> m = idx.match("1#Cu+2", 0);
    assertThat(m).isPresent();
    assertThat(m.get().variableCode()).isEqualTo("Cu_hq");
    assertThat(m.get().length()).isEqualTo(4);
  }

  /** (4) 非法输入：offset 超界或 text=null → Optional.empty() */
  @Test
  @DisplayName("未命中：非变量字符开头返回 empty")
  void missReturnsEmpty() {
    VariableAliasIndex idx = build(List.of(
        variable("Cu", "[\"电解铜\"]")));
    assertThat(idx.match("xyz", 0)).isEmpty();
    assertThat(idx.match(null, 0)).isEmpty();
    assertThat(idx.match("Cu", 10)).isEmpty();
  }

  /** (5) 别名冲突：同一别名指向不同 code 启动失败 */
  @Test
  @DisplayName("别名冲突 fail-fast：'铜' 同时指向 Cu 与 Cu2")
  void conflictFailsFast() {
    PriceVariableMapper mapper = mock(PriceVariableMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        variable("Cu", "[\"铜\"]"),
        variable("Cu2", "[\"铜\"]")));
    VariableAliasIndex idx = new VariableAliasIndex(mapper);
    assertThatThrownBy(idx::init)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("别名冲突");
  }

  /** (6) variable_code 自身即别名：公式直接写 Cu 也能命中 */
  @Test
  @DisplayName("variable_code 自身作为别名")
  void codeIsAlsoAlias() {
    VariableAliasIndex idx = build(List.of(variable("Cu", null)));
    Optional<VariableAliasIndex.Match> m = idx.match("Cu*0.5", 0);
    assertThat(m).isPresent();
    assertThat(m.get().variableCode()).isEqualTo("Cu");
    assertThat(m.get().length()).isEqualTo(2);
  }

  /** (7) 偏移扫描：match 从 offset 起按最长匹配 */
  @Test
  @DisplayName("从偏移位置匹配：offset=2 跳过前缀")
  void matchFromOffset() {
    VariableAliasIndex idx = build(List.of(
        variable("blank_weight", "[\"下料重量\"]")));
    Optional<VariableAliasIndex.Match> m = idx.match("ab下料重量*5", 2);
    assertThat(m).isPresent();
    assertThat(m.get().variableCode()).isEqualTo("blank_weight");
    assertThat(m.get().length()).isEqualTo(4);
  }
}
