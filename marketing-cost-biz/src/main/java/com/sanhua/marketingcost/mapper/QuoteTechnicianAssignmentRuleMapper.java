package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QuoteTechnicianAssignmentRule;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuoteTechnicianAssignmentRuleMapper
    extends BaseMapper<QuoteTechnicianAssignmentRule> {

  @Select("""
      SELECT *
        FROM lp_quote_technician_assignment_rule
       WHERE business_unit_type = #{businessUnitType}
         AND status = 'ENABLED'
         AND deleted = 0
         AND (effective_from IS NULL OR effective_from <= #{asOfDate})
         AND (effective_to IS NULL OR effective_to >= #{asOfDate})
       ORDER BY priority ASC, id ASC
      """)
  List<QuoteTechnicianAssignmentRule> selectEffectiveRules(
      @Param("businessUnitType") String businessUnitType,
      @Param("asOfDate") LocalDate asOfDate);
}
