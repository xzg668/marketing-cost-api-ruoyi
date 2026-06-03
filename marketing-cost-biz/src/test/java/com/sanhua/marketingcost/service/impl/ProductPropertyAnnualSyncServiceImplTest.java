package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRow;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductPropertyAnnualSyncServiceImplTest {

  @Test
  @DisplayName("产品属性系数：标准品自动为 1，不取导入系数")
  void coefficientStandardProductIsOne() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ProductProperty.class))).thenReturn(1);

    ProductPropertyAnnualSyncServiceImpl service = new ProductPropertyAnnualSyncServiceImpl(mapper);
    ProductPropertyAnnualSyncResult result = service.sync(request(row("P-STD", "标准品", null, "9.9900")));

    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getRecords().get(0).getCoefficient()).isEqualByComparingTo("1.0000");
  }

  @Test
  @DisplayName("产品属性系数：非标准品年用量不超过 100000 自动为 1.05")
  void coefficientNonStandardProductIsOnePointZeroFive() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ProductProperty.class))).thenReturn(1);

    ProductPropertyAnnualSyncServiceImpl service = new ProductPropertyAnnualSyncServiceImpl(mapper);
    ProductPropertyAnnualSyncResult result =
        service.sync(request(row("P-NON-STD", "非标准品", "100000", null)));

    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getRecords().get(0).getCoefficient()).isEqualByComparingTo("1.0500");
  }

  @Test
  @DisplayName("产品属性系数：非标准品年用量大于 100000 自动回到 1")
  void coefficientHighUsageNonStandardProductIsOne() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ProductProperty.class))).thenReturn(1);

    ProductPropertyAnnualSyncServiceImpl service = new ProductPropertyAnnualSyncServiceImpl(mapper);
    ProductPropertyAnnualSyncResult result =
        service.sync(request(row("P-HIGH-USAGE", "非标准品", "100000.01", null)));

    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getRecords().get(0).getCoefficient()).isEqualByComparingTo("1.0000");
  }

  @Test
  @DisplayName("产品属性去重：缺产品料号不再按名称型号规格兜底匹配")
  void missingProductCodeDoesNotFallbackMatch() {
    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    ProductPropertyAnnualSyncRequest request = request(row(null, "标准品", null, null));

    ProductPropertyAnnualSyncServiceImpl service = new ProductPropertyAnnualSyncServiceImpl(mapper);
    ProductPropertyAnnualSyncResult result = service.sync(request);

    assertThat(result.getErrors()).isEqualTo(1);
    assertThat(result.getErrorMessages()).containsExactly("当前行缺产品料号");
    verify(mapper, never()).selectOne(any(Wrapper.class));
    verify(mapper, never()).insert(any(ProductProperty.class));
  }

  @Test
  @DisplayName("产品属性系数：OA 年用量更新后按既有产品属性重算")
  void usageOnlySyncRecalculatesCoefficient() {
    ProductProperty existing = new ProductProperty();
    existing.setId(1L);
    existing.setBusinessUnitType("COMMERCIAL");
    existing.setPropertyYear(2026);
    existing.setProductCode("P-OA");
    existing.setParentCode("P-OA");
    existing.setLevel1Code("四通阀事业部");
    existing.setLevel1Name("四通阀事业部");
    existing.setProductName("非标测试");
    existing.setParentName("非标测试");
    existing.setProductAttr("非标品");
    existing.setCoefficient(new BigDecimal("1.0500"));

    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(mapper.updateById(any(ProductProperty.class))).thenReturn(1);

    ProductPropertyAnnualSyncRequest request = new ProductPropertyAnnualSyncRequest();
    request.setPropertyYear(2026);
    request.setBusinessUnitType("COMMERCIAL");
    request.setUsageOnly(true);
    request.setRequireProductCode(true);
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setProductCode("P-OA");
    row.setAnnualUsage(new BigDecimal("100001"));
    request.setRows(List.of(row));

    ProductPropertyAnnualSyncServiceImpl service = new ProductPropertyAnnualSyncServiceImpl(mapper);
    ProductPropertyAnnualSyncResult result = service.sync(request);

    assertThat(result.getUpdated()).isEqualTo(1);
    assertThat(result.getRecords().get(0).getCoefficient()).isEqualByComparingTo("1.0000");
    ArgumentCaptor<ProductProperty> captor = ArgumentCaptor.forClass(ProductProperty.class);
    verify(mapper).updateById(captor.capture());
    assertThat(captor.getValue().getCoefficient()).isEqualByComparingTo("1.0000");
  }

  @Test
  @DisplayName("OA年用量同步：已有占位记录的待维护字段可被后续OA信息补齐")
  void usageOnlySyncFillsExistingPlaceholderFields() {
    ProductProperty existing = new ProductProperty();
    existing.setId(2L);
    existing.setBusinessUnitType("COMMERCIAL");
    existing.setPropertyYear(2026);
    existing.setProductCode("P-OA-PLACEHOLDER");
    existing.setParentCode("P-OA-PLACEHOLDER");
    existing.setLevel1Code("OA_PLACEHOLDER");
    existing.setLevel1Name("待维护");
    existing.setBusinessDivision("待维护");
    existing.setProductName("待维护");
    existing.setParentName("待维护");
    existing.setProductAttr("待维护");

    ProductPropertyMapper mapper = mock(ProductPropertyMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(mapper.updateById(any(ProductProperty.class))).thenReturn(1);

    ProductPropertyAnnualSyncRequest request = new ProductPropertyAnnualSyncRequest();
    request.setPropertyYear(2026);
    request.setBusinessUnitType("COMMERCIAL");
    request.setUsageOnly(true);
    request.setRequireProductCode(true);
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setProductCode("P-OA-PLACEHOLDER");
    row.setBusinessDivision("商用部品事业部");
    row.setLevel1Name("商用部品事业部");
    row.setProductName("料品档案产品名称");
    row.setAnnualUsage(new BigDecimal("12300"));
    request.setRows(List.of(row));

    ProductPropertyAnnualSyncServiceImpl service = new ProductPropertyAnnualSyncServiceImpl(mapper);
    ProductPropertyAnnualSyncResult result = service.sync(request);

    assertThat(result.getUpdated()).isEqualTo(1);
    ArgumentCaptor<ProductProperty> captor = ArgumentCaptor.forClass(ProductProperty.class);
    verify(mapper).updateById(captor.capture());
    assertThat(captor.getValue().getBusinessDivision()).isEqualTo("商用部品事业部");
    assertThat(captor.getValue().getLevel1Name()).isEqualTo("商用部品事业部");
    assertThat(captor.getValue().getProductName()).isEqualTo("料品档案产品名称");
    assertThat(captor.getValue().getAnnualUsage()).isEqualByComparingTo("12300");
  }

  private ProductPropertyAnnualSyncRequest request(ProductPropertyAnnualSyncRow row) {
    ProductPropertyAnnualSyncRequest request = new ProductPropertyAnnualSyncRequest();
    request.setPropertyYear(2026);
    request.setBusinessUnitType("COMMERCIAL");
    request.setAttrSourceType("TECH_IMPORT");
    request.setAnnualUsageSourceType("TECH_IMPORT");
    request.setRows(List.of(row));
    return request;
  }

  private ProductPropertyAnnualSyncRow row(
      String productCode, String productAttr, String annualUsage, String importedCoefficient) {
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setBusinessDivision("四通阀事业部");
    row.setProductCode(productCode);
    row.setProductName("测试产品");
    row.setProductModel("MODEL");
    row.setProductSpec("SPEC");
    row.setProductAttr(productAttr);
    if (annualUsage != null) {
      row.setAnnualUsage(new BigDecimal(annualUsage));
    }
    if (importedCoefficient != null) {
      row.setCoefficient(new BigDecimal(importedCoefficient));
    }
    return row;
  }
}
