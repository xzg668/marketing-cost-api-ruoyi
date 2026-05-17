package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CmsWorkshopLaborRawMapper extends BaseMapper<CmsWorkshopLaborRaw> {
  @Insert({
    "INSERT INTO cms_workshop_labor_raw (",
    "  import_batch_id, row_no, period, first_unit_code, first_unit_name, parent_code, parent_name,",
    "  parent_spec, parent_type, last_unit_name, last_unit_code, working_hours, funding,",
    "  working_cost_cent, working_cost_yuan, build_flag, path, source_row_id, sequence_no,",
    "  sequence_status, material_price, first_subject_code, first_subject_name, second_subject_code,",
    "  second_subject_name, third_subject_code, third_subject_name, business_unit_type",
    ") VALUES (",
    "  #{row.importBatchId}, #{row.rowNo}, #{row.period}, #{row.firstUnitCode}, #{row.firstUnitName}, #{row.parentCode}, #{row.parentName},",
    "  #{row.parentSpec}, #{row.parentType}, #{row.lastUnitName}, #{row.lastUnitCode}, #{row.workingHours}, #{row.funding},",
    "  #{row.workingCostCent}, #{row.workingCostYuan}, #{row.buildFlag}, #{row.path}, #{row.sourceRowId}, #{row.sequenceNo},",
    "  #{row.sequenceStatus}, #{row.materialPrice}, #{row.firstSubjectCode}, #{row.firstSubjectName}, #{row.secondSubjectCode},",
    "  #{row.secondSubjectName}, #{row.thirdSubjectCode}, #{row.thirdSubjectName}, #{row.businessUnitType}",
    ") ON DUPLICATE KEY UPDATE",
    "  import_batch_id = VALUES(import_batch_id),",
    "  row_no = VALUES(row_no),",
    "  first_unit_code = VALUES(first_unit_code),",
    "  first_unit_name = VALUES(first_unit_name),",
    "  parent_code = VALUES(parent_code),",
    "  parent_name = VALUES(parent_name),",
    "  parent_spec = VALUES(parent_spec),",
    "  parent_type = VALUES(parent_type),",
    "  last_unit_name = VALUES(last_unit_name),",
    "  last_unit_code = VALUES(last_unit_code),",
    "  working_hours = VALUES(working_hours),",
    "  funding = VALUES(funding),",
    "  working_cost_cent = VALUES(working_cost_cent),",
    "  working_cost_yuan = VALUES(working_cost_yuan),",
    "  build_flag = VALUES(build_flag),",
    "  path = VALUES(path),",
    "  sequence_no = VALUES(sequence_no),",
    "  sequence_status = VALUES(sequence_status),",
    "  material_price = VALUES(material_price),",
    "  first_subject_code = VALUES(first_subject_code),",
    "  first_subject_name = VALUES(first_subject_name),",
    "  second_subject_code = VALUES(second_subject_code),",
    "  second_subject_name = VALUES(second_subject_name),",
    "  third_subject_code = VALUES(third_subject_code),",
    "  third_subject_name = VALUES(third_subject_name)"
  })
  int upsert(@Param("row") CmsWorkshopLaborRaw row);
}
