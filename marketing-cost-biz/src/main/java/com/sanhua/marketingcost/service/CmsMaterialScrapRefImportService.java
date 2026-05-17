package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportRequest;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportResponse;
import java.io.InputStream;

public interface CmsMaterialScrapRefImportService {
  CmsMaterialScrapRefImportResponse importExcel(InputStream input, String businessUnitType);

  CmsMaterialScrapRefImportResponse importSourceRows(CmsMaterialScrapRefImportRequest request);
}
