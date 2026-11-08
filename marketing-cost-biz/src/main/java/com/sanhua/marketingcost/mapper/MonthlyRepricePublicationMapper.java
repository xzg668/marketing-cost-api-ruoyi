package com.sanhua.marketingcost.mapper;

import org.apache.ibatis.annotations.*;

/** Publication changes references and confirmation evidence only, never calculated amounts. */
@Mapper
public interface MonthlyRepricePublicationMapper {
  /** Pin the previously approved references before an explicitly requested first repair of an older batch. */
  @Update("""
      UPDATE lp_monthly_reprice_result r JOIN lp_monthly_reprice_batch b ON b.reprice_no=r.reprice_no
      SET r.published_cost_version_id=r.cost_run_version_id,
        r.published_source_cost_version_id=r.source_cost_version_id
      WHERE b.reprice_no=#{no} AND b.contract_version=1 AND b.confirmed_at IS NOT NULL
        AND b.published_revision IS NULL AND r.contract_version=1 AND r.calc_status='SUCCESS'
        AND r.cost_run_version_id IS NOT NULL AND r.published_cost_version_id IS NULL
      """)
  int pinLegacyResults(String no);

  @Update("""
      UPDATE lp_monthly_reprice_batch SET published_revision=COALESCE(checked_revision,result_revision)
      WHERE reprice_no=#{no} AND contract_version=1 AND confirmed_at IS NOT NULL AND published_revision IS NULL
      """)
  int pinLegacyRevision(String no);

  String READY="""
      FROM lp_monthly_reprice_result r
      JOIN lp_cost_run_task t ON t.batch_no=r.reprice_no AND t.calc_object_key=r.calc_object_key
        AND BINARY t.source_no=BINARY r.reprice_no AND t.scene='MONTHLY_REPRICE'
        AND BINARY t.calc_object_key=BINARY r.calc_object_key AND t.status='SUCCESS'
        AND t.cost_run_version_id=r.cost_run_version_id AND t.oa_form_item_id=r.oa_form_item_id
        AND BINARY t.oa_no=BINARY r.oa_no AND BINARY t.product_code=BINARY r.product_code
        AND BINARY t.business_unit_type=BINARY r.business_unit_type AND t.pricing_month=r.pricing_month
      JOIN lp_quote_cost_run_version v ON v.id=r.cost_run_version_id AND v.status='SUCCESS'
        AND BINARY v.reprice_no=BINARY r.reprice_no AND v.oa_form_item_id=r.oa_form_item_id
        AND BINARY v.oa_no=BINARY r.oa_no AND BINARY v.product_code=BINARY r.product_code
        AND BINARY v.business_unit_type=BINARY r.business_unit_type AND v.pricing_month=r.pricing_month
        AND v.total_cost=r.total_cost
      WHERE r.reprice_no=#{no} AND r.contract_version=1 AND r.calc_status='SUCCESS'
      """;

  @Select("SELECT COUNT(*) "+READY)
  int readyCount(String no);

  @Update("""
      UPDATE lp_monthly_reprice_result r SET published_cost_version_id=cost_run_version_id,
        published_source_cost_version_id=source_cost_version_id
      WHERE reprice_no=#{no} AND contract_version=1 AND calc_status='SUCCESS'
        AND (published_cost_version_id IS NULL OR published_cost_version_id<>cost_run_version_id
          OR published_source_cost_version_id<>source_cost_version_id)
      """)
  int publishResults(String no);

  @Update("""
      UPDATE lp_quote_cost_run_version v JOIN lp_monthly_reprice_result r ON r.cost_run_version_id=v.id
      SET v.confirmed_at=NOW(6),v.confirmed_by=#{actor},v.confirm_message=JSON_OBJECT(
        'type','MONTHLY_REPRICE','revision',#{revision},'resultId',r.id,'sourceCostVersionId',r.published_source_cost_version_id)
      WHERE r.reprice_no=#{no} AND r.contract_version=1 AND r.calc_status='SUCCESS' AND v.confirmed_at IS NULL
        AND (r.published_cost_version_id IS NULL OR r.published_cost_version_id<>r.cost_run_version_id
          OR r.published_source_cost_version_id<>r.source_cost_version_id)
        AND BINARY v.reprice_no=BINARY r.reprice_no
      """)
  int recordVersionPublication(@Param("no") String no,@Param("revision") long revision,@Param("actor") String actor);

  @Update("""
      UPDATE lp_monthly_reprice_batch SET status='CONFIRMED',published_revision=#{revision},
        confirmed_by=COALESCE(confirmed_by,#{actor}),confirmed_name=COALESCE(confirmed_name,#{actor}),
        confirmed_at=COALESCE(confirmed_at,NOW())
      WHERE reprice_no=#{no} AND contract_version=1 AND status='WAIT_CONFIRM'
        AND check_status='PASSED' AND checked_revision=#{revision} AND result_revision=#{revision}
        AND success_count=#{success} AND success_count>0
      """)
  int confirm(@Param("no") String no,@Param("revision") long revision,@Param("success") int success,@Param("actor") String actor);

  @Insert("""
      INSERT INTO lp_monthly_reprice_audit_log(reprice_no,pricing_month,business_unit_type,operation_type,
        operation_name,operator_id,operation_time,target_type,target_key,before_json,after_json,change_summary)
      SELECT reprice_no,pricing_month,business_unit_type,'CONFIRM','确认成功核算结果',#{actor},NOW(),
        'PUBLICATION',CONCAT(reprice_no,':',#{revision}),#{before},#{after},#{summary}
      FROM lp_monthly_reprice_batch WHERE reprice_no=#{no} AND contract_version=1 AND published_revision=#{revision}
      """)
  int audit(@Param("no") String no,@Param("revision") long revision,@Param("actor") String actor,
      @Param("before") String before,@Param("after") String after,@Param("summary") String summary);
}
