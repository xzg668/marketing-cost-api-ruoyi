package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("报价产品裸品 / 非裸品识别服务")
class QuoteProductTypeResolveServiceImplTest {

  @Test
  @DisplayName("main_category_code 以 11 开头判定为裸品，并返回主档字段")
  void resolvesBareProductByMainCategoryPrefix11() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("MAT-BARE", "110101", "自制", "裸品A", "规格A")));

    QuoteProductTypeResolveServiceImpl service = new QuoteProductTypeResolveServiceImpl(mapper);
    QuoteProductTypeResolveResult result = service.resolve(" MAT-BARE ");

    assertThat(result.quoteProductCode()).isEqualTo("MAT-BARE");
    assertThat(result.productType()).isEqualTo(QuoteProductType.BARE);
    assertThat(result.productTypeCode()).isEqualTo("BARE");
    assertThat(result.mainCategoryCode()).isEqualTo("110101");
    assertThat(result.shapeAttr()).isEqualTo("自制");
    assertThat(result.materialName()).isEqualTo("裸品A");
    assertThat(result.materialSpec()).isEqualTo("规格A");
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName("main_category_code 非 11 开头判定为非裸品")
  void resolvesNonBareProductWhenMainCategoryDoesNotStartWith11() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("MAT-FINISHED", "120101", "成品", "成品A", "规格B")));

    QuoteProductTypeResolveServiceImpl service = new QuoteProductTypeResolveServiceImpl(mapper);
    QuoteProductTypeResolveResult result = service.resolve("MAT-FINISHED");

    assertThat(result.productType()).isEqualTo(QuoteProductType.NON_BARE);
    assertThat(result.productTypeCode()).isEqualTo("NON_BARE");
    assertThat(result.mainCategoryCode()).isEqualTo("120101");
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  @DisplayName("查不到主档时返回 DATA_MISSING")
  void returnsDataMissingWhenMaterialMasterRawMissing() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull())).thenReturn(List.of());

    QuoteProductTypeResolveServiceImpl service = new QuoteProductTypeResolveServiceImpl(mapper);
    QuoteProductTypeResolveResult result = service.resolve("MAT-MISSING");

    assertThat(result.quoteProductCode()).isEqualTo("MAT-MISSING");
    assertThat(result.productType()).isEqualTo(QuoteProductType.DATA_MISSING);
    assertThat(result.errorMessage()).contains("料品主档缺失");
  }

  @Test
  @DisplayName("main_category_code 为空时返回 UNKNOWN，并保留可展示主档字段")
  void returnsUnknownWhenMainCategoryBlank() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("MAT-BLANK", " ", "采购", "料品B", "规格B")));

    QuoteProductTypeResolveServiceImpl service = new QuoteProductTypeResolveServiceImpl(mapper);
    QuoteProductTypeResolveResult result = service.resolve("MAT-BLANK");

    assertThat(result.productType()).isEqualTo(QuoteProductType.UNKNOWN);
    assertThat(result.mainCategoryCode()).isNull();
    assertThat(result.shapeAttr()).isEqualTo("采购");
    assertThat(result.materialName()).isEqualTo("料品B");
    assertThat(result.errorMessage()).contains("main_category_code 为空");
  }

  @Test
  @DisplayName("批量查询去重访问主档，并按入参顺序回填重复料号和空料号")
  void batchResolveDeduplicatesLookupAndBackfillsByInputOrder() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(
            raw("MAT-BARE", "110101", "自制", "裸品A", "规格A"),
            raw("MAT-FINISHED", "120101", "成品", "成品A", "规格B")));

    QuoteProductTypeResolveServiceImpl service = new QuoteProductTypeResolveServiceImpl(mapper);
    List<QuoteProductTypeResolveResult> results =
        service.batchResolve(List.of(" MAT-BARE ", "MAT-FINISHED", "MAT-BARE", " ", "MAT-MISS"));

    assertThat(results).extracting(QuoteProductTypeResolveResult::quoteProductCode)
        .containsExactly("MAT-BARE", "MAT-FINISHED", "MAT-BARE", null, "MAT-MISS");
    assertThat(results).extracting(QuoteProductTypeResolveResult::productType)
        .containsExactly(
            QuoteProductType.BARE,
            QuoteProductType.NON_BARE,
            QuoteProductType.BARE,
            QuoteProductType.UNKNOWN,
            QuoteProductType.DATA_MISSING);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
    verify(mapper).selectByLatestBatchAndCodes(captor.capture(), isNull());
    assertThat(captor.getValue()).containsExactly("MAT-BARE", "MAT-FINISHED", "MAT-MISS");
  }

  private static MaterialMasterRaw raw(
      String materialCode,
      String mainCategoryCode,
      String shapeAttr,
      String materialName,
      String materialSpec) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode(materialCode);
    raw.setMainCategoryCode(mainCategoryCode);
    raw.setShapeAttr(shapeAttr);
    raw.setMaterialName(materialName);
    raw.setMaterialSpec(materialSpec);
    raw.setImportBatchId("u9-latest");
    raw.setActiveFlag(1);
    return raw;
  }
}
