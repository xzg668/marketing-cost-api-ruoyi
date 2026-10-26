package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Shared bounded supplier policy inputs; no review decisions are published as public price facts. */
@Mapper
public interface MonthlySupplierInputMapper {
  @Select("""
    <script>SELECT r.*, requested.material_code AS requested_material_code
      FROM (
        <foreach collection="codes" item="code" separator=" UNION ALL ">
          SELECT CONVERT(#{code} USING utf8mb4) COLLATE utf8mb4_unicode_ci AS material_code
        </foreach>
      ) requested JOIN lp_supplier_supply_ratio r ON r.material_code=requested.material_code
      WHERE r.business_unit_type=#{unit} AND r.is_active=1 AND r.deleted=0
        AND (r.effective_from IS NULL OR r.effective_from&lt;=#{date})
        AND (r.effective_to IS NULL OR r.effective_to&gt;=#{date})
      ORDER BY requested.material_code,r.supplier_code,r.id</script>
    """)
  List<MonthlySupplierRatioInput> ratios(@Param("unit") String unit,@Param("date") LocalDate date,@Param("codes") Collection<String> codes);
  @Select("""
    <script>SELECT d.* FROM lp_supplier_price_decision d WHERE d.scope_key IN
      <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
      AND NOT EXISTS(SELECT 1 FROM lp_supplier_price_decision n WHERE n.scope_key=d.scope_key AND n.id&gt;d.id)
      ORDER BY d.scope_key</script>
    """)
  List<SupplierPriceDecision> decisions(@Param("keys") Collection<String> keys);
}
