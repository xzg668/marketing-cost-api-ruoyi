package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 报价 BOM 上下文的唯一权威解析入口。
 *
 * <p>产品行 customer_code 是客户物料号，不能作为客户身份；核算月份也不能取服务器当前月份。
 */
@Component
public class QuoteBomContextResolver {

  public QuoteBomContext resolve(OaForm form, OaFormItem item) {
    return resolve(form, item, null);
  }

  /** verifiedCustomerCode 仅接收已经过主数据核验的正式客户编码。 */
  public QuoteBomContext resolve(
      OaForm form, OaFormItem item, String verifiedCustomerCode) {
    return resolveInternal(form, item, verifiedCustomerCode, null);
  }

  /** 已进入月度调价等后续流程时，沿用已明确保存的核算月份；绝不使用服务器当前月份。 */
  public QuoteBomContext resolveWithExistingCostPeriod(
      OaForm form, OaFormItem item, String existingCostPeriodMonth) {
    return resolveInternal(form, item, null, existingCostPeriodMonth);
  }

  private QuoteBomContext resolveInternal(
      OaForm form,
      OaFormItem item,
      String verifiedCustomerCode,
      String existingCostPeriodMonth) {
    if (form == null) {
      throw new QuoteIngestException("报价单不能为空");
    }
    if (item == null) {
      throw new QuoteIngestException("报价单产品行不能为空");
    }
    String productCode = QuoteProductIdentityUtils.resolveCostingCode(item);
    if (productCode == null) {
      throw new QuoteIngestException("产品料号、三花型号和客户图号均为空，无法识别报价产品");
    }
    return new QuoteBomContext(
        resolveCostPeriodMonth(form, existingCostPeriodMonth),
        productCode,
        resolveCustomer(form, verifiedCustomerCode),
        normalizePackageMethod(item.getPackageMethod()),
        resolveOrganization(form, item));
  }

  public ResolvedCustomerKey resolveCustomer(OaForm form, String verifiedCustomerCode) {
    String formalCode = normalizeOptional(verifiedCustomerCode);
    if (formalCode != null) {
      return new ResolvedCustomerKey(
          formalCode, ResolvedCustomerKey.Source.VERIFIED_CUSTOMER_CODE, null);
    }

    String headerCustomer = normalizeOptional(form == null ? null : form.getCustomer());
    if (headerCustomer != null) {
      return new ResolvedCustomerKey(
          headerCustomer, ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null);
    }

    String oaNo = trimToNull(form == null ? null : form.getOaNo());
    if (oaNo == null) {
      throw new QuoteIngestException("客户信息和 OA 单号均为空，无法生成客户隔离键");
    }
    return new ResolvedCustomerKey(
        "OA:" + oaNo,
        ResolvedCustomerKey.Source.OA_NUMBER_FALLBACK,
        "客户信息缺失，本次 BOM 已按 OA 单号隔离，不能跨 OA 沿用");
  }

  public String resolveCostPeriodMonth(OaForm form) {
    return resolveCostPeriodMonth(form, null);
  }

  private String resolveCostPeriodMonth(OaForm form, String existingCostPeriodMonth) {
    if (form == null) {
      throw new QuoteIngestException("报价单不能为空，无法确定核算月份");
    }
    String existingPeriod = trimToNull(existingCostPeriodMonth);
    if (existingPeriod != null) {
      return parseCostPeriodMonth(existingPeriod, "已有 BOM 核算月份");
    }
    String accountingMonth = trimToNull(form.getAccountingPeriodMonth());
    if (accountingMonth != null) {
      return parseCostPeriodMonth(accountingMonth, "OA 核算月份");
    }
    if (form.getApplyDate() != null) {
      return YearMonth.from(form.getApplyDate()).toString();
    }
    throw new QuoteIngestException("OA 核算月份和申请日期均为空，无法确定报价 BOM 核算月份");
  }

  private String parseCostPeriodMonth(String value, String fieldName) {
    try {
      return YearMonth.parse(value).toString();
    } catch (DateTimeParseException ex) {
      throw new QuoteIngestException(fieldName + "格式错误，应为 YYYY-MM: " + value);
    }
  }

  public String normalizePackageMethod(String value) {
    String normalized = trimToNull(value);
    return normalized == null || "/".equals(normalized) ? "" : normalized;
  }

  public QuoteDataOrganization resolveOrganization(OaForm form, OaFormItem item) {
    try {
      return MaterialOrganization.quoteDataForQuoteProduct(
          form == null ? null : form.getProcessCode(),
          form == null ? null : form.getOaNo(),
          item == null ? null : item.getBusinessUnitType(),
          item == null ? null : item.getProductName(),
          item == null ? null : item.getSunlModel(),
          item == null ? null : item.getMaterialNo());
    } catch (IllegalArgumentException ex) {
      throw new QuoteIngestException("报价 BOM 组织解析失败: " + ex.getMessage());
    }
  }

  /** 原始 BOM 读取完成后调用，禁止 210 与 220 的源数据串用。 */
  public void validateSourceOrganization(QuoteBomContext context, String sourcePriceOrgCode) {
    if (context == null || context.organization() == null) {
      throw new QuoteIngestException("报价 BOM 上下文组织不能为空");
    }
    String sourceOrg = trimToNull(sourcePriceOrgCode);
    if (sourceOrg == null) {
      throw new QuoteIngestException("原始 BOM 缺少 price_org_code，无法校验组织一致性");
    }
    String normalizedSource;
    try {
      normalizedSource = MaterialOrganization.fromPriceOrgCode(sourceOrg).getPriceOrgCode();
    } catch (IllegalArgumentException ex) {
      throw new QuoteIngestException("原始 BOM 组织非法: " + ex.getMessage());
    }
    String resolved = context.organization().priceOrgCode();
    if (!resolved.equals(normalizedSource)) {
      throw new QuoteIngestException(
          "OA 解析组织 " + resolved + " 与原始 BOM 组织 " + normalizedSource + " 不一致，已阻断");
    }
  }

  private String normalizeOptional(String value) {
    String normalized = trimToNull(value);
    return normalized == null || "/".equals(normalized) ? null : normalized;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
