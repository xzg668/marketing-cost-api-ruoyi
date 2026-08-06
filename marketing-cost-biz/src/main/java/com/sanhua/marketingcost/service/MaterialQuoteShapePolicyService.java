package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyQuery;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyResponse;
import java.util.List;

/** 料品报价形态规则维护服务；本服务只维护规则，不解析或裁剪 BOM。 */
public interface MaterialQuoteShapePolicyService {

  List<MaterialQuoteShapePolicyResponse> list(
      MaterialQuoteShapePolicyQuery query);

  MaterialQuoteShapePolicyResponse get(Long id);

  MaterialQuoteShapePolicyResponse create(
      MaterialQuoteShapePolicyRequest request);

  MaterialQuoteShapePolicyResponse update(
      Long id, MaterialQuoteShapePolicyRequest request);

  MaterialQuoteShapePolicyResponse setEnabled(Long id, Integer enabled);

  boolean delete(Long id);
}
