package com.sanhua.marketingcost.mapper;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仅发现可能命中的成品，实际可用版本及父子关系由正式 BOM 读取服务核实。 */
@Mapper
public interface TechnicalDataPackageReferenceMapper {
  @Select("""
      SELECT DISTINCT h.top_product_code
        FROM lp_bom_raw_hierarchy h
        LEFT JOIN lp_material_master_raw m
          ON m.material_code=h.material_code AND m.organization_code=#{materialOrg} AND m.active_flag=1
       WHERE h.source_type='U9' AND h.price_org_code=#{priceOrg}
         AND (h.business_unit_type=#{businessUnit} OR h.business_unit_type IS NULL)
         AND h.effective_from<=#{effectiveDate}
         AND (h.effective_to IS NULL OR h.effective_to>=#{effectiveDate})
         AND (LOCATE(#{keyword},h.material_code)>0 OR LOCATE(#{keyword},h.material_name)>0
           OR LOCATE(#{keyword},h.material_spec)>0 OR LOCATE(#{keyword},m.material_model)>0
           OR LOCATE(#{keyword},m.drawing_no)>0 OR LOCATE(#{keyword},m.material_spec)>0)
       ORDER BY h.top_product_code
       LIMIT 101
      """)
  List<String> searchProducts(@Param("keyword") String keyword, @Param("priceOrg") String priceOrg,
      @Param("materialOrg") String materialOrg, @Param("businessUnit") String businessUnit,
      @Param("effectiveDate") LocalDate effectiveDate);
}
