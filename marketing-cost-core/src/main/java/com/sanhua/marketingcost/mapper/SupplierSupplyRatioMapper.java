package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.time.LocalDateTime;

@Mapper
public interface SupplierSupplyRatioMapper extends BaseMapper<SupplierSupplyRatio> {

  // 同一组织、料号的整段索引范围加锁，串行切换该物料；空组也由 RR 的间隙锁保护。
  @Select("SELECT id FROM lp_supplier_supply_ratio WHERE business_unit_type=#{businessUnitType} "
      + "AND material_code=#{materialCode} ORDER BY id FOR UPDATE")
  List<Long> lockMaterial(@Param("businessUnitType") String businessUnitType,
      @Param("materialCode") String materialCode);

  @Update("UPDATE lp_supplier_supply_ratio SET is_active=0,updated_by=#{operator},updated_at=#{updatedAt} "
      + "WHERE business_unit_type=#{businessUnitType} AND material_code=#{materialCode} "
      + "AND is_active=1 AND deleted=0")
  int deactivateMaterial(@Param("businessUnitType") String businessUnitType,
      @Param("materialCode") String materialCode, @Param("operator") String operator,
      @Param("updatedAt") LocalDateTime updatedAt);
}
