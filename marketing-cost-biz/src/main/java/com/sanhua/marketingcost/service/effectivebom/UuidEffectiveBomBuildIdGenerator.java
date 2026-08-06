package com.sanhua.marketingcost.service.effectivebom;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** 生成不依赖 OA 或客户信息的随机构建编号。 */
@Component
public final class UuidEffectiveBomBuildIdGenerator
    implements EffectiveBomBuildIdGenerator {

  @Override
  public String nextId() {
    return "QEB_" + UUID.randomUUID().toString().replace("-", "");
  }
}
