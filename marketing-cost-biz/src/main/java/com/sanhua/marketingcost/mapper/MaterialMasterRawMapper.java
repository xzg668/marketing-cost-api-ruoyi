package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** T15：U9 staging 表 mapper。同步用，找最新批次 + 按料号过滤。 */
@Mapper
public interface MaterialMasterRawMapper extends BaseMapper<MaterialMasterRaw> {

  /** 查 staging 最新批次 id（按字典序倒序），无数据返 null */
  @Select("SELECT MAX(import_batch_id) FROM lp_material_master_raw")
  String selectLatestBatchId();
}
