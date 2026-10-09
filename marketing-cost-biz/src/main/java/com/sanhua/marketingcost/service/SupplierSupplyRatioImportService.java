package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import java.io.InputStream;
import java.util.List;

public interface SupplierSupplyRatioImportService {

  SupplierSupplyRatioImportResponse importExcel(
      InputStream input,
      String sourceFileName,
      String businessUnitType,
      String operator,
      String sheetName);

  SupplierSupplyRatioImportResponse importRows(
      List<SupplierSupplyRatioExcelRow> rows,
      String sourceFileName,
      String businessUnitType,
      String operator);

}
