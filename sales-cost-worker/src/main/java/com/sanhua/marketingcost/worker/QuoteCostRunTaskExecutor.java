package com.sanhua.marketingcost.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.entity.CostRunTask;
import com.sanhua.marketingcost.enums.CostRunTaskScene;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** QUOTE 队列执行器；整单和单品入口共用同一条产品级核算流水线。 */
@Component
public class QuoteCostRunTaskExecutor implements CostRunTaskExecutor {

  private final ProductCostingPipeline productCostingPipeline;
  private final ObjectMapper objectMapper;

  public QuoteCostRunTaskExecutor(
      ProductCostingPipeline productCostingPipeline, ObjectMapper objectMapper) {
    this.productCostingPipeline = productCostingPipeline;
    this.objectMapper = objectMapper;
  }

  @Override
  public CostRunTaskScene scene() {
    return CostRunTaskScene.QUOTE;
  }

  @Override
  public CostRunTaskExecutionResult execute(CostRunTask task, String workerId) {
    if (task == null) {
      throw new IllegalArgumentException("核算任务不能为空");
    }
    String oaNo = required("oaNo", task.getOaNo());
    if (task.getOaFormItemId() == null || task.getOaFormItemId() <= 0) {
      throw new IllegalArgumentException("报价产品行 ID 必须大于0");
    }
    String initiatedBy = firstText(submittedBy(task), workerId, "cost-run-worker");
    SecurityContext previousContext = SecurityContextHolder.getContext();
    SecurityContext taskContext = taskSecurityContext(initiatedBy, task.getBusinessUnitType());
    ProductCostingResult result;
    try {
      SecurityContextHolder.setContext(taskContext);
      result =
          productCostingPipeline.execute(
              new ProductCostingRequest(
                  oaNo,
                  task.getOaFormItemId(),
                  task.getPricingMonth(),
                  initiatedBy,
                  false));
    } finally {
      SecurityContextHolder.setContext(previousContext);
    }
    if (result == null) {
      throw new IllegalStateException("产品核算流水线没有返回结果");
    }
    String summary = toJson(result);
    if ("SUCCESS".equals(result.getPipelineStatus())) {
      return new CostRunTaskExecutionResult(summary);
    }
    if ("BLOCKED".equals(result.getPipelineStatus())) {
      throw new CostRunTaskCollaborationRequiredException(
          firstText(result.getMessage(), "产品核算资料存在缺口"), summary);
    }
    throw new IllegalStateException(firstText(result.getMessage(), "产品核算失败"));
  }

  private SecurityContext taskSecurityContext(String username, String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(username, null, List.of());
    Map<String, Object> details = new HashMap<>();
    if (StringUtils.hasText(businessUnitType)) {
      details.put(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType.trim());
    }
    authentication.setDetails(details);
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    return context;
  }

  private String toJson(ProductCostingResult result) {
    try {
      return objectMapper.writeValueAsString(result);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("产品核算结果序列化失败", ex);
    }
  }

  private String submittedBy(CostRunTask task) {
    if (task == null || !StringUtils.hasText(task.getRequestSnapshotJson())) {
      return null;
    }
    try {
      return firstText(
          objectMapper.readTree(task.getRequestSnapshotJson()).path("submittedBy").asText(null));
    } catch (JsonProcessingException exception) {
      return null;
    }
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  private String firstText(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }
}
