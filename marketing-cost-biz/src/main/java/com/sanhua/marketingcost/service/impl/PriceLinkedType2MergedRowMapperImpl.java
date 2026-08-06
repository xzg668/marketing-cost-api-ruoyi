package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2RowMatchSummary;
import com.sanhua.marketingcost.dto.PriceLinkedType2StandardRow;
import com.sanhua.marketingcost.enums.PriceLinkedType2RowMatchStatus;
import com.sanhua.marketingcost.service.PriceLinkedType2MergedRowMapper;
import com.sanhua.marketingcost.service.PriceLinkedType2TextNormalizer;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PriceLinkedType2MergedRowMapperImpl
    implements PriceLinkedType2MergedRowMapper {

  private final PriceLinkedType2TextNormalizer textNormalizer;

  public PriceLinkedType2MergedRowMapperImpl(
      PriceLinkedType2TextNormalizer textNormalizer) {
    this.textNormalizer = textNormalizer;
  }

  @Override
  public List<PriceLinkedType2MergedRow> map(
      PriceLinkedType2RowMatchSummary matchSummary, YearMonth pricingMonth) {
    if (matchSummary == null) {
      throw new IllegalArgumentException("类型 2 匹配结果不能为空");
    }
    if (pricingMonth == null) {
      throw new IllegalArgumentException("核算月份不能为空");
    }

    List<PriceLinkedType2MergedRow> mergedRows = new ArrayList<>();
    for (PriceLinkedType2RowMatchResult result : matchSummary.getMatchedResults()) {
      mergedRows.add(mapMatched(result, pricingMonth));
    }
    return List.copyOf(mergedRows);
  }

  private PriceLinkedType2MergedRow mapMatched(
      PriceLinkedType2RowMatchResult result, YearMonth pricingMonth) {
    PriceLinkedType2ProductRow businessRow = result.getMatchedBusinessRow();
    PriceLinkedType2StandardRow standardRow = result.getMatchedStandardRow();
    boolean supplierFallback =
        result.getStatus() == PriceLinkedType2RowMatchStatus.MATCHED_SUPPLIER_FALLBACK;
    String businessUnit = field(standardRow, "组织", "业务单元", "事业部");
    String materialCode = businessRow.getMaterialCode();
    String supplierName = businessRow.getSupplierName();
    String supplierCode = standardRow.getSupplierCode();
    String pricingMonthText = pricingMonth.toString();
    YearMonth previousMonth = pricingMonth.minusMonths(1);
    String effectiveDate = supplierFallback
        ? previousMonth.atDay(1).toString()
        : field(standardRow, "生效日期");
    String expiryDate = supplierFallback
        ? previousMonth.atEndOfMonth().toString()
        : field(standardRow, "失效日期");
    String identityKey = String.join(
        " | ",
        textNormalizer.normalize(businessUnit),
        pricingMonthText,
        textNormalizer.normalize(materialCode),
        textNormalizer.normalize(supplierCode));

    return new PriceLinkedType2MergedRow(
        businessRow,
        standardRow,
        pricingMonthText,
        businessUnit,
        materialCode,
        supplierName,
        supplierCode,
        field(standardRow, "来源"),
        field(standardRow, "物料属性", "采购分类"),
        field(standardRow, "是否含税"),
        effectiveDate,
        expiryDate,
        identityKey,
        supplierFallback);
  }

  private String field(PriceLinkedType2StandardRow row, String... aliases) {
    for (String alias : aliases) {
      String normalizedAlias = textNormalizer.normalizeHeader(alias);
      for (PriceLinkedType2CellSnapshot cell : row.getCells()) {
        if (normalizedAlias.equals(textNormalizer.normalizeHeader(cell.getHeader()))) {
          return cell.getDisplayValue();
        }
      }
    }
    return null;
  }
}
