package com.sanhua.marketingcost.integration.technicaldata;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.integration.oa.OaPeer;
import java.util.List;
import java.util.Optional;

/** 稳定内部契约；真实 OA 的路由、认证和字段差异留在适配器内。 */
public interface TechnicalDataOaGateway {
  enum Operation { TASK_DISPATCH, TECH_SUBMISSION, TECH_RETURN, QUOTE_STATUS, QUOTE_DATA_SUBMIT, QUOTE_RESULT_SAVE, QUOTE_COST_SUBMIT }
  record Receipt(String requestId, boolean accepted, String errorCode, JsonNode result) {}
  record ExternalUser(String externalUserId, String displayName, boolean active) {}
  record Identity(String externalUserId, long taskId) {}

  boolean enabled();
  OaPeer peer();
  Receipt send(Operation operation, String commandJson);
  Optional<Receipt> query(Operation operation, String requestId);
  Identity exchange(long taskId, String code);
  List<ExternalUser> users();
  String taskAccessUrl(long taskId);
}
