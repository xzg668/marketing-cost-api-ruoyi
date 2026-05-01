package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomCostingRow;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * lp_bom_costing_row 访问层。
 *
 * <p>T5 阶段补 {@link #batchUpsert}：UK = {@code (oa_no, top_product_code, material_code,
 * as_of_date, raw_version_effective_from)}；命中时刷新可变字段（结算标记 / 用量 / 业务属性），
 * 不同 asOfDate / raw_version_effective_from 的历史快照天然并存。
 *
 * <p>选择 ON DUPLICATE KEY UPDATE 而不是"DELETE + INSERT"的原因：
 * <ul>
 *   <li>遵循 project memory "BOM 版本 append-only 多版本并存" —— 不丢历史 asOfDate 快照</li>
 *   <li>和 T4 的 raw_hierarchy batchUpsert 统一语义</li>
 *   <li>幂等重跑自然：相同 UK 命中就刷新，业务层不需要先 DELETE 再 INSERT</li>
 * </ul>
 */
@Mapper
public interface BomCostingRowMapper extends BaseMapper<BomCostingRow> {

  /**
   * 批量 upsert 拍平后的结算行。
   *
   * <p>不能 UPDATE 的身份字段（UK 组成部分）：oa_no, top_product_code, material_code,
   * as_of_date, raw_version_effective_from。其他字段随 ON DUPLICATE KEY UPDATE 刷新。
   *
   * @param rows 一批结算行；上层自控每批 ≤ 500 行
   * @return MySQL 协议受影响行数（INSERT=1，UPDATE 命中=2）
   */
  @Insert(
      "<script>"
          + "INSERT INTO lp_bom_costing_row ("
          + "  oa_no, top_product_code, parent_code, material_code, level, path,"
          + "  qty_per_parent, qty_per_top, is_costing_row, subtree_cost_required,"
          + "  raw_hierarchy_node_id, matched_drill_rule_id,"
          + "  material_name, material_spec, shape_attr, source_category, cost_element_code,"
          + "  bom_purpose, bom_version, u9_is_cost_flag, effective_from, effective_to,"
          + "  build_batch_id, built_at, as_of_date, raw_version_effective_from, business_unit_type"
          + ") VALUES "
          + "<foreach collection='rows' item='e' separator=','>"
          + "  (#{e.oaNo}, #{e.topProductCode}, #{e.parentCode}, #{e.materialCode}, #{e.level}, #{e.path},"
          + "   #{e.qtyPerParent}, #{e.qtyPerTop}, #{e.isCostingRow}, #{e.subtreeCostRequired},"
          + "   #{e.rawHierarchyNodeId}, #{e.matchedDrillRuleId},"
          + "   #{e.materialName}, #{e.materialSpec}, #{e.shapeAttr}, #{e.sourceCategory}, #{e.costElementCode},"
          + "   #{e.bomPurpose}, #{e.bomVersion}, #{e.u9IsCostFlag}, #{e.effectiveFrom}, #{e.effectiveTo},"
          + "   #{e.buildBatchId}, #{e.builtAt}, #{e.asOfDate}, #{e.rawVersionEffectiveFrom}, #{e.businessUnitType})"
          + "</foreach>"
          + " ON DUPLICATE KEY UPDATE"
          + "  parent_code = VALUES(parent_code),"
          + "  level = VALUES(level),"
          + "  path = VALUES(path),"
          + "  qty_per_parent = VALUES(qty_per_parent),"
          + "  qty_per_top = VALUES(qty_per_top),"
          + "  is_costing_row = VALUES(is_costing_row),"
          + "  subtree_cost_required = VALUES(subtree_cost_required),"
          + "  raw_hierarchy_node_id = VALUES(raw_hierarchy_node_id),"
          + "  matched_drill_rule_id = VALUES(matched_drill_rule_id),"
          + "  material_name = VALUES(material_name),"
          + "  material_spec = VALUES(material_spec),"
          + "  shape_attr = VALUES(shape_attr),"
          + "  source_category = VALUES(source_category),"
          + "  cost_element_code = VALUES(cost_element_code),"
          + "  bom_purpose = VALUES(bom_purpose),"
          + "  bom_version = VALUES(bom_version),"
          + "  u9_is_cost_flag = VALUES(u9_is_cost_flag),"
          + "  effective_from = VALUES(effective_from),"
          + "  effective_to = VALUES(effective_to),"
          + "  build_batch_id = VALUES(build_batch_id),"
          + "  built_at = VALUES(built_at)"
          + "</script>")
  int batchUpsert(@Param("rows") List<BomCostingRow> rows);

  /** T15：拿 OA 涉及的去重料号清单（主档同步入口用），不限 asOfDate / version。 */
  @Select("SELECT DISTINCT material_code FROM lp_bom_costing_row WHERE oa_no=#{oaNo}")
  List<String> selectDistinctMaterialCodesByOaNo(@Param("oaNo") String oaNo);
}
