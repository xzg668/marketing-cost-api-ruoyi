package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MonthlyRepriceResultMapper extends BaseMapper<MonthlyRepriceResult> {

  @Select("""
      SELECT COUNT(*)
        FROM lp_monthly_reprice_result
       WHERE reprice_no = #{repriceNo}
      """)
  long countByRepriceNo(@Param("repriceNo") String repriceNo);

}
