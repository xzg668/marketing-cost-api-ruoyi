package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface DrawingVerificationTaskMapper extends BaseMapper<DrawingVerificationTask> {
  @Select("""
      SELECT * FROM lp_drawing_verification_task
      WHERE oa_no=#{oaNo} AND accounting_month=#{month} AND business_unit_type=#{businessUnit}
        AND active_scope_key IS NOT NULL
      ORDER BY id DESC
      """)
  List<DrawingVerificationTask> findActiveForQuote(@Param("oaNo") String oaNo,
      @Param("month") String month, @Param("businessUnit") String businessUnit);

  @Select("""
      SELECT * FROM lp_drawing_verification_task
      WHERE oa_form_item_id=#{itemId} AND accounting_month=#{month} AND business_unit_type=#{businessUnit}
      ORDER BY id DESC
      """)
  List<DrawingVerificationTask> findProductHistory(@Param("itemId") Long itemId,
      @Param("month") String month, @Param("businessUnit") String businessUnit);

  @Select("SELECT * FROM lp_drawing_verification_task WHERE source_system=#{source} AND external_task_id=#{externalId}")
  DrawingVerificationTask findExternal(@Param("source") String source, @Param("externalId") String externalId);

  @Select("SELECT * FROM lp_drawing_verification_task WHERE active_scope_key=#{scopeKey}")
  DrawingVerificationTask findActive(@Param("scopeKey") String scopeKey);

  @Select("SELECT * FROM lp_drawing_verification_task WHERE id=#{id} FOR UPDATE")
  DrawingVerificationTask selectForUpdate(@Param("id") Long id);

  @Select("""
      SELECT * FROM lp_drawing_verification_task
      WHERE task_state='RECEIVED' AND consumption_state='PENDING'
        AND active_scope_key IS NOT NULL AND current_result_id IS NOT NULL
      ORDER BY id LIMIT #{limit}
      """)
  List<DrawingVerificationTask> findPendingConsumption(@Param("limit") int limit);

  @Update("""
      UPDATE lp_drawing_verification_task SET consumption_state='NOT_READY',error_code='U9_BOM_AVAILABLE',
        error_message='U9 BOM 已可用，保留图库结果作为历史',row_version=row_version+1,updated_at=#{now}
      WHERE id=#{id} AND current_result_id=#{resultId} AND active_scope_key IS NOT NULL
        AND task_state='RECEIVED' AND consumption_state IN ('PENDING','FAILED')
      """)
  int deferToU9(@Param("id") Long id, @Param("resultId") Long resultId, @Param("now") LocalDateTime now);

  @Update("""
      UPDATE lp_drawing_verification_task SET consumed_result_id=#{resultId},consumption_state='COMPLETE',
        error_code=NULL,error_message=NULL,row_version=row_version+1,updated_at=#{now}
      WHERE id=#{id} AND current_result_id=#{resultId} AND active_scope_key IS NOT NULL AND task_state='RECEIVED'
      """)
  int completeConsumption(@Param("id") Long id, @Param("resultId") Long resultId, @Param("now") LocalDateTime now);

  @Update("""
      UPDATE lp_drawing_verification_task SET consumption_state='FAILED',error_code='CONSUMPTION_FAILED',
        error_message=#{message},row_version=row_version+1,updated_at=#{now}
      WHERE id=#{id} AND current_result_id=#{resultId} AND active_scope_key IS NOT NULL
        AND task_state='RECEIVED'
      """)
  int failConsumption(@Param("id") Long id, @Param("resultId") Long resultId,
      @Param("message") String message, @Param("now") LocalDateTime now);

  @Update("""
      UPDATE lp_drawing_verification_task SET active_scope_key=NULL, task_state='SUPERSEDED',
        row_version=row_version+1, updated_at=#{now}
      WHERE id=#{id} AND row_version=#{expectedVersion} AND active_scope_key IS NOT NULL
      """)
  int supersede(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
      @Param("now") LocalDateTime now);

  @Update("""
      UPDATE lp_drawing_verification_task SET task_state='FAILED',error_code=#{code},error_message=#{message},
        row_version=row_version+1,updated_at=#{now}
      WHERE id=#{id} AND task_state='REQUESTING' AND current_result_id IS NULL
      """)
  int failRequest(@Param("id") Long id, @Param("code") String code, @Param("message") String message,
      @Param("now") LocalDateTime now);

  @Update("""
      UPDATE lp_drawing_verification_task SET external_task_id=#{externalId},current_result_id=#{resultId},
        edit_url=#{editUrl},difference_count=#{differenceCount},task_state=#{state},
        consumption_state=#{consumptionState},error_code=NULL,error_message=NULL,
        updated_at=#{now},row_version=row_version+1
      WHERE id=#{id} AND row_version=#{expectedVersion}
        AND (external_task_id IS NULL OR external_task_id=#{externalId})
      """)
  int attachResult(@Param("id") Long id, @Param("expectedVersion") int expectedVersion,
      @Param("externalId") String externalId, @Param("resultId") Long resultId,
      @Param("editUrl") String editUrl, @Param("differenceCount") int differenceCount,
      @Param("state") String state, @Param("consumptionState") String consumptionState,
      @Param("now") LocalDateTime now);
}
