package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.OaProductPropertyUsageSyncRequest;
import com.sanhua.marketingcost.dto.OaProductPropertyUsageSyncRow;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncRow;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.ProductPropertyAnnualSyncService;
import com.sanhua.marketingcost.service.ProductPropertyAnnualUsageService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProductPropertyAnnualUsageServiceImpl implements ProductPropertyAnnualUsageService {
  private static final BigDecimal OA_ANNUAL_USAGE_UNIT = new BigDecimal("10000");

  private final ProductPropertyAnnualSyncService annualSyncService;
  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;

  public ProductPropertyAnnualUsageServiceImpl(
      ProductPropertyAnnualSyncService annualSyncService,
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      MaterialMasterRawMapper materialMasterRawMapper) {
    this.annualSyncService = annualSyncService;
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public ProductPropertyAnnualSyncResult syncFromOaForm(OaForm form, List<OaFormItem> items) {
    return syncFromOaForm(form, items, null, null);
  }

  private ProductPropertyAnnualSyncResult syncFromOaForm(
      OaForm form, List<OaFormItem> items, Integer quoteYear, String businessUnitType) {
    ProductPropertyAnnualSyncRequest request = new ProductPropertyAnnualSyncRequest();
    request.setBusinessUnitType(firstText(businessUnitType, form == null ? null : form.getBusinessUnitType()));
    request.setAnnualUsageSourceType("OA_QUOTE_USAGE");
    request.setAnnualUsageSourceBatchNo(form == null ? null : form.getOaNo());
    request.setUsageOnly(true);
    request.setRequireProductCode(true);
    request.setCreatePlaceholderOnMissing(true);

    List<ProductPropertyAnnualSyncRow> rows = new ArrayList<>();
    if (items != null) {
      for (OaFormItem item : items) {
        rows.add(toRow(form, item, quoteYear, businessUnitType));
      }
    }
    request.setRows(rows);
    return annualSyncService.sync(request);
  }

  @Override
  public ProductPropertyAnnualSyncResult syncFromRequest(OaProductPropertyUsageSyncRequest source) {
    if (source != null
        && (source.getItems() == null || source.getItems().isEmpty())
        && StringUtils.hasText(source.getOaNo())) {
      return syncStoredOaForm(source);
    }

    ProductPropertyAnnualSyncRequest request = new ProductPropertyAnnualSyncRequest();
    request.setPropertyYear(source == null ? null : source.getQuoteYear());
    request.setBusinessUnitType(source == null ? null : source.getBusinessUnitType());
    request.setAnnualUsageSourceType("OA_QUOTE_USAGE");
    request.setAnnualUsageSourceBatchNo(source == null ? null : source.getOaNo());
    request.setUsageOnly(true);
    request.setRequireProductCode(true);
    request.setCreatePlaceholderOnMissing(true);

    List<ProductPropertyAnnualSyncRow> rows = new ArrayList<>();
    if (source != null && source.getItems() != null) {
      for (OaProductPropertyUsageSyncRow item : source.getItems()) {
        rows.add(toRow(source, item));
      }
    }
    request.setRows(rows);
    return annualSyncService.sync(request);
  }

  private ProductPropertyAnnualSyncResult syncStoredOaForm(OaProductPropertyUsageSyncRequest source) {
    ProductPropertyAnnualSyncResult result = new ProductPropertyAnnualSyncResult();
    OaForm form =
        oaFormMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, source.getOaNo().trim()));
    if (form == null) {
      result.addError("未找到 OA 报价单：" + source.getOaNo().trim());
      return result;
    }
    List<OaFormItem> items =
        oaFormItemMapper.selectList(
            Wrappers.lambdaQuery(OaFormItem.class)
                .eq(OaFormItem::getOaFormId, form.getId())
                .orderByAsc(OaFormItem::getSeq)
                .orderByAsc(OaFormItem::getId));
    return syncFromOaForm(form, items, source.getQuoteYear(), source.getBusinessUnitType());
  }

  private ProductPropertyAnnualSyncRow toRow(
      OaForm form, OaFormItem item, Integer quoteYear, String businessUnitType) {
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setRowNo(item == null ? null : item.getSeq());
    row.setBusinessUnitType(
        firstText(
            businessUnitType,
            item == null ? null : item.getBusinessUnitType(),
            form == null ? null : form.getBusinessUnitType()));
    row.setPropertyYear(quoteYear == null ? resolveQuoteYear(form, item) : quoteYear);
    String productCode = item == null ? null : item.getMaterialNo();
    MaterialMasterRaw material = lookupLatestMaterial(productCode);
    String division = resolveBusinessDivision(form);
    row.setBusinessDivision(division);
    row.setLevel1Name(division);
    row.setProductCode(productCode);
    row.setProductName(resolveProductName(item == null ? null : item.getProductName(), material));
    row.setProductModel(item == null ? null : item.getSunlModel());
    row.setProductSpec(item == null ? null : item.getSpec());
    row.setAnnualUsage(toActualAnnualUsage(item == null ? null : item.getAnnualVolume()));
    row.setAnnualUsageOaNo(form == null ? null : form.getOaNo());
    row.setAnnualUsageOaLineId(resolveLineId(item));
    return row;
  }

  private ProductPropertyAnnualSyncRow toRow(
      OaProductPropertyUsageSyncRequest source, OaProductPropertyUsageSyncRow item) {
    ProductPropertyAnnualSyncRow row = new ProductPropertyAnnualSyncRow();
    row.setRowNo(item == null ? null : item.getSeq());
    row.setBusinessUnitType(source == null ? null : source.getBusinessUnitType());
    row.setPropertyYear(source == null ? null : source.getQuoteYear());
    String productCode = item == null ? null : item.getProductCode();
    MaterialMasterRaw material = lookupLatestMaterial(productCode);
    row.setProductCode(productCode);
    row.setProductName(resolveProductName(null, material));
    row.setAnnualUsage(toActualAnnualUsage(item == null ? null : item.getAnnualUsage()));
    row.setAnnualUsageOaNo(source == null ? null : source.getOaNo());
    row.setAnnualUsageOaLineId(resolveLineId(item));
    return row;
  }

  private String resolveBusinessDivision(OaForm form) {
    if (form == null) {
      return null;
    }
    return firstText(
        sanitizeBusinessDivision(form.getSourceBusinessDivision()),
        sanitizeBusinessDivision(form.getApplicantUnit()));
  }

  private String resolveProductName(String oaProductName, MaterialMasterRaw material) {
    return firstText(oaProductName, material == null ? null : material.getMaterialName());
  }

  private BigDecimal toActualAnnualUsage(BigDecimal oaAnnualUsage) {
    return oaAnnualUsage == null ? null : oaAnnualUsage.multiply(OA_ANNUAL_USAGE_UNIT);
  }

  private String sanitizeBusinessDivision(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    String cleaned = removeLeadingLabel(trimmed, "事业部");
    cleaned = removeLeadingParentheticalNotes(cleaned);
    return trimToNull(removeLeadingLabel(cleaned, "事业部"));
  }

  private String removeLeadingLabel(String value, String label) {
    String trimmed = trimToNull(value);
    if (trimmed == null || !trimmed.startsWith(label)) {
      return trimmed;
    }
    return trimToNull(trimmed.substring(label.length()));
  }

  private String removeLeadingParentheticalNotes(String value) {
    String result = trimToNull(value);
    while (result != null && (result.startsWith("（") || result.startsWith("("))) {
      char close = result.startsWith("（") ? '）' : ')';
      int closeIndex = result.indexOf(close);
      if (closeIndex < 0) {
        return null;
      }
      result = trimToNull(result.substring(closeIndex + 1));
    }
    return result;
  }

  private MaterialMasterRaw lookupLatestMaterial(String productCode) {
    if (!StringUtils.hasText(productCode)) {
      return null;
    }
    List<MaterialMasterRaw> rows =
        materialMasterRawMapper.selectByLatestBatchAndCodes(List.of(productCode.trim()), null);
    return first(rows);
  }

  private MaterialMasterRaw first(Collection<MaterialMasterRaw> rows) {
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    return rows.iterator().next();
  }

  private Integer resolveQuoteYear(OaForm form, OaFormItem item) {
    LocalDate validDate = item == null ? null : item.getValidDate();
    if (validDate != null) {
      return validDate.getYear();
    }
    LocalDate applyDate = form == null ? null : form.getApplyDate();
    if (applyDate != null) {
      return applyDate.getYear();
    }
    return Year.now().getValue();
  }

  private String resolveLineId(OaFormItem item) {
    if (item == null) {
      return null;
    }
    if (StringUtils.hasText(item.getExternalLineId())) {
      return item.getExternalLineId().trim();
    }
    if (item.getId() != null) {
      return String.valueOf(item.getId());
    }
    return item.getSeq() == null ? null : "SEQ-" + item.getSeq();
  }

  private String resolveLineId(OaProductPropertyUsageSyncRow item) {
    if (item == null) {
      return null;
    }
    if (StringUtils.hasText(item.getOaLineId())) {
      return item.getOaLineId().trim();
    }
    return item.getSeq() == null ? null : "SEQ-" + item.getSeq();
  }

  private String firstText(String... values) {
    if (values == null) {
      return null;
    }
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
