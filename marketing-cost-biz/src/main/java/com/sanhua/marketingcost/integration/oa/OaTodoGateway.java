package com.sanhua.marketingcost.integration.oa;

public interface OaTodoGateway {

  PushResult push(PushRequest request);

  StatusResult query(String oaTodoId);

  StatusResult close(String oaTodoId);

  record PushRequest(
      Long taskId,
      String taskNo,
      String title,
      String assigneeName,
      String collaborationUrl,
      String payloadJson) {}

  record PushResult(boolean success, String oaTodoId, String oaTodoUrl, String errorMessage) {}

  record StatusResult(boolean success, String status, String oaTodoId, String oaTodoUrl, String errorMessage) {}
}
