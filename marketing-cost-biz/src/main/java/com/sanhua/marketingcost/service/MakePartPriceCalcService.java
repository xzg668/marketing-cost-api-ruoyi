package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MakePartPriceCalcPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGapPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

public interface MakePartPriceCalcService {

  MakePartPriceCalcPageResponse page(MakePartPriceCalcQueryRequest request);

  MakePartPriceGapPageResponse gapPage(MakePartPriceCalcQueryRequest request);

  MakePartPriceCalcRow get(Long id);

  MakePartPriceGenerateResponse generate(MakePartPriceGenerateRequest request);

  String latestBatch(String oaNo, String businessUnitType, String parentMaterialNo);

  Map<String, Integer> statusSummary(MakePartPriceCalcQueryRequest request);

  int export(MakePartPriceCalcQueryRequest request, OutputStream output);

  List<String> exportHeaders();
}
