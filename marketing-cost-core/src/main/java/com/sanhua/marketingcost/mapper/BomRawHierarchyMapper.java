package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * lp_bom_raw_hierarchy 访问层。
 *
 * <p>T4 阶段补 {@link #batchUpsert}：<b>版本 append-only 语义的核心</b> —— 用
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}，UK 命中时刷新可变字段、<b>绝不 DELETE</b>。
 * 不同 effective_from 的历史版本天然在 UK 外并存。
 */
@Mapper
public interface BomRawHierarchyMapper extends BaseMapper<BomRawHierarchy> {

  /**
   * 批量 upsert 构建出的层级行。
   *
   * <p>UK = {@code (top_product_code, source_type, bom_purpose, effective_from, material_code, parent_code)}；
   * 命中时只刷新非身份字段（见 ON DUPLICATE KEY UPDATE 子句）。
   *
   * <p>为什么不走 MP 的 {@code saveBatch}：
   * <ul>
   *   <li>MP 原生 batch insert 不支持 ON DUPLICATE KEY UPDATE，只能先查后插</li>
   *   <li>我们要的是 "INSERT ON DUPLICATE" 原子语义，避免并发/幂等下的 race</li>
   *   <li>自定义 SQL 明确控制哪些字段被 UPDATE（有些字段是身份/历史语义，不能覆盖）</li>
   * </ul>
   *
   * @param rows 批次行；**上层自己控制每批 ≤ 500 行**，避免 SQL 超 max_allowed_packet
   * @return 受影响行数（INSERT=1 行，UPDATE 命中=2 行，per MySQL 协议）
   */
  @Insert(
      "<script>"
          + "INSERT INTO lp_bom_raw_hierarchy ("
          + "  top_product_code, parent_code, material_code, level, path, sort_seq,"
          + "  qty_per_parent, qty_per_top, material_name, material_spec, shape_attr,"
          + "  source_category, cost_element_code, material_category_1, material_category_2,"
          + "  bom_purpose, bom_version, bom_status,"
          + "  u9_is_cost_flag, is_leaf, effective_from, effective_to,"
          + "  source_type, source_import_batch_id, build_batch_id, built_at, business_unit_type"
          + ") VALUES "
          + "<foreach collection='rows' item='e' separator=','>"
          + "  (#{e.topProductCode}, #{e.parentCode}, #{e.materialCode}, #{e.level}, #{e.path}, #{e.sortSeq},"
          + "   #{e.qtyPerParent}, #{e.qtyPerTop}, #{e.materialName}, #{e.materialSpec}, #{e.shapeAttr},"
          + "   #{e.sourceCategory}, #{e.costElementCode}, #{e.materialCategory1}, #{e.materialCategory2},"
          + "   #{e.bomPurpose}, #{e.bomVersion}, #{e.bomStatus},"
          + "   #{e.u9IsCostFlag}, #{e.isLeaf}, #{e.effectiveFrom}, #{e.effectiveTo},"
          + "   #{e.sourceType}, #{e.sourceImportBatchId}, #{e.buildBatchId}, #{e.builtAt}, #{e.businessUnitType})"
          + "</foreach>"
          + " ON DUPLICATE KEY UPDATE"
          + "  qty_per_parent = VALUES(qty_per_parent),"
          + "  qty_per_top = VALUES(qty_per_top),"
          + "  path = VALUES(path),"
          + "  sort_seq = VALUES(sort_seq),"
          + "  effective_to = VALUES(effective_to),"
          + "  material_name = VALUES(material_name),"
          + "  material_spec = VALUES(material_spec),"
          + "  shape_attr = VALUES(shape_attr),"
          + "  source_category = VALUES(source_category),"
          + "  cost_element_code = VALUES(cost_element_code),"
          + "  material_category_1 = VALUES(material_category_1),"
          + "  material_category_2 = VALUES(material_category_2),"
          + "  bom_version = VALUES(bom_version),"
          + "  bom_status = VALUES(bom_status),"
          + "  u9_is_cost_flag = VALUES(u9_is_cost_flag),"
          + "  is_leaf = VALUES(is_leaf),"
          + "  source_import_batch_id = VALUES(source_import_batch_id),"
          + "  build_batch_id = VALUES(build_batch_id),"
          + "  built_at = VALUES(built_at)"
          + "</script>")
  int batchUpsert(@Param("rows") List<BomRawHierarchy> rows);
}
