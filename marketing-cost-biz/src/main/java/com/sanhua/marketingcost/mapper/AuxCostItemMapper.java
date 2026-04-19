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
            m1.aux_subject_code AS auxSubjectCode,
            m1.aux_subject_name AS auxSubjectName,
            m2.float_rate AS floatRate,
            m1.unit_price AS unitPrice
          FROM lp_aux_subject m1
          LEFT JOIN lp_aux_rate_item m2
            ON m1.aux_subject_code = m2.material_code
          WHERE m1.material_code IN
            <foreach collection="materialCodes" item="code" open="(" separator="," close=")">
              #{code}
            </foreach>
          </script>
          """)
  List<AuxCostItemDto> selectByMaterialCodes(@Param("materialCodes") Collection<String> materialCodes);
}
