package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsCostImportRequest;
import com.sanhua.marketingcost.dto.CmsCostImportResponse;
import java.io.InputStream;

public interface CmsCostImportService {
  CmsCostImportResponse importParsedRows(CmsCostImportRequest request);

  CmsCostImportResponse importExcel(
      InputStream planInput,
      String planFileName,
      InputStream workshopInput,
      String workshopFileName,
      InputStream subjectInput,
      String subjectFileName,
      InputStream subjectSettingInput,
      String subjectSettingFileName,
      boolean dryRun,
      String importedBy,
      String businessUnitType);
}
