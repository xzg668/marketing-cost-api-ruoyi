package com.sanhua.marketingcost.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import java.math.BigDecimal;

/**
 * 影响因素 10 Excel 行模型 —— T17 新增。
 *
 * <p>使用 EasyExcel 的 {@link ExcelProperty} 按 <b>表头名</b> 绑定（不按列索引），
 * 这样财务每月上传的 Excel 即使列顺序微调（或前后多一列）也不会错位。
 * 列名严格对齐现实沿用的"影响因素10" 模板 7 列：
 * <ol>
 *   <li>序号 → {@link #seq}</li>
 *   <li>价表影响因素名称 → {@link #factorName}</li>
 *   <li>简称 → {@link #shortName}</li>
 *   <li>取价来源 → {@link #priceSource}</li>
 *   <li>价格 → {@link #price}</li>
 *   <li>单位 → {@link #unit}</li>
 *   <li>价格-原价 → {@link #priceOriginal}</li>
 * </ol>
 *
 * <p>注意：Excel 里的 <b>首行</b>（"2026年2月参照基准"）是标题单元，<b>第二行</b>才是表头。
 * 读取时会通过 {@code EasyExcel.read(...).headRowNumber(2)} 跳过标题，
 * 见 {@code FinanceBasePriceImportServiceImpl} 的 EasyExcel 调用配置。
 */
public class InfluenceFactorImportRow {

  /** 序号，对应 {@code lp_finance_base_price.seq}。空则由 Service 分配。 */
  @ExcelProperty("序号")
  private Integer seq;

  /** 价表影响因素名称（全名），例如 "上月宝新SUS304/2B δ0.8 不锈钢冷卷含税出厂价格"。 */
  @ExcelProperty("价表影响因素名称")
  private String factorName;

  /** 简称，例如 "SUS304/2Bδ0.8"，是 {@code FinancePriceResolver} 按名查价的主键。 */
  @ExcelProperty("简称")
  private String shortName;

  /** 取价来源，例如 "出厂价" / "上海有色网"。 */
  @ExcelProperty("取价来源")
  private String priceSource;

  /** 本期价格（元），必填；解析失败或 ≤0 视为错误行。 */
  @ExcelProperty("价格")
  private BigDecimal price;

  /** 单位，例如 "公斤" / "吨"；空时 Service 默认填 "公斤"。 */
  @ExcelProperty("单位")
  private String unit;

  /** 上期原价（"价格-原价" 列），供前端做涨跌提示；可为空。 */
  @ExcelProperty("价格-原价")
  private BigDecimal priceOriginal;

  public Integer getSeq() {
    return seq;
  }

  public void setSeq(Integer seq) {
    this.seq = seq;
  }

  public String getFactorName() {
    return factorName;
  }

  public void setFactorName(String factorName) {
    this.factorName = factorName;
  }

  public String getShortName() {
    return shortName;
  }

  public void setShortName(String shortName) {
    this.shortName = shortName;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public void setPriceSource(String priceSource) {
    this.priceSource = priceSource;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public BigDecimal getPriceOriginal() {
    return priceOriginal;
  }

  public void setPriceOriginal(BigDecimal priceOriginal) {
    this.priceOriginal = priceOriginal;
  }
}
