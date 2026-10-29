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

  /** Complete current relationships, including explicit zero and unfilled ratios.
   * Legacy date bounds remain applicable; new imports have open bounds. Never read historical
   * positive versions as a fallback for a current zero relationship. */
  static com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SupplierSupplyRatio> currentQuery(
      String unit, java.util.Collection<String> codes, java.time.LocalDate date) {
    if (codes == null || codes.isEmpty()) throw new IllegalArgumentException("供货关系查询料号不能为空");
    var query = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SupplierSupplyRatio>()
        .eq("business_unit_type", unit).in("material_code", codes)
        .eq("is_active", 1).eq("deleted", 0);
    if (date != null) query
        .and(q -> q.isNull("effective_from").or().le("effective_from", date))
        .and(q -> q.isNull("effective_to").or().ge("effective_to", date));
    return query.orderByAsc("material_code", "supplier_code", "id");
  }

  /** SQL collation decides the match; the exact requested identity routes each returned row. */
  String CURRENT_RELATIONS_SQL = """
    <script>SELECT r.*, requested.material_code AS requested_material_code
      FROM (
        <foreach collection="codes" item="code" separator=" UNION ALL ">
          SELECT CONVERT(#{code} USING utf8mb4) COLLATE utf8mb4_unicode_ci AS material_code
        </foreach>
      ) requested JOIN lp_supplier_supply_ratio r ON r.material_code=requested.material_code
      WHERE r.business_unit_type=#{unit} AND r.is_active=1 AND r.deleted=0
        <if test="date != null">
          AND (r.effective_from IS NULL OR r.effective_from&lt;=#{date})
          AND (r.effective_to IS NULL OR r.effective_to&gt;=#{date})
        </if>
      ORDER BY requested.material_code,r.supplier_code,r.id</script>
    """;

  @Select(CURRENT_RELATIONS_SQL)
  List<com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput> currentRelations(
      @Param("unit") String unit, @Param("date") java.time.LocalDate date,
      @Param("codes") java.util.Collection<String> codes);

  @Select("SELECT * FROM lp_supplier_supply_ratio WHERE business_unit_type=#{businessUnitType} "
      + "AND material_code=#{materialCode} AND supplier_code=#{supplierCode} "
      + "AND is_active=1 AND deleted=0 ORDER BY id FOR UPDATE")
  List<SupplierSupplyRatio> currentRelation(@Param("businessUnitType") String businessUnitType,
      @Param("materialCode") String materialCode, @Param("supplierCode") String supplierCode);

  @Select("<script>SELECT * FROM lp_supplier_supply_ratio WHERE business_unit_type=#{businessUnitType} "
      + "AND material_code=#{materialCode} AND supplier_name=#{supplierName} "
      + "AND is_active=1 AND deleted=0 "
      + "<if test='unnamedOnly'>AND (supplier_code IS NULL OR TRIM(supplier_code)='')</if> "
      + "ORDER BY id FOR UPDATE</script>")
  List<SupplierSupplyRatio> currentRelationByName(@Param("businessUnitType") String businessUnitType,
      @Param("materialCode") String materialCode, @Param("supplierName") String supplierName,
      @Param("unnamedOnly") boolean unnamedOnly);

  @Update("UPDATE lp_supplier_supply_ratio SET is_active=0,updated_by=#{operator},updated_at=#{updatedAt} "
      + "WHERE business_unit_type=#{businessUnitType} AND id=#{id} "
      + "AND is_active=1 AND deleted=0")
  int retireVersion(@Param("businessUnitType") String businessUnitType,
      @Param("id") long id, @Param("operator") String operator,
      @Param("updatedAt") LocalDateTime updatedAt);
}
