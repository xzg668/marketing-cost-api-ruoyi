package com.sanhua.marketingcost.mapper;

/** One public projection for result pages, quotation history and monthly comparisons. */
final class MonthlyRepricePublishedSql {
  private MonthlyRepricePublishedSql() {}
  static final String COST_ID=" CASE WHEN b.published_revision IS NULL THEN r.cost_run_version_id ELSE r.published_cost_version_id END ";
  static final String SOURCE_ID=" CASE WHEN b.published_revision IS NULL THEN r.source_cost_version_id ELSE r.published_source_cost_version_id END ";
  static final String RESULTS="""
      SELECT r.id,r.reprice_no,r.pricing_month,r.business_unit_type,r.product_code,r.customer_name,r.calc_object_key,
        r.contract_version,r.reuse_batch_id,r.customer_identity_source,r.customer_identity_value,r.merged_source_count,
        r.published_cost_version_id,r.published_source_cost_version_id,r.created_at,
        CASE WHEN r.contract_version=1 THEN COALESCE(v.oa_no,r.oa_no) ELSE r.oa_no END oa_no,
        CASE WHEN r.contract_version=1 THEN COALESCE(v.oa_form_item_id,r.oa_form_item_id) ELSE r.oa_form_item_id END oa_form_item_id,
        CASE WHEN r.contract_version=1 THEN v.id ELSE r.cost_run_version_id END cost_run_version_id,
        CASE WHEN r.contract_version=1 THEN COALESCE(sv.id,r.source_cost_version_id) ELSE r.source_cost_version_id END source_cost_version_id,
        CASE WHEN r.contract_version=1 THEN v.total_cost ELSE r.total_cost END total_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.material_cost END material_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.labor_cost END labor_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.auxiliary_cost END auxiliary_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.manufacturing_cost END manufacturing_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.management_cost END management_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.sales_cost END sales_cost,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.finance_cost END finance_cost,
        CASE WHEN r.contract_version=1 THEN v.algorithm_version ELSE r.cost_engine_version END cost_engine_version,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.price_version END price_version,
        CASE WHEN r.contract_version=1 THEN v.fee_policy_version ELSE r.rule_version END rule_version,
        CASE WHEN r.contract_version=1 THEN NULL ELSE r.compute_group_id END compute_group_id,
        CASE WHEN r.contract_version=1 AND r.reuse_batch_id IS NOT NULL
          THEN NULLIF(JSON_UNQUOTE(JSON_EXTRACT(s.source_snapshot_json,'$.item.packageMethod')),'null')
          ELSE r.package_method END package_method,
        CASE WHEN r.contract_version=1 THEN COALESCE(v.confirmed_at,b.confirmed_at) ELSE r.updated_at END updated_at,
        CASE WHEN r.contract_version IS NULL THEN r.calc_status
          WHEN v.id IS NOT NULL THEN 'SUCCESS'
          WHEN """+COST_ID+"""
            IS NOT NULL THEN 'DATA_ISSUE'
          WHEN r.calc_status='SUCCESS' THEN 'WAIT_CONFIRM' ELSE COALESCE(t.status,r.calc_status) END calc_status,
        CASE WHEN r.contract_version IS NULL THEN r.calc_message
          WHEN v.id IS NOT NULL AND (r.cost_run_version_id IS NULL OR r.cost_run_version_id!=v.id OR r.calc_status!='SUCCESS')
            THEN '此前已确认，补算待确认'
          WHEN v.id IS NOT NULL THEN '已确认'
          WHEN """+COST_ID+"""
            IS NOT NULL THEN '已确认成本不完整或归属不一致'
          WHEN r.calc_status='SUCCESS' THEN '补算已完成，待确认后发布' ELSE COALESCE(t.error_message,r.calc_message) END calc_message
      FROM lp_monthly_reprice_result r JOIN lp_monthly_reprice_batch b ON b.reprice_no=r.reprice_no
      LEFT JOIN lp_cost_run_task t ON t.source_no=r.reprice_no AND t.scene='MONTHLY_REPRICE'
        AND t.calc_object_key=r.calc_object_key AND BINARY t.business_unit_type=BINARY r.business_unit_type
        AND t.pricing_month=r.pricing_month AND t.oa_form_item_id=r.oa_form_item_id AND BINARY t.oa_no=BINARY r.oa_no
      LEFT JOIN lp_quote_cost_run_version sv ON sv.id=("""+SOURCE_ID+"""
        ) AND BINARY sv.product_code=BINARY r.product_code AND BINARY sv.business_unit_type=BINARY r.business_unit_type
      LEFT JOIN lp_monthly_reprice_source s ON s.batch_id=r.reuse_batch_id AND s.object_id=r.id AND s.source_cost_version_id=sv.id
      LEFT JOIN lp_quote_cost_run_version v ON v.id=("""+COST_ID+"""
        ) AND BINARY v.reprice_no=BINARY r.reprice_no AND BINARY v.business_unit_type=BINARY r.business_unit_type
        AND BINARY v.product_code=BINARY r.product_code AND v.pricing_month=r.pricing_month
        AND v.oa_form_item_id=sv.oa_form_item_id AND BINARY v.oa_no=BINARY sv.oa_no
        AND (r.reuse_batch_id IS NULL OR s.id IS NOT NULL)
        AND v.status IN ('SUCCESS','CONFIRMED','HISTORY','VOIDED','STALE')
        AND v.trial_finished_at IS NOT NULL AND v.total_cost IS NOT NULL
      WHERE (b.contract_version=1 AND b.confirmed_at IS NOT NULL)
        OR (b.contract_version IS NULL AND b.status='CONFIRMED')
      """;
}
