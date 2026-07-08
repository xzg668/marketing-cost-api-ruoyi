package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import org.springframework.stereotype.Component;

/** @deprecated Use {@link QuoteProductTypeResolveService} for new BOM preparation flows. */
@Deprecated(forRemoval = false)
@Component
public class U9ProductPackagingTypeResolver {
  public static final String NAKED_PRODUCT = "NAKED_PRODUCT";
  public static final String PACKAGED_PRODUCT = "PACKAGED_PRODUCT";
  public static final String UNKNOWN = "UNKNOWN";

  private final QuoteProductTypeResolveService quoteProductTypeResolveService;

  public U9ProductPackagingTypeResolver(
      QuoteProductTypeResolveService quoteProductTypeResolveService) {
    this.quoteProductTypeResolveService = quoteProductTypeResolveService;
  }

  public Result resolve(String materialCode) {
    throw new IllegalArgumentException("产品包装类型识别必须由上游显式传入料品组织");
  }

  public Result resolve(String materialCode, String organizationCode) {
    QuoteProductTypeResolveResult result =
        quoteProductTypeResolveService.resolve(materialCode, organizationCode);
    if (QuoteProductType.BARE.equals(result.productType())) {
      return new Result(NAKED_PRODUCT, result.mainCategoryCode());
    }
    if (QuoteProductType.NON_BARE.equals(result.productType())) {
      return new Result(PACKAGED_PRODUCT, result.mainCategoryCode());
    }
    return Result.unknown(result.mainCategoryCode());
  }

  public record Result(String productPackagingType, String mainCategoryCode) {
    public static Result unknown(String mainCategoryCode) {
      return new Result(UNKNOWN, mainCategoryCode);
    }
  }
}
