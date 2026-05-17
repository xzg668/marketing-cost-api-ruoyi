package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.CmsSubjectSettingRaw;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CmsSubjectSettingRawMapper extends BaseMapper<CmsSubjectSettingRaw> {
  @Insert({
    "INSERT INTO cms_subject_setting_raw (",
    "  import_batch_id, row_no, first_subject_code, first_subject_name,",
    "  second_subject_code, second_subject_name, third_subject_code, third_subject_name,",
    "  business_unit_type",
    ") VALUES (",
    "  #{row.importBatchId}, #{row.rowNo}, #{row.firstSubjectCode}, #{row.firstSubjectName},",
    "  #{row.secondSubjectCode}, #{row.secondSubjectName}, #{row.thirdSubjectCode}, #{row.thirdSubjectName},",
    "  #{row.businessUnitType}",
    ") ON DUPLICATE KEY UPDATE",
    "  import_batch_id = VALUES(import_batch_id),",
    "  row_no = VALUES(row_no),",
    "  first_subject_name = VALUES(first_subject_name),",
    "  second_subject_name = VALUES(second_subject_name),",
    "  third_subject_name = VALUES(third_subject_name),",
    "  business_unit_type = VALUES(business_unit_type)"
  })
  int upsert(@Param("row") CmsSubjectSettingRaw row);
}
