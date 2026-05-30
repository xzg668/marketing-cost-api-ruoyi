package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.MakePartSpec;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 制造件工艺规格 mapper (Task #8)。 */
@Mapper
public interface MakePartSpecMapper extends BaseMapper<MakePartSpec> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<MakePartSpec> selectList(@Param("ew") Wrapper<MakePartSpec> queryWrapper);
}
