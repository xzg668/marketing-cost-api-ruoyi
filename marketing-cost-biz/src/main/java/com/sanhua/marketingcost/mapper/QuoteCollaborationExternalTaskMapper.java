package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationExternalTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteCollaborationExternalTaskMapper
    extends BaseMapper<QuoteCollaborationExternalTask> {

  @Select("""
      <script>
      SELECT e.* FROM lp_quote_collaboration_external_task e
      JOIN lp_quote_collaboration_task t ON t.id = e.collaboration_id
      LEFT JOIN lp_quote_collaboration_product_task p ON p.id = e.product_task_id
      WHERE e.assignee_user_id = #{assigneeUserId} AND e.current_flag = 1
        AND t.business_unit_type = #{businessUnitType}
        AND (p.id IS NULL OR p.applicable_org_code = #{applicableOrgCode})
        AND e.external_status IN
        <foreach collection="statuses" item="status" open="(" separator="," close=")">
          #{status}
        </foreach>
      ORDER BY e.updated_at DESC, e.id DESC
      </script>
      """)
  List<QuoteCollaborationExternalTask> selectCurrentByAssigneeAndStatuses(
      @Param("assigneeUserId") String assigneeUserId,
      @Param("businessUnitType") String businessUnitType,
      @Param("applicableOrgCode") String applicableOrgCode,
      @Param("statuses") List<String> statuses);
}
