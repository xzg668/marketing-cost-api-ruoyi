package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.OaForm;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OaFormMapper extends BaseMapper<OaForm> {

  /** V21：selectList 走数据隔离（按登录用户 business_unit_type 过滤） */
  @DataScope
  @Override
  List<OaForm> selectList(@Param("ew") Wrapper<OaForm> queryWrapper);
}
