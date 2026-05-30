package com.sanhua.marketingcost.service.ingest;

import java.util.List;

public class QuoteOaPdfTableDefinition {
  private final List<String> startAnchors;
  private final List<String> endAnchors;
  private final List<QuoteOaPdfFieldDefinition> columns;

  public QuoteOaPdfTableDefinition(
      List<String> startAnchors, List<String> endAnchors, List<QuoteOaPdfFieldDefinition> columns) {
    this.startAnchors = startAnchors == null ? List.of() : List.copyOf(startAnchors);
    this.endAnchors = endAnchors == null ? List.of() : List.copyOf(endAnchors);
    this.columns = columns == null ? List.of() : List.copyOf(columns);
  }

  public List<String> getStartAnchors() {
    return startAnchors;
  }

  public List<String> getEndAnchors() {
    return endAnchors;
  }

  public List<QuoteOaPdfFieldDefinition> getColumns() {
    return columns;
  }
}
