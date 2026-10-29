package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Shared bounded supplier policy inputs; no review decisions are published as public price facts. */
@Mapper
public interface MonthlySupplierInputMapper {
  @Select(com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper.CURRENT_RELATIONS_SQL)
  List<MonthlySupplierRatioInput> ratios(@Param("unit") String unit,@Param("date") LocalDate date,@Param("codes") Collection<String> codes);
  @Select("""
    <script>SELECT d.* FROM lp_supplier_price_decision d WHERE d.scope_key IN
      <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
      AND NOT EXISTS(SELECT 1 FROM lp_supplier_price_decision n WHERE n.scope_key=d.scope_key AND n.id&gt;d.id)
      ORDER BY d.scope_key</script>
    """)
  List<SupplierPriceDecision> decisions(@Param("keys") Collection<String> keys);
}
