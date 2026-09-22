package com.sanhua.marketingcost.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TechnicalPriceSourceMapper {
  record Source(String priceType, Long recordId) {}

  @Select("""
      SELECT DISTINCT kind FROM (
        SELECT 'LINKED' kind FROM lp_price_linked_item WHERE material_code=#{code} AND source_kind='PUBLIC' AND deleted=0
          AND pricing_month<=#{month} AND (business_unit_type=#{businessUnit} OR business_unit_type IS NULL OR business_unit_type='')
        UNION ALL
        SELECT 'FIXED' kind FROM lp_price_fixed_item WHERE material_code=#{code} AND source_kind='PUBLIC'
          AND source_type IN ('PURCHASE','PURCHASE_FIXED') AND (business_unit_type=#{businessUnit} OR business_unit_type IS NULL OR business_unit_type='')
      ) p ORDER BY CASE kind WHEN 'LINKED' THEN 0 ELSE 1 END
      """)
  List<String> publicKinds(@Param("code") String code, @Param("month") String month, @Param("businessUnit") String businessUnit);

  @Select("""
      SELECT p.price_type AS priceType,p.id AS recordId FROM (
        SELECT 'FIXED' price_type,id,material_code,business_unit_type,technical_version_id,technical_publication_status
          FROM lp_price_fixed_item WHERE source_kind='TECH_SUPPLEMENTAL'
        UNION ALL
        SELECT 'LINKED' price_type,id,material_code,business_unit_type,technical_version_id,technical_publication_status
          FROM lp_price_linked_item WHERE source_kind='TECH_SUPPLEMENTAL' AND deleted=0
      ) p JOIN lp_quote_tech_price_claim c ON c.material_code=p.material_code
      JOIN lp_quote_tech_module m ON m.id=c.owner_module_id AND m.current_version_id=p.technical_version_id
      JOIN lp_quote_tech_data_version v ON v.id=p.technical_version_id
      WHERE p.material_code=#{code} AND p.business_unit_type=#{businessUnit}
        AND p.technical_publication_status='AVAILABLE' AND m.module_status='APPROVED' AND v.version_status='APPROVED'
      """)
  List<Source> supplemental(@Param("code") String code, @Param("businessUnit") String businessUnit);
}
