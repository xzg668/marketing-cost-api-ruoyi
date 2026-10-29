package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Last complete success per original OA/product/month, selected before report pagination. */
@Mapper
public interface MonthlyCostComparisonMapper {
  record Scope(String unit,String baseline,String comparison,String oa,Long item,String product,
      boolean review,int offset,int size) {}
  record Coverage(String month,long objects) {}
  record SavedSnapshot(long id,String json) {}
  record TaskInput(long versionId,String inputJson) {}
  String RANKED="""
      WITH ranked AS (
        SELECT v.id,v.oa_no,v.oa_form_item_id,v.product_code,v.business_unit_type,v.pricing_month,
          v.trial_finished_at,v.cost_run_no,v.algorithm_version,v.fee_policy_version,v.part_item_count,v.cost_item_count,
          v.finance_material_cost,v.total_cost,v.cu_material_adjustment,v.final_quote_amount,v.finance_cu_price,
          ROW_NUMBER() OVER(PARTITION BY BINARY v.oa_no,v.oa_form_item_id,v.pricing_month
          ORDER BY v.trial_finished_at DESC,v.id DESC) successful_rank
        FROM lp_quote_cost_run_version v
        JOIN oa_form_item i ON i.id=v.oa_form_item_id AND BINARY i.business_unit_type=BINARY v.business_unit_type
        JOIN oa_form f ON f.id=i.oa_form_id AND BINARY f.oa_no=BINARY v.oa_no
          AND BINARY f.business_unit_type=BINARY v.business_unit_type
        WHERE BINARY v.business_unit_type=BINARY #{s.unit}
          AND v.pricing_month IN (#{s.baseline},#{s.comparison})
          AND v.status IN ('SUCCESS','CONFIRMED','HISTORY','VOIDED','STALE')
          AND v.trial_finished_at IS NOT NULL AND v.total_cost IS NOT NULL
          AND v.part_item_count&gt;0 AND v.cost_item_count&gt;0
          AND v.part_item_count=(SELECT COUNT(*) FROM lp_cost_run_part_item p WHERE p.cost_run_version_id=v.id)
          AND v.cost_item_count=(SELECT COUNT(*) FROM lp_cost_run_cost_item c WHERE c.cost_run_version_id=v.id)
          AND (#{s.review} OR v.reprice_no IS NULL OR EXISTS (
            SELECT 1 FROM lp_monthly_reprice_batch b WHERE BINARY b.reprice_no=BINARY v.reprice_no AND b.status='CONFIRMED'))
          <if test="s.oa != null">AND BINARY v.oa_no=BINARY #{s.oa}</if>
          <if test="s.item != null">AND v.oa_form_item_id=#{s.item}</if>
          <if test="s.product != null">AND BINARY v.product_code=BINARY #{s.product}</if>
      )
      """;

  @Select("<script>"+RANKED+" SELECT pricing_month month,COUNT(*) objects FROM ranked WHERE successful_rank=1 GROUP BY pricing_month ORDER BY pricing_month</script>")
  List<Coverage> coverage(@Param("s") Scope scope);

  @Select("<script>"+RANKED+" SELECT COUNT(*) FROM (SELECT oa_no,oa_form_item_id FROM ranked WHERE successful_rank=1 GROUP BY BINARY oa_no,oa_form_item_id,oa_no) objects</script>")
  long countObjects(@Param("s") Scope scope);

  @Select("<script>"+RANKED+"""
      , object_page AS (
        SELECT oa_no,oa_form_item_id FROM ranked WHERE successful_rank=1
        GROUP BY BINARY oa_no,oa_form_item_id,oa_no ORDER BY BINARY oa_no,oa_form_item_id LIMIT #{s.offset},#{s.size})
      SELECT v.* FROM ranked v JOIN object_page p ON BINARY p.oa_no=BINARY v.oa_no AND p.oa_form_item_id=v.oa_form_item_id
      WHERE v.successful_rank=1 ORDER BY BINARY v.oa_no,v.oa_form_item_id,v.pricing_month
      </script>
      """)
  List<QuoteCostRunVersion> pageVersions(@Param("s") Scope scope);

  @Select("""
      <script>SELECT id,jianji_snapshot_json json FROM lp_quote_cost_run_version WHERE id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
      """)
  List<SavedSnapshot> snapshots(@Param("ids") List<Long> ids);

  @Select("""
      <script>SELECT cost_run_version_id version_id,request_snapshot_json input_json
      FROM lp_cost_run_task WHERE scene='MONTHLY_REPRICE' AND status='SUCCESS' AND cost_run_version_id IN
      <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach></script>
      """)
  List<TaskInput> taskInputs(@Param("ids") List<Long> ids);
}
