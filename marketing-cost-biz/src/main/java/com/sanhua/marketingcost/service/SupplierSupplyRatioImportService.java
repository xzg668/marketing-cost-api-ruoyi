package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import com.sanhua.marketingcost.enums.SupplierSupplyRatioSourceType;
import java.io.InputStream;
import java.util.List;

public interface SupplierSupplyRatioImportService {

  SupplierSupplyRatioImportResponse importExcel(
      InputStream input,
      String sourceFileName,
      String businessUnitType,
      String operator);

  SupplierSupplyRatioImportResponse importRows(
      List<SupplierSupplyRatioExcelRow> rows,
      String sourceFileName,
      String businessUnitType,
      String operator);

  SupplierSupplyRatioImportResponse upsertFromRows(
      List<SupplierSupplyRatioImportRow> rows,
      SupplierSupplyRatioSourceType sourceType,
      String batchNo);

  SupplierSupplyRatioImportResponse upsertFromRows(
      List<SupplierSupplyRatioImportRow> rows,
      SupplierSupplyRatioSourceType sourceType,
      String batchNo,
      String sourceFileName,
      String businessUnitType,
      String operator);
}
