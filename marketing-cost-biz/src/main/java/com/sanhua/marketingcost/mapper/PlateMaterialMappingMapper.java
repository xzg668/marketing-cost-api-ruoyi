package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PlateMaterialMappingMapper extends BaseMapper<PlateMaterialMapping> {
  @Insert("""
      INSERT INTO lp_plate_material_mapping
        (business_unit_type, normalized_model, plate_material, row_version, source_type, created_by, updated_by)
      VALUES
        (#{row.businessUnitType}, #{row.normalizedModel}, #{row.plateMaterial}, 0, 'MANUAL', #{row.createdBy}, #{row.updatedBy})
      ON DUPLICATE KEY UPDATE id=id
      """)
  int insertConfirmedIfAbsent(@Param("row") PlateMaterialMapping row);

  @Select("""
      <script>
      SELECT * FROM lp_plate_material_mapping
      WHERE business_unit_type = #{unit} AND normalized_model IN
      <foreach collection="models" item="model" open="(" separator="," close=")">#{model}</foreach>
      ORDER BY normalized_model
      <if test="lock">FOR UPDATE</if>
      </script>
      """)
  // A workflow may read and validate twice in one transaction after a concurrent configuration edit.
  @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
  List<PlateMaterialMapping> findModels(@Param("unit") String unit,
      @Param("models") List<String> models, @Param("lock") boolean lock);

  // Explicit null assignments clear obsolete workbook provenance after a manual edit.
  @Update("""
      UPDATE lp_plate_material_mapping
      SET normalized_model=#{row.normalizedModel}, plate_material=#{row.plateMaterial},
          source_type=#{row.sourceType}, source_file_name=#{row.sourceFileName},
          source_file_sha256=#{row.sourceFileSha256}, source_sheet_name=#{row.sourceSheetName},
          source_row_number=#{row.sourceRowNumber}, import_batch_id=#{row.importBatchId},
          updated_by=#{row.updatedBy}, updated_at=CURRENT_TIMESTAMP(3), row_version=row_version+1
      WHERE id=#{row.id} AND business_unit_type=#{row.businessUnitType} AND row_version=#{expectedVersion}
      """)
  int updateWithVersion(@Param("row") PlateMaterialMapping row, @Param("expectedVersion") int expectedVersion);
}
