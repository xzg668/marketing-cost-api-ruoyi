package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.*;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Bounded source reads and atomic monthly membership, under the contract's batch lock. */
@Mapper
public interface MonthlyRepriceScopeMapper {
  @Select("""
      <script>SELECT i.* FROM oa_form_item i WHERE i.id IN
      <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></script>
      """)
  List<OaFormItem> items(@Param("ids") List<Long> ids);

  @Select("""
      <script>SELECT f.* FROM oa_form f WHERE f.id IN
      <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></script>
      """)
  List<OaForm> forms(@Param("ids") List<Long> ids);

  record AnnualUnit(long itemId, String unit) {}
  @Select("""
      <script>SELECT oa_form_item_id AS item_id,field_value AS unit
      FROM lp_oa_form_item_extra_field WHERE field_code='ANNUAL_VOLUME_UNIT' AND oa_form_item_id IN
      <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach></script>
      """)
  List<AnnualUnit> annualUnits(@Param("ids") List<Long> ids);

  record ObjectRow(long id, String calcObjectKey) {}
  @Select("SELECT id,calc_object_key FROM lp_monthly_reprice_result WHERE reuse_batch_id=#{batchId}")
  List<ObjectRow> objects(long batchId);

  record SourceRow(long versionId, Long objectId, String oaNo, long itemId, String issue, String snapshot) {}
  @Insert("""
      <script>INSERT INTO lp_monthly_reprice_source(batch_id,object_id,source_cost_version_id,
        oa_no,oa_form_item_id,issue_code,source_snapshot_json) VALUES
      <foreach collection="rows" item="s" separator=",">
        (#{batch},#{s.objectId},#{s.versionId},#{s.oaNo},#{s.itemId},#{s.issue},#{s.snapshot})
      </foreach>
      ON DUPLICATE KEY UPDATE object_id=VALUES(object_id),issue_code=VALUES(issue_code),
        source_snapshot_json=VALUES(source_snapshot_json)</script>
      """)
  void sources(@Param("batch") long batchId,@Param("rows") List<SourceRow> rows);

  record SavedSource(long id,Long objectId,long versionId,String oaNo,long itemId,String issue,String snapshot) {}
  @Select("""
      <script>SELECT id,object_id,source_cost_version_id version_id,oa_no,oa_form_item_id item_id,
        issue_code issue,source_snapshot_json snapshot FROM lp_monthly_reprice_source
      WHERE batch_id=#{batch} AND id IN
      <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach> ORDER BY id</script>
      """)
  List<SavedSource> selectedSources(@Param("batch") long batch,@Param("ids") List<Long> ids);

  @Select("""
      SELECT * FROM lp_monthly_reprice_result WHERE reuse_batch_id=#{batch}
        AND product_code=#{product} AND customer_identity_source=#{source} AND customer_identity_value=#{customer}
        AND BINARY product_code=BINARY #{product} AND BINARY customer_identity_source=BINARY #{source}
        AND BINARY customer_identity_value=BINARY #{customer}
      """)
  MonthlyRepriceResult customerObject(@Param("batch") long batch,@Param("product") String product,
      @Param("source") String source,@Param("customer") String customer);

  @Select("SELECT source_snapshot_json FROM lp_monthly_reprice_source WHERE batch_id=#{batch} AND source_cost_version_id=#{version}")
  String snapshot(@Param("batch") long batch,@Param("version") long version);

  @Select("SELECT * FROM lp_cost_run_task WHERE source_no=#{no} AND scene='MONTHLY_REPRICE' AND calc_object_key=#{key}")
  CostRunTask objectTask(@Param("no") String no,@Param("key") String key);

  @Update("""
      UPDATE lp_monthly_reprice_source SET object_id=#{object},issue_code=NULL,source_snapshot_json=#{snapshot}
      WHERE id=#{id} AND batch_id=#{batch} AND object_id IS NULL AND issue_code='MISSING_CUSTOMER'
      """)
  int resolve(@Param("batch") long batch,@Param("id") long id,@Param("object") long object,@Param("snapshot") String snapshot);

  @Update("UPDATE lp_monthly_reprice_result SET merged_source_count=merged_source_count+#{count} WHERE id=#{id}")
  int addMembers(@Param("id") long id,@Param("count") int count);

  @Update("""
      UPDATE lp_cost_run_task SET oa_no=#{t.version.oaNo},oa_form_item_id=#{t.version.oaFormItemId},
        package_method=#{t.packageMethod},request_snapshot_json=#{t.request},status='PENDING',
        execution_no=execution_no+1,retry_count=retry_count+1,cost_run_version_id=NULL,cost_run_no=NULL,
        progress=0,error_message=NULL,result_summary_json=NULL,started_at=NULL,finished_at=NULL,
        worker_id=NULL,locked_at=NULL,lock_expire_time=NULL
      WHERE id=#{id} AND source_no=#{no} AND scene='MONTHLY_REPRICE' AND status IN ('PENDING','FAILED','SUCCESS')
      """)
  int replaceSelectionTask(@Param("no") String no,@Param("id") long id,@Param("t") MonthlyRepriceContractMapper.Target target);

  @Update("""
      UPDATE lp_monthly_reprice_result SET oa_no=#{t.version.oaNo},oa_form_item_id=#{t.version.oaFormItemId},
        source_cost_version_id=#{t.version.id},package_method=#{t.packageMethod},calc_status='PENDING',calc_message=NULL,
        cost_run_version_id=NULL,compute_group_id=NULL,total_cost=NULL,cost_engine_version=NULL
      WHERE id=#{id} AND reuse_batch_id=#{t.batchId}
      """)
  int replaceSelectionResult(@Param("id") long id,@Param("t") MonthlyRepriceContractMapper.Target target);

  @Insert("""
      INSERT INTO lp_monthly_reprice_audit_log(reprice_no,pricing_month,business_unit_type,operation_type,
        operation_name,operator_id,target_type,target_key,before_json,after_json,change_summary)
      VALUES(#{b.repriceNo},#{b.pricingMonth},#{b.businessUnitType},'RETRY_SOURCE','补算原报价资料',#{actor},
        'REPRICE_SOURCE',#{source},#{before},#{after},#{summary})
      """)
  void recordSourceAttempt(@Param("b") MonthlyRepriceBatch batch,@Param("source") long source,
      @Param("actor") String actor,@Param("before") String before,@Param("after") String after,@Param("summary") String summary);

  @Update("""
      UPDATE lp_monthly_reprice_batch SET scope_version=1,source_count=#{sources},
        unresolved_source_count=#{unresolved},total_count=#{objects} WHERE id=#{batch}
      """)
  void counts(@Param("batch") long batch,@Param("sources") int sources,
      @Param("unresolved") int unresolved,@Param("objects") int objects);

  @Update("UPDATE lp_cost_run_batch SET total_count=#{count} WHERE batch_no=#{no} AND scene='MONTHLY_REPRICE'")
  void queueCount(@Param("no") String no,@Param("count") int count);



}
