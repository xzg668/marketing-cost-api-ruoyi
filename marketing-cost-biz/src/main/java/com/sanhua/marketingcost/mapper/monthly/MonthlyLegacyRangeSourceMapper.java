package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyLegacyRangeSourceMapper {
  @Select("""
    <script>SELECT DISTINCT material_code FROM lp_price_range_factor_rule WHERE business_unit_type=#{unit}
      AND (org_code=#{org} OR (org_code IS NULL AND pricing_month IS NULL AND rule_status='LEGACY'))
      AND rule_status IN ('LEGACY','PUBLISHED') AND (pricing_month IS NOT NULL OR current_flag=1)
      AND material_code IN <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach></script>
    """)
  List<String> factorMaterials(@Param("unit") String unit,@Param("org") String org,@Param("codes") Collection<String> codes);

  @Select("""
    <script>SELECT * FROM lp_price_range_factor_rule WHERE business_unit_type=#{unit}
      AND (org_code=#{org} OR (org_code IS NULL AND pricing_month IS NULL AND rule_status='LEGACY'))
      AND rule_status IN ('LEGACY','PUBLISHED') AND material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      ORDER BY version_no DESC,id DESC</script>
    """)
  List<PriceRangeFactorRule> rules(@Param("unit") String unit,@Param("org") String org,@Param("codes") Collection<String> codes);
  @Select("""
    <script>SELECT * FROM lp_price_range_item WHERE range_basis='FACTOR' AND factor_rule_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY effective_from DESC,created_at DESC,id DESC</script>
    """)
  List<PriceRangeItem> bands(@Param("ids") Collection<Long> ids);
  @Select("""
    SELECT * FROM lp_price_variable WHERE status='active'
      AND (business_unit_type=#{unit} OR business_unit_type IS NULL) ORDER BY id
    """)
  List<PriceVariable> variables(@Param("unit") String unit);
  @Select("""
    <script>SELECT * FROM lp_factor_identity WHERE business_unit_type=#{unit} AND status='ACTIVE' AND id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach> ORDER BY id</script>
    """)
  List<FactorIdentity> identities(@Param("unit") String unit,@Param("ids") Collection<Long> ids);
}
