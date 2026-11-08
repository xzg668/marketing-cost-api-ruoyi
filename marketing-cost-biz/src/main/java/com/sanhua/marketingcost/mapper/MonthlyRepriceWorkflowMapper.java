package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Only saved 1130 batches enter the new worker; retired queue rows cannot be replayed. */
@Mapper
public interface MonthlyRepriceWorkflowMapper {
  // Retry readiness uses the database clock and an existing per-task summary, never a JVM timer.
  String CLAIMABLE = """
      ((t.status='PENDING' AND (JSON_EXTRACT(t.result_summary_json,'$.retryNotBefore') IS NULL
        OR CAST(JSON_UNQUOTE(JSON_EXTRACT(t.result_summary_json,'$.retryNotBefore')) AS DATETIME(6))<=NOW(6)))
       OR (t.status='RUNNING' AND t.lock_expire_time IS NOT NULL AND t.lock_expire_time<=NOW(6)))
      """;
  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE contract_version=1 AND business_unit_type=#{unit} AND pricing_month=#{month}")
  MonthlyRepriceBatch month(@Param("unit") String unit,@Param("month") String month);

  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE contract_version=1 AND business_unit_type=#{unit} AND confirmed_at IS NULL ORDER BY id LIMIT 1")
  MonthlyRepriceBatch active(String unit);

  // Discovery does not lock rows: the optimizer may choose a table scan for the EXISTS join.
  @Select("""
      SELECT b.reprice_no FROM lp_monthly_reprice_batch b
      WHERE b.contract_version=1 AND b.status IN ('QUEUED','RUNNING','FAILED','CHECKING','WAIT_CONFIRM','CONFIRMED')
        AND EXISTS (
        SELECT 1 FROM lp_cost_run_task t WHERE t.source_no=b.reprice_no AND t.scene='MONTHLY_REPRICE'
          AND
      """ + CLAIMABLE + """
      )
      ORDER BY b.id LIMIT 64
      """)
  List<String> claimableBatchNumbers();

  @Select("""
      SELECT * FROM lp_monthly_reprice_batch FORCE INDEX (uk_monthly_reprice_no)
      WHERE reprice_no=#{no} AND contract_version=1 FOR UPDATE SKIP LOCKED
      """)
  MonthlyRepriceBatch lockClaimableBatch(String no);

  /** Point locks keep the batch-number/PRIMARY order and skip busy older batches. */
  default MonthlyRepriceBatch leaseBatch() {
    for(String no:claimableBatchNumbers()) {
      var batch=lockClaimableBatch(no);
      if(batch!=null)return batch;
    }
    return null;
  }

  @Select("""
      SELECT t.* FROM lp_cost_run_task t WHERE t.source_no=#{no} AND t.scene='MONTHLY_REPRICE' AND
      """ + CLAIMABLE + """
      ORDER BY t.id LIMIT 1
      """)
  CostRunTask candidate(String no);

  @Update("""
      UPDATE lp_cost_run_task t SET execution_no=execution_no+CASE WHEN status='RUNNING' THEN 1 ELSE 0 END,
        status='RUNNING',worker_id=#{worker},locked_at=NOW(6),lock_expire_time=DATE_ADD(NOW(6),INTERVAL 2 MINUTE),
        started_at=COALESCE(started_at,NOW(6)),finished_at=NULL,
        result_summary_json=JSON_SET(JSON_REMOVE(COALESCE(result_summary_json,JSON_OBJECT()),'$.retryNotBefore'),
          '$.admissionPolicy',#{policy})
      WHERE t.id=#{id} AND t.scene='MONTHLY_REPRICE' AND
      """ + CLAIMABLE)
  int lease(@Param("id") long id,@Param("worker") String worker,@Param("policy") String policy);

  @Update("""
      UPDATE lp_cost_run_task SET status='PENDING',execution_no=execution_no+1,retry_count=retry_count+1,
        worker_id=NULL,locked_at=NULL,lock_expire_time=NULL,finished_at=NOW(6),error_message=#{message},
        result_summary_json=JSON_SET(COALESCE(result_summary_json,JSON_OBJECT()),
          '$.failure',CAST(#{failure} AS JSON),
          '$.retryNotBefore',DATE_FORMAT(TIMESTAMPADD(SECOND,#{delay},NOW(6)),'%Y-%m-%d %H:%i:%s.%f'))
      WHERE id=#{id} AND scene='MONTHLY_REPRICE' AND status='RUNNING' AND execution_no=#{execution}
        AND worker_id=#{worker} AND lock_expire_time>NOW(6)
      """)
  int defer(@Param("id") long id,@Param("execution") int execution,@Param("worker") String worker,
      @Param("failure") String failure,@Param("message") String message,@Param("delay") int delay);

  @Update("""
      UPDATE lp_cost_run_task SET result_summary_json=JSON_SET(COALESCE(result_summary_json,JSON_OBJECT()),
        '$.failure',CAST(#{failure} AS JSON))
      WHERE id=#{id} AND scene='MONTHLY_REPRICE' AND status='RUNNING' AND execution_no=#{execution}
        AND worker_id=#{worker} AND lock_expire_time>NOW(6)
      """)
  int failureSummary(@Param("id") long id,@Param("execution") int execution,@Param("worker") String worker,
      @Param("failure") String failure);

  @Update("""
      UPDATE lp_cost_run_task SET lock_expire_time=DATE_ADD(NOW(6),INTERVAL 2 MINUTE)
      WHERE id=#{id} AND execution_no=#{execution} AND worker_id=#{worker} AND status='RUNNING'
        AND lock_expire_time>NOW(6)
      """)
  int heartbeat(@Param("id") long id,@Param("execution") int execution,@Param("worker") String worker);

  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE contract_version=1 AND status='CHECKING'  ORDER BY id LIMIT 20")
  List<MonthlyRepriceBatch> checking();

  @Select("SELECT * FROM lp_cost_run_task WHERE source_no=#{no} AND scene='MONTHLY_REPRICE' AND status='FAILED' ORDER BY id LIMIT 50")
  List<CostRunTask> problems(String no);

  @Select("""
      SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot
      WHERE id=#{id} AND cost_period_month=#{month} AND product_code=#{product}
        AND price_org_code=#{org} AND sync_status='SUCCESS'
      """)
  long bom(@Param("id") long id,@Param("month") String month,@Param("product") String product,@Param("org") String org);

  @Select("""
      SELECT COUNT(*) FROM lp_cost_run_part_item p JOIN lp_bom_costing_row r ON r.id=p.bom_row_id
      WHERE p.cost_run_version_id=#{version} AND BINARY r.calculation_scope=BINARY #{no}
        AND BINARY r.period_month=BINARY #{month} AND r.oa_form_item_id=#{item}
        AND EXISTS(SELECT 1 FROM lp_quote_effective_bom_node e
          WHERE BINARY e.build_batch_id=BINARY r.build_batch_id AND e.origin_monthly_snapshot_id=#{snapshot})
      """)
  long partEvidence(@Param("version") long version,@Param("no") String no,@Param("month") String month,
      @Param("item") long item,@Param("snapshot") long snapshot);
}
