package com.sanhua.marketingcost.mapper.monthly;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import org.apache.ibatis.annotations.*;

/** Serializes final publication of one existing cost version; no new table or pricing route. */
@Mapper
public interface MonthlyCostVersionMapper {
  @Select("SELECT * FROM lp_quote_cost_run_version WHERE id=#{id} AND business_unit_type=#{unit} FOR UPDATE")
  QuoteCostRunVersion lock(@Param("unit") String unit, @Param("id") long id);
}
