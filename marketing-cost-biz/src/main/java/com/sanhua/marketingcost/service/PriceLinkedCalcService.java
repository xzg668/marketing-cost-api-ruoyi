package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;

/**
 * 联动价格计算服务 —— 分页查询、按 oaNo 刷新、按条目查 trace。
 */
public interface PriceLinkedCalcService {

  /** 分页查询计算结果。 */
  Page<PriceLinkedCalcRow> page(String oaNo, String itemCode, String shapeAttr,
      int page, int pageSize);

  /** 按 oaNo 批量重算并写回 trace_json。返回新增/更新行数。 */
  int refresh(String oaNo);

  /**
   * 按 {@code lp_price_linked_item.id} 当场跑 preview 产出 trace。
   *
   * <p>历史实现按 {@code lp_price_linked_calc_item.selectById} 查 {@code trace_json}，
   * 但 calc_item 的 PK 与 linked_item 的 PK 完全独立，前端点的又是 linked_item.id，
   * 造成必 miss 的 404。改成走 {@code PriceLinkedFormulaPreviewService.preview} 当场计算：
   * 口径与列表页"系统结果"一致，且不依赖 OA 单是否跑过。
   *
   * <p>{@code id} 不存在 → 返回 null（Controller 映射 404）；
   * preview 内部任何失败（公式语法错误 / 求值异常）都落成 {@code traceJson} 里的 {@code error} 字段。
   */
  PriceLinkedCalcTraceResponse getTrace(Long id);

  /**
   * 按 {@code lp_price_linked_calc_item.id} 读取刷新时落库的 trace。
   *
   * <p>这是 OA 结果页"查看 trace"入口 —— 展示的就是该 OA 单最近一次 refresh
   * 代入 OA 金属锁价 + BOM 行参数实际跑出的结果，口径完全对得上 {@code partUnitPrice}。
   * 与 {@link #getTrace(Long)}（preview 模板口径，不带 OA 锁价）互补，不可混用。
   *
   * <p>{@code calcId} 不存在 → 返回 null（Controller 转 404）；
   * trace_json 未落库（legacy-only 模式或从未 refresh）→ 返回 {@code traceJson=null} 的对象，
   * 前端展示"本次刷新未记录 trace，请重新刷新"。
   */
  PriceLinkedCalcTraceResponse getCalcTrace(Long calcId);
}
