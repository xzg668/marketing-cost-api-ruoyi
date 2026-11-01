package com.sanhua.marketingcost.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** Thin indexed reads; a prepared price, unfinished reprice or draft cost cannot advance the month. */
@Mapper
public interface QuotePricingPeriodMapper {
  @Select("""
      SELECT pricing_month FROM lp_monthly_reprice_batch
      WHERE business_unit_type=#{unit} AND contract_version=1
        AND status='CONFIRMED' AND confirmed_at IS NOT NULL
      ORDER BY pricing_month DESC LIMIT 1
      """)
  String confirmedMonth(String unit);

  @Select("""
      SELECT pricing_month FROM lp_quote_cost_run_version
      WHERE business_unit_type=#{unit} AND (reprice_no IS NULL OR reprice_no='')
        AND status IN ('SUCCESS','CONFIRMED','HISTORY','STALE')
        AND trial_finished_at IS NOT NULL AND total_cost IS NOT NULL
      ORDER BY pricing_month DESC, id DESC LIMIT 1
      """)
  String existingQuotationMonth(String unit);
}
