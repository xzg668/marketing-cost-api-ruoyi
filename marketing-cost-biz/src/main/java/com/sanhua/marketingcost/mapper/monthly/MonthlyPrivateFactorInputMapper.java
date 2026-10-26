package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Captures the existing private-factor SQL predicate and order, including database collation. */
@Mapper
public interface MonthlyPrivateFactorInputMapper {
  @Select("""
    <script>SELECT p.* FROM lp_factor_monthly_price p
      JOIN lp_factor_identity f ON f.id=p.factor_identity_id
      WHERE f.business_unit_type=#{unit} AND p.scheme_code='LEGACY' AND p.status='ACTIVE'
        AND p.price_month&lt;=#{month} AND p.factor_identity_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
      ORDER BY p.factor_identity_id,p.price_month DESC,p.id DESC</script>
    """)
  List<FactorMonthlyPrice> prices(@Param("unit") String unit,@Param("month") String month,
      @Param("ids") Collection<Long> ids);

  default List<FactorMonthlyPrice> read(String unit,String month,Collection<Long> identityIds) {
    var ids=new ArrayList<>(new TreeSet<>(identityIds));var result=new ArrayList<FactorMonthlyPrice>();
    for(int start=0;start<ids.size();start+=256)
      result.addAll(prices(unit,month,ids.subList(start,Math.min(start+256,ids.size()))));
    return List.copyOf(result);
  }
}
