package com.sanhua.marketingcost.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 金标夹具加载器 —— 读取 Excel 提取脚本生成的 JSON 夹具。
 *
 * 夹具来源：tools/excel_extractor.py 解密《产品成本计算表（3.29- 提供）.xls》后导出。
 * 同步路径：fixtures/ → src/test/resources/fixtures/（每次跑 extractor 后手动 cp -r 同步）。
 *
 * <p>组织约定：
 * <pre>
 *   golden/SHF-AA-79/
 *     header.json            报价单表头（OA/客户/金属基准价）
 *     bom_items.json         29 行部品明细（含 Excel 实测单价/金额/形态/价格来源）
 *     aux_costs.json         酸洗/辅料/焊料/包装/工装/大修/水电
 *     labor_rate.json        人工/损失率/制造费率/三项费率/产品属性系数
 *     expected_summary.json  材料费/制造成本/调整后制造成本/不含税总成本
 *   source/
 *     price_fixed.json / price_linked.json / price_settle.json …  价源快照
 * </pre>
 *
 * 设计原则：纯 POJO + Jackson；不依赖 Spring 上下文，任何测试可直接 new 一个用。
 */
public final class GoldenSampleFixture {

  /** 样本产品物料编码（金标基线产品 1079900000536）。 */
  public static final String PRODUCT_CODE = "1079900000536";

  /** 样本产品图号（SHF-AA-79 四通阀）。 */
  public static final String PRODUCT_MODEL = "SHF-AA-79";

  /** 不含税总成本金标，验收硬指标 152.503 ± 0.01。 */
  public static final BigDecimal EXPECTED_TOTAL_COST_EXCL_TAX = new BigDecimal("152.503");

  /** classpath 下的金标根目录。 */
  private static final String GOLDEN_ROOT = "/fixtures/golden/" + PRODUCT_MODEL + "/";

  /** classpath 下的源数据根目录。 */
  private static final String SOURCE_ROOT = "/fixtures/source/";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private GoldenSampleFixture() {}

  // ============================ 金标读取 ============================

  /** 见机表3 表头（OA单号/客户/产品/金属基准价）。 */
  public static Map<String, Object> header() {
    return readMap(GOLDEN_ROOT + "header.json");
  }

  /**
   * 部品明细 29 行；每行包含：
   * partName / drawingNo / unitPrice / qty / amount / material / formAttr / priceSource。
   *
   * <p>注意 Excel 中 formAttr 的实际取值是中文文案（采购/联动/自制/原材料联动/家用结算价），
   * 与系统枚举（采购件/制造件/委外加工件）需要做映射，详见 GoldenSampleRegressionTest 注释。
   */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> bomItems() {
    return (List<Map<String, Object>>) readObject(GOLDEN_ROOT + "bom_items.json", List.class);
  }

  /** 辅料/焊料/包装/工装/大修/水电（产品级附加项）。 */
  public static Map<String, Object> auxCosts() {
    return readMap(GOLDEN_ROOT + "aux_costs.json");
  }

  /** 人工成本、损失率、制造费率、产品属性系数、三项费率与金额。 */
  public static Map<String, Object> laborRate() {
    return readMap(GOLDEN_ROOT + "labor_rate.json");
  }

  /** 汇总链金标：materialTotal / manufactureCost / adjustedManufactureCost / totalCostExclTax。 */
  public static Map<String, Object> expectedSummary() {
    return readMap(GOLDEN_ROOT + "expected_summary.json");
  }

  // ============================ 源数据读取 ============================

  /** 固定采购价 5（lp_price_fixed_item 的金标输入）。 */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> priceFixed() {
    return (List<Map<String, Object>>) readObject(SOURCE_ROOT + "price_fixed.json", List.class);
  }

  /** 联动价-部品 6（含联动公式原文 + Excel 实测单价）。 */
  @SuppressWarnings("unchecked")
  public static List<Map<String, Object>> priceLinked() {
    return (List<Map<String, Object>>) readObject(SOURCE_ROOT + "price_linked.json", List.class);
  }

  /** 家用结算价 9。 */
  public static Map<String, Object> priceSettle() {
    return readMap(SOURCE_ROOT + "price_settle.json");
  }

  /** 自制件 4（净重/下料重/加工费/原材料代码等）。 */
  public static Map<String, Object> makePart() {
    return readMap(SOURCE_ROOT + "make_part.json");
  }

  /** 原材料拆解 8（焊料/毛细管按父件展开）。 */
  public static Map<String, Object> rawBreakdown() {
    return readMap(SOURCE_ROOT + "raw_breakdown.json");
  }

  /**
   * 原材料联动 + 固定 17 行夹具（Excel 的"原材料(联动+固定-7）"页签导出）。
   * <p>返回顶层 Map；{@code data} 字段是 17 行（row=0 为表头，row=1..16 为数据行）。
   */
  public static Map<String, Object> rawMaterialLinkedFixed() {
    return readMap(SOURCE_ROOT + "raw_material_linked_fixed.json");
  }

  /** 金属原材料价格（影响因素 10 的基准价）。 */
  public static Map<String, Object> financeBasePrice() {
    return readMap(SOURCE_ROOT + "finance_base_price.json");
  }

  /** 产品属性对照表 14（含产品属性系数）。 */
  public static Map<String, Object> productProperty() {
    return readMap(SOURCE_ROOT + "product_property.json");
  }

  /** 质量损失率 / 制造费用率 / 三项费用率 三张对照表。 */
  public static Map<String, Object> rates() {
    return readMap(SOURCE_ROOT + "rates.json");
  }

  // ============================ 工具方法 ============================

  /** 把 JSON 数值字段读成 BigDecimal，避免 Double 精度抖动；缺失返回 null。 */
  public static BigDecimal big(Map<String, Object> map, String key) {
    Object v = map == null ? null : map.get(key);
    if (v == null) {
      return null;
    }
    return new BigDecimal(v.toString());
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> readMap(String classpathResource) {
    return (Map<String, Object>) readObject(classpathResource, Map.class);
  }

  private static <T> T readObject(String classpathResource, Class<T> type) {
    try (InputStream in = GoldenSampleFixture.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException(
            "金标夹具未找到：" + classpathResource
                + "（请先运行 tools/excel_extractor.py 并 cp -r 到 test/resources/fixtures/）");
      }
      return MAPPER.readValue(in, type);
    } catch (IOException e) {
      throw new IllegalStateException("加载夹具失败：" + classpathResource, e);
    }
  }
}
