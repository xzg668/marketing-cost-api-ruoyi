package com.sanhua.marketingcost.service.rule;

/**
 * BOM 规则匹配的节点上下文。
 *
 * <p>拍平阶段从 {@code lp_bom_raw_hierarchy} 每行派生一个 context，交给
 * {@link RuleMatcher} 做条件判定。故意不直接传整个 entity：
 * <ul>
 *   <li>让 RuleMatcher 不依赖 raw_hierarchy 具体结构，便于未来给手工 BOM / 电子图库复用</li>
 *   <li>字段限定在规则关心的几项，读起来更明确</li>
 * </ul>
 *
 * <p>字段来源映射（从 BomRawHierarchy 推断）：
 * <ul>
 *   <li>{@link #materialCode} ← material_code</li>
 *   <li>{@link #materialName} ← material_name</li>
 *   <li>{@link #materialCategory} ← material_category_1（T8 起与 materialCategory1 同源，保持老规则兼容）</li>
 *   <li>{@link #materialCategory1} ← material_category_1（T8 新增）</li>
 *   <li>{@link #materialCategory2} ← material_category_2（T8 新增）</li>
 *   <li>{@link #shapeAttr} ← shape_attr</li>
 *   <li>{@link #productionCategory} ← source_category（生产分类：制造件/采购件/半成品）</li>
 *   <li>{@link #costElementCode} ← cost_element_code（T8 复合条件 nodeConditions 常用）</li>
 *   <li>{@link #businessUnitType} ← business_unit_type</li>
 * </ul>
 */
public record BomNodeContext(
    String materialCode,
    String materialName,
    String materialCategory,
    String shapeAttr,
    String productionCategory,
    String businessUnitType,
    /* T8 新增 */
    String costElementCode,
    String materialCategory1,
    String materialCategory2) {

  /** 便捷工厂：老字段全给 + T8 字段置空（供单测 / 老代码路径快速构造） */
  public static BomNodeContext legacy(
      String materialCode,
      String materialName,
      String materialCategory,
      String shapeAttr,
      String productionCategory,
      String businessUnitType) {
    return new BomNodeContext(
        materialCode, materialName, materialCategory, shapeAttr, productionCategory,
        businessUnitType, null, null, null);
  }
}
