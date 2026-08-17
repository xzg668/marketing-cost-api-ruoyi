package com.sanhua.marketingcost.integration.oa;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 旧BOM待办链的安全关闭实现。
 *
 * <p>QCBP切换完成前仍需保留旧接口以保证应用可启动，但关闭模式绝不伪造OA待办号或链接。
 */
@Component
@ConditionalOnProperty(
    prefix = "quote.collaboration.oa",
    name = "mode",
    havingValue = "DISABLED",
    matchIfMissing = true)
public class DisabledOaTodoGateway implements OaTodoGateway {

  private static final String DISABLED = "INTEGRATION_DISABLED";

  @Override
  public PushResult push(PushRequest request) {
    return new PushResult(false, null, null, DISABLED);
  }

  @Override
  public StatusResult query(String oaTodoId) {
    return new StatusResult(false, null, null, null, DISABLED);
  }

  @Override
  public StatusResult close(String oaTodoId) {
    return new StatusResult(false, null, null, null, DISABLED);
  }
}
