package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.FormulaReformatResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderRegistry;
import com.sanhua.marketingcost.formula.registry.RowLocalPlaceholderTestSupport;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Plan B T7 —— FormulaReformatServiceImpl 幂等回洗单测（扩展：同时覆盖 cn 派生）。
 *
 * <p>扩展覆盖：
 * <ol>
 *   <li>脏行（中文 token / 英文未规范）→ normalize + 派生 cn → 两列都写</li>
 *   <li>expr 已规范但 cn 仍是脏 Excel 原文 / null → 只回写 cn，expr 不动</li>
 *   <li>两列都已一致 → 幂等不 update</li>
 *   <li>非法公式 → failed 列表，两列都不动</li>
 *   <li>空公式 → unchanged</li>
 *   <li>连续调用 → 第二次 rewrote=0 rewroteCn=0</li>
 * </ol>
 */
class FormulaReformatServiceImplTest {

  private PriceLinkedItemMapper itemMapper;
  private FormulaReformatServiceImpl service;

  @BeforeEach
  void setUp() {
    itemMapper = mock(PriceLinkedItemMapper.class);

    // 种子：电解铜 / 下料重量；Normalizer 和 Renderer 都需要 variable 定义
    PriceVariableMapper priceVariableMapper = mock(PriceVariableMapper.class);
    when(priceVariableMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
        variable("Cu", "电解铜", "[\"电解铜\",\"Cu\"]"),
        variable("blank_weight", "下料重量", "[\"下料重量\"]")));
    VariableAliasIndex aliasIndex = new VariableAliasIndex(priceVariableMapper);
    aliasIndex.init();
    RowLocalPlaceholderRegistry placeholderRegistry =
        RowLocalPlaceholderTestSupport.defaultRegistry();
    FormulaNormalizer normalizer = new FormulaNormalizer(aliasIndex, placeholderRegistry);
    FormulaDisplayRenderer renderer =
        new FormulaDisplayRenderer(priceVariableMapper, placeholderRegistry);

    service = new FormulaReformatServiceImpl(itemMapper, normalizer, renderer);
  }

  @Test
  @DisplayName("脏行（expr 中文 + cn null）→ 两列同时回写")
  void dirtyRowsRewrittenCleanRowsUntouched() {
    // dirty: expr 中文，cn null → expr 会被 normalize，cn 也会被派生
    PriceLinkedItem dirty = row(1L, "电解铜*2", null);
    // clean：expr 已规范 + cn 已派生 → 两列一致，不回写
    PriceLinkedItem clean = row(2L, "[Cu]*2", "电解铜*2");
    // empty：expr 空 → unchanged
    PriceLinkedItem empty = row(3L, "", null);
    when(itemMapper.selectList(isNull())).thenReturn(List.of(dirty, clean, empty));

    FormulaReformatResponse resp = service.reformatAll();

    assertThat(resp.getTotal()).isEqualTo(3);
    assertThat(resp.getRewrote()).isEqualTo(1); // dirty 的 expr 要变
    assertThat(resp.getRewroteCn()).isEqualTo(1); // dirty 的 cn 也要派生
    assertThat(resp.getUnchanged()).isEqualTo(2); // clean + empty
    assertThat(resp.getFailed()).isEmpty();

    ArgumentCaptor<PriceLinkedItem> captor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper, times(1)).updateById(captor.capture());
    PriceLinkedItem patch = captor.getValue();
    assertThat(patch.getId()).isEqualTo(1L);
    assertThat(patch.getFormulaExpr()).isEqualTo("[Cu]*2");
    assertThat(patch.getFormulaExprCn()).isEqualTo("电解铜*2");
  }

  @Test
  @DisplayName("expr 已规范但 cn 仍是脏 —— 只回写 cn")
  void onlyCnDirty_rewroteCnOnly() {
    // 历史脏数据典型：expr 已在上一轮清洗过，但 cn 还是 Excel 原文带 /1000
    PriceLinkedItem row = row(5L, "[Cu]*2", "电解铜*2/1000");
    when(itemMapper.selectList(isNull())).thenReturn(List.of(row));

    FormulaReformatResponse resp = service.reformatAll();

    assertThat(resp.getTotal()).isEqualTo(1);
    assertThat(resp.getRewrote()).isZero(); // expr 没变
    assertThat(resp.getRewroteCn()).isEqualTo(1); // cn 需要派生覆盖
    ArgumentCaptor<PriceLinkedItem> captor = ArgumentCaptor.forClass(PriceLinkedItem.class);
    verify(itemMapper, times(1)).updateById(captor.capture());
    PriceLinkedItem patch = captor.getValue();
    assertThat(patch.getFormulaExpr()).isNull(); // 没 set expr
    assertThat(patch.getFormulaExprCn()).isEqualTo("电解铜*2");
  }

  @Test
  @DisplayName("非法公式行 → 失败列表记录原文与 reason，不 update")
  void invalidRowRecordedAsFailed() {
    PriceLinkedItem good = row(10L, "电解铜*2", null);
    PriceLinkedItem bad = row(11L, "神秘金属*2", null); // Normalizer 抛异常
    when(itemMapper.selectList(isNull())).thenReturn(List.of(good, bad));

    FormulaReformatResponse resp = service.reformatAll();

    assertThat(resp.getTotal()).isEqualTo(2);
    assertThat(resp.getRewrote()).isEqualTo(1);
    assertThat(resp.getFailed()).hasSize(1);
    FormulaReformatResponse.FailedRow f = resp.getFailed().get(0);
    assertThat(f.getId()).isEqualTo(11L);
    assertThat(f.getFormulaExpr()).isEqualTo("神秘金属*2");
    assertThat(f.getReason()).contains("未识别的中文 token");

    verify(itemMapper, times(1)).updateById(any(PriceLinkedItem.class));
  }

  @Test
  @DisplayName("空表 → total=0 且不碰 update")
  void emptyTableNoUpdate() {
    when(itemMapper.selectList(isNull())).thenReturn(List.of());

    FormulaReformatResponse resp = service.reformatAll();

    assertThat(resp.getTotal()).isZero();
    assertThat(resp.getRewrote()).isZero();
    assertThat(resp.getRewroteCn()).isZero();
    assertThat(resp.getUnchanged()).isZero();
    assertThat(resp.getFailed()).isEmpty();
    verify(itemMapper, never()).updateById(any(PriceLinkedItem.class));
  }

  @Test
  @DisplayName("连续调用幂等：第二次 rewrote=0 rewroteCn=0")
  void idempotentOnSecondRun() {
    PriceLinkedItem dirty = row(1L, "下料重量*0.001+电解铜*2", null);
    when(itemMapper.selectList(isNull())).thenReturn(List.of(dirty));

    FormulaReformatResponse first = service.reformatAll();
    assertThat(first.getRewrote()).isEqualTo(1);
    assertThat(first.getRewroteCn()).isEqualTo(1);

    // 第二次：expr 和 cn 都已是派生后的一致值
    PriceLinkedItem cleaned =
        row(1L, "[blank_weight]*0.001+[Cu]*2", "下料重量*0.001+电解铜*2");
    when(itemMapper.selectList(isNull())).thenReturn(List.of(cleaned));
    FormulaReformatResponse second = service.reformatAll();

    assertThat(second.getRewrote()).isZero();
    assertThat(second.getRewroteCn()).isZero();
    assertThat(second.getUnchanged()).isEqualTo(1);
    assertThat(second.getFailed()).isEmpty();
    verify(itemMapper, times(1)).updateById(any(PriceLinkedItem.class));
  }

  private static PriceLinkedItem row(Long id, String formulaExpr, String formulaExprCn) {
    PriceLinkedItem it = new PriceLinkedItem();
    it.setId(id);
    it.setFormulaExpr(formulaExpr);
    it.setFormulaExprCn(formulaExprCn);
    return it;
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
