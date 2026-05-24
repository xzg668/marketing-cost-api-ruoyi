package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class U9ProductPackagingTypeResolver {
  public static final String NAKED_PRODUCT = "NAKED_PRODUCT";
  public static final String PACKAGED_PRODUCT = "PACKAGED_PRODUCT";
  public static final String UNKNOWN = "UNKNOWN";

  private static final String NAKED_MAIN_CATEGORY_PREFIX = "11";

  private final MaterialMasterRawMapper materialMasterRawMapper;

  public U9ProductPackagingTypeResolver(MaterialMasterRawMapper materialMasterRawMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  public Result resolve(String materialCode) {
    String code = trimToNull(materialCode);
    if (code == null) {
      return Result.unknown(null);
    }

    // 裸品判断必须读取 raw 最新有效批次；缺料号或缺主分类时保持 UNKNOWN，不能默认归类。
    List<MaterialMasterRaw> rows = materialMasterRawMapper.selectByLatestBatchAndCodes(List.of(code), null);
    if (rows == null || rows.isEmpty()) {
      return Result.unknown(null);
    }
    String mainCategoryCode = trimToNull(rows.get(0).getMainCategoryCode());
    if (mainCategoryCode == null) {
      return Result.unknown(null);
    }
    if (mainCategoryCode.startsWith(NAKED_MAIN_CATEGORY_PREFIX)) {
      return new Result(NAKED_PRODUCT, mainCategoryCode);
    }
    return new Result(PACKAGED_PRODUCT, mainCategoryCode);
  }

  private static String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  public record Result(String productPackagingType, String mainCategoryCode) {
    public static Result unknown(String mainCategoryCode) {
      return new Result(UNKNOWN, mainCategoryCode);
    }
  }
}
