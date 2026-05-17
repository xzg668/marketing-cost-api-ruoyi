package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CmsProductSubjectCostRawMapper extends BaseMapper<CmsProductSubjectCostRaw> {
  @Insert({
    "INSERT INTO cms_product_subject_cost_raw (",
    "  import_batch_id, row_no, period, first_unit_code, first_unit_name, parent_code, parent_name,",
    "  parent_spec, parent_type, last_subject_code, last_subject_name, last_subject_level,",
    "  material_price, material_price_yuan, build_flag, path, first_subject_code, first_subject_name,",
    "  second_subject_code, second_subject_name, third_subject_code, third_subject_name, source_row_id,",
    "  sequence_no, sequence_status, business_unit_type",
    ") VALUES (",
    "  #{row.importBatchId}, #{row.rowNo}, #{row.period}, #{row.firstUnitCode}, #{row.firstUnitName}, #{row.parentCode}, #{row.parentName},",
    "  #{row.parentSpec}, #{row.parentType}, #{row.lastSubjectCode}, #{row.lastSubjectName}, #{row.lastSubjectLevel},",
    "  #{row.materialPrice}, #{row.materialPriceYuan}, #{row.buildFlag}, #{row.path}, #{row.firstSubjectCode}, #{row.firstSubjectName},",
    "  #{row.secondSubjectCode}, #{row.secondSubjectName}, #{row.thirdSubjectCode}, #{row.thirdSubjectName}, #{row.sourceRowId},",
    "  #{row.sequenceNo}, #{row.sequenceStatus}, #{row.businessUnitType}",
    ") ON DUPLICATE KEY UPDATE",
    "  import_batch_id = VALUES(import_batch_id),",
    "  row_no = VALUES(row_no),",
    "  first_unit_code = VALUES(first_unit_code),",
    "  first_unit_name = VALUES(first_unit_name),",
    "  parent_code = VALUES(parent_code),",
    "  parent_name = VALUES(parent_name),",
    "  parent_spec = VALUES(parent_spec),",
    "  parent_type = VALUES(parent_type),",
    "  last_subject_code = VALUES(last_subject_code),",
    "  last_subject_name = VALUES(last_subject_name),",
    "  last_subject_level = VALUES(last_subject_level),",
    "  material_price = VALUES(material_price),",
    "  material_price_yuan = VALUES(material_price_yuan),",
    "  build_flag = VALUES(build_flag),",
    "  path = VALUES(path),",
    "  first_subject_code = VALUES(first_subject_code),",
    "  first_subject_name = VALUES(first_subject_name),",
    "  second_subject_code = VALUES(second_subject_code),",
    "  second_subject_name = VALUES(second_subject_name),",
    "  third_subject_code = VALUES(third_subject_code),",
    "  third_subject_name = VALUES(third_subject_name),",
    "  sequence_no = VALUES(sequence_no),",
    "  sequence_status = VALUES(sequence_status)"
  })
  int upsert(@Param("row") CmsProductSubjectCostRaw row);
}
