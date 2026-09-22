package com.sanhua.marketingcost.service.technicaldata;

import java.math.BigDecimal;

/** 报价产品的来源事实快照，不包含技术草稿值或外部系统私有字段。 */
public record TechnicalDataProductSource(
    Long oaFormId,
    String oaNo,
    Long oaFormItemId,
    String externalLineId,
    Integer levelNo,
    String materialNo,
    String productName,
    String sourceModel,
    String sourceSpec,
    String sourceProductProperty,
    Boolean newProduct,
    BigDecimal annualVolume,
    String annualVolumeUnit,
    String packageMethod,
    String businessUnitType,
    String applicableOrgCode,
    String materialOrganizationCode) {}
