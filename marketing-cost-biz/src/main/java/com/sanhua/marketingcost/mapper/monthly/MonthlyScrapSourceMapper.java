package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.ScrapPrice;
import java.util.*;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyScrapSourceMapper {
  // Future rows identify managed scrap too; absence of an applicable rule must block lower sources.
  @Select("""
    <script>SELECT * FROM lp_scrap_price WHERE business_unit_type=#{unit} AND material_code IN
      <foreach collection="codes" item="code" open="(" separator="," close=")">#{code}</foreach>
      ORDER BY material_code,price_month,id</script>
    """)
  List<ScrapPrice> rules(@Param("unit") String unit,@Param("codes") Collection<String> codes);
}
