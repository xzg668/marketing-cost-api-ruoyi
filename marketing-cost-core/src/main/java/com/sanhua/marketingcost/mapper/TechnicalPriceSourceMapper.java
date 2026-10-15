package com.sanhua.marketingcost.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TechnicalPriceSourceMapper {
  record Source(String priceType, Long recordId) {}

  default List<String> publicKinds(String code, String month, String sourceMonth,
      String businessUnit, String org, java.time.LocalDate date) {
    return publicKindsBatch(List.of(code), month, sourceMonth, businessUnit, org, date).stream()
        .map(MaterialKind::kind).distinct().toList();
  }

  record MaterialKind(String materialCode, String kind) {}

  @Select("""
      <script>SELECT DISTINCT material_code AS materialCode,kind FROM (
        SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin AS material_code,'LINKED' kind FROM lp_price_linked_item WHERE material_code IN <foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach>
          AND source_kind='PUBLIC' AND deleted=0 AND rule_status IN ('LEGACY','PUBLISHED')
          AND pricing_month=#{sourceMonth} AND org_code=#{org} AND business_unit_type=#{businessUnit}
        UNION ALL
        SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin AS material_code,'RANGE' kind FROM lp_price_range_factor_rule WHERE material_code IN <foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach>
          AND rule_status IN ('LEGACY','PUBLISHED') AND pricing_month=#{sourceMonth}
          AND org_code=#{org} AND business_unit_type=#{businessUnit}
        UNION ALL
        SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin AS material_code,'RANGE' kind FROM lp_price_range_item WHERE material_code IN <foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach>
          AND (range_basis='QTY' OR range_basis IS NULL)
          AND (effective_from IS NULL OR effective_from&lt;=#{date})
          AND org_code=#{org} AND business_unit_type=#{businessUnit}
        UNION ALL
        SELECT CONVERT(material_code USING utf8mb4) COLLATE utf8mb4_bin AS material_code,CASE WHEN source_type IN ('SETTLE','SETTLE_FIXED') THEN 'SETTLE_FIXED' ELSE 'FIXED' END kind
          FROM lp_price_fixed_item WHERE material_code IN <foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach> AND source_kind='PUBLIC'
          AND org_code=#{org} AND business_unit_type=#{businessUnit} AND (
            (source_type IN ('PURCHASE','PURCHASE_FIXED') AND effective_from&lt;=#{date} AND effective_to>#{date})
            OR (source_type IN ('SETTLE','SETTLE_FIXED') AND pricing_month&lt;=#{month}
              AND (effective_from IS NULL OR effective_from&lt;=#{date})))
      ) p</script>
      """)
  List<MaterialKind> publicKindsBatch(@Param("codes") List<String> codes, @Param("month") String month,
      @Param("sourceMonth") String sourceMonth, @Param("businessUnit") String businessUnit,
      @Param("org") String org, @Param("date") java.time.LocalDate date);

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
        AND p.technical_publication_status='AVAILABLE' AND m.module_status IN ('SUBMITTED','APPROVED') AND v.version_status IN ('SUBMITTED','APPROVED')
      """)
  List<Source> supplemental(@Param("code") String code, @Param("businessUnit") String businessUnit);
}
