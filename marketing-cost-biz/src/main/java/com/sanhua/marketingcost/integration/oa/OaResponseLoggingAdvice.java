package com.sanhua.marketingcost.integration.oa;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** 只提取业务结果码，让 HTTP 200 中的业务失败可检索；正文和返回的身份令牌不进入日志。 */
@RestControllerAdvice
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OaResponseLoggingAdvice implements ResponseBodyAdvice<Object> {
  @Override public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType, ServerHttpRequest request, ServerHttpResponse response) {
    if (request instanceof ServletServerHttpRequest servlet
        && servlet.getServletRequest().getAttribute(OaHttpLoggingFilter.CALL) instanceof OaInterfaceLog.Call call) {
      String code = null;
      if (body instanceof CommonResult<?> result) code = String.valueOf(result.getCode());
      else if (body instanceof JsonNode result && result.path("code").isValueNode()) code = result.path("code").asText();
      else if (body instanceof OaQuotationResponse result) code = result.code();
      else if (body instanceof OaWorkflowReply result) code = result.code();
      else if (body instanceof java.util.Map<?, ?> result && result.get("code") instanceof String value) code = value;
      if (code != null) servlet.getServletRequest().setAttribute(OaHttpLoggingFilter.RESULT_CODE, OaInterfaceLog.identifier(code));
    }
    return body;
  }
}
