package com.sanhua.marketingcost.service;

import java.util.Collection;
import java.util.Map;

/** 包装组件父料号识别服务。 */
public interface PackageComponentIdentifyService {

  /** 判断单个料号是否为包装组件父料号。 */
  boolean isPackageComponent(String materialCode);

  /** 批量判断料号是否为包装组件父料号，返回 key 为清洗后的料号。 */
  Map<String, Boolean> batchIdentify(Collection<String> materialCodes);
}
