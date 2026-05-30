package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("U9 裸品 / 非裸品判定兼容适配")
class U9ProductPackagingTypeResolverTest {

  @Test
  @DisplayName("新产品形态 BARE 适配为旧 NAKED_PRODUCT")
  void resolvesNakedProductByMainCategoryPrefix11() {
    QuoteProductTypeResolveService service = mock(QuoteProductTypeResolveService.class);
    when(service.resolve(" MAT-NAKED "))
        .thenReturn(result("MAT-NAKED", QuoteProductType.BARE, "110101"));

    U9ProductPackagingTypeResolver resolver = new U9ProductPackagingTypeResolver(service);
    U9ProductPackagingTypeResolver.Result result = resolver.resolve(" MAT-NAKED ");

    assertThat(result.productPackagingType()).isEqualTo(U9ProductPackagingTypeResolver.NAKED_PRODUCT);
    assertThat(result.mainCategoryCode()).isEqualTo("110101");
  }

  @Test
  @DisplayName("新产品形态 NON_BARE 适配为旧 PACKAGED_PRODUCT")
  void resolvesPackagedProductWhenMainCategoryDoesNotStartWith11() {
    QuoteProductTypeResolveService service = mock(QuoteProductTypeResolveService.class);
    when(service.resolve("MAT-PACKAGED"))
        .thenReturn(result("MAT-PACKAGED", QuoteProductType.NON_BARE, "120101"));

    U9ProductPackagingTypeResolver resolver = new U9ProductPackagingTypeResolver(service);
    U9ProductPackagingTypeResolver.Result result = resolver.resolve("MAT-PACKAGED");

    assertThat(result.productPackagingType()).isEqualTo(U9ProductPackagingTypeResolver.PACKAGED_PRODUCT);
    assertThat(result.mainCategoryCode()).isEqualTo("120101");
  }

  @Test
  @DisplayName("新产品形态 DATA_MISSING / UNKNOWN 适配为旧 UNKNOWN")
  void keepsUnknownWhenRawMissingOrMainCategoryBlank() {
    QuoteProductTypeResolveService service = mock(QuoteProductTypeResolveService.class);
    when(service.resolve("MAT-MISSING"))
        .thenReturn(result("MAT-MISSING", QuoteProductType.DATA_MISSING, null));
    when(service.resolve("MAT-BLANK"))
        .thenReturn(result("MAT-BLANK", QuoteProductType.UNKNOWN, null));
    when(service.resolve(" "))
        .thenReturn(result(null, QuoteProductType.UNKNOWN, null));

    U9ProductPackagingTypeResolver resolver = new U9ProductPackagingTypeResolver(service);

    assertThat(resolver.resolve("MAT-MISSING").productPackagingType())
        .isEqualTo(U9ProductPackagingTypeResolver.UNKNOWN);
    assertThat(resolver.resolve("MAT-BLANK").productPackagingType())
        .isEqualTo(U9ProductPackagingTypeResolver.UNKNOWN);
    assertThat(resolver.resolve(" ").productPackagingType())
        .isEqualTo(U9ProductPackagingTypeResolver.UNKNOWN);
  }

  private static QuoteProductTypeResolveResult result(
      String quoteProductCode, QuoteProductType productType, String mainCategoryCode) {
    return new QuoteProductTypeResolveResult(
        quoteProductCode, productType, mainCategoryCode, null, null, null, null);
  }
}
