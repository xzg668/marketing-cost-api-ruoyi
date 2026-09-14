package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuoteBomSupplementDetailMapper extends BaseMapper<QuoteBomSupplementDetail> {

  /** 只允许替换电子图库 DRAFT 版本的活动混合树。 */
  @Delete("""
      DELETE detail
      FROM lp_quote_bom_supplement_detail detail
      INNER JOIN lp_quote_bom_supplement_version version
        ON version.id = detail.supplement_version_id
      WHERE detail.supplement_version_id = #{supplementVersionId}
        AND version.version_status = 'DRAFT'
        AND version.bom_source = 'ELECTRONIC_DRAWING_EXCEL'
      """)
  int deleteElectronicDrawingHybridDraft(@Param("supplementVersionId") Long supplementVersionId);

  @Insert({
      "<script>",
      "INSERT INTO lp_quote_bom_supplement_detail (",
      "supplement_version_id,preparation_id,oa_no,oa_form_item_id,quote_product_code,",
      "supplement_scope,line_no,level,parent_code,material_code,material_name,material_spec,",
      "material_model,drawing_no,shape_attr,main_category_code,source_category,cost_element_code,",
      "bom_purpose,bom_version,qty_per_parent,qty_per_top,parent_base_qty,unit,path,sort_seq,",
      "source_raw_hierarchy_id,source_u9_bom_id,node_source_type,source_electronic_node_id,",
      "mapping_status,manual_flag,remark,created_at,updated_at",
      ") VALUES",
      "<foreach collection='details' item='row' separator=','>",
      "(#{row.supplementVersionId},#{row.preparationId},#{row.oaNo},#{row.oaFormItemId},",
      "#{row.quoteProductCode},#{row.supplementScope},#{row.lineNo},#{row.level},",
      "#{row.parentCode},#{row.materialCode},#{row.materialName},#{row.materialSpec},",
      "#{row.materialModel},#{row.drawingNo},#{row.shapeAttr},#{row.mainCategoryCode},",
      "#{row.sourceCategory},#{row.costElementCode},#{row.bomPurpose},#{row.bomVersion},",
      "#{row.qtyPerParent},#{row.qtyPerTop},#{row.parentBaseQty},#{row.unit},#{row.path},",
      "#{row.sortSeq},#{row.sourceRawHierarchyId},#{row.sourceU9BomId},#{row.nodeSourceType},",
      "#{row.sourceElectronicNodeId},#{row.mappingStatus},#{row.manualFlag},#{row.remark},",
      "#{row.createdAt},#{row.updatedAt})",
      "</foreach>",
      "</script>"
  })
  int insertElectronicDrawingHybridBatch(
      @Param("details") List<QuoteBomSupplementDetail> details);
}
