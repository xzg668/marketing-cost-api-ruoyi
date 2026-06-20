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
 * <p>报价产品核算入口按 {@code oa_no + oa_form_item_id + top_product_code + period_month}
 * 隔离快照；旧正式拍平入口没有产品行上下文时 {@code oa_form_item_id} 可为空。
 */
@Mapper
public interface BomCostingRowMapper extends BaseMapper<BomCostingRow> {

  /**
   * 批量 upsert 拍平后的结算行。
   *
   * @param rows 一批结算行；上层自控每批 ≤ 500 行
   * @return MySQL 协议受影响行数（INSERT=1，UPDATE 命中=2）
   */
  @Insert(
      "<script>"
          + "INSERT INTO lp_bom_costing_row ("
          + "  oa_no, oa_form_item_id, top_product_code, parent_code, material_code, level, path,"
          + "  qty_per_parent, qty_per_top, is_costing_row, subtree_cost_required,"
          + "  raw_hierarchy_node_id, matched_settlement_rule_id, settlement_row_type,"
          + "  material_name, material_spec, unit, material_attribute, shape_attr, source_category, cost_element_code,"
          + "  bom_purpose, bom_version, u9_is_cost_flag, effective_from, effective_to,"
          + "  build_batch_id, built_at, period_month, as_of_date, raw_version_effective_from,"
          + "  manual_modified, modified_by, modified_at, business_unit_type"
          + ") VALUES "
          + "<foreach collection='rows' item='e' separator=','>"
          + "  (#{e.oaNo}, #{e.oaFormItemId}, #{e.topProductCode}, #{e.parentCode}, #{e.materialCode}, #{e.level}, #{e.path},"
          + "   #{e.qtyPerParent}, #{e.qtyPerTop}, #{e.isCostingRow}, #{e.subtreeCostRequired},"
          + "   #{e.rawHierarchyNodeId}, #{e.matchedSettlementRuleId}, #{e.settlementRowType},"
          + "   #{e.materialName}, #{e.materialSpec}, #{e.unit}, #{e.materialAttribute}, #{e.shapeAttr}, #{e.sourceCategory}, #{e.costElementCode},"
          + "   #{e.bomPurpose}, #{e.bomVersion}, #{e.u9IsCostFlag}, #{e.effectiveFrom}, #{e.effectiveTo},"
          + "   #{e.buildBatchId}, #{e.builtAt}, #{e.periodMonth}, #{e.asOfDate}, #{e.rawVersionEffectiveFrom},"
          + "   COALESCE(#{e.manualModified}, 0), #{e.modifiedBy}, #{e.modifiedAt}, #{e.businessUnitType})"
          + "</foreach>"
          + " ON DUPLICATE KEY UPDATE"
          + "  oa_form_item_id = VALUES(oa_form_item_id),"
          + "  parent_code = VALUES(parent_code),"
          + "  level = VALUES(level),"
          + "  path = VALUES(path),"
          + "  qty_per_parent = VALUES(qty_per_parent),"
          + "  qty_per_top = VALUES(qty_per_top),"
          + "  is_costing_row = VALUES(is_costing_row),"
          + "  subtree_cost_required = VALUES(subtree_cost_required),"
          + "  raw_hierarchy_node_id = VALUES(raw_hierarchy_node_id),"
          + "  matched_settlement_rule_id = VALUES(matched_settlement_rule_id),"
          + "  settlement_row_type = VALUES(settlement_row_type),"
          + "  material_name = VALUES(material_name),"
          + "  material_spec = VALUES(material_spec),"
          + "  unit = VALUES(unit),"
          + "  material_attribute = VALUES(material_attribute),"
          + "  shape_attr = VALUES(shape_attr),"
          + "  source_category = VALUES(source_category),"
          + "  cost_element_code = VALUES(cost_element_code),"
          + "  bom_purpose = VALUES(bom_purpose),"
          + "  bom_version = VALUES(bom_version),"
          + "  u9_is_cost_flag = VALUES(u9_is_cost_flag),"
          + "  effective_from = VALUES(effective_from),"
          + "  effective_to = VALUES(effective_to),"
          + "  build_batch_id = VALUES(build_batch_id),"
          + "  built_at = VALUES(built_at),"
          + "  period_month = VALUES(period_month),"
          + "  manual_modified = VALUES(manual_modified),"
          + "  modified_by = VALUES(modified_by),"
          + "  modified_at = VALUES(modified_at)"
          + "</script>")
  int batchUpsert(@Param("rows") List<BomCostingRow> rows);

  /** T15：拿 OA 涉及的去重料号清单（主档同步入口用），不限 asOfDate / version。 */
  @Select("SELECT DISTINCT material_code FROM lp_bom_costing_row WHERE oa_no=#{oaNo}")
  List<String> selectDistinctMaterialCodesByOaNo(@Param("oaNo") String oaNo);

  /**
   * BOM 可用性检查只需要快照头信息，显式列出字段，避免旧库缺少新结算规则字段时
   * MyBatis-Plus 默认 SELECT 全实体列导致检查失败。
   */
  @Select(
      "SELECT bom_purpose, bom_version, effective_from, effective_to, build_batch_id, period_month "
          + "FROM lp_bom_costing_row "
          + "WHERE top_product_code=#{topProductCode} "
          + "AND (#{periodMonth} IS NULL OR period_month=#{periodMonth}) "
          + "ORDER BY built_at DESC, id DESC "
          + "LIMIT 1")
  BomCostingRow selectAvailabilitySnapshot(
      @Param("topProductCode") String topProductCode, @Param("periodMonth") String periodMonth);

  @Select(
      "SELECT period_month "
          + "FROM lp_bom_costing_row "
          + "WHERE oa_no=#{oaNo} "
          + "AND oa_form_item_id=#{oaFormItemId} "
          + "AND top_product_code=#{topProductCode} "
          + "ORDER BY built_at DESC, id DESC "
          + "LIMIT 1")
  String selectLatestQuoteCostingPeriod(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("topProductCode") String topProductCode);

  @Select(
      "SELECT * "
          + "FROM lp_bom_costing_row "
          + "WHERE oa_no=#{oaNo} "
          + "AND oa_form_item_id=#{oaFormItemId} "
          + "AND top_product_code=#{topProductCode} "
          + "AND period_month=#{periodMonth} "
          + "ORDER BY path ASC, id ASC")
  List<BomCostingRow> selectQuoteCostingSnapshot(
      @Param("oaNo") String oaNo,
      @Param("oaFormItemId") Long oaFormItemId,
      @Param("topProductCode") String topProductCode,
      @Param("periodMonth") String periodMonth);
}
