package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.FactorAdjustPriceMapper;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorAdjustTemplateExportService;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FactorAdjustTemplateExportServiceImpl implements FactorAdjustTemplateExportService {

  private static final String[] HEADERS = {
      "序号", "价表影响因素名称", "简称", "取价来源", "价格", "原价", "单位", "影响因素ID", "月度价格ID"
  };

  private final FactorIdentityMapper factorIdentityMapper;
  private final FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private final FactorAdjustBatchMapper factorAdjustBatchMapper;
  private final FactorAdjustPriceMapper factorAdjustPriceMapper;

  public FactorAdjustTemplateExportServiceImpl(
      FactorIdentityMapper factorIdentityMapper,
      FactorMonthlyPriceMapper factorMonthlyPriceMapper,
      FactorAdjustBatchMapper factorAdjustBatchMapper,
      FactorAdjustPriceMapper factorAdjustPriceMapper) {
    this.factorIdentityMapper = factorIdentityMapper;
    this.factorMonthlyPriceMapper = factorMonthlyPriceMapper;
    this.factorAdjustBatchMapper = factorAdjustBatchMapper;
    this.factorAdjustPriceMapper = factorAdjustPriceMapper;
  }

  @Override
  public byte[] exportTemplate(
      String pricingMonth, String businessUnitType, String keyword, Long adjustBatchId) {
    String month = required("pricingMonth", pricingMonth);
    String bu = required("businessUnitType", businessUnitType);
    List<TemplateRow> rows = adjustBatchId == null
        ? dailyRows(month, bu, keyword)
        : adjustBatchRows(month, bu, keyword, adjustBatchId);
    return writeWorkbook(month, rows);
  }

  private List<TemplateRow> dailyRows(String pricingMonth, String businessUnitType, String keyword) {
    var query = Wrappers.lambdaQuery(FactorIdentity.class)
        .eq(FactorIdentity::getBusinessUnitType, businessUnitType)
        .eq(FactorIdentity::getStatus, "ACTIVE");
    if (StringUtils.hasText(keyword)) {
      String like = keyword.trim();
      query.and(w -> w.like(FactorIdentity::getFactorSeqNo, like)
          .or().like(FactorIdentity::getFactorName, like)
          .or().like(FactorIdentity::getShortName, like)
          .or().like(FactorIdentity::getPriceSource, like));
    }
    query.orderByAsc(FactorIdentity::getFactorSeqNo)
        .orderByAsc(FactorIdentity::getId);

    List<TemplateRow> rows = new ArrayList<>();
    for (FactorIdentity identity : factorIdentityMapper.selectList(query)) {
      FactorMonthlyPrice monthlyPrice = factorMonthlyPriceMapper.selectOne(
          Wrappers.lambdaQuery(FactorMonthlyPrice.class)
              .eq(FactorMonthlyPrice::getFactorIdentityId, identity.getId())
              .eq(FactorMonthlyPrice::getPriceMonth, pricingMonth)
              .eq(FactorMonthlyPrice::getStatus, "ACTIVE")
              .last("LIMIT 1"));
      BigDecimal price = monthlyPrice == null ? null : monthlyPrice.getPrice();
      rows.add(new TemplateRow(
          identity.getFactorSeqNo(),
          identity.getFactorName(),
          identity.getShortName(),
          identity.getPriceSource(),
          price,
          price,
          null,
          identity.getId(),
          monthlyPrice == null ? null : monthlyPrice.getId()));
    }
    return rows;
  }

  private List<TemplateRow> adjustBatchRows(
      String pricingMonth, String businessUnitType, String keyword, Long adjustBatchId) {
    FactorAdjustBatch batch = factorAdjustBatchMapper.selectById(adjustBatchId);
    if (batch == null || Integer.valueOf(1).equals(batch.getDeleted())) {
      throw new IllegalArgumentException("adjustBatchId 不存在");
    }
    if (!pricingMonth.equals(normalize(batch.getPricingMonth()))
        || !businessUnitType.equals(normalize(batch.getBusinessUnitType()))) {
      throw new IllegalArgumentException("adjustBatchId 与 pricingMonth/businessUnitType 不一致");
    }
    List<FactorAdjustPrice> prices = factorAdjustPriceMapper.selectList(
        Wrappers.lambdaQuery(FactorAdjustPrice.class)
            .eq(FactorAdjustPrice::getAdjustBatchId, adjustBatchId)
            .ne(FactorAdjustPrice::getStatus, "FAILED")
            .eq(FactorAdjustPrice::getDeleted, 0)
            .orderByAsc(FactorAdjustPrice::getSourceRowNumber)
            .orderByAsc(FactorAdjustPrice::getId));
    String like = normalize(keyword).toLowerCase();
    return prices.stream()
        .filter(row -> matchesKeyword(row, like))
        .map(row -> new TemplateRow(
            row.getFactorSeqNo(),
            row.getFactorName(),
            row.getShortName(),
            row.getPriceSource(),
            row.getAdjustedPrice(),
            row.getOriginalPrice(),
            row.getUnit(),
            row.getFactorIdentityId(),
            row.getFactorMonthlyPriceId()))
        .toList();
  }

  private boolean matchesKeyword(FactorAdjustPrice row, String keyword) {
    if (!StringUtils.hasText(keyword)) {
      return true;
    }
    return contains(row.getFactorSeqNo(), keyword)
        || contains(row.getFactorName(), keyword)
        || contains(row.getShortName(), keyword)
        || contains(row.getPriceSource(), keyword);
  }

  private boolean contains(String value, String keyword) {
    return value != null && value.toLowerCase().contains(keyword);
  }

  private byte[] writeWorkbook(String pricingMonth, List<TemplateRow> rows) {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("影响因素调价模板");
      sheet.setColumnHidden(7, true);
      sheet.setColumnHidden(8, true);

      Row title = sheet.createRow(0);
      title.createCell(0).setCellValue(pricingMonth + " 调价模板");

      CellStyle headerStyle = workbook.createCellStyle();
      Font headerFont = workbook.createFont();
      headerFont.setBold(true);
      headerStyle.setFont(headerFont);
      Row header = sheet.createRow(1);
      for (int i = 0; i < HEADERS.length; i++) {
        Cell cell = header.createCell(i);
        cell.setCellValue(HEADERS[i]);
        cell.setCellStyle(headerStyle);
      }

      int rowIndex = 2;
      for (TemplateRow row : rows) {
        writeRow(sheet.createRow(rowIndex++), row);
      }
      for (int i = 0; i <= 6; i++) {
        sheet.autoSizeColumn(i);
      }
      workbook.write(out);
      return out.toByteArray();
    } catch (Exception e) {
      throw new IllegalStateException("导出月度调价模板失败: " + e.getMessage(), e);
    }
  }

  private void writeRow(Row excelRow, TemplateRow row) {
    text(excelRow, 0, row.factorSeqNo());
    text(excelRow, 1, row.factorName());
    text(excelRow, 2, row.shortName());
    text(excelRow, 3, row.priceSource());
    decimal(excelRow, 4, row.price());
    decimal(excelRow, 5, row.originalPrice());
    text(excelRow, 6, row.unit());
    number(excelRow, 7, row.factorIdentityId());
    number(excelRow, 8, row.factorMonthlyPriceId());
  }

  private void text(Row row, int column, String value) {
    if (StringUtils.hasText(value)) {
      row.createCell(column).setCellValue(value);
    }
  }

  private void decimal(Row row, int column, BigDecimal value) {
    if (value != null) {
      row.createCell(column).setCellValue(value.doubleValue());
    }
  }

  private void number(Row row, int column, Long value) {
    if (value != null) {
      row.createCell(column).setCellValue(value);
    }
  }

  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  private record TemplateRow(
      String factorSeqNo,
      String factorName,
      String shortName,
      String priceSource,
      BigDecimal price,
      BigDecimal originalPrice,
      String unit,
      Long factorIdentityId,
      Long factorMonthlyPriceId) {
  }
}
