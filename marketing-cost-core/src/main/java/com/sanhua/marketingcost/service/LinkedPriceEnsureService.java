package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.LinkedPriceEnsureRequest;
import com.sanhua.marketingcost.dto.LinkedPriceEnsureResult;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import java.util.List;

/** 联动价按需确保服务：业务入口调用前先保证本次需要的联动价结果已生成。 */
public interface LinkedPriceEnsureService {

  /** 使用与 ensure 相同的公式和变量口径计算，但不插入或更新 calc_item。 */
  List<PriceLinkedCalcItem> calculate(LinkedPriceEnsureRequest request);

  /**
   * 按请求上下文确保联动价结果。
   *
   * <p>ensure 是业务入口前置能力，不放在 LinkedPriceResolver 内；Resolver 只负责读取已确定的取价结果。
   */
  LinkedPriceEnsureResult ensure(LinkedPriceEnsureRequest request);
}
