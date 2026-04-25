package com.sanhua.marketingcost.dto;

import java.util.List;

/**
 * 行局部占位符对外视图（V36）—— 供前端构造中英文映射。
 *
 * <p>对应 {@code lp_row_local_placeholder} 表的一条记录，但只暴露前端需要的字段
 * （不含创建时间、状态等内部字段）。
 *
 * <p>前端使用方式：
 * <ol>
 *   <li>启动时调 {@code GET /api/v1/price-linked/row-local-placeholders} 拿全量</li>
 *   <li>构造 {@code code → displayName} 映射（供 toChineseExpr 渲染）</li>
 *   <li>构造 {@code tokenNames[i] → code} 反向映射（供 toCodeExpr 把用户输入的中文转成占位符）</li>
 * </ol>
 */
public class RowLocalPlaceholderDto {

  /** 占位符 code，如 {@code __material} */
  private String code;

  /** 中文显示名，如 "材料含税价格"；渲染时 code 被替换成它 */
  private String displayName;

  /** 可接受的中文 token 列表，如 ["材料含税价格","材料价格"]；用户输入命中任一即转成 code */
  private List<String> tokenNames;

  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }

  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }

  public List<String> getTokenNames() { return tokenNames; }
  public void setTokenNames(List<String> tokenNames) { this.tokenNames = tokenNames; }
}
