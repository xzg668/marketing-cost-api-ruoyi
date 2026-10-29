package com.sanhua.marketingcost.mapper.monthly;

import org.apache.ibatis.annotations.*;

/** Nullable extensions on legacy rows; old entities keep their existing runtime behavior. */
@Mapper
public interface MonthlyPriceLinkMapper {
  record PriceLink(long id,Long monthlyPriceId,String monthlyPricingType) {}
  record VersionSnapshot(long id,String feePolicyVersion,Integer jianjiSnapshotVersion,String jianjiSnapshotJson) {}

  @Update("""
      UPDATE lp_price_prepare_item SET monthly_price_id=#{price},monthly_pricing_type=#{type}
      WHERE id=#{id} AND business_unit_type=#{unit} AND monthly_price_id IS NULL AND status='READY'
        AND (current_flag=1 OR (current_flag=0 AND EXISTS (
          SELECT 1 FROM lp_price_prepare_batch b
          WHERE b.prepare_no=lp_price_prepare_item.prepare_no AND b.business_unit_type=#{unit}
            AND b.scenario_type='FINANCE_QUOTE_BASE' AND b.source_prepare_no IS NOT NULL
            AND b.status='SUCCESS')))
      """)
  int attachPreparation(@Param("unit") String unit,@Param("id") long id,@Param("price") long price,@Param("type") String type);

  @Update("""
      UPDATE lp_price_prepare_item SET monthly_price_id=#{price},monthly_pricing_type=#{type}
      WHERE id=#{id} AND business_unit_type=#{unit} AND monthly_price_id IS NULL
        AND status='READY' AND current_flag=0 AND EXISTS (
          SELECT 1 FROM lp_bom_costing_row r JOIN lp_cost_run_task t
            ON BINARY t.source_no=BINARY r.calculation_scope AND t.oa_form_item_id=r.oa_form_item_id
              AND BINARY t.oa_no=BINARY r.oa_no AND BINARY t.pricing_month=BINARY r.period_month
          WHERE r.id=lp_price_prepare_item.bom_row_id AND BINARY r.calculation_scope=BINARY #{no}
            AND BINARY r.business_unit_type=BINARY #{unit} AND BINARY t.business_unit_type=BINARY #{unit}
            AND t.scene='MONTHLY_REPRICE' AND t.status='RUNNING')
      """)
  int attachRepricePreparation(@Param("unit") String unit,@Param("no") String no,
      @Param("id") long id,@Param("price") long price,@Param("type") String type);

  @Update("""
      UPDATE lp_cost_run_part_item SET monthly_price_id=#{price},monthly_pricing_type=#{type}
      WHERE id=#{id} AND business_unit_type=#{unit} AND monthly_price_id IS NULL
      """)
  int attachCostPart(@Param("unit") String unit,@Param("id") long id,@Param("price") long price,@Param("type") String type);

  @Select("SELECT id,monthly_price_id,monthly_pricing_type FROM lp_price_prepare_item WHERE id=#{id} AND business_unit_type=#{unit}")
  PriceLink preparation(@Param("unit") String unit,@Param("id") long id);

  @Select("SELECT id,monthly_price_id,monthly_pricing_type FROM lp_cost_run_part_item WHERE id=#{id} AND business_unit_type=#{unit}")
  PriceLink costPart(@Param("unit") String unit,@Param("id") long id);

  @Update("""
      UPDATE lp_quote_cost_run_version SET fee_policy_version=#{policy},jianji_snapshot_version=#{schema},jianji_snapshot_json=#{snapshot}
      WHERE id=#{id} AND business_unit_type=#{unit} AND status IN ('TRIAL','RUNNING')
      """)
  int saveSnapshot(@Param("unit") String unit,@Param("id") long id,@Param("policy") String policy,
      @Param("schema") int schema,@Param("snapshot") String snapshot);

  @Select("SELECT id,fee_policy_version,jianji_snapshot_version,jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=#{id} AND business_unit_type=#{unit}")
  VersionSnapshot snapshot(@Param("unit") String unit,@Param("id") long id);
}
