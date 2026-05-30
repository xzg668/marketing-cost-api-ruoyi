package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteValidationError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class QuoteParsedImport {
  final String fileName;
  final Map<String, QuoteIngestRequest> requests = new LinkedHashMap<>();
  final List<QuoteValidationError> errors = new ArrayList<>();
  int formCount;
  int itemCount;
  int feeCount;

  QuoteParsedImport(String fileName) {
    this.fileName = fileName;
  }
}
