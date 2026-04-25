package com.sanhua.marketingcost.formula.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link FormulaDisplayRenderer} 单测 —— 覆盖反向映射的常见与边界场景。
 */
class FormulaDisplayRendererTest {

  private PriceVariableMapper mapper;
  private RowLocalPlaceholderRegistry rowLocalRegistry;
  private FormulaDisplayRenderer renderer;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
  }

  @BeforeEach
  void setUp() {
    mapper = mock(PriceVariableMapper.class);
    rowLocalRegistry = mock(RowLocalPlaceholderRegistry.class);
    // 默认返回两个系统常用占位符，和 DB seed 一致
    when(rowLocalRegistry.displayNames()).thenReturn(
        Map.of("__material", "材料含税价格", "__scrap", "废料含税价格"));
    renderer = new FormulaDisplayRenderer(mapper, rowLocalRegistry);
  }

  @Test
  @DisplayName("常规：aliases_json[0] 作为显示名")
  void basic() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("Cu", "电解铜", "[\"电解铜\",\"铜\",\"1#Cu\"]"),
        variable("Zn", "电解锌", "[\"电解锌\",\"锌\"]")));

    String out = renderer.renderCn("[Cu]*0.59+[Zn]*0.41");

    assertThat(out).isEqualTo("电解铜*0.59+电解锌*0.41");
  }

  @Test
  @DisplayName("aliases 为空 → fallback variable_name")
  void aliasesEmptyFallbackToName() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("blank_weight", "下料重量", null)));

    String out = renderer.renderCn("[blank_weight]*0.001");

    assertThat(out).isEqualTo("下料重量*0.001");
  }

  @Test
  @DisplayName("aliases 和 variable_name 都为空 → fallback variable_code 自身")
  void everythingEmptyFallbackToCode() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("my_weird_var", null, null)));

    String out = renderer.renderCn("[my_weird_var]*2");

    assertThat(out).isEqualTo("my_weird_var*2");
  }

  @Test
  @DisplayName("未知 token → 原样保留 [xxx] 并打 WARN，不炸")
  void unknownTokenKeptAsIs() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("Cu", "电解铜", "[\"电解铜\"]")));

    String out = renderer.renderCn("[Cu]*2+[UnknownVar]");

    assertThat(out).isEqualTo("电解铜*2+[UnknownVar]");
  }

  @Test
  @DisplayName("复杂表达式：多 token 混杂常量、括号、乘除，全部正确替换")
  void complex() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("blank_weight", "下料重量",
            "[\"下料重量\",\"下料重\"]"),
        variable("material_price_incl", "材料含税价格",
            "[\"材料含税价格\",\"材料价格\"]"),
        variable("net_weight", "产品净重",
            "[\"产品净重\",\"净重\"]"),
        variable("scrap_price_incl", "废料含税价格",
            "[\"废料含税价格\"]"),
        variable("process_fee_incl", "含税加工费",
            "[\"含税加工费\"]")));

    String out = renderer.renderCn(
        "[blank_weight]*[material_price_incl]"
            + "-([blank_weight]-[net_weight])*[scrap_price_incl]"
            + "+[process_fee_incl]");

    assertThat(out).isEqualTo(
        "下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格+含税加工费");
  }

  @Test
  @DisplayName("输入 null/空白 → 原样返回（不触发 DB 查询）")
  void nullAndBlank() {
    assertThat(renderer.renderCn(null)).isNull();
    assertThat(renderer.renderCn("")).isEmpty();
    assertThat(renderer.renderCn("   ")).isEqualTo("   ");
  }

  @Test
  @DisplayName("别名中含 regex 特殊字符（如 '$'、'\\\\'）不会破坏替换")
  void specialCharsInAlias() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("weird", null, "[\"$weird\\\\name\"]")));

    String out = renderer.renderCn("[weird]+1");

    assertThat(out).isEqualTo("$weird\\name+1");
  }

  @Test
  @DisplayName("行局部占位符 __material / __scrap —— DB 没登记时走预置中文名")
  void rowLocalPlaceholdersFallbackToBuiltinDisplay() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("blank_weight", "下料重量", "[\"下料重量\"]"),
        variable("net_weight", "产品净重", "[\"产品净重\"]"),
        variable("process_fee_incl", "含税加工费", "[\"含税加工费\"]")));

    String out = renderer.renderCn(
        "[blank_weight]*[__material]-([blank_weight]-[net_weight])*[__scrap]+[process_fee_incl]");

    assertThat(out).isEqualTo(
        "下料重量*材料含税价格-(下料重量-产品净重)*废料含税价格+含税加工费");
  }

  @Test
  @DisplayName("refresh 后读到新映射")
  void refreshReloads() {
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("Cu", "电解铜", "[\"电解铜\"]")));
    assertThat(renderer.renderCn("[Cu]")).isEqualTo("电解铜");

    // 模拟运维改了别名，第 2 次 loadAll 返回不同结果
    when(mapper.selectList(any())).thenReturn(List.of(
        variable("Cu", "电解铜", "[\"1#Cu\"]")));
    renderer.refresh();

    assertThat(renderer.renderCn("[Cu]")).isEqualTo("1#Cu");
  }

  private static PriceVariable variable(String code, String name, String aliasesJson) {
    PriceVariable v = new PriceVariable();
    v.setVariableCode(code);
    v.setVariableName(name);
    v.setAliasesJson(aliasesJson);
    v.setStatus("active");
    return v;
  }
}
