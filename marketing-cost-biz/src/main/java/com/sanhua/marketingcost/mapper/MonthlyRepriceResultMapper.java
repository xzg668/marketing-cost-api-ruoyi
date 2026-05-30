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

  @Select("""
      SELECT COUNT(*)
        FROM (
          SELECT calc_object_key
            FROM lp_monthly_reprice_result
           WHERE reprice_no = #{repriceNo}
           GROUP BY calc_object_key
          HAVING COUNT(*) > 1
        ) duplicate_result
      """)
  long countDuplicateCalcObjectKeys(@Param("repriceNo") String repriceNo);

  @Select("""
      SELECT COUNT(*)
        FROM lp_monthly_reprice_result r
       WHERE r.reprice_no = #{repriceNo}
         AND NOT EXISTS (
           SELECT 1
             FROM lp_monthly_reprice_part_item p
            WHERE p.reprice_no = r.reprice_no
              AND p.calc_object_key = r.calc_object_key
         )
      """)
  long countResultsMissingPartItems(@Param("repriceNo") String repriceNo);

  @Select("""
      SELECT COUNT(*)
        FROM lp_monthly_reprice_result r
       WHERE r.reprice_no = #{repriceNo}
         AND NOT EXISTS (
           SELECT 1
             FROM lp_monthly_reprice_cost_item c
            WHERE c.reprice_no = r.reprice_no
              AND c.calc_object_key = r.calc_object_key
         )
      """)
  long countResultsMissingCostItems(@Param("repriceNo") String repriceNo);

  @Select("""
      SELECT COUNT(*)
        FROM lp_monthly_reprice_result r
       WHERE r.reprice_no = #{repriceNo}
         AND NOT EXISTS (
           SELECT 1
             FROM lp_cost_run_task t
            WHERE t.scene = 'MONTHLY_REPRICE'
              AND t.source_no = r.reprice_no
              AND t.calc_object_key = r.calc_object_key
              AND t.status = 'SUCCESS'
         )
      """)
  long countResultsWithoutSuccessfulTask(@Param("repriceNo") String repriceNo);
}
