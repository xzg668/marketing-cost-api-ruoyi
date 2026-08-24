package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QuoteCostRunVersionMapper extends BaseMapper<QuoteCostRunVersion> {

  /** 成本结果主表合并后，列表查询继续保持原结果表的数据权限隔离。 */
  @DataScope
  @Override
  List<QuoteCostRunVersion> selectList(
      @Param("ew") Wrapper<QuoteCostRunVersion> queryWrapper);
}
