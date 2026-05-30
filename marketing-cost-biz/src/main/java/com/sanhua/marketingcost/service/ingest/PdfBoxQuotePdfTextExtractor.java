package com.sanhua.marketingcost.service.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PdfBoxQuotePdfTextExtractor implements QuotePdfTextExtractor {
  private static final float LINE_Y_TOLERANCE = 2.0f;

  @Override
  public QuotePdfDocument extract(InputStream inputStream, String fileName) {
    if (inputStream == null) {
      throw new QuotePdfParseException("PDF_TEXT_EMPTY", "PDF 文件不能为空");
    }
    try (PDDocument pdDocument = Loader.loadPDF(inputStream.readAllBytes())) {
      QuotePdfPositionStripper stripper = new QuotePdfPositionStripper();
      stripper.setSortByPosition(true);
      String fullText = stripper.getText(pdDocument);
      QuotePdfDocument document = stripper.toDocument(fileName, fullText, pdDocument.getNumberOfPages());
      if (!StringUtils.hasText(document.getFullText())) {
        throw new QuotePdfParseException("PDF_TEXT_EMPTY", "PDF 文本为空，当前阶段只支持文本型 PDF");
      }
      return document;
    } catch (QuotePdfParseException ex) {
      throw ex;
    } catch (IOException | RuntimeException ex) {
      throw new QuotePdfParseException("PDF_PARSE_FAILED", "PDF 解析失败: " + ex.getMessage(), ex);
    }
  }

  private static final class QuotePdfPositionStripper extends PDFTextStripper {
    private final Map<Integer, List<QuotePdfToken>> tokensByPage = new LinkedHashMap<>();

    private QuotePdfPositionStripper() throws IOException {
      super();
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      int pageIndex = getCurrentPageNo() - 1;
      tokensByPage.computeIfAbsent(pageIndex, ignored -> new ArrayList<>()).addAll(toTokens(pageIndex, textPositions));
      super.writeString(text, textPositions);
    }

    private QuotePdfDocument toDocument(String fileName, String fullText, int pageCount) {
      QuotePdfDocument document = new QuotePdfDocument();
      document.setFileName(fileName);
      document.setFullText(fullText == null ? "" : fullText);
      List<QuotePdfPage> pages = new ArrayList<>();
      for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
        QuotePdfPage page = new QuotePdfPage();
        page.setPageIndex(pageIndex);
        List<QuotePdfToken> tokens = new ArrayList<>(tokensByPage.getOrDefault(pageIndex, List.of()));
        tokens.sort(tokenComparator());
        page.setTokens(tokens);
        page.setLines(toLines(pageIndex, tokens));
        pages.add(page);
      }
      document.setPages(pages);
      return document;
    }

    private List<QuotePdfToken> toTokens(int pageIndex, List<TextPosition> textPositions) {
      List<QuotePdfToken> tokens = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      Bounds bounds = new Bounds();
      for (TextPosition position : textPositions) {
        String unicode = position.getUnicode();
        if (!StringUtils.hasText(unicode)) {
          flushToken(tokens, pageIndex, text, bounds);
          continue;
        }
        for (int i = 0; i < unicode.length(); i++) {
          char ch = unicode.charAt(i);
          if (Character.isWhitespace(ch)) {
            flushToken(tokens, pageIndex, text, bounds);
          } else {
            text.append(ch);
            bounds.include(position);
          }
        }
      }
      flushToken(tokens, pageIndex, text, bounds);
      return tokens;
    }

    private List<QuotePdfLine> toLines(int pageIndex, List<QuotePdfToken> tokens) {
      List<QuotePdfLine> lines = new ArrayList<>();
      List<QuotePdfToken> current = new ArrayList<>();
      Float currentY = null;
      for (QuotePdfToken token : tokens) {
        if (currentY == null || Math.abs(token.getY() - currentY) <= LINE_Y_TOLERANCE) {
          current.add(token);
          currentY = currentY == null ? token.getY() : Math.min(currentY, token.getY());
        } else {
          lines.add(toLine(pageIndex, current));
          current = new ArrayList<>();
          current.add(token);
          currentY = token.getY();
        }
      }
      if (!current.isEmpty()) {
        lines.add(toLine(pageIndex, current));
      }
      return lines;
    }

    private QuotePdfLine toLine(int pageIndex, List<QuotePdfToken> tokens) {
      tokens.sort(Comparator.comparing(QuotePdfToken::getX));
      QuotePdfLine line = new QuotePdfLine();
      line.setPageIndex(pageIndex);
      line.setTokens(new ArrayList<>(tokens));
      line.setText(joinText(tokens));
      float minX = Float.MAX_VALUE;
      float minY = Float.MAX_VALUE;
      float maxX = Float.MIN_VALUE;
      float maxY = Float.MIN_VALUE;
      for (QuotePdfToken token : tokens) {
        minX = Math.min(minX, token.getX());
        minY = Math.min(minY, token.getY());
        maxX = Math.max(maxX, token.getX() + token.getWidth());
        maxY = Math.max(maxY, token.getY() + token.getHeight());
      }
      line.setX(minX == Float.MAX_VALUE ? 0f : minX);
      line.setY(minY == Float.MAX_VALUE ? 0f : minY);
      line.setWidth(maxX == Float.MIN_VALUE ? 0f : maxX - line.getX());
      line.setHeight(maxY == Float.MIN_VALUE ? 0f : maxY - line.getY());
      return line;
    }

    private String joinText(List<QuotePdfToken> tokens) {
      StringBuilder builder = new StringBuilder();
      QuotePdfToken previous = null;
      for (QuotePdfToken token : tokens) {
        if (builder.length() > 0 && shouldInsertSpace(previous, token)) {
          builder.append(' ');
        }
        builder.append(token.getText());
        previous = token;
      }
      return builder.toString();
    }

    private boolean shouldInsertSpace(QuotePdfToken previous, QuotePdfToken current) {
      if (previous == null) {
        return false;
      }
      return current.getX() - (previous.getX() + previous.getWidth()) > 1.5f;
    }

    private void flushToken(
        List<QuotePdfToken> tokens, int pageIndex, StringBuilder text, Bounds bounds) {
      if (text.length() == 0) {
        bounds.clear();
        return;
      }
      QuotePdfToken token = new QuotePdfToken();
      token.setText(text.toString());
      token.setPageIndex(pageIndex);
      token.setX(bounds.minX == Float.MAX_VALUE ? 0f : bounds.minX);
      token.setY(bounds.minY == Float.MAX_VALUE ? 0f : bounds.minY);
      token.setWidth(bounds.maxX == Float.MIN_VALUE ? 0f : bounds.maxX - token.getX());
      token.setHeight(bounds.maxY == Float.MIN_VALUE ? 0f : bounds.maxY - token.getY());
      tokens.add(token);
      text.setLength(0);
      bounds.clear();
    }

    private Comparator<QuotePdfToken> tokenComparator() {
      return Comparator.comparing(QuotePdfToken::getY).thenComparing(QuotePdfToken::getX);
    }
  }

  private static final class Bounds {
    private float minX = Float.MAX_VALUE;
    private float minY = Float.MAX_VALUE;
    private float maxX = Float.MIN_VALUE;
    private float maxY = Float.MIN_VALUE;

    private void include(TextPosition position) {
      minX = Math.min(minX, position.getXDirAdj());
      minY = Math.min(minY, position.getYDirAdj());
      maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
      maxY = Math.max(maxY, position.getYDirAdj() + position.getHeightDir());
    }

    private void clear() {
      minX = Float.MAX_VALUE;
      minY = Float.MAX_VALUE;
      maxX = Float.MIN_VALUE;
      maxY = Float.MIN_VALUE;
    }
  }
}
