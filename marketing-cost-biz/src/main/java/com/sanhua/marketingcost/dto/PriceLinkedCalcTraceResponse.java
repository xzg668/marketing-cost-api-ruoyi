package com.sanhua.marketingcost.dto;

/**
 * 联动计算 trace 响应。
 *
 * <p>接口 {@code GET /api/v1/price-linked/items/{id}/trace} 的返回类型。
 * {@link #traceJson} 为后端把 {@code PriceLinkedFormulaPreviewService.preview} 结果
 * 拍平后的 JSON 字符串，schema：
 * <pre>
 *   {rawExpr, normalizedExpr, variables: {code -&gt; value}, result, error?}
 * </pre>
 * 前端 {@code parseTraceJson + buildTraceTimeline} 解析后渲染 normalize / resolve / evaluate 时间轴。
 *
 * <p>序列化失败时 {@link #traceJson} 可能为 null —— 前端按"暂无追踪"提示。
 */
public class PriceLinkedCalcTraceResponse {

  /** 回显请求里的 linked_item.id，便于前端对照。 */
  private Long id;

  /** trace 原文（JSON 字符串）；可能为 null。 */
  private String traceJson;

  public PriceLinkedCalcTraceResponse() {
  }

  public PriceLinkedCalcTraceResponse(Long id, String traceJson) {
    this.id = id;
    this.traceJson = traceJson;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getTraceJson() {
    return traceJson;
  }

  public void setTraceJson(String traceJson) {
    this.traceJson = traceJson;
  }
}
