package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorAdjustExcelParseResult;
import java.io.InputStream;

public interface FactorAdjustExcelParseService {

  /**
   * 解析月度调价 Excel。
   *
   * <p>这里只识别并匹配已有影响因素，不新增身份、不更新价格。
   */
  FactorAdjustExcelParseResult parse(
      InputStream input, String sourceFileName, String pricingMonth, String businessUnitType);
}
