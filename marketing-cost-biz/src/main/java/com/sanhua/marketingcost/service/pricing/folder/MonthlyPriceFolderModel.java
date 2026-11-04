package com.sanhua.marketingcost.service.pricing.folder;

import com.sanhua.marketingcost.formula.workbook.OriginalPriceTable.RowNotice;
import com.sanhua.marketingcost.service.impl.OriginalLinkedWorkbookParser;
import com.sanhua.marketingcost.service.impl.OriginalRangeWorkbookParser;
import com.sanhua.marketingcost.service.pricing.MaterialPriceRuleKey;
import java.util.List;

/** 原始行与全部有效规则分别保留；前端只接收核对资料，不提供可执行公式。 */
public final class MonthlyPriceFolderModel {
  private MonthlyPriceFolderModel() {}
  public record Issue(String sheet, Integer row, String materialCode, String message) {}
  public record Presence(String orgCode, String materialCode, String supplierCode, String supplierName) {}
  public record ParsedFile(String kind, List<OriginalLinkedWorkbookParser.Rule> linked,
      List<OriginalRangeWorkbookParser.Rule> ranges, List<Issue> errors,
      List<RowNotice> notices, List<Presence> present, String message) {}
  public record Candidate(String id, Long fileId, String fileName, String sheet, int row,
      String kind, MaterialPriceRuleKey identity, String signature, String formula, Long sourceId, String sourceState) {}
  public record Group(String identityKey, MaterialPriceRuleKey identity, String status,
      int variantCount, List<Candidate> candidates) {}
  public record FileView(long id, String path, String kind, String status, String sha256,
      int rowCount, int validRows, int noPriceRows, List<Issue> errors,
      List<RowNotice> notices, String message) {}
  public record Summary(int sourceRows, int validRows, int noPriceRows, int errorRows,
      int ruleCount, int alternativeRuleCount, int importedRules, int duplicateRows, int pendingFiles) {}
  public record Report(long id, String businessUnitType, String pricingMonth, String status,
      long revision, Summary summary, List<FileView> files, List<Group> groups, Object completion) {}
  public static String rowKey(String sheet, int row) { return sheet + "!" + row; }
}
