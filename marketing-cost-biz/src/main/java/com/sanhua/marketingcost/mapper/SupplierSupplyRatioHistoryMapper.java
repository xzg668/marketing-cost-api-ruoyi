package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioChange;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Generic change log rows are scoped through their actual ratio version, never a client log ID. */
@Mapper
public interface SupplierSupplyRatioHistoryMapper {
  String FILTER = """
      FROM lp_business_change_log l JOIN lp_supplier_supply_ratio r ON r.id=l.biz_id
      WHERE l.biz_domain='SUPPLIER_SUPPLY_RATIO' AND l.biz_type='SUPPLIER_RELATION'
        AND r.business_unit_type=#{unit}
        <if test="material != null and material != ''">AND r.material_code LIKE CONCAT('%',#{material},'%')</if>
        <if test="supplier != null and supplier != ''">AND r.supplier_code LIKE CONCAT('%',#{supplier},'%')</if>
        <if test="batch != null and batch != ''">AND l.submit_batch_no=#{batch}</if>
      """;

  @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
  long count(@Param("unit") String unit, @Param("material") String material,
      @Param("supplier") String supplier, @Param("batch") String batch);

  @Select("""
      <script>SELECT l.id,l.biz_detail_id previous_version_id,l.biz_id version_id,
        r.material_code,r.supplier_code,r.material_name,r.supplier_name,r.import_file_name,
        l.submit_batch_no batch_no,l.changed_by_name,l.changed_at,l.change_reason,l.before_value,l.after_value
      """ + FILTER + " ORDER BY l.id DESC LIMIT #{size} OFFSET #{offset}</script>")
  List<SupplierSupplyRatioChange> list(@Param("unit") String unit, @Param("material") String material,
      @Param("supplier") String supplier, @Param("batch") String batch,
      @Param("size") int size, @Param("offset") long offset);
}
