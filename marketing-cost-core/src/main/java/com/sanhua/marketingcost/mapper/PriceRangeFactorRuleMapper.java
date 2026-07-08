package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PriceRangeFactorRuleMapper extends BaseMapper<PriceRangeFactorRule> {

  /** 行情因素区间价规则按业务单元隔离。 */
  @DataScope
  @Override
  List<PriceRangeFactorRule> selectList(@Param("ew") Wrapper<PriceRangeFactorRule> queryWrapper);
}
