package com.sanhua.marketingcost.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Type;
import java.util.Map;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** 统一校验报价资源归属。列表的范围与计数在各查询中处理，不能用分页后的过滤代替。 */
@ControllerAdvice
public class QuoteVisibilityWebGuard extends RequestBodyAdviceAdapter implements WebMvcConfigurer, HandlerInterceptor {
  private final QuoteVisibilityPolicy policy;
  private final ObjectMapper json;
  public QuoteVisibilityWebGuard(QuoteVisibilityPolicy policy, ObjectMapper json) {
    this.policy = policy;
    this.json = json;
  }
  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(this).addPathPatterns("/api/v1/quote-requests/**", "/api/v1/oa-forms/**",
        "/api/v1/cost-run/**", "/api/v1/quote-ingest/**", "/api/v2/technical-data/**");
  }
  @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!QuoteVisibilityContext.restricted()) return true;
    String route = String.valueOf(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    @SuppressWarnings("unchecked")
    Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
    if (variables != null) {
      require(variables, "oaNo", "oaNo");
      require(variables, "formId", "form");
      require(variables, "itemId", "item");
      require(variables, "oaFormItemId", "item");
      require(variables, "productId", "product");
      require(variables, "taskId", route.contains("/electronic-drawing/tasks/") ? "item" : "task");
      require(variables, "costRunNo", "costRun");
      if (route.contains("/cost-run")) require(variables, "versionId", "costVersion");
      if (route.startsWith("/api/v2/technical-data/returns/")) require(variables, "batchId", "returnBatch");
      if (route.equals("/api/v1/quote-ingest/logs/{id}")) require(variables, "id", "ingestLog");
    }
    // oaNo在列表中是搜索条件，不能当作完整单号；详情和导出则校验所有可指定的资源。
    if (route.startsWith("/api/v1/cost-run/") || route.equals("/api/v1/quote-ingest/bom-status")) {
      query(request, "oaNo", "oaNo");
      query(request, "costRunNo", "costRun");
      query(request, "versionId", "costVersion");
    }
    if (route.startsWith("/api/v1/quote-requests/")) {
      query(request, "itemId", "item");
      query(request, "oaFormItemId", "item");
      if (route.contains("/cost-run")) query(request, "versionId", "costVersion");
    }
    if (route.startsWith("/api/v1/cost-run/")) query(request, "oaFormItemId", "item");
    return true;
  }
  private void require(Map<String,String> variables, String field, String kind) {
    policy.require(kind, variables.get(field));
  }
  private void query(HttpServletRequest request, String field, String kind) {
    var values = request.getParameterValues(field);
    if (values != null) for (String value : values) if (!value.isBlank()) policy.require(kind, value);
  }
  @Override public boolean supports(MethodParameter parameter, Type type,
      Class<? extends HttpMessageConverter<?>> converter) { return true; }
  @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter,
      Type targetType, Class<? extends HttpMessageConverter<?>> converter) {
    if (!QuoteVisibilityContext.restricted() || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servlet)) return body;
    String route = String.valueOf(servlet.getRequest().getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
    if (route.startsWith("/api/v1/quote-requests/") || route.startsWith("/api/v1/quote-ingest/bom-status/")
        || route.equals("/api/v2/technical-data/tasks/publish-from-quote")
        || route.startsWith("/api/v2/technical-data/returns")) {
      JsonNode node = json.valueToTree(body);
      field(node, "oaNo", "oaNo");
      field(node, "oaFormItemId", "item");
      field(node, "itemId", "item");
      field(node, "oaFormItemIds", "item");
      field(node, "itemIds", "item");
      field(node, "taskIds", "task");
      for (JsonNode target : node.path("targets")) field(target, "taskId", "task");
    }
    return body;
  }
  private void field(JsonNode node, String field, String kind) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) return;
    if (value.isArray()) for (JsonNode element : value) policy.require(kind, element.asText());
    else policy.require(kind, value.asText());
  }
}
