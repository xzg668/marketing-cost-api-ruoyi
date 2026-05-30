package com.sanhua.marketingcost.service.ingest;

import java.io.InputStream;

public interface QuotePdfTextExtractor {
  QuotePdfDocument extract(InputStream inputStream, String fileName);
}
