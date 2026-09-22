package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.OaFormItemExtraField;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OaFormItemExtraFieldMapper extends BaseMapper<OaFormItemExtraField> {
  @Select("SELECT field_value FROM lp_oa_form_item_extra_field WHERE oa_form_item_id=#{itemId} AND field_code='ANNUAL_VOLUME_UNIT'")
  String selectAnnualVolumeUnit(@Param("itemId") Long itemId);
}
