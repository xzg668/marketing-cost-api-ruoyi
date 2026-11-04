package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MonthlyPriceImportFile;
import org.apache.ibatis.annotations.*;

@Mapper
public interface MonthlyPriceImportFileMapper extends BaseMapper<MonthlyPriceImportFile> {
  @Select("SELECT id,source_workbook FROM lp_monthly_price_import_file WHERE id=#{id}")
  MonthlyPriceImportFile workbook(@Param("id") long id);
}
