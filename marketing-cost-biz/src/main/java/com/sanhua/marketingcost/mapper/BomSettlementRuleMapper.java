package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** lp_bom_settlement_rule 访问层。 */
@Mapper
public interface BomSettlementRuleMapper extends BaseMapper<BomSettlementRule> {

  @Select("SELECT MAX(COALESCE(updated_at, created_at)) FROM lp_bom_settlement_rule")
  LocalDateTime selectLatestRuleChangeTime();
}
