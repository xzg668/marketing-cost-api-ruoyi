package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.quotebom.QuoteProductTypeResolveResult;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class QuoteProductTypeResolveServiceImpl implements QuoteProductTypeResolveService {

  static final String BARE_MAIN_CATEGORY_PREFIX = "11";

  private final MaterialMasterRawMapper materialMasterRawMapper;

  public QuoteProductTypeResolveServiceImpl(MaterialMasterRawMapper materialMasterRawMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public QuoteProductTypeResolveResult resolve(String quoteProductCode) {
    return resolve(quoteProductCode, MaterialOrganization.COMMERCIAL.getCode());
  }

  @Override
  public QuoteProductTypeResolveResult resolve(String quoteProductCode, String organizationCode) {
    List<QuoteProductTypeResolveResult> results =
        batchResolve(Collections.singletonList(quoteProductCode), organizationCode);
    return results.isEmpty() ? missingInput(null) : results.get(0);
  }

  @Override
  public List<QuoteProductTypeResolveResult> batchResolve(Collection<String> quoteProductCodes) {
    return batchResolve(quoteProductCodes, MaterialOrganization.COMMERCIAL.getCode());
  }

  @Override
  public List<QuoteProductTypeResolveResult> batchResolve(
      Collection<String> quoteProductCodes, String organizationCode) {
    if (quoteProductCodes == null || quoteProductCodes.isEmpty()) {
      return Collections.emptyList();
    }

    List<String> requestedCodes = new ArrayList<>(quoteProductCodes.size());
    Set<String> uniqueCodes = new LinkedHashSet<>();
    for (String quoteProductCode : quoteProductCodes) {
      String code = trimToNull(quoteProductCode);
      requestedCodes.add(code);
      if (code != null) {
        uniqueCodes.add(code);
      }
    }

    Map<String, QuoteProductTypeResolveResult> resolvedByCode =
        resolveUniqueCodes(uniqueCodes, organizationCode);
    List<QuoteProductTypeResolveResult> results = new ArrayList<>(requestedCodes.size());
    for (String code : requestedCodes) {
      if (code == null) {
        results.add(missingInput(null));
      } else {
        results.add(resolvedByCode.getOrDefault(code, dataMissing(code)));
      }
    }
    return results;
  }

  private Map<String, QuoteProductTypeResolveResult> resolveUniqueCodes(
      Set<String> uniqueCodes, String organizationCode) {
    Map<String, QuoteProductTypeResolveResult> result = new LinkedHashMap<>();
    if (uniqueCodes.isEmpty()) {
      return result;
    }
    for (String code : uniqueCodes) {
      result.put(code, dataMissing(code));
    }

    List<MaterialMasterRaw> rows =
        selectRawRows(uniqueCodes, organizationCode);
    if (rows == null || rows.isEmpty()) {
      return result;
    }
    for (MaterialMasterRaw row : rows) {
      String code = row == null ? null : trimToNull(row.getMaterialCode());
      if (code == null || !result.containsKey(code)) {
        continue;
      }
      result.put(code, resolveFromRaw(code, row));
    }
    return result;
  }

  private List<MaterialMasterRaw> selectRawRows(Set<String> uniqueCodes, String organizationCode) {
    String organization = MaterialOrganization.normalize(organizationCode);
    if (MaterialOrganization.COMMERCIAL.getCode().equals(organization)) {
      return materialMasterRawMapper.selectByLatestBatchAndCodes(uniqueCodes, null);
    }
    return materialMasterRawMapper.selectByLatestBatchAndCodes(uniqueCodes, null, organization);
  }

  private QuoteProductTypeResolveResult resolveFromRaw(String quoteProductCode, MaterialMasterRaw row) {
    String mainCategoryCode = trimToNull(row.getMainCategoryCode());
    if (mainCategoryCode == null) {
      return new QuoteProductTypeResolveResult(
          quoteProductCode,
          QuoteProductType.UNKNOWN,
          null,
          trimToNull(row.getShapeAttr()),
          trimToNull(row.getMaterialName()),
          trimToNull(row.getMaterialSpec()),
          "料品主档 main_category_code 为空，无法判断裸品/非裸品");
    }
    QuoteProductType productType =
        mainCategoryCode.startsWith(BARE_MAIN_CATEGORY_PREFIX)
            ? QuoteProductType.BARE
            : QuoteProductType.NON_BARE;
    return new QuoteProductTypeResolveResult(
        quoteProductCode,
        productType,
        mainCategoryCode,
        trimToNull(row.getShapeAttr()),
        trimToNull(row.getMaterialName()),
        trimToNull(row.getMaterialSpec()),
        null);
  }

  private QuoteProductTypeResolveResult dataMissing(String quoteProductCode) {
    return new QuoteProductTypeResolveResult(
        quoteProductCode,
        QuoteProductType.DATA_MISSING,
        null,
        null,
        null,
        null,
        "料品主档缺失，无法判断裸品/非裸品");
  }

  private QuoteProductTypeResolveResult missingInput(String quoteProductCode) {
    return new QuoteProductTypeResolveResult(
        quoteProductCode,
        QuoteProductType.UNKNOWN,
        null,
        null,
        null,
        null,
        "报价产品料号为空，无法判断裸品/非裸品");
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
