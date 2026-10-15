package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;

public interface MaterialPriceTypeMapper extends BaseMapper<MaterialPriceType> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<MaterialPriceType> selectList(@Param("ew") Wrapper<MaterialPriceType> queryWrapper);
  @Select("""
      <script>SELECT * FROM lp_material_price_type
      WHERE business_unit_type=#{scope.businessUnitType} AND period=#{scope.period}
        AND price_org_code=#{scope.priceOrgCode} AND material_code=#{scope.materialCode}
      ORDER BY priority,id <if test="lock">FOR UPDATE</if></script>
      """)
  List<MaterialPriceType> selectScope(@Param("scope") Scope scope, @Param("lock") boolean lock);

  @Select("""
      <script>SELECT * FROM lp_material_price_type
      WHERE business_unit_type=#{scope.businessUnitType} AND period=#{scope.period}
        AND price_org_code=#{scope.priceOrgCode} AND material_code IN
        <foreach collection="codes" item="code" open="(" close=")" separator=",">#{code}</foreach>
      ORDER BY material_code,priority,id</script>
      """)
  List<MaterialPriceType> selectScopes(@Param("scope") Scope scope, @Param("codes") List<String> codes);

  @Insert("""
      <script>INSERT INTO lp_material_price_type
        (business_unit_type,period,price_org_code,material_code,material_name,price_type,
         priority,source,source_system,created_at,updated_at) VALUES
      <foreach collection="types" item="type" separator=",">
        (#{scope.businessUnitType},#{scope.period},#{scope.priceOrgCode},#{scope.materialCode},
         #{materialName},#{type},#{type.defaultPriority},'SYSTEM','SYSTEM',NOW(),NOW())
      </foreach>
      ON DUPLICATE KEY UPDATE id=id</script>
      """)
  int insertDefaults(@Param("scope") Scope scope, @Param("materialName") String materialName,
      @Param("types") List<MaterialPriceSourceType> types);

  @Update("""
      UPDATE lp_material_price_type SET manual_priority=#{priority},priority_updated_by=#{userId},
        priority_updated_at=NOW(6),updated_at=NOW() WHERE id=#{id}
      """)
  int updateManual(@Param("id") Long id, @Param("priority") Integer priority, @Param("userId") Long userId);

}
