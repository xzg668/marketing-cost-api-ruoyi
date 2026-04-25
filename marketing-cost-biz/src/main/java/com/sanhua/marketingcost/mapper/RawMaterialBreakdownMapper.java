package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.RawMaterialBreakdown;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 原材料拆解 mapper (Task #8)。 */
@Mapper
public interface RawMaterialBreakdownMapper extends BaseMapper<RawMaterialBreakdown> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<RawMaterialBreakdown> selectList(@Param("ew") Wrapper<RawMaterialBreakdown> queryWrapper);
}
