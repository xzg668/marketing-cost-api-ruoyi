package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteExcelTemplateInfoResponse;
import java.util.List;

public interface QuoteExcelTemplateService {
  List<QuoteExcelTemplateInfoResponse> listTemplates();

  QuoteExcelTemplateFile getTemplate(String templateType);
}
