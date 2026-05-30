package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.quotebom.OaTodoCallbackRequest;
import com.sanhua.marketingcost.dto.quotebom.OaTodoPushResponse;

public interface OaTodoPushService {

  OaTodoPushResponse pushBomSupplementTask(Long taskId);

  OaTodoPushResponse queryTodoStatus(Long taskId);

  OaTodoPushResponse closeTodo(Long taskId);

  OaTodoPushResponse handleCallback(OaTodoCallbackRequest request);
}
