package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.*;
import java.util.List;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.*;

/** Persistence for the 1130 contract. Legacy readers and retired HTTP writes are unchanged. */
@Mapper
public interface MonthlyRepriceContractMapper {
  @Insert("""
      INSERT INTO lp_monthly_reprice_batch(reprice_no,pricing_month,price_as_of_time,
        business_unit_type,bom_source_policy,status,contract_version,go_live_month,
        result_revision,check_status,created_by,created_name)
      VALUES(#{no},#{month},NOW(),#{unit},'MONTHLY_SNAPSHOT','QUEUED',1,#{goLive},0,'PENDING',#{actor},#{actor})
      ON DUPLICATE KEY UPDATE id=id
      """)
  void reserve(@Param("no") String no, @Param("unit") String unit, @Param("month") String month,
      @Param("goLive") String goLive, @Param("actor") String actor);

  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE contract_month_key=CONCAT(#{unit},':',#{month}) FOR UPDATE")
  MonthlyRepriceBatch month(@Param("unit") String unit, @Param("month") String month);

  @Select("""
      SELECT * FROM lp_monthly_reprice_batch FORCE INDEX (uk_monthly_reprice_no)
      WHERE reprice_no=#{no} AND contract_version=1 FOR UPDATE
      """)
  MonthlyRepriceBatch lock(String no);

  @Select("SELECT COUNT(*) FROM lp_monthly_reprice_batch WHERE contract_version=1 AND business_unit_type=#{unit} AND confirmed_at IS NULL")
  long blocked(String unit);

  @Insert("""
      INSERT INTO lp_cost_run_batch(batch_no,scene,source_no,pricing_month,business_unit_type,status,created_by,total_count)
      VALUES(#{no},'MONTHLY_REPRICE',#{no},#{month},#{unit},'PENDING',#{actor},#{count})
      """)
  void queue(@Param("no") String no, @Param("unit") String unit, @Param("month") String month,
      @Param("actor") String actor, @Param("count") int count);

  String SUCCESSFUL_SOURCES = """
      SELECT v.id,v.oa_no,v.oa_form_item_id,v.product_code,v.business_unit_type,v.pricing_month,v.trial_finished_at FROM lp_quote_cost_run_version v
      JOIN oa_form_item i ON i.id=v.oa_form_item_id AND BINARY i.business_unit_type=BINARY v.business_unit_type
      JOIN oa_form f ON f.id=i.oa_form_id AND BINARY f.oa_no=BINARY v.oa_no
        AND BINARY f.business_unit_type=BINARY v.business_unit_type
      WHERE BINARY v.business_unit_type=BINARY #{unit}
        AND v.status IN ('SUCCESS','CONFIRMED','HISTORY','VOIDED','STALE') AND v.reprice_no IS NULL
        AND v.trial_finished_at IS NOT NULL AND v.total_cost IS NOT NULL
        AND v.trial_finished_at >= #{from} AND v.trial_finished_at < #{until}
      """;

  // A quotation contains multiple product lines: retain each line once under its original OA number.
  // Completion time and id select the latest successful version, regardless of current OA status.
  @Select("SELECT ranked.* FROM (SELECT eligible.*,ROW_NUMBER() OVER (PARTITION BY "
      + "BINARY eligible.oa_no,eligible.oa_form_item_id ORDER BY eligible.trial_finished_at DESC,eligible.id DESC) source_rank "
      + "FROM (" + SUCCESSFUL_SOURCES + ") eligible) ranked WHERE source_rank=1 "
      + "ORDER BY BINARY ranked.oa_no,ranked.oa_form_item_id")
  List<QuoteCostRunVersion> cumulativeSources(@Param("unit") String unit,
      @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

  record Target(String key,QuoteCostRunVersion version,long batchId,String customerSource,
      String customerValue,int mergedCount,String packageMethod,String request) {}
  @Insert("""
      <script>INSERT INTO lp_cost_run_task(batch_no,scene,source_no,calc_object_key,oa_no,oa_form_item_id,
        product_code,business_unit_type,pricing_month,status,customer_name,package_method,request_snapshot_json) VALUES
      <foreach collection="targets" item="t" separator=",">
        (#{no},'MONTHLY_REPRICE',#{no},#{t.key},#{t.version.oaNo},#{t.version.oaFormItemId},
        #{t.version.productCode},#{t.version.businessUnitType},#{month},'PENDING',#{t.customerValue},#{t.packageMethod},
        #{t.request})
      </foreach></script>
      """)
  void tasksBatch(@Param("no") String no,@Param("month") String month,@Param("targets") List<Target> targets);
  @Insert("""
      <script>INSERT INTO lp_monthly_reprice_result(reprice_no,pricing_month,business_unit_type,oa_no,
        oa_form_item_id,product_code,calc_object_key,source_cost_version_id,calc_status,contract_version,
        reuse_batch_id,customer_identity_source,customer_identity_value,merged_source_count,customer_name,package_method) VALUES
      <foreach collection="targets" item="t" separator=",">
        (#{no},#{month},#{t.version.businessUnitType},#{t.version.oaNo},#{t.version.oaFormItemId},
        #{t.version.productCode},#{t.key},#{t.version.id},'PENDING',1,
        #{t.batchId},#{t.customerSource},#{t.customerValue},#{t.mergedCount},#{t.customerValue},#{t.packageMethod})
      </foreach></script>
      """)
  void objectsBatch(@Param("no") String no,@Param("month") String month,@Param("targets") List<Target> targets);

  @Select("SELECT * FROM lp_cost_run_task WHERE source_no=#{no} AND scene='MONTHLY_REPRICE' ORDER BY id")
  List<CostRunTask> tasks(String no);

  @Select("""
      <script>SELECT t.* FROM lp_cost_run_task t JOIN lp_monthly_reprice_result r
        ON r.reprice_no=t.source_no AND r.calc_object_key=t.calc_object_key AND r.contract_version=1
      WHERE t.source_no=#{no} AND t.scene='MONTHLY_REPRICE' AND t.id IN
      <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach> ORDER BY t.id</script>
      """)
  List<CostRunTask> selectedTasks(@Param("no") String no,@Param("ids") List<Long> ids);

  @Select("SELECT * FROM lp_cost_run_task WHERE id=#{id} AND source_no=#{no} AND scene='MONTHLY_REPRICE'")
  CostRunTask readTask(@Param("no") String no,@Param("id") long id);

  @Select("SELECT * FROM lp_monthly_reprice_batch WHERE reprice_no=#{no} AND contract_version=1")
  MonthlyRepriceBatch readBatch(String no);

  @Select("SELECT DISTINCT price_org_code,material_organization_code FROM lp_cost_run_part_item WHERE cost_run_version_id=#{id}")
  List<com.sanhua.marketingcost.dto.QuoteDataOrganization> sourceOrganizations(long id);

  @Update("UPDATE lp_cost_run_task SET request_snapshot_json=#{json} WHERE id=#{id} AND scene='MONTHLY_REPRICE'")
  int saveRequest(@Param("id") long id, @Param("json") String json);

  @Select("""
      SELECT EXISTS(SELECT 1 FROM lp_cost_run_task
        WHERE id=#{id} AND source_no=#{no} AND scene='MONTHLY_REPRICE'
          AND status='RUNNING' AND execution_no=#{execution} AND worker_id=#{worker}
          AND lock_expire_time>NOW(6))
      """)
  boolean ownsLiveTask(@Param("no") String no, @Param("id") long id,
      @Param("execution") int execution, @Param("worker") String worker);

  @Update("""
      UPDATE lp_monthly_reprice_batch SET status=#{status},result_revision=result_revision+1,
        check_status='PENDING',checked_revision=NULL,check_result_json=NULL,checked_at=NULL,
        started_at=CASE WHEN #{status}='RUNNING' THEN COALESCE(started_at,NOW()) ELSE started_at END,
        finished_at=CASE WHEN #{status} IN ('CHECKING','FAILED') THEN NOW() ELSE NULL END,
        success_count=success_count+#{successDelta},failed_count=failed_count+#{failedDelta}
      WHERE reprice_no=#{no} AND contract_version=1
        AND success_count+#{successDelta}>=0 AND failed_count+#{failedDelta}>=0
        AND success_count+#{successDelta}+failed_count+#{failedDelta}<=total_count
      """)
  int progress(@Param("no") String no, @Param("status") String status,
      @Param("successDelta") int successDelta,@Param("failedDelta") int failedDelta);

  /** Status-only changes do not scan or modify task counters. */
  default int state(String no,String status) {
    int updated=progress(no,status,0,0);
    if(updated!=1)throw new IllegalStateException("调价进度与批次状态不一致");
    return updated;
  }

  @Update("""
      UPDATE lp_cost_run_task SET status='FAILED',error_message=#{message},finished_at=NOW(),worker_id=NULL,locked_at=NULL,lock_expire_time=NULL
      WHERE id=#{id} AND status='RUNNING' AND execution_no=#{execution}
        AND worker_id=#{worker} AND lock_expire_time>NOW(6)
      """)
  int fail(@Param("id") long id, @Param("execution") int execution,
      @Param("worker") String worker, @Param("message") String message);

  @Update("""
      <script>UPDATE lp_cost_run_task SET status='PENDING',execution_no=execution_no+1,retry_count=retry_count+1,
        result_summary_json=JSON_REMOVE(JSON_SET(COALESCE(result_summary_json,JSON_OBJECT()),'$.failure.automaticRetries',0,
          '$.failure.exhausted',CAST('false' AS JSON),'$.lastAttemptAt',
          DATE_FORMAT(COALESCE(finished_at,started_at),'%Y-%m-%d %H:%i:%s.%f')),'$.retryNotBefore'),
        error_message=NULL,started_at=NULL,finished_at=NULL,worker_id=NULL,locked_at=NULL,lock_expire_time=NULL
      WHERE source_no=#{no} AND scene='MONTHLY_REPRICE' AND status='FAILED' AND id IN
      <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></script>
      """)
  int retry(@Param("no") String no,@Param("ids") List<Long> ids);

  @Select("SELECT * FROM lp_quote_cost_run_version WHERE id=#{id} FOR UPDATE")
  QuoteCostRunVersion version(long id);

  @Select("SELECT reprice_no FROM lp_quote_cost_run_version WHERE id=#{id}")
  String versionOwner(long id);

  @Select("""
      SELECT COUNT(*) FROM lp_quote_cost_run_version v WHERE v.id=#{id}
        AND v.status='SUCCESS' AND v.trial_finished_at IS NOT NULL AND v.total_cost IS NOT NULL
        AND v.algorithm_version IS NOT NULL AND v.fee_policy_version IS NOT NULL
        AND v.part_item_count>0 AND v.cost_item_count>0
        AND v.part_item_count=(SELECT COUNT(*) FROM lp_cost_run_part_item p WHERE p.cost_run_version_id=v.id)
        AND v.cost_item_count=(SELECT COUNT(*) FROM lp_cost_run_cost_item c WHERE c.cost_run_version_id=v.id)
        AND v.jianji_snapshot_version=1 AND v.jianji_snapshot_json IS NOT NULL
      """)
  long completeVersion(long id);

  @Select("SELECT jianji_snapshot_json FROM lp_quote_cost_run_version WHERE id=#{id}")
  String snapshot(long id);

  @Update("""
      UPDATE lp_monthly_reprice_result SET cost_run_version_id=#{v.id},total_cost=#{v.totalCost},
        cost_engine_version=#{v.algorithmVersion},calc_status='SUCCESS',calc_message='OK'
      WHERE reprice_no=#{no} AND calc_object_key=#{key} AND contract_version=1 AND calc_status='PENDING'
      """)
  int link(@Param("no") String no, @Param("key") String key, @Param("v") QuoteCostRunVersion version);

  @Update("""
      UPDATE lp_cost_run_task SET status='SUCCESS',cost_run_version_id=#{v.id},cost_run_no=#{v.costRunNo},
        progress=100,finished_at=NOW(),error_message=NULL,worker_id=NULL,locked_at=NULL,lock_expire_time=NULL
      WHERE id=#{id} AND execution_no=#{execution} AND status='RUNNING'
        AND worker_id=#{worker} AND lock_expire_time>NOW(6)
      """)
  int succeed(@Param("id") long id, @Param("execution") int execution,
      @Param("worker") String worker, @Param("v") QuoteCostRunVersion version);

  @Select("SELECT * FROM lp_monthly_reprice_result WHERE reprice_no=#{no} AND contract_version=1 ORDER BY id")
  List<MonthlyRepriceResult> results(String no);

  @Update("""
      UPDATE lp_monthly_reprice_batch SET status=#{status},check_status=#{checkStatus},
        checked_revision=result_revision,check_result_json=#{json},checked_at=NOW(6),finished_at=NOW()
      WHERE reprice_no=#{no} AND contract_version=1
      """)
  void check(@Param("no") String no, @Param("status") String status,
      @Param("checkStatus") String checkStatus, @Param("json") String json);

  @Update("""
      UPDATE lp_cost_run_batch SET status=#{status},success_count=#{success},failed_count=#{failed},
        progress=#{progress},control_version=control_version+1 WHERE batch_no=#{no} AND scene='MONTHLY_REPRICE'
      """)
  void queueState(@Param("no") String no, @Param("status") String status,
      @Param("success") int success, @Param("failed") int failed, @Param("progress") int progress);
}
