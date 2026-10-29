package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Only saved 1130 batches enter the new worker; retired queue rows cannot be replayed. */
@Mapper
public interface MonthlyRepriceWorkflowMapper {
  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE contract_version=1 AND business_unit_type=#{unit} AND pricing_month=#{month}")
  MonthlyRepriceBatch month(@Param("unit") String unit,@Param("month") String month);

  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE contract_version=1 AND business_unit_type=#{unit} AND status<>'CONFIRMED' ORDER BY id LIMIT 1")
  MonthlyRepriceBatch active(String unit);

  @Select("""
      SELECT b.* FROM lp_monthly_reprice_batch b
      WHERE b.contract_version=1 AND b.status IN ('QUEUED','RUNNING','FAILED') AND EXISTS (
        SELECT 1 FROM lp_cost_run_task t WHERE t.source_no=b.reprice_no AND t.scene='MONTHLY_REPRICE'
          AND ((t.status='PENDING' AND b.status<>'FAILED')
            OR (t.status='RUNNING' AND t.lock_expire_time IS NOT NULL AND t.lock_expire_time<=NOW(6))))
      ORDER BY b.id LIMIT 1 FOR UPDATE SKIP LOCKED
      """)
  MonthlyRepriceBatch leaseBatch();

  @Select("""
      SELECT * FROM lp_cost_run_task WHERE source_no=#{no} AND scene='MONTHLY_REPRICE'
        AND ((status='PENDING' AND #{allowPending})
          OR (status='RUNNING' AND lock_expire_time IS NOT NULL AND lock_expire_time<=NOW(6)))
      ORDER BY id LIMIT 1
      """)
  CostRunTask candidate(@Param("no") String no,@Param("allowPending") boolean allowPending);

  @Update("""
      UPDATE lp_cost_run_task SET execution_no=execution_no+CASE WHEN status='RUNNING' THEN 1 ELSE 0 END,
        status='RUNNING',worker_id=#{worker},locked_at=NOW(6),lock_expire_time=DATE_ADD(NOW(6),INTERVAL 2 MINUTE),
        started_at=COALESCE(started_at,NOW(6)),finished_at=NULL,error_message=NULL
      WHERE id=#{id} AND (status='PENDING' OR
        (status='RUNNING' AND lock_expire_time IS NOT NULL AND lock_expire_time<=NOW(6)))
      """)
  int lease(@Param("id") long id,@Param("worker") String worker);

  @Update("""
      UPDATE lp_cost_run_task SET lock_expire_time=DATE_ADD(NOW(6),INTERVAL 2 MINUTE)
      WHERE id=#{id} AND execution_no=#{execution} AND worker_id=#{worker} AND status='RUNNING'
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
