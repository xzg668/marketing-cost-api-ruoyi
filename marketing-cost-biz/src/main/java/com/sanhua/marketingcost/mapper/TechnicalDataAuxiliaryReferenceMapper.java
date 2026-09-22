package com.sanhua.marketingcost.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TechnicalDataAuxiliaryReferenceMapper {

  @Select("""
      SELECT DISTINCT e.parent_code
        FROM cms_cost_source_effective e
        LEFT JOIN lp_material_master_raw m
          ON m.material_code=e.parent_code AND m.organization_code=#{materialOrg} AND m.active_flag=1
       WHERE e.source_type='AUX_SUBJECT' AND e.cost_year=#{costYear}
         AND e.business_unit_type=#{businessUnit} AND e.period<=#{month}
         AND (e.subject_name IS NULL OR TRIM(e.subject_name)<>'包装辅料')
         AND (LOCATE(#{keyword},e.parent_code)>0 OR LOCATE(#{keyword},m.material_name)>0
           OR LOCATE(#{keyword},m.material_model)>0 OR LOCATE(#{keyword},m.material_spec)>0)
       ORDER BY e.parent_code LIMIT 101
      """)
  List<String> searchProducts(@Param("keyword") String keyword, @Param("materialOrg") String materialOrg,
      @Param("businessUnit") String businessUnit, @Param("costYear") int costYear, @Param("month") String month);

}
