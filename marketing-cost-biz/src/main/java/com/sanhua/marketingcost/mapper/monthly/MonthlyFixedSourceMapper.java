package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Bounded read-only inputs for the existing supplier selection policy. */
@Mapper
public interface MonthlyFixedSourceMapper {
  @Select("""
    <script>SELECT * FROM lp_price_fixed_item WHERE source_kind='PUBLIC'
      AND source_type IN ('PURCHASE','PURCHASE_FIXED') AND fixed_price IS NOT NULL
      AND business_unit_type=#{unit} AND org_code=#{org}
      AND material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      ORDER BY effective_from DESC,effective_to DESC,imported_at DESC,created_at DESC,id DESC</script>
    """)
  List<PriceFixedItem> prices(@Param("unit") String unit,@Param("org") String org,@Param("codes") Collection<String> codes);
}
