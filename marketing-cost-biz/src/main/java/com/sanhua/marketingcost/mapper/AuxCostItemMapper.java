package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.AuxCostItemDto;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AuxCostItemMapper {
  @Select(
      """
          <script>
          SELECT
            m1.material_code AS materialCode,
            m1.ref_material_code AS refMaterialCode,
            m1.aux_subject_code AS auxSubjectCode,
            m1.aux_subject_name AS auxSubjectName,
            m2.float_rate AS floatRate,
            m1.unit_price AS unitPrice,
            NULL AS amountCalcMode,
            NULL AS displayOrder,
            m1.source AS source
          FROM lp_aux_subject m1
          LEFT JOIN lp_aux_rate_item m2
            ON m1.aux_subject_code = m2.material_code
          WHERE m1.material_code IN
            <foreach collection="materialCodes" item="code" open="(" separator="," close=")">
              #{code}
            </foreach>
            AND (m1.aux_subject_name IS NULL OR TRIM(m1.aux_subject_name) &lt;&gt; '包装辅料')
          ORDER BY m1.aux_subject_code ASC
          </script>
          """)
  List<AuxCostItemDto> selectByMaterialCodes(@Param("materialCodes") Collection<String> materialCodes);

  @Select(
      """
          <script>
          SELECT
            e.parent_code AS materialCode,
            NULL AS refMaterialCode,
            e.subject_code AS auxSubjectCode,
            e.subject_name AS auxSubjectName,
            NULL AS floatRate,
            e.amount_yuan AS unitPrice,
            'DIRECT' AS amountCalcMode,
            NULL AS displayOrder,
            'CMS_EFFECTIVE' AS source
          FROM cms_cost_source_effective e
          WHERE e.cost_year = #{costYear}
            AND e.source_type = 'AUX_SUBJECT'
            AND e.business_unit_type = #{businessUnitType}
            AND (e.subject_name IS NULL OR TRIM(e.subject_name) &lt;&gt; '包装辅料')
            AND e.parent_code IN
              <foreach collection="materialCodes" item="code" open="(" separator="," close=")">
                #{code}
              </foreach>
          ORDER BY e.subject_code ASC
          </script>
          """)
  List<AuxCostItemDto> selectEffectiveAuxCostItems(
      @Param("costYear") int costYear,
      @Param("materialCodes") Collection<String> materialCodes,
      @Param("businessUnitType") String businessUnitType);
}
