package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import java.io.InputStream;

public interface QuotePdfImportService {
  QuoteExcelImportPreviewResponse preview(InputStream inputStream, String fileName);

  QuoteExcelImportCommitResponse commit(InputStream inputStream, String fileName);
}
