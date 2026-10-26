package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.PriceRangeItem;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyQuantityRangeSourceMapper {
  // Preserve the existing QTY resolver's effective-from/historical selection semantics.
  @Select("""
    <script>SELECT * FROM lp_price_range_item WHERE business_unit_type=#{unit} AND org_code=#{org}
      AND (range_basis='QTY' OR range_basis IS NULL)
      AND (price_incl_tax IS NOT NULL OR price_excl_tax IS NOT NULL)
      AND (effective_from IS NULL OR effective_from&lt;=#{date}) AND material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      ORDER BY effective_from DESC,created_at DESC,id DESC</script>
    """)
  List<PriceRangeItem> prices(@Param("unit") String unit,@Param("org") String org,@Param("date") LocalDate date,@Param("codes") Collection<String> codes);
}
