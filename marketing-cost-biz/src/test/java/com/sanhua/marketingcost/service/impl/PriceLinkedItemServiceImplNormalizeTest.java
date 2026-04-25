package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plan B T6 —— PriceLinkedItemServiceImpl 手工新增/编辑路径的 normalize-on-save
 * 与 formulaExprCn 派生专项测试。
 *
 * <p>覆盖：
 * <ol>
 *   <li>{@code create()} 输入中文公式 → 存 {@code [code]} 形式</li>
 *   <li>{@code update()} 输入中文公式 → 存 {@code [code]} 形式</li>
 *   <li>输入含未知 token 的公式 → 抛 {@link FormulaSyntaxException}（不静默兜底）</li>
 *   <li>{@code toDto} 生成的 {@code formulaExprCn} 由 renderer 派生，与 DB 原值无关</li>
 *   <li>request 传入 {@code formulaExprCn} 被忽略（写入不生效）</li>
 * </ol>
 */
class PriceLinkedItemServiceImplNormalizeTest {

  private PriceLinkedItemMapper itemMapper;
  private PriceFixedItemMapper fixedItemMapper;
  private PriceVariableMapper priceVariableMapper;
  private FormulaNormalizer normalizer;
  private FormulaDisplayRenderer renderer;
  private FormulaValidator validator;
  private PriceLinkedItemServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceLinkedItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceFixedItem.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariable.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceLinkedItemMapper.class);
    fixedItemMapper = mock(PriceFixedItemMapper.class);
    priceVariableMapper = mock(PriceVariableMapper.class);

    // Renderer 读同一份 priceVariableMapper —— 保证写回读取语义一致
    when(priceVariableMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        variable("Cu", "电解铜", "[\"电解铜\",\"Cu\"]"),
        variable("blank_weight", "下料重量", "[\"下料重量\"]")));

    // Normalizer：用真实 VariableAliasIndex 跑，保证中英文 → [code] 映射正确
    VariableAliasIndex aliasIndex = new VariableAliasIndex(priceVariableMapper);
    aliasIndex.init();
    RowLocalPlaceholderRegistry rowLocal =
        com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport
            .defaultRegistry();
    normalizer = new FormulaNormalizer(aliasIndex, rowLocal);
    renderer = new FormulaDisplayRenderer(priceVariableMapper, rowLocal);
    // Validator：跟上游同一份 mapper —— 白名单含 Cu / blank_weight，toDto 中的公式健康检查据此判定
    validator = new FormulaValidator(priceVariableMapper, rowLocal);
    validator.init();

    service = new PriceLinkedItemServiceImpl(
        itemMapper, fixedItemMapper, normalizer, renderer, validator);
  }

  @Test
  @DisplayName("create：中文公式 → DB 存 [code] 形式")
  void createNormalizesFormula() {
    PriceLinkedItemUpdateRequest req = new PriceLinkedItemUpdateRequest();
    req.setPricingMonth("2026-04");
    req.setMaterialCode("MAT-001");
    req.setFormulaExpr("下料重量*0.001+电解铜*2");

    service.create(req);

    ArgumentCaptor<PriceLinkedItem> captor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper).insert(captor.capture());
    // DB 里只留 [code] 形式 —— 再也没有中文 token 漂移风险
    assertThat(captor.getValue().getFormulaExpr())
        .isEqualTo("[blank_weight]*0.001+[Cu]*2");
  }

  @Test
  @DisplayName("update：中文公式 → DB 存 [code] 形式")
  void updateNormalizesFormula() {
    PriceLinkedItem existing = new PriceLinkedItem();
    existing.setId(42L);
    when(itemMapper.selectById(42L)).thenReturn(existing);

    PriceLinkedItemUpdateRequest req = new PriceLinkedItemUpdateRequest();
    req.setFormulaExpr("下料重量*0.001");

    service.update(42L, req);

    ArgumentCaptor<PriceLinkedItem> captor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper).updateById(captor.capture());
    assertThat(captor.getValue().getFormulaExpr())
        .isEqualTo("[blank_weight]*0.001");
  }

  @Test
  @DisplayName("未知 token 的公式 → 抛 FormulaSyntaxException，不入库")
  void unknownTokenThrows() {
    PriceLinkedItemUpdateRequest req = new PriceLinkedItemUpdateRequest();
    req.setPricingMonth("2026-04");
    req.setMaterialCode("MAT-BAD");
    // "神秘金属" 不在 alias 索引里 → FormulaNormalizer 抛 FormulaSyntaxException
    req.setFormulaExpr("神秘金属*2");

    assertThatThrownBy(() -> service.create(req))
        .isInstanceOf(FormulaSyntaxException.class);
    verify(itemMapper, never()).insert(any(PriceLinkedItem.class));
  }

  @Test
  @DisplayName("toDto：formulaExprCn 由 Renderer 派生，与 DB 原值无关")
  void toDtoRendersFormulaExprCn() {
    // 人为在 DB 里留一个漂移的中文列（模拟历史脏数据）
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(7L);
    item.setFormulaExpr("[Cu]*2+[blank_weight]");
    item.setFormulaExprCn("旧的漂移中文"); // 这条应被派生值覆盖
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

    List<PriceLinkedItemDto> list = service.list("2026-04", null);
    assertThat(list).hasSize(1);
    // Renderer 读 aliases_json[0] 回显 —— 电解铜 / 下料重量 是测试 seed 的第一项
    assertThat(list.get(0).getFormulaExprCn()).isEqualTo("电解铜*2+下料重量");
  }

  @Test
  @DisplayName("toDto：正常公式 → formulaValid=true，formulaError=null")
  void toDtoFormulaHealthy() {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(8L);
    item.setFormulaExpr("[Cu]*2");
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

    List<PriceLinkedItemDto> list = service.list("2026-04", null);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getFormulaValid()).isTrue();
    assertThat(list.get(0).getFormulaError()).isNull();
  }

  @Test
  @DisplayName("toDto：空公式 → formulaValid=true（没东西可错）")
  void toDtoEmptyFormulaHealthy() {
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(9L);
    item.setFormulaExpr(null);
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

    List<PriceLinkedItemDto> list = service.list("2026-04", null);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getFormulaValid()).isTrue();
    assertThat(list.get(0).getFormulaError()).isNull();
  }

  @Test
  @DisplayName("toDto：公式含未识别 token → formulaValid=false，error 为 Normalizer 消息")
  void toDtoFormulaInvalid() {
    // 列表 list() 路径下残留的脏公式（写路径因后装 Normalizer 已拦，但历史行仍可能未回洗）
    PriceLinkedItem item = new PriceLinkedItem();
    item.setId(10L);
    item.setFormulaExpr("神秘金属*2");
    when(itemMapper.selectList(any(Wrapper.class))).thenReturn(List.of(item));

    List<PriceLinkedItemDto> list = service.list("2026-04", null);
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getFormulaValid()).isFalse();
    assertThat(list.get(0).getFormulaError()).contains("神秘金属");
  }

  @Test
  @DisplayName("request.formulaExprCn 被忽略；入库值由 Renderer 派生")
  void requestFormulaExprCnIgnored() {
    PriceLinkedItemUpdateRequest req = new PriceLinkedItemUpdateRequest();
    req.setPricingMonth("2026-04");
    req.setMaterialCode("MAT-001");
    req.setFormulaExpr("电解铜*2");
    req.setFormulaExprCn("攻击者尝试手填的漂移值");

    service.create(req);

    ArgumentCaptor<PriceLinkedItem> captor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper).insert(captor.capture());
    // 派生口径升级（2026-04 改动）：merge() 现在写 expr 时顺带派生 cn 列，
    // DB 里 formulaExprCn 不再是 null 而是 Renderer 反向生成的一致值。
    // 前端传入的 "攻击者尝试手填的漂移值" 被无条件丢弃 —— 派生值和它毫无关系。
    assertThat(captor.getValue().getFormulaExprCn()).isEqualTo("电解铜*2");
    assertThat(captor.getValue().getFormulaExprCn())
        .isNotEqualTo("攻击者尝试手填的漂移值");
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
