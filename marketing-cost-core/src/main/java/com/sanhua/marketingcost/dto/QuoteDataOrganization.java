package com.sanhua.marketingcost.dto;

/** 报价数据组织：BOM 使用 priceOrgCode，U9 料品主档使用 materialOrganizationCode。 */
public record QuoteDataOrganization(String priceOrgCode, String materialOrganizationCode) {}
