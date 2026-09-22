package com.sanhua.marketingcost.dto.technicaldata;

/** 浏览器传入的归属声明，必须与服务端当前审批资料逐项核对。 */
public record TechnicalPriceImportContext(
    String oaNo, Long oaFormItemId, Long technicalVersionId) {}
