package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FactorUploadBatchMapper extends BaseMapper<FactorUploadBatch> {
  @org.apache.ibatis.annotations.Select(
      "SELECT id,file_sha256,parser_version,file_name,source_workbook FROM lp_factor_upload_batch"
          + " WHERE id=#{id}")
  FactorUploadBatch findSourceWorkbook(@org.apache.ibatis.annotations.Param("id") Long id);
}
