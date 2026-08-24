package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.OaForm;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OaFormMapper extends BaseMapper<OaForm> {

  /** V21：selectList 走数据隔离（按登录用户 business_unit_type 过滤） */
  @DataScope
  @Override
  List<OaForm> selectList(@Param("ew") Wrapper<OaForm> queryWrapper);

  /**
   * 同一 OA 的产品可以并行核算，但 BOM 快照需要在同一张表中删除后重建。这里用 OA
   * 表头行作为跨 Worker 的短事务锁，只串行化工作台的 BOM 启动阶段；后续价格准备和
   * 成本计算仍按 Worker 线程数并行。
   */
  @Select("SELECT id FROM oa_form WHERE oa_no=#{oaNo} AND deleted=0 FOR UPDATE")
  Long selectIdForCostingUpdate(@Param("oaNo") String oaNo);
}
