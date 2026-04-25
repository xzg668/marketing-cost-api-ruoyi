package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import java.math.BigDecimal;
import java.util.Map;

/**
 * 联动公式预览服务 —— T14 引入。
 *
 * <p>把 {@link com.sanhua.marketingcost.formula.normalize.FormulaNormalizer} +
 * {@link com.sanhua.marketingcost.formula.registry.FactorVariableRegistry} +
 * {@link com.sanhua.marketingcost.formula.registry.ExpressionEvaluator} 串成一次"只算不写"的预览，
 * 供前端编辑器实时反馈公式含义与计算结果。
 *
 * <p>与 {@link PriceLinkedCalcService#refresh(String)} 区别：
 * <ul>
 *   <li>不动数据库：既不写 calc_item 也不写 trace_json</li>
 *   <li>单条执行：不读 BomManage；只按请求里的 materialCode 最多拉一条 linkedItem 做 PART_CONTEXT</li>
 *   <li>错误宽容：语法/求值错误记录到 response.error 而非抛异常</li>
 * </ul>
 */
public interface PriceLinkedFormulaPreviewService {

  /** 根据原公式 + 部品上下文，返回规范化结果 + 变量赋值 + 计算值 + trace */
  PriceLinkedFormulaPreviewResponse preview(PriceLinkedFormulaPreviewRequest request);

  /**
   * OA 刷新场景的统一入口 —— 和 {@link #preview} 完全同一套 normalize → resolve → evaluate → tax
   * 流水线，**唯一差异是变量来源**：
   * <ul>
   *   <li>已有 {@code linkedItem} 实例（不用再按 materialCode 查库），规避重复 DB 读</li>
   *   <li>传入 {@code variableOverrides}（例如 OA 单锁价 Cu/Zn），优先级高于 finance 基价回落</li>
   * </ul>
   *
   * <p>这个重构消除了 OA refresh 和 preview 两套独立实现 —— 以前两处分别做 normalize /
   * tax 转换，改一处忘一处就偏差（2026-04 的 1.13 倍 bug 就是这么来的）。
   *
   * @param linkedItem      已预加载的部品（提供 formulaExpr / taxIncluded / pricingMonth）
   * @param variableOverrides 变量 code → 强制值，例如 {@code {"Cu": 90, "Zn": 21.684}}（null 视同空）
   * @return 与 preview() 同结构的响应，含已做 tax 转换的 {@code result} 和完整 trace
   */
  PriceLinkedFormulaPreviewResponse previewForRefresh(
      PriceLinkedItem linkedItem, Map<String, BigDecimal> variableOverrides);
}
