package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ElectronicDrawingSourceNodeMapper
    extends BaseMapper<ElectronicDrawingSourceNode> {

  @Insert({
      "<script>",
      "INSERT INTO lp_electronic_drawing_source_node (",
      "supplement_version_id,source_row_no,source_sequence,parent_source_sequence,",
      "drawing_code,source_name,qty,material,importance_class,hsf_risk_class,",
      "reference_weight,source_remark,match_status,resolved_material_code,resolved_by,resolved_at",
      ") VALUES",
      "<foreach collection='sourceNodes' item='node' separator=','>",
      "(#{node.supplementVersionId},#{node.sourceRowNo},#{node.sourceSequence},",
      "#{node.parentSourceSequence},#{node.drawingCode},#{node.sourceName},#{node.qty},",
      "#{node.material},#{node.importanceClass},#{node.hsfRiskClass},#{node.referenceWeight},",
      "#{node.sourceRemark},#{node.matchStatus},#{node.resolvedMaterialCode},",
      "#{node.resolvedBy},#{node.resolvedAt})",
      "</foreach>",
      "</script>"
  })
  int insertBatch(@Param("sourceNodes") List<ElectronicDrawingSourceNode> sourceNodes);

  /** 只更新解析字段；来源字段没有出现在 SQL 中，并且仅草稿版本允许更新。 */
  @Update("""
      UPDATE lp_electronic_drawing_source_node source_node
      INNER JOIN lp_quote_bom_supplement_version version
        ON version.id = source_node.supplement_version_id
      SET source_node.match_status = #{targetStatus},
          source_node.resolved_material_code = #{resolvedMaterialCode},
          source_node.resolved_by = #{resolvedBy},
          source_node.resolved_at = #{resolvedAt},
          source_node.updated_at = #{updatedAt}
      WHERE source_node.id = #{sourceNodeId}
        AND source_node.match_status = #{expectedStatus}
        AND version.version_status = 'DRAFT'
      """)
  int updateResolutionIfDraft(
      @Param("sourceNodeId") Long sourceNodeId,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus,
      @Param("resolvedMaterialCode") String resolvedMaterialCode,
      @Param("resolvedBy") String resolvedBy,
      @Param("resolvedAt") LocalDateTime resolvedAt,
      @Param("updatedAt") LocalDateTime updatedAt);
}
