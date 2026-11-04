package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MonthlyPriceImport;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyPriceImportMapper extends BaseMapper<MonthlyPriceImport> {
  @Select("SELECT * FROM lp_monthly_price_import WHERE id=#{id} FOR UPDATE")
  MonthlyPriceImport lock(@Param("id") long id);

  @Select("SELECT COUNT(*) FROM lp_monthly_price_import WHERE business_unit_type=#{unit}"
      + " AND pricing_month=#{month} AND status<>'COMPLETE'")
  long unfinished(@Param("unit") String unit, @Param("month") String month);

}
