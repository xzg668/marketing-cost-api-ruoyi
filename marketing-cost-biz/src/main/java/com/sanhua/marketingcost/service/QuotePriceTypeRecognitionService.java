package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;

/** 按当前 BOM 和正式价格源实时识别物料价格类型；只读，不生成产品级确认副本。 */
public interface QuotePriceTypeRecognitionService {

  QuotePriceTypeRecognitionResponse getRecognition(
      String oaNo, Long oaFormItemId, String periodMonth);
}
