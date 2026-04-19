package com.sanhua.marketingcost.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DynamicValueMapper {
  @Select("select ${field} from ${table} where material_code = #{itemCode} limit 1")
  Object selectByMaterialCode(@Param("table") String table,
      @Param("field") String field,
      @Param("itemCode") String itemCode);

  @Select("select ${field} from ${table} where item_code = #{itemCode} limit 1")
  Object selectByItemCode(@Param("table") String table,
      @Param("field") String field,
      @Param("itemCode") String itemCode);
}
