package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("U9 裸品 / 非裸品判定")
class U9ProductPackagingTypeResolverTest {

  @Test
  @DisplayName("main_category_code 前两位为 11 判定为裸品")
  void resolvesNakedProductByMainCategoryPrefix11() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("MAT-NAKED", "110101")));

    U9ProductPackagingTypeResolver resolver = new U9ProductPackagingTypeResolver(mapper);
    U9ProductPackagingTypeResolver.Result result = resolver.resolve(" MAT-NAKED ");

    assertThat(result.productPackagingType()).isEqualTo(U9ProductPackagingTypeResolver.NAKED_PRODUCT);
    assertThat(result.mainCategoryCode()).isEqualTo("110101");
    verify(mapper).selectByLatestBatchAndCodes(List.of("MAT-NAKED"), null);
  }

  @Test
  @DisplayName("main_category_code 前两位不是 11 判定为非裸品")
  void resolvesPackagedProductWhenMainCategoryDoesNotStartWith11() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(any(), isNull()))
        .thenReturn(List.of(raw("MAT-PACKAGED", "120101")));

    U9ProductPackagingTypeResolver resolver = new U9ProductPackagingTypeResolver(mapper);
    U9ProductPackagingTypeResolver.Result result = resolver.resolve("MAT-PACKAGED");

    assertThat(result.productPackagingType()).isEqualTo(U9ProductPackagingTypeResolver.PACKAGED_PRODUCT);
    assertThat(result.mainCategoryCode()).isEqualTo("120101");
  }

  @Test
  @DisplayName("查不到 raw 或 main_category_code 为空时保持 UNKNOWN")
  void keepsUnknownWhenRawMissingOrMainCategoryBlank() {
    MaterialMasterRawMapper mapper = mock(MaterialMasterRawMapper.class);
    when(mapper.selectByLatestBatchAndCodes(List.of("MAT-MISSING"), null)).thenReturn(List.of());
    when(mapper.selectByLatestBatchAndCodes(List.of("MAT-BLANK"), null))
        .thenReturn(List.of(raw("MAT-BLANK", " ")));

    U9ProductPackagingTypeResolver resolver = new U9ProductPackagingTypeResolver(mapper);

    assertThat(resolver.resolve("MAT-MISSING").productPackagingType())
        .isEqualTo(U9ProductPackagingTypeResolver.UNKNOWN);
    assertThat(resolver.resolve("MAT-BLANK").productPackagingType())
        .isEqualTo(U9ProductPackagingTypeResolver.UNKNOWN);
    assertThat(resolver.resolve(" ").productPackagingType())
        .isEqualTo(U9ProductPackagingTypeResolver.UNKNOWN);
    verify(mapper, never()).selectByLatestBatchAndCodes(List.of(""), null);
  }

  private static MaterialMasterRaw raw(String materialCode, String mainCategoryCode) {
    MaterialMasterRaw raw = new MaterialMasterRaw();
    raw.setMaterialCode(materialCode);
    raw.setMainCategoryCode(mainCategoryCode);
    raw.setImportBatchId("u9-latest");
    raw.setActiveFlag(1);
    return raw;
  }
}
