package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.dto.ingest.QuoteIngestHeaderRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestItemRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;

/**
 * 报价单接入字段编码绑定器。
 *
 * <p>Excel 隐藏 Sheet 和未来泛微 payload 都必须使用同一套 field_code，这里集中维护字段编码到
 * QuoteIngestRequest 的绑定关系，避免不同入口各自猜字段。
 */
final class QuoteIngestFieldBinder {
  private QuoteIngestFieldBinder() {}

  static boolean applyRequestField(QuoteIngestRequest request, String fieldCode, String value) {
    switch (fieldCode) {
      case "sourceType":
        request.setSourceType(value);
        return true;
      case "sourceSystem":
        request.setSourceSystem(value);
        return true;
      case "oaNo":
        request.setOaNo(value);
        request.setExternalFormNo(value);
        return true;
      case "externalFormNo":
        request.setExternalFormNo(value);
        return true;
      case "version":
        request.setVersion(value);
        return true;
      default:
        return false;
    }
  }

  static boolean applyHeaderField(QuoteIngestHeaderRequest header, String fieldCode, String value) {
    switch (fieldCode) {
      case "processCode":
        header.setProcessCode(value);
        return true;
      case "processName":
        header.setProcessName(value);
        return true;
      case "quoteScenario":
        header.setQuoteScenario(value);
        return true;
      case "businessUnitType":
        header.setBusinessUnitType(value);
        return true;
      case "formType":
        header.setFormType(value);
        return true;
      case "applyDate":
        header.setApplyDate(dateOnly(value));
        return true;
      case "applyDateTime":
        header.setApplyDate(dateOnly(value));
        return false;
      case "customer":
        header.setCustomer(value);
        return true;
      case "applicantUnit":
        header.setApplicantUnit(value);
        return true;
      case "sourceCompany":
        header.setSourceCompany(value);
        return true;
      case "sourceBusinessDivision":
        header.setSourceBusinessDivision(value);
        return true;
      case "expenseProductCategory":
        header.setExpenseProductCategory(value);
        return true;
      case "applicantDept":
        header.setApplicantDept(value);
        return true;
      case "applicantOffice":
        header.setApplicantOffice(value);
        return true;
      case "applicantName":
        header.setApplicantName(value);
        return true;
      case "urgency":
        header.setUrgency(value);
        return true;
      case "productAttr":
        header.setProductAttr(value);
        return true;
      case "priceLinkMode":
        header.setPriceLinkMode(value);
        return true;
      case "overseasSalesMode":
        header.setOverseasSalesMode(value);
        return true;
      case "tradeTerms":
        header.setTradeTerms(value);
        return true;
      case "exchangeRate":
        header.setExchangeRate(value);
        return true;
      case "copperPrice":
        header.setCopperPrice(value);
        return true;
      case "zincPrice":
        header.setZincPrice(value);
        return true;
      case "aluminumPrice":
        header.setAluminumPrice(value);
        return true;
      case "steelPrice":
        header.setSteelPrice(value);
        return true;
      case "silverPrice":
        header.setSilverPrice(value);
        return true;
      case "goldPrice":
        header.setGoldPrice(value);
        return true;
      case "sus304Price":
        header.setSus304Price(value);
        return true;
      case "sus316lPrice":
        header.setSus316lPrice(value);
        return true;
      case "otherMaterial":
        header.setOtherMaterial(value);
        return true;
      case "baseShipping":
        header.setBaseShipping(value);
        return true;
      case "saleLink":
        header.setSaleLink(value);
        return true;
      case "remark":
        header.setRemark(value);
        return true;
      default:
        return false;
    }
  }

  static boolean applyItemField(QuoteIngestItemRequest item, String fieldCode, String value) {
    switch (fieldCode) {
      case "externalLineId":
        item.setExternalLineId(value);
        return true;
      case "seq":
        item.setSeq(parseInteger(value));
        return true;
      case "productName":
        item.setProductName(value);
        return true;
      case "customerDrawing":
        item.setCustomerDrawing(value);
        return true;
      case "customerCode":
        item.setCustomerCode(value);
        return true;
      case "materialNo":
        item.setMaterialNo(value);
        return true;
      case "sunlModel":
        item.setSunlModel(value);
        return true;
      case "spec":
        item.setSpec(value);
        return true;
      case "productAttr":
        item.setProductAttr(value);
        return true;
      case "businessType":
        item.setBusinessType(value);
        return true;
      case "firstQuoteFlag":
        item.setFirstQuoteFlag(parseBoolean(value));
        return true;
      case "certificationRequired":
        item.setCertificationRequired(parseBoolean(value));
        return true;
      case "originCountry":
        item.setOriginCountry(value);
        return true;
      case "technicianName":
        item.setTechnicianName(value);
        return true;
      case "packageType":
        item.setPackageType(value);
        return true;
      case "packageMethod":
        item.setPackageMethod(value);
        return true;
      case "packageComponentCode":
        item.setPackageComponentCode(value);
        return true;
      case "packageQty":
        item.setPackageQty(value);
        return true;
      case "shippingFee":
        item.setShippingFee(value);
        return true;
      case "supportQty":
        item.setSupportQty(value);
        return true;
      case "annualVolume":
        item.setAnnualVolume(value);
        return true;
      case "projectNo":
        item.setProjectNo(value);
        return true;
      case "productStatus":
        item.setProductStatus(value);
        return true;
      case "scrapRate":
        item.setScrapRate(value);
        return true;
      case "unitLaborCost":
        item.setUnitLaborCost(value);
        return true;
      case "totalWithShip":
        item.setTotalWithShip(value);
        return true;
      case "totalNoShip":
        item.setTotalNoShip(value);
        return true;
      case "materialCost":
        item.setMaterialCost(value);
        return true;
      case "laborCost":
        item.setLaborCost(value);
        return true;
      case "manufacturingCost":
        item.setManufacturingCost(value);
        return true;
      case "managementCost":
        item.setManagementCost(value);
        return true;
      case "validMonth":
        item.setValidMonth(value);
        return true;
      case "validDate":
        item.setValidDate(dateOnly(value));
        return true;
      case "sus304WeightG":
        item.setSus304WeightG(value);
        return true;
      case "sus316WeightG":
        item.setSus316WeightG(value);
        return true;
      case "copperWeightG":
        item.setCopperWeightG(value);
        return true;
      default:
        return false;
    }
  }

  private static Integer parseInteger(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return Integer.valueOf(normalized.replace(",", "").replace(".0", ""));
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static Boolean parseBoolean(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    if ("是".equals(normalized)
        || "Y".equalsIgnoreCase(normalized)
        || "YES".equalsIgnoreCase(normalized)
        || "TRUE".equalsIgnoreCase(normalized)
        || "1".equals(normalized)) {
      return true;
    }
    if ("否".equals(normalized)
        || "N".equalsIgnoreCase(normalized)
        || "NO".equalsIgnoreCase(normalized)
        || "FALSE".equalsIgnoreCase(normalized)
        || "0".equals(normalized)) {
      return false;
    }
    return null;
  }

  private static String dateOnly(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    int blank = normalized.indexOf(' ');
    return blank > 0 ? normalized.substring(0, blank) : normalized;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
