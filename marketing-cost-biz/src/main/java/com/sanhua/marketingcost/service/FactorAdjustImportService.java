package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.FactorAdjustImportRequest;
import com.sanhua.marketingcost.dto.FactorAdjustImportResponse;
import java.io.InputStream;

public interface FactorAdjustImportService {

  /**
   * 导入月度调价 Excel。
   *
   * <p>REPRICE_ONLY 只写调价批次价；REPRICE_AND_DAILY 才同步为日常报价生效价。
   */
  FactorAdjustImportResponse importAdjustExcel(
      InputStream input, String sourceFileName, FactorAdjustImportRequest request, String operator);
}
