package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

/** lp_u9_bom_byproduct_master 访问层。 */
@Mapper
public interface U9BomByproductMasterMapper extends BaseMapper<U9BomByproductMaster> {

  @Insert(
      "INSERT INTO lp_u9_bom_byproduct_master ("
          + "parent_material_no, parent_material_name, parent_material_spec, bom_purpose,"
          + "version_no, output_type, byproduct_material_no, byproduct_material_name,"
          + "operation_no, output_qty, unit, status, production_dept_code, production_dept_name,"
          + "effective_from, effective_to, u9_created_by, u9_created_time,"
          + "source_type, source_file_name, imported_by, imported_at"
          + ") VALUES ("
          + "#{parentMaterialNo}, #{parentMaterialName}, #{parentMaterialSpec}, #{bomPurpose},"
          + "#{versionNo}, #{outputType}, #{byproductMaterialNo}, #{byproductMaterialName},"
          + "#{operationNo}, #{outputQty}, #{unit}, #{status}, #{productionDeptCode}, #{productionDeptName},"
          + "#{effectiveFrom}, #{effectiveTo}, #{u9CreatedBy}, #{u9CreatedTime},"
          + "#{sourceType}, #{sourceFileName}, #{importedBy}, #{importedAt}"
          + ") ON DUPLICATE KEY UPDATE "
          + "parent_material_name = VALUES(parent_material_name),"
          + "parent_material_spec = VALUES(parent_material_spec),"
          + "version_no = VALUES(version_no),"
          + "output_type = VALUES(output_type),"
          + "byproduct_material_name = VALUES(byproduct_material_name),"
          + "operation_no = VALUES(operation_no),"
          + "output_qty = VALUES(output_qty),"
          + "unit = VALUES(unit),"
          + "status = VALUES(status),"
          + "production_dept_code = VALUES(production_dept_code),"
          + "production_dept_name = VALUES(production_dept_name),"
          + "u9_created_by = VALUES(u9_created_by),"
          + "u9_created_time = VALUES(u9_created_time),"
          + "source_type = VALUES(source_type),"
          + "source_file_name = VALUES(source_file_name),"
          + "imported_by = VALUES(imported_by),"
          + "imported_at = VALUES(imported_at),"
          + "updated_at = CURRENT_TIMESTAMP")
  int upsert(U9BomByproductMaster row);
}
