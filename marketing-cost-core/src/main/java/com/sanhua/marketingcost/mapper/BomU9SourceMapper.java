package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.entity.BomU9Source;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * lp_bom_u9_source 访问层。
 *
 * <p>T2 阶段只继承 BaseMapper；T3 补 {@link #listBatchSummaries} 供 {@code GET /batches?layer=U9_SOURCE} 使用。
 */
@Mapper
public interface BomU9SourceMapper extends BaseMapper<BomU9Source> {

  @Insert(
      "<script>"
          + "INSERT INTO lp_bom_u9_source ("
          + " import_batch_id, source_type, source_file_name, imported_at, imported_by,"
          + " parent_material_no, parent_material_name, production_unit, bom_purpose, bom_version,"
          + " bom_status, child_seq, child_type, child_material_no, child_material_name,"
          + " child_material_spec, cost_element_code, cost_element_name, consign_source,"
          + " u9_is_cost_flag, engineering_change_no, issue_unit, stock_unit, qty_per_parent,"
          + " process_seq, material_category_1, material_category_2, production_category,"
          + " shape_attr, production_dept, issue_method, is_virtual, parent_base_qty,"
          + " segment3, segment4, order_complete, effective_from, effective_to"
          + ") VALUES "
          + "<foreach collection='rows' item='e' separator=','>"
          + " (#{e.importBatchId}, #{e.sourceType}, #{e.sourceFileName}, #{e.importedAt}, #{e.importedBy},"
          + "  #{e.parentMaterialNo}, #{e.parentMaterialName}, #{e.productionUnit}, #{e.bomPurpose}, #{e.bomVersion},"
          + "  #{e.bomStatus}, #{e.childSeq}, #{e.childType}, #{e.childMaterialNo}, #{e.childMaterialName},"
          + "  #{e.childMaterialSpec}, #{e.costElementCode}, #{e.costElementName}, #{e.consignSource},"
          + "  #{e.u9IsCostFlag}, #{e.engineeringChangeNo}, #{e.issueUnit}, #{e.stockUnit}, #{e.qtyPerParent},"
          + "  #{e.processSeq}, #{e.materialCategory1}, #{e.materialCategory2}, #{e.productionCategory},"
          + "  #{e.shapeAttr}, #{e.productionDept}, #{e.issueMethod}, #{e.isVirtual}, #{e.parentBaseQty},"
          + "  #{e.segment3}, #{e.segment4}, #{e.orderComplete}, #{e.effectiveFrom}, #{e.effectiveTo})"
          + "</foreach>"
          + " ON DUPLICATE KEY UPDATE"
          + " import_batch_id = VALUES(import_batch_id),"
          + " source_type = VALUES(source_type),"
          + " source_file_name = VALUES(source_file_name),"
          + " imported_at = VALUES(imported_at),"
          + " imported_by = VALUES(imported_by),"
          + " parent_material_name = VALUES(parent_material_name),"
          + " production_unit = VALUES(production_unit),"
          + " bom_status = VALUES(bom_status),"
          + " child_type = VALUES(child_type),"
          + " child_material_name = VALUES(child_material_name),"
          + " child_material_spec = VALUES(child_material_spec),"
          + " cost_element_code = VALUES(cost_element_code),"
          + " cost_element_name = VALUES(cost_element_name),"
          + " consign_source = VALUES(consign_source),"
          + " u9_is_cost_flag = VALUES(u9_is_cost_flag),"
          + " engineering_change_no = VALUES(engineering_change_no),"
          + " issue_unit = VALUES(issue_unit),"
          + " stock_unit = VALUES(stock_unit),"
          + " qty_per_parent = VALUES(qty_per_parent),"
          + " process_seq = VALUES(process_seq),"
          + " material_category_1 = VALUES(material_category_1),"
          + " material_category_2 = VALUES(material_category_2),"
          + " production_category = VALUES(production_category),"
          + " shape_attr = VALUES(shape_attr),"
          + " production_dept = VALUES(production_dept),"
          + " issue_method = VALUES(issue_method),"
          + " is_virtual = VALUES(is_virtual),"
          + " parent_base_qty = VALUES(parent_base_qty),"
          + " segment3 = VALUES(segment3),"
          + " segment4 = VALUES(segment4),"
          + " order_complete = VALUES(order_complete)"
          + "</script>")
  int batchUpsert(@Param("rows") List<BomU9Source> rows);

  /**
   * 按 import_batch_id 聚合查询导入批次摘要，按时间倒序分页。
   *
   * <p>为什么不走 BaseMapper 的 selectPage：MP 的 selectPage 基于实体一行一记录，这里要的是
   * 聚合后的摘要行（批次维度），直接写 @Select 最简单。
   *
   * @param offset 跳过多少条（(page-1)*size）
   * @param limit 取多少条
   */
  @Select(
      "SELECT import_batch_id AS batchId,"
          + "       MAX(source_type) AS sourceType,"
          + "       MAX(source_file_name) AS sourceFileName,"
          + "       COUNT(*) AS rowCount,"
          + "       MAX(imported_at) AS importedAt,"
          + "       MAX(imported_by) AS importedBy"
          + "  FROM lp_bom_u9_source"
          + " GROUP BY import_batch_id"
          + " ORDER BY MAX(imported_at) DESC"
          + " LIMIT #{offset}, #{limit}")
  List<BomBatchSummary> listBatchSummaries(@Param("offset") int offset, @Param("limit") int limit);

  /**
   * 查询某批次里出现过的所有 bom_purpose。
   *
   * <p>用于 "import-and-build" 合成端点：导入后按 purpose 循环触发 build ALL，
   * 避免财务需要知道系统里有哪些 purpose 然后逐个点。
   *
   * @param importBatchId 本次导入批次
   * @return purpose 列表（过滤空值，去重）
   */
  @Select(
      "SELECT DISTINCT bom_purpose"
          + "  FROM lp_bom_u9_source"
          + " WHERE import_batch_id = #{importBatchId}"
          + "   AND bom_purpose IS NOT NULL"
          + "   AND bom_purpose <> ''")
  List<String> findDistinctPurposes(@Param("importBatchId") String importBatchId);
}
