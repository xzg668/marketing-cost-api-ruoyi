package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsCostExcelParseResult;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefSourceRow;
import com.sanhua.marketingcost.dto.CmsPlanCostExcelRow;
import com.sanhua.marketingcost.dto.CmsProductSubjectCostExcelRow;
import com.sanhua.marketingcost.dto.CmsSubjectSettingExcelRow;
import com.sanhua.marketingcost.dto.CmsWorkshopLaborExcelRow;
import java.io.InputStream;

public interface CmsCostExcelParseService {
  CmsCostExcelParseResult<CmsPlanCostExcelRow> parsePlanCost(InputStream input);

  CmsCostExcelParseResult<CmsWorkshopLaborExcelRow> parseWorkshopLabor(InputStream input);

  CmsCostExcelParseResult<CmsProductSubjectCostExcelRow> parseProductSubjectCost(InputStream input);

  CmsCostExcelParseResult<CmsSubjectSettingExcelRow> parseSubjectSetting(InputStream input);

  CmsCostExcelParseResult<CmsMaterialScrapRefSourceRow> parseMaterialScrapRef(InputStream input);
}
