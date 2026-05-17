package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FactorAdjustExcelParseResult;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.FactorAdjustPriceMapper;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactorAdjustTemplateExportServiceImplTest {

  private FactorIdentityMapper factorIdentityMapper;
  private FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private FactorAdjustBatchMapper factorAdjustBatchMapper;
  private FactorAdjustPriceMapper factorAdjustPriceMapper;
  private FactorAdjustTemplateExportServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, FactorAdjustBatch.class);
    TableInfoHelper.initTableInfo(assistant, FactorAdjustPrice.class);
  }

  @BeforeEach
  void setUp() {
    factorIdentityMapper = mock(FactorIdentityMapper.class);
    factorMonthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    factorAdjustBatchMapper = mock(FactorAdjustBatchMapper.class);
    factorAdjustPriceMapper = mock(FactorAdjustPriceMapper.class);
    service = new FactorAdjustTemplateExportServiceImpl(
        factorIdentityMapper, factorMonthlyPriceMapper, factorAdjustBatchMapper, factorAdjustPriceMapper);
  }

  @Test
  @DisplayName("exportTemplate：日常报价模板列名顺序正确，隐藏 ID 可被调价解析服务读回")
  void exportDailyTemplateCanBeParsedBack() throws Exception {
    FactorIdentity identity = identity(191L);
    FactorMonthlyPrice monthlyPrice = monthlyPrice(501L, 191L, "2026-05", "18.7929");
    when(factorIdentityMapper.selectList(any(Wrapper.class))).thenReturn(List.of(identity));
    when(factorMonthlyPriceMapper.selectOne(any(Wrapper.class))).thenReturn(monthlyPrice);

    byte[] bytes = service.exportTemplate("2026-05", "COMMERCIAL", "锰", null);

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      Sheet sheet = workbook.getSheetAt(0);
      Row header = sheet.getRow(1);
      assertThat(header.getCell(0).getStringCellValue()).isEqualTo("序号");
      assertThat(header.getCell(1).getStringCellValue()).isEqualTo("价表影响因素名称");
      assertThat(header.getCell(2).getStringCellValue()).isEqualTo("简称");
      assertThat(header.getCell(3).getStringCellValue()).isEqualTo("取价来源");
      assertThat(header.getCell(4).getStringCellValue()).isEqualTo("价格");
      assertThat(header.getCell(5).getStringCellValue()).isEqualTo("原价");
      assertThat(header.getCell(6).getStringCellValue()).isEqualTo("单位");
      assertThat(header.getCell(7).getStringCellValue()).isEqualTo("影响因素ID");
      assertThat(header.getCell(8).getStringCellValue()).isEqualTo("月度价格ID");
      assertThat(sheet.isColumnHidden(7)).isTrue();
      assertThat(sheet.isColumnHidden(8)).isTrue();
      assertThat(sheet.getRow(2).getCell(4).getNumericCellValue()).isEqualTo(18.7929d);
      assertThat(sheet.getRow(2).getCell(5).getNumericCellValue()).isEqualTo(18.7929d);
    }

    when(factorIdentityMapper.selectById(191L)).thenReturn(identity);
    when(factorMonthlyPriceMapper.selectById(501L)).thenReturn(monthlyPrice);
    FactorAdjustExcelParseServiceImpl parser =
        new FactorAdjustExcelParseServiceImpl(factorIdentityMapper, factorMonthlyPriceMapper);
    FactorAdjustExcelParseResult parsed = parser.parse(
        new ByteArrayInputStream(bytes), "factor-adjust-template.xlsx", "2026-05", "COMMERCIAL");

    assertThat(parsed.getMatchedCount()).isEqualTo(1);
    assertThat(parsed.getRows().getFirst().getFactorIdentityId()).isEqualTo(191L);
    assertThat(parsed.getRows().getFirst().getFactorMonthlyPriceId()).isEqualTo(501L);
    assertThat(parsed.getRows().getFirst().getPrice()).isEqualByComparingTo("18.7929");
  }

  @Test
  @DisplayName("exportTemplate：传 adjustBatchId 时导出批次调价价")
  void exportAdjustBatchTemplate() throws Exception {
    when(factorAdjustBatchMapper.selectById(9001L)).thenReturn(batch(9001L));
    when(factorAdjustPriceMapper.selectList(any(Wrapper.class))).thenReturn(List.of(adjustPrice()));

    byte[] bytes = service.exportTemplate("2026-05", "COMMERCIAL", null, 9001L);

    try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
      Row data = workbook.getSheetAt(0).getRow(2);
      assertThat(data.getCell(0).getStringCellValue()).isEqualTo("15");
      assertThat(data.getCell(4).getNumericCellValue()).isEqualTo(19.1d);
      assertThat(data.getCell(5).getNumericCellValue()).isEqualTo(18.7929d);
      assertThat(data.getCell(7).getNumericCellValue()).isEqualTo(191d);
      assertThat(data.getCell(8).getNumericCellValue()).isEqualTo(501d);
    }
  }

  @Test
  @DisplayName("exportTemplate：必填月份和业务单元")
  void exportRequiresMonthAndBusinessUnit() {
    assertThatThrownBy(() -> service.exportTemplate("", "COMMERCIAL", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pricingMonth");
    assertThatThrownBy(() -> service.exportTemplate("2026-05", "", null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("businessUnitType");
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

  private FactorMonthlyPrice monthlyPrice(Long id, Long factorIdentityId, String month, String price) {
    FactorMonthlyPrice monthlyPrice = new FactorMonthlyPrice();
    monthlyPrice.setId(id);
    monthlyPrice.setFactorIdentityId(factorIdentityId);
    monthlyPrice.setPriceMonth(month);
    monthlyPrice.setPrice(new BigDecimal(price));
    monthlyPrice.setStatus("ACTIVE");
    return monthlyPrice;
  }

  private FactorAdjustBatch batch(Long id) {
    FactorAdjustBatch batch = new FactorAdjustBatch();
    batch.setId(id);
    batch.setPricingMonth("2026-05");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setDeleted(0);
    return batch;
  }

  private FactorAdjustPrice adjustPrice() {
    FactorAdjustPrice price = new FactorAdjustPrice();
    price.setId(8001L);
    price.setAdjustBatchId(9001L);
    price.setFactorIdentityId(191L);
    price.setFactorMonthlyPriceId(501L);
    price.setFactorSeqNo("15");
    price.setFactorName("上月16日-本月15日中华商务网长江现货市场1#锰平均价格");
    price.setShortName("1#Mn");
    price.setPriceSource("平均价");
    price.setOriginalPrice(new BigDecimal("18.7929"));
    price.setAdjustedPrice(new BigDecimal("19.10"));
    price.setUnit("公斤");
    price.setStatus("CHANGED");
    price.setDeleted(0);
    return price;
  }
}
