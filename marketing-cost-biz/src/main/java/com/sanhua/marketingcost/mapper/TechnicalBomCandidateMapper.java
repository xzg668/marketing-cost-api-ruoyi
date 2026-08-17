package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.collaboration.TechnicalBomCandidateRow;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** U9 相似 BOM 只读查询；报价组织和料品组织必须由产品任务决定。 */
@Mapper
public interface TechnicalBomCandidateMapper {

  @Select("""
      <script>
      SELECT h.top_product_code AS productCode,
             COALESCE(NULLIF(TRIM(m.material_name), ''), MAX(h.material_name)) AS productName,
             COALESCE(NULLIF(TRIM(m.material_spec), ''), MAX(h.material_spec)) AS productSpec,
             NULLIF(TRIM(m.material_model), '') AS productModel,
             h.bom_purpose AS bomPurpose,
             h.bom_version AS bomVersion,
             COUNT(DISTINCT all_node.id) AS bomNodeCount,
             (
               CASE WHEN #{model} IS NOT NULL AND TRIM(m.material_model) = #{model} THEN 100 ELSE 0 END
               + CASE WHEN #{spec} IS NOT NULL AND TRIM(COALESCE(m.material_spec, MAX(h.material_spec))) = #{spec} THEN 90 ELSE 0 END
               + CASE WHEN #{model} IS NOT NULL AND m.material_model LIKE CONCAT('%', #{model}, '%') THEN 30 ELSE 0 END
               + CASE WHEN #{spec} IS NOT NULL AND COALESCE(m.material_spec, MAX(h.material_spec)) LIKE CONCAT('%', #{spec}, '%') THEN 25 ELSE 0 END
               + CASE WHEN #{keyword} IS NOT NULL AND h.top_product_code LIKE CONCAT('%', #{keyword}, '%') THEN 15 ELSE 0 END
               + CASE WHEN #{keyword} IS NOT NULL AND m.material_name LIKE CONCAT('%', #{keyword}, '%') THEN 10 ELSE 0 END
             ) AS matchScore
      FROM lp_bom_raw_hierarchy h
      LEFT JOIN lp_material_master_raw m
        ON m.material_code = h.top_product_code
       AND m.organization_code = #{materialOrganizationCode}
       AND m.active_flag = 1
      JOIN lp_bom_raw_hierarchy all_node
        ON all_node.price_org_code = h.price_org_code
       AND all_node.top_product_code = h.top_product_code
       AND all_node.source_type = h.source_type
       AND (all_node.bom_purpose &lt;=&gt; h.bom_purpose)
       AND all_node.effective_from &lt;= #{effectiveDate}
       AND (all_node.effective_to IS NULL OR all_node.effective_to &gt;= #{effectiveDate})
      WHERE h.price_org_code = #{priceOrgCode}
        AND h.source_type = 'U9'
        AND h.level = 0
        AND h.effective_from &lt;= #{effectiveDate}
        AND (h.effective_to IS NULL OR h.effective_to &gt;= #{effectiveDate})
        AND EXISTS (
          SELECT 1 FROM lp_bom_raw_hierarchy child
          WHERE child.price_org_code = h.price_org_code
            AND child.top_product_code = h.top_product_code
            AND child.source_type = h.source_type
            AND (child.bom_purpose &lt;=&gt; h.bom_purpose)
            AND child.level &gt; 0
            AND child.effective_from &lt;= #{effectiveDate}
            AND (child.effective_to IS NULL OR child.effective_to &gt;= #{effectiveDate})
        )
      <if test='exactProductCode != null'>
        AND h.top_product_code = #{exactProductCode}
      </if>
      <if test='bomPurpose != null'>
        AND h.bom_purpose = #{bomPurpose}
      </if>
      <if test='exactProductCode == null and (keyword != null or spec != null or model != null)'>
        AND (
          <if test='keyword != null'>
            h.top_product_code LIKE CONCAT('%', #{keyword}, '%')
            OR m.material_name LIKE CONCAT('%', #{keyword}, '%')
            OR m.material_spec LIKE CONCAT('%', #{keyword}, '%')
            OR m.material_model LIKE CONCAT('%', #{keyword}, '%')
          </if>
          <if test='keyword != null and (spec != null or model != null)'> OR </if>
          <if test='spec != null'>
            COALESCE(m.material_spec, h.material_spec) LIKE CONCAT('%', #{spec}, '%')
          </if>
          <if test='spec != null and model != null'> OR </if>
          <if test='model != null'>
            m.material_model LIKE CONCAT('%', #{model}, '%')
          </if>
        )
      </if>
      GROUP BY h.top_product_code, m.material_name, m.material_spec, m.material_model,
               h.bom_purpose, h.bom_version
      ORDER BY matchScore DESC, h.top_product_code ASC, h.bom_version DESC
      LIMIT #{limit}
      </script>
      """)
  List<TechnicalBomCandidateRow> selectCandidates(
      @Param("priceOrgCode") String priceOrgCode,
      @Param("materialOrganizationCode") String materialOrganizationCode,
      @Param("effectiveDate") LocalDate effectiveDate,
      @Param("keyword") String keyword,
      @Param("spec") String spec,
      @Param("model") String model,
      @Param("exactProductCode") String exactProductCode,
      @Param("bomPurpose") String bomPurpose,
      @Param("limit") int limit);
}
