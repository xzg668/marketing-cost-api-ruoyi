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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FormulaNormalizer 单元测试 —— 覆盖四阶段管线 + Excel 真实公式样本。
 */
class FormulaNormalizerTest {

  /** 构造一条 active 状态变量（带别名） */
  private static PriceVariable v(String code, String aliasesJson) {
    PriceVariable pv = new PriceVariable();
    pv.setVariableCode(code);
    pv.setVariableName(code);
    pv.setStatus("active");
    pv.setAliasesJson(aliasesJson);
    return pv;
  }

  /** 构造一个覆盖 Excel 27 条公式所需的最小变量字典 */
  @SuppressWarnings("unchecked")
  private static FormulaNormalizer buildNormalizer() {
    PriceVariableMapper mapper = mock(PriceVariableMapper.class);
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        v("Cu", "[\"电解铜\",\"铜\"]"),
        v("Zn", "[\"电解锌\",\"锌\"]"),
        v("Ag", "[\"白银\"]"),
        v("In", "[\"精铟\"]"),
        v("Pcu", "[\"磷铜\"]"),
        v("blank_weight", "[\"下料重量\",\"下料重\"]"),
        v("net_weight", "[\"产品净重\",\"净重\"]"),
        v("process_fee", "[\"加工费\"]"),
        v("process_fee_incl", "[\"含税加工费\"]"),
        v("material_price_incl", "[\"材料含税价格\",\"材料价格\"]"),
        v("scrap_price_incl", "[\"废料含税价格\",\"废料价格\"]"),
        v("copper_scrap_price", "[\"铜沫价格\"]")));
    VariableAliasIndex idx = new VariableAliasIndex(mapper);
    idx.init();
    return new FormulaNormalizer(
        idx,
        com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport
            .defaultRegistry());
  }

  // ============================ 阶段单测 ============================

  @Test
  @DisplayName("阶段1 - 括号归一化：（） → ()")
  void stage1Brackets() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalizeBrackets("（Cu+Zn）*2")).isEqualTo("(Cu+Zn)*2");
  }

  @Test
  @DisplayName("阶段2 - 单位剥离：3.5元/Kg → 3.5")
  void stage2StripUnit() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.stripUnitAnnotations("3.5元/Kg*1.13")).isEqualTo("3.5*1.13");
    assertThat(n.stripUnitAnnotations("3.5元/KG*1.13")).isEqualTo("3.5*1.13");
    // 复合"元/Kg/1.17" —— 单位剥离后留 /1.17
    assertThat(n.stripUnitAnnotations("3.5元/Kg/1.17*1.13"))
        .isEqualTo("3.5/1.17*1.13");
  }

  @Test
  @DisplayName("阶段3 - 变量标签化：中文别名替换为 [code]")
  void stage3TagChinese() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.tagVariables("电解铜+加工费")).isEqualTo("[Cu]+[process_fee]");
  }

  @Test
  @DisplayName("阶段3 - 最长优先：下料重量 不被 下料重 抢走")
  void stage3LongestFirst() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.tagVariables("下料重量*0.5")).isEqualTo("[blank_weight]*0.5");
  }

  @Test
  @DisplayName("阶段4 - 括号不平衡报错")
  void stage4UnbalancedThrows() {
    FormulaNormalizer n = buildNormalizer();
    assertThatThrownBy(() -> n.cleanup("(Cu+Zn"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("括号不平衡");
  }

  // ============================ 端到端用例 ============================

  @Test
  @DisplayName("E2E - 中文括号+中文变量：（Cu+Zn）*2")
  void e2eBracketsChinese() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalize("（Cu+Zn）*2")).isEqualTo("([Cu]+[Zn])*2");
  }

  @Test
  @DisplayName("E2E - 混合中英：（电解铜*0.65+Zn*0.35）*1.03*产品净重+加工费")
  void e2eMixedChineseEnglish() {
    FormulaNormalizer n = buildNormalizer();
    String out = n.normalize("（电解铜*0.65+Zn*0.35）*1.03*产品净重+加工费");
    assertThat(out).isEqualTo("([Cu]*0.65+[Zn]*0.35)*1.03*[net_weight]+[process_fee]");
  }

  @Test
  @DisplayName("E2E - Excel 样本#1：下料重量*材料含税价格-（下料重量-产品净重）*废料含税价格+含税加工费")
  void e2eExcelSample1() {
    FormulaNormalizer n = buildNormalizer();
    String out = n.normalize(
        "下料重量*材料含税价格-（下料重量-产品净重）*废料含税价格+含税加工费");
    // V34 起 B 组 token（材料含税价格/废料含税价格）→ [__material]/[__scrap]
    // 由 evaluator 运行期按 linked_item_id 反查 lp_price_variable_binding 实际解析
    assertThat(out).isEqualTo(
        "[blank_weight]*[__material]-([blank_weight]-[net_weight])"
            + "*[__scrap]+[process_fee_incl]");
  }

  @Test
  @DisplayName("E2E - Excel 样本#2：含单位注释")
  void e2eExcelSample2() {
    FormulaNormalizer n = buildNormalizer();
    // 3.5元/Kg/1.17*1.13 —— 剥单位后 /1.17*1.13
    String out = n.normalize(
        "下料重量*（Cu*0.62/0.98+Zn*0.38/0.95+3.5元/Kg/1.17*1.13）/1000");
    assertThat(out).isEqualTo(
        "[blank_weight]*([Cu]*0.62/0.98+[Zn]*0.38/0.95+3.5/1.17*1.13)/1000");
  }

  @Test
  @DisplayName("E2E - Excel 样本#3：银基焊环 Ag*0.012+Cu*0.5+...")
  void e2eExcelSample3() {
    FormulaNormalizer n = buildNormalizer();
    String out = n.normalize("Ag*0.012+Cu*0.5+In*0.0013+Pcu*0.4867+加工费");
    assertThat(out).isEqualTo(
        "[Ag]*0.012+[Cu]*0.5+[In]*0.0013+[Pcu]*0.4867+[process_fee]");
  }

  @Test
  @DisplayName("E2E - 毛细管Ⅰ：电解铜+加工费")
  void e2eExcelSampleCapillary() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalize("电解铜+加工费")).isEqualTo("[Cu]+[process_fee]");
  }

  @Test
  @DisplayName("E2E - 阀体：(Cu*0.65+Zn*0.35)*1.03*产品净重+加工费")
  void e2eValveBody() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalize("(Cu*0.65+Zn*0.35)*1.03*产品净重+加工费"))
        .isEqualTo("([Cu]*0.65+[Zn]*0.35)*1.03*[net_weight]+[process_fee]");
  }

  @Test
  @DisplayName("E2E - 空串与 null 回传空串")
  void e2eEmpty() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalize(null)).isEmpty();
    assertThat(n.normalize("")).isEmpty();
  }

  @Test
  @DisplayName("E2E - 括号不平衡抛 FormulaSyntaxException")
  void e2eUnbalanced() {
    FormulaNormalizer n = buildNormalizer();
    assertThatThrownBy(() -> n.normalize("(Cu+Zn"))
        .isInstanceOf(FormulaSyntaxException.class);
  }

  @Test
  @DisplayName("E2E - 铜沫价格整体作为 PART_CONTEXT 变量被标签化")
  void e2eCopperScrapAsVariable() {
    FormulaNormalizer n = buildNormalizer();
    // "铜沫价格" 本身是 PART_CONTEXT 变量；原 Excel 带括号注释 "(Cu*0.59+Zn*0.41)"
    // 先用 normalize 处理整段：括号归一化 + 变量替换
    String out = n.normalize("铜沫价格*0.915");
    assertThat(out).isEqualTo("[copper_scrap_price]*0.915");
  }

  @Test
  @DisplayName("E2E - 规范化是幂等的：已规范化的字符串再跑一次不变")
  void e2eIdempotent() {
    FormulaNormalizer n = buildNormalizer();
    String once = n.normalize("(电解铜+加工费)*2");
    String twice = n.normalize(once);
    assertThat(twice).isEqualTo(once);
  }

  @Test
  @DisplayName("E2E - 未命中中文 token 严格抛 FormulaSyntaxException（Plan B 写入口径）")
  void e2eUnknownCjkTokenThrows() {
    FormulaNormalizer n = buildNormalizer();
    // Plan B：写路径不允许静默放行未注册别名；由 cleanup 阶段的 CJK 扫描兜住
    assertThatThrownBy(() -> n.normalize("未知变量*2"))
        .isInstanceOf(FormulaSyntaxException.class)
        .hasMessageContaining("未识别的中文 token");
  }

  @Test
  @DisplayName("E2E - 全角空白归一化为 ASCII 空格")
  void e2eFullwidthSpace() {
    FormulaNormalizer n = buildNormalizer();
    // 中间含全角空格 \u3000
    String out = n.normalize("Cu\u3000+\u3000Zn");
    assertThat(out).isEqualTo("[Cu] + [Zn]");
  }

  @Test
  @DisplayName("E2E - 代数常量多重除法")
  void e2eMultipleDivision() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalize("Cu/1000*2")).isEqualTo("[Cu]/1000*2");
  }

  // ============================ V34 新增：B 组 token + 全角扩展 ============================

  @Test
  @DisplayName("阶段 2.5 - B 组 token：材料含税价格 → [__material]、废料含税价格 → [__scrap]")
  void stage2_5RowLocalTokens() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.tagRowLocalTokens("下料重量*材料含税价格"))
        .isEqualTo("下料重量*[__material]");
    assertThat(n.tagRowLocalTokens("废料含税价格*0.5"))
        .isEqualTo("[__scrap]*0.5");
  }

  @Test
  @DisplayName("阶段 2.5 - B 组 token 短形：材料价格 / 废料价格")
  void stage2_5RowLocalShortTokens() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.tagRowLocalTokens("A*材料价格-B*废料价格"))
        .isEqualTo("A*[__material]-B*[__scrap]");
  }

  @Test
  @DisplayName("阶段 2.5 - B 组 token 最长优先：材料含税价格 不被 材料价格 抢走")
  void stage2_5RowLocalLongestWins() {
    FormulaNormalizer n = buildNormalizer();
    // "材料含税价格" 长 6 字，"材料价格" 长 4 字；短匹配在前 2 字就分道扬镳，
    // 但如果扫描时先选最长前缀，长 token 优先
    assertThat(n.tagRowLocalTokens("材料含税价格")).isEqualTo("[__material]");
  }

  @Test
  @DisplayName("阶段 2.5 - 已包裹 [xxx] 内部不再扫 B 组 token")
  void stage2_5SkipBracketedSegment() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.tagRowLocalTokens("[blank_weight]+材料价格"))
        .isEqualTo("[blank_weight]+[__material]");
  }

  @Test
  @DisplayName("E2E - V34 B 组 token 完整公式：R2/R4/R10 样本")
  void e2eBGroupFullExpr() {
    FormulaNormalizer n = buildNormalizer();
    // R10 连杆公式样本（Excel 原样）
    String out = n.normalize(
        "下料重量*材料含税价格-（下料重量-产品净重）*废料含税价格+含税加工费");
    assertThat(out).isEqualTo(
        "[blank_weight]*[__material]-([blank_weight]-[net_weight])"
            + "*[__scrap]+[process_fee_incl]");
  }

  @Test
  @DisplayName("阶段 1 - 全角算符：＊ ／ ＋ － ，")
  void stage1FullwidthOperators() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalizeBrackets("3＊4／2＋1－5，6"))
        .isEqualTo("3*4/2+1-5,6");
  }

  @Test
  @DisplayName("阶段 1 - 方头括号 【】 → []")
  void stage1SquareBracketsFullwidth() {
    FormulaNormalizer n = buildNormalizer();
    assertThat(n.normalizeBrackets("【Cu】*2")).isEqualTo("[Cu]*2");
  }

  @Test
  @DisplayName("E2E - 全角混合：（電解銅＊0.62）/1000 + 材料含税价格")
  void e2eFullwidthMixed() {
    FormulaNormalizer n = buildNormalizer();
    // 注意："電解銅" 不在 aliases 表里（只有"电解铜"），应命中简体别名；这里用简体确保可跑
    String out = n.normalize("（电解铜＊0.62）／1000＋材料含税价格");
    assertThat(out).isEqualTo("([Cu]*0.62)/1000+[__material]");
  }

  @Test
  @DisplayName("E2E - 规范化后再跑一次：[__material] 幂等保留")
  void e2eBGroupTokenIdempotent() {
    FormulaNormalizer n = buildNormalizer();
    String once = n.normalize("材料含税价格*2");
    assertThat(once).isEqualTo("[__material]*2");
    String twice = n.normalize(once);
    assertThat(twice).isEqualTo("[__material]*2");
  }
}
