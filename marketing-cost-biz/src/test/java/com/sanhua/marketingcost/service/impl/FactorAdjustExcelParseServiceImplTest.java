package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactorAdjustExcelParseServiceImplTest {

  private FactorIdentityMapper factorIdentityMapper;
  private FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private FactorAdjustExcelParseServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
  }

  @BeforeEach
  void setUp() {
    factorIdentityMapper = mock(FactorIdentityMapper.class);
    factorMonthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    service = new FactorAdjustExcelParseServiceImpl(
        factorIdentityMapper, factorMonthlyPriceMapper);
  }

  @Test
  @DisplayName("parse：系统导出模板带隐藏 ID 时优先按影响因素 ID 匹配")
  void parseMatchesByHiddenSystemId() throws Exception {
    when(factorIdentityMapper.selectById(191L)).thenReturn(identity(191L));
    when(factorMonthlyPriceMapper.selectById(501L)).thenReturn(monthlyPrice(501L, 191L, "2026-05"));

    FactorAdjustExcelParseResult result = service.parse(
        new ByteArrayInputStream(workbook(true, "18.90")),
        "adjust-template.xlsx", "2026-05", "COMMERCIAL");

    assertThat(result.getTotalCount()).isEqualTo(1);
    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getFailedCount()).isZero();
    assertThat(result.getRows().getFirst().getFactorIdentityId()).isEqualTo(191L);
    assertThat(result.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(501L);
    assertThat(result.getRows().getFirst().getMatchMethod()).isEqualTo("SYSTEM_ID");
    assertThat(result.getRows().getFirst().getStatus()).isEqualTo("MATCHED");
    assertThat(result.getRows().getFirst().getPrice()).isEqualByComparingTo("18.9");

    verify(factorIdentityMapper, never()).selectList(any(Wrapper.class));
  }

  @Test
  @DisplayName("parse：财务自维护 Excel 没有隐藏 ID 时按身份字段匹配")
  void parseMatchesByIdentityFields() throws Exception {
    when(factorIdentityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(identity(191L)));
    when(factorMonthlyPriceMapper.selectOne(any(Wrapper.class)))
        .thenReturn(monthlyPrice(501L, 191L, "2026-05"));

    FactorAdjustExcelParseResult result = service.parse(
        new ByteArrayInputStream(workbook(false, "18.90")),
        "finance-adjust.xlsx", "2026-05", "COMMERCIAL");

    assertThat(result.getMatchedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getFactorIdentityId()).isEqualTo(191L);
    assertThat(result.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(501L);
    assertThat(result.getRows().getFirst().getMatchMethod()).isEqualTo("IDENTITY_FIELDS");
  }

  @Test
  @DisplayName("parse：没有匹配到已有影响因素身份时返回失败，不静默新增")
  void parseFailsWhenIdentityNotFound() throws Exception {
    when(factorIdentityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    FactorAdjustExcelParseResult result = service.parse(
        new ByteArrayInputStream(workbook(false, "18.90")),
        "finance-adjust.xlsx", "2026-05", "COMMERCIAL");

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getStatus()).isEqualTo("FAILED");
    assertThat(result.getRows().getFirst().getFailReason()).contains("未匹配到已有影响因素身份");
    verify(factorMonthlyPriceMapper, never()).selectOne(any(Wrapper.class));
  }

  @Test
  @DisplayName("parse：身份字段匹配到多条时返回冲突")
  void parseFailsWhenIdentityConflict() throws Exception {
    when(factorIdentityMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(identity(191L), identity(192L)));

    FactorAdjustExcelParseResult result = service.parse(
        new ByteArrayInputStream(workbook(false, "18.90")),
        "finance-adjust.xlsx", "2026-05", "COMMERCIAL");

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getConflictCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getFailReason()).contains("匹配到多条");
  }

  @Test
  @DisplayName("parse：价格为空或非法时返回失败，不查库匹配")
  void parseFailsWhenPriceInvalid() throws Exception {
    FactorAdjustExcelParseResult result = service.parse(
        new ByteArrayInputStream(workbook(false, "abc")),
        "finance-adjust.xlsx", "2026-05", "COMMERCIAL");

    assertThat(result.getMatchedCount()).isZero();
    assertThat(result.getFailedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getFailReason()).contains("价格");
    verify(factorIdentityMapper, never()).selectList(any(Wrapper.class));
    verify(factorIdentityMapper, never()).selectById(any());
  }

  private byte[] workbook(boolean withHiddenIds, String price) throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("影响因素");
      sheet.createRow(0).createCell(0).setCellValue("2026年5月参照基准");
      Row header = sheet.createRow(1);
      header.createCell(0).setCellValue("序号");
      header.createCell(1).setCellValue("价表影响因素名称");
      header.createCell(2).setCellValue("简称");
      header.createCell(3).setCellValue("取价来源");
      header.createCell(4).setCellValue("价格");
      header.createCell(5).setCellValue("原价");
      header.createCell(6).setCellValue("单位");
      if (withHiddenIds) {
        header.createCell(7).setCellValue("影响因素ID");
        header.createCell(8).setCellValue("月度价格ID");
        sheet.setColumnHidden(7, true);
        sheet.setColumnHidden(8, true);
      }

      Row row = sheet.createRow(2);
      row.createCell(0).setCellValue(15);
      row.createCell(1).setCellValue("上月16日-本月15日中华商务网长江现货市场1#锰平均价格");
      row.createCell(2).setCellValue("1#Mn");
      row.createCell(3).setCellValue("平均价");
      row.createCell(4).setCellValue(price);
      row.createCell(5).setCellValue(18.7929);
      row.createCell(6).setCellValue("公斤");
      if (withHiddenIds) {
        row.createCell(7).setCellValue(191);
        row.createCell(8).setCellValue(501);
      }
      wb.write(out);
      return out.toByteArray();
    }
  }

  private FactorIdentity identity(Long id) {
    FactorIdentity identity = new FactorIdentity();
    identity.setId(id);
    identity.setBusinessUnitType("COMMERCIAL");
    identity.setFactorSeqNo("15");
    identity.setFactorName("上月16日-本月15日中华商务网长江现货市场1#锰平均价格");
    identity.setShortName("1#Mn");
    identity.setPriceSource("平均价");
    identity.setStatus("ACTIVE");
    return identity;
  }

  private FactorMonthlyPrice monthlyPrice(Long id, Long factorIdentityId, String priceMonth) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(factorIdentityId);
    monthlyPrice.setPriceMonth(priceMonth);
    monthlyPrice.setPrice(new BigDecimal("18.7929"));
    monthlyPrice.setStatus("ACTIVE");
    return monthlyPrice;
  }
}
