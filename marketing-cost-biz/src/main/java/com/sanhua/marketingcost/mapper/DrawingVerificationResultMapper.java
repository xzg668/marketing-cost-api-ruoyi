package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.DrawingVerificationResult;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DrawingVerificationResultMapper {
  @Select("""
      SELECT r.id,r.verification_task_id,r.event_type,r.upstream_revision,r.confirmed_at_ms,r.received_at
      FROM lp_drawing_verification_result r JOIN lp_drawing_verification_task t ON t.id=r.verification_task_id
      WHERE t.oa_form_item_id=#{itemId} AND t.accounting_month=#{month} AND t.business_unit_type=#{businessUnit}
      ORDER BY r.id DESC
      """)
  List<DrawingVerificationResult> findProductHeaders(@Param("itemId") Long itemId,
      @Param("month") String month, @Param("businessUnit") String businessUnit);

  @Insert("""
      INSERT INTO lp_drawing_verification_result
        (verification_task_id,event_type,upstream_revision,raw_summary_json,data_json,plm_data_json,
         plm_source_result_id,content_fingerprint,confirmed_at_ms,received_at)
      VALUES (#{verificationTaskId},#{eventType},#{upstreamRevision},#{rawSummaryJson},#{dataJson},
         #{plmDataJson},#{plmSourceResultId},#{contentFingerprint},#{confirmedAtMs},#{receivedAt})
      """)
  @Options(useGeneratedKeys = true, keyProperty = "id")
  int insert(DrawingVerificationResult result);

  @Select("SELECT * FROM lp_drawing_verification_result WHERE id=#{id}")
  DrawingVerificationResult findById(@Param("id") Long id);

  @Select("SELECT * FROM lp_drawing_verification_result WHERE verification_task_id=#{taskId} AND event_type=#{eventType}")
  DrawingVerificationResult findEvent(@Param("taskId") Long taskId, @Param("eventType") String eventType);

  @Select("SELECT * FROM lp_drawing_verification_result WHERE verification_task_id=#{taskId} ORDER BY id")
  List<DrawingVerificationResult> findHistory(@Param("taskId") Long taskId);
}
