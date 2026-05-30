package com.sanhua.marketingcost.integration.oa;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MockOaTodoGateway implements OaTodoGateway {

  @Override
  public PushResult push(PushRequest request) {
    if (request == null || request.taskId() == null) {
      return new PushResult(false, null, null, "缺少任务 ID");
    }
    if (StringUtils.hasText(request.assigneeName())
        && request.assigneeName().contains("FAIL")) {
      return new PushResult(false, null, null, "OA mock 推送失败");
    }
    String id = "OA-TODO-" + request.taskNo();
    return new PushResult(true, id, "/oa/todo/" + id, null);
  }

  @Override
  public StatusResult query(String oaTodoId) {
    if (!StringUtils.hasText(oaTodoId)) {
      return new StatusResult(false, null, null, null, "缺少 OA 待办 ID");
    }
    return new StatusResult(true, "PUSHED", oaTodoId, "/oa/todo/" + oaTodoId, null);
  }

  @Override
  public StatusResult close(String oaTodoId) {
    if (!StringUtils.hasText(oaTodoId)) {
      return new StatusResult(false, null, null, null, "缺少 OA 待办 ID");
    }
    return new StatusResult(true, "CLOSED", oaTodoId, "/oa/todo/" + oaTodoId, null);
  }
}
