package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;
import com.sanhua.marketingcost.enums.QuoteSourceType;
import java.util.ArrayList;
import java.util.List;

final class QuoteOaPdfDetailTableParserTestSupport {
  private QuoteOaPdfDetailTableParserTestSupport() {}

  static QuoteIngestRequest request(QuoteExcelTemplateType templateType) {
    QuoteOaPdfTemplateDefinition definition = QuoteOaPdfTemplateDefinitions.get(templateType);
    QuoteIngestRequest request = new QuoteIngestRequest();
    request.setSourceType(QuoteSourceType.OA.getCode());
    request.setVersion("1");
    request.setOaNo(templateType.getProcessCode() + "-20260530-001");
    request.setExternalFormNo(request.getOaNo());
    QuoteIngestHeaderRequest header = new QuoteIngestHeaderRequest();
    header.setProcessCode(definition.getProcessCode());
    header.setProcessName(definition.getProcessName());
    header.setQuoteScenario(definition.getQuoteScenario());
    header.setBusinessUnitType(definition.getBusinessUnitType());
    header.setExpenseProductCategory(definition.getExpenseProductCategory());
    header.setApplyDate("2026-05-30");
    request.setHeader(header);
    return request;
  }

  static QuotePdfParseContext context(QuoteExcelTemplateType templateType, QuotePdfDocument document) {
    QuotePdfParseContext context = new QuotePdfParseContext();
    context.setFileName(document.getFileName());
    context.setDocument(document);
    context.setTemplateDefinition(QuoteOaPdfTemplateDefinitions.get(templateType));
    return context;
  }

  static QuotePdfDocument document(String fileName, Row... rows) {
    QuotePdfDocument document = new QuotePdfDocument();
    document.setFileName(fileName);
    QuotePdfPage page = new QuotePdfPage();
    page.setPageIndex(0);
    StringBuilder fullText = new StringBuilder();
    for (int i = 0; i < rows.length; i++) {
      Row row = rows[i];
      QuotePdfLine line = new QuotePdfLine();
      line.setPageIndex(0);
      line.setY(80 + i * 18);
      List<QuotePdfToken> tokens = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      for (Cell cell : row.cells()) {
        QuotePdfToken token = token(cell.text(), cell.x(), 80 + i * 18);
        tokens.add(token);
        if (text.length() > 0) {
          text.append(' ');
        }
        text.append(cell.text());
      }
      line.setTokens(tokens);
      line.setText(text.toString());
      page.getLines().add(line);
      fullText.append(text).append('\n');
    }
    document.setFullText(fullText.toString());
    document.setPages(List.of(page));
    return document;
  }

  static Row row(Cell... cells) {
    return new Row(List.of(cells));
  }

  static Cell cell(String text, float x) {
    return new Cell(text, x);
  }

  private static QuotePdfToken token(String text, float x, float y) {
    QuotePdfToken token = new QuotePdfToken();
    token.setText(text);
    token.setPageIndex(0);
    token.setX(x);
    token.setY(y);
    token.setWidth(Math.max(6f, text.length() * 8f));
    token.setHeight(10f);
    return token;
  }

  record Row(List<Cell> cells) {}

  record Cell(String text, float x) {}
}
