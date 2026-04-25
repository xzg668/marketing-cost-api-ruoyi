package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import org.apache.ibatis.annotations.Mapper;

/** bom_stop_drill_rule 访问层。T2 阶段只继承 BaseMapper，规则 CRUD 由 T6 的控制器使用。 */
@Mapper
public interface BomStopDrillRuleMapper extends BaseMapper<BomStopDrillRule> {}
