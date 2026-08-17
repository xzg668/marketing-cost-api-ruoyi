package com.sanhua.marketingcost.integration.drawing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 未配置电子图库时明确失败，不读取本地草稿冒充正式回取结果。 */
@Component
@ConditionalOnProperty(
    prefix = "quote.collaboration.electronic-drawing",
    name = "mode",
    havingValue = "DISABLED",
    matchIfMissing = true)
public class DisabledElectronicDrawingBomGateway implements ElectronicDrawingBomGateway {

  @Override
  public ElectronicBomFetchResult fetchCurrentBom(ElectronicBomQuery query) {
    return ElectronicBomFetchResult.failure(
        ElectronicBomFetchResult.Status.INTEGRATION_DISABLED,
        "电子图库BOM查询接口尚未启用");
  }
}
