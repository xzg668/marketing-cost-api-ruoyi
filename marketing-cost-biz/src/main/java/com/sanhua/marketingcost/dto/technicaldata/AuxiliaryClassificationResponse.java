package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

public record AuxiliaryClassificationResponse(Long oaFormItemId, String accountingMonth,
    Long technicalVersionId, String status, String message, boolean canClassify,
    String contentFingerprint, List<Item> items, List<Subject> subjects, List<Total> totals) {
  public record Item(Long detailId, String materialNo, String name, String amount,
      String subjectCode, String subjectName, List<String> approvedColumns) {}
  public record Subject(String code, String name) {}
  public record Total(String subjectCode, String subjectName, String amount) {}
  public record Row(int sheetRow, List<String> columns) {}
  public record Issue(Integer row, String message) {}
  public record Preview(boolean valid, String fingerprint, List<Issue> issues, List<Total> totals) {}
}
