package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CostRunTraceSnapshotMapper extends BaseMapper<CostRunTraceSnapshot> {

  /** 底稿快照带 business_unit_type，基础列表查询保持业务单元隔离。 */
  @DataScope
  @Override
  List<CostRunTraceSnapshot> selectList(@Param("ew") Wrapper<CostRunTraceSnapshot> queryWrapper);
}
