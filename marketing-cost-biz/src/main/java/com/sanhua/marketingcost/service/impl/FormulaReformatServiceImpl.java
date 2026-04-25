package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FormulaReformatResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.formula.normalize.FormulaDisplayRenderer;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.FormulaReformatService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 历史脏数据回洗实现 —— Plan B T7（扩展）。
 *
 * <p>一次扫 {@code lp_price_linked_item} 全表，每行做两件事：
 * <ol>
 *   <li><b>规范化 {@code formula_expr}</b>：跑 {@link FormulaNormalizer#normalize}
 *       把中文 token / 任意大小写统一成 {@code [variable_code]} 形态；</li>
 *   <li><b>派生 {@code formula_expr_cn}</b>：用 {@link FormulaDisplayRenderer#renderCn}
 *       从规范化后的公式反向生成中文展示列，彻底消除"公式里带 /1000 的历史中文原文"
 *       和规范化公式不一致的脏状态。</li>
 * </ol>
 *
 * <p>只要 {@code formula_expr} 或 {@code formula_expr_cn} 任一需要变化就触发 update；
 * 两者都已一致则计入 unchanged。Normalizer 异常的行记入 failed、不写回。
 *
 * <p>幂等：再次调用已清洗的 DB，应返回 rewrote=0 / rewroteCn=0 / unchanged=total。
 */
@Service
public class FormulaReformatServiceImpl implements FormulaReformatService {

  private static final Logger log = LoggerFactory.getLogger(FormulaReformatServiceImpl.class);

  private final PriceLinkedItemMapper itemMapper;
  private final FormulaNormalizer formulaNormalizer;
  private final FormulaDisplayRenderer formulaDisplayRenderer;

  public FormulaReformatServiceImpl(
      PriceLinkedItemMapper itemMapper,
      FormulaNormalizer formulaNormalizer,
      FormulaDisplayRenderer formulaDisplayRenderer) {
    this.itemMapper = itemMapper;
    this.formulaNormalizer = formulaNormalizer;
    this.formulaDisplayRenderer = formulaDisplayRenderer;
  }

  @Override
  public FormulaReformatResponse reformatAll() {
    FormulaReformatResponse resp = new FormulaReformatResponse();
    // selectList(null) 在 MP 语义下 = 无条件全表；本表是业务主键表，规模可控，不分页
    List<PriceLinkedItem> all = itemMapper.selectList(null);
    resp.setTotal(all.size());

    for (PriceLinkedItem item : all) {
      String raw = item.getFormulaExpr();
      // 空公式：expr / cn 都无需处理
      if (!StringUtils.hasText(raw)) {
        resp.setUnchanged(resp.getUnchanged() + 1);
        continue;
      }

      String normalized;
      try {
        normalized = formulaNormalizer.normalize(raw);
      } catch (RuntimeException ex) {
        // 未注册别名 / 括号不平衡 / 其他语法错 —— 记失败、不写回
        log.warn("formula-reformat 行 id={} 规范化失败：{}", item.getId(), ex.getMessage());
        resp.getFailed().add(new FormulaReformatResponse.FailedRow(
            item.getId(), raw, ex.getMessage()));
        continue;
      }

      // 用规范化后的 expr 派生 cn —— Renderer 查 aliases_json[0] / variable_name
      String derivedCn = formulaDisplayRenderer.renderCn(normalized);
      boolean exprChanged = !Objects.equals(raw, normalized);
      boolean cnChanged = !Objects.equals(item.getFormulaExprCn(), derivedCn);

      if (!exprChanged && !cnChanged) {
        // 两列都已一致，幂等：不触发 update
        resp.setUnchanged(resp.getUnchanged() + 1);
        continue;
      }

      PriceLinkedItem patch = new PriceLinkedItem();
      patch.setId(item.getId());
      if (exprChanged) {
        patch.setFormulaExpr(normalized);
      }
      if (cnChanged) {
        patch.setFormulaExprCn(derivedCn);
      }
      itemMapper.updateById(patch);
      if (exprChanged) {
        resp.setRewrote(resp.getRewrote() + 1);
      }
      if (cnChanged) {
        resp.setRewroteCn(resp.getRewroteCn() + 1);
      }
    }

    log.info(
        "formula-reformat 完成：total={} rewroteExpr={} rewroteCn={} unchanged={} failed={}",
        resp.getTotal(), resp.getRewrote(), resp.getRewroteCn(),
        resp.getUnchanged(), resp.getFailed().size());
    return resp;
  }
}
