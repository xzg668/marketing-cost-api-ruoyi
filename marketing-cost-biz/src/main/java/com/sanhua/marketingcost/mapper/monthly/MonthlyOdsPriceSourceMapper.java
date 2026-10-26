package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.monthly.OdsMaterialPriceRow;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Each table's latest snapshot only; business selection belongs to the source adapter. */
@Mapper
public interface MonthlyOdsPriceSourceMapper {
  @Select("""
      <script>
      SELECT r.*,r.supply_code AS supplier_code,r.supply_name AS supplier_name
      FROM ods_sy_v_mm_material_order_price_cg05_df r
      WHERE r.dt=(SELECT MAX(dt) FROM ods_sy_v_mm_material_order_price_cg05_df)
        AND r.company_name=#{company}
      <choose>
        <when test="codes != null and codes.size() > 0">
          AND r.material_code IN
          <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
        </when>
        <otherwise>AND 1=0</otherwise>
      </choose>
      ORDER BY r.material_code,r.supply_code
      </script>
      """)
  List<OdsMaterialPriceRow> orders(@Param("company") String company,
      @Param("codes") Collection<String> codes);

  @Select("""
      <script>
      SELECT r.*,r.supply_code AS supplier_code,r.supply_name AS supplier_name
      FROM ods_sy_v_mm_material_jt_price_cg05_df r
      WHERE r.dt=(SELECT MAX(dt) FROM ods_sy_v_mm_material_jt_price_cg05_df)
        AND r.company_name=#{company}
      <choose>
        <when test="codes != null and codes.size() > 0">
          AND r.material_code IN
          <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
        </when>
        <otherwise>AND 1=0</otherwise>
      </choose>
      ORDER BY r.material_code,r.supply_code
      </script>
      """)
  List<OdsMaterialPriceRow> tiers(@Param("company") String company,
      @Param("codes") Collection<String> codes);
}
