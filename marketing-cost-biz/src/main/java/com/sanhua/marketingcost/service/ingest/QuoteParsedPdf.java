package com.sanhua.marketingcost.service.ingest;

final class QuoteParsedPdf extends QuoteParsedImport {
  QuotePdfDocument document;

  QuoteParsedPdf(String fileName) {
    super(fileName);
  }
}
