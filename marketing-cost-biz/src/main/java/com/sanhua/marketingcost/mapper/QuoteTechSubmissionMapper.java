package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteTechSubmissionMapper extends BaseMapper<QuoteTechSubmission> {
  @Select("SELECT * FROM lp_quote_tech_submission WHERE task_id=#{taskId} AND request_id=#{requestId}")
  QuoteTechSubmission selectByRequest(@Param("taskId") Long taskId, @Param("requestId") String requestId);

  @Select("""
      SELECT id FROM lp_quote_tech_submission
       WHERE task_id=#{taskId} AND assignee_user_id=#{assigneeUserId} AND submission_status IN ('SENT','RETURNED','APPROVED')
       ORDER BY submission_round DESC LIMIT 1
      """)
  Long selectLastSentId(@Param("taskId") Long taskId, @Param("assigneeUserId") Long assigneeUserId);

  @Select("SELECT COALESCE(MAX(submission_round),0)+1 FROM lp_quote_tech_submission WHERE task_id=#{taskId} AND assignee_user_id=#{assigneeUserId}")
  int nextRound(@Param("taskId") Long taskId, @Param("assigneeUserId") Long assigneeUserId);
}
