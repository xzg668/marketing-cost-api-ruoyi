package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import org.apache.ibatis.annotations.Mapper;

/** lp_bom_byproduct_cost_rule 访问层；副产品附加规则独立于 BOM 树节点结算规则。 */
@Mapper
public interface BomByproductCostRuleMapper extends BaseMapper<BomByproductCostRule> {}
