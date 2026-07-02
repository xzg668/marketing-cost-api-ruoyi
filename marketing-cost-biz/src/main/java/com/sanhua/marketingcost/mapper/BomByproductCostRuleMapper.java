package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** lp_bom_byproduct_cost_rule 访问层；副产品附加规则独立于 BOM 树节点结算规则。 */
@Mapper
public interface BomByproductCostRuleMapper extends BaseMapper<BomByproductCostRule> {

  @Select("SELECT MAX(COALESCE(updated_at, created_at)) FROM lp_bom_byproduct_cost_rule")
  LocalDateTime selectLatestRuleChangeTime();
}
