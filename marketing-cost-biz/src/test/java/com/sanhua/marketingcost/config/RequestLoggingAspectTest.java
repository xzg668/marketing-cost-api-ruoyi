package com.sanhua.marketingcost.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class RequestLoggingAspectTest {

  @Test
  void oauthCallbackStringsAndHandoffUrlsAreNeverLogged() throws Throwable {
    var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingAspect.class);
    var appender = new ListAppender<ILoggingEvent>(); appender.start(); logger.addAppender(appender);
    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/v1/auth/oa/callback");
    org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
        new org.springframework.web.context.request.ServletRequestAttributes(request));
    try {
      var point = mock(ProceedingJoinPoint.class);
      when(point.getArgs()).thenReturn(new Object[]{"private-code", "private-state"});
      when(point.proceed()).thenReturn(Map.of("authorizeUrl", "http://oa.test?state=private-state", "ticket", "private-ticket"));
      new RequestLoggingAspect(new ObjectMapper()).logRequest(point);
      assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
          .doesNotContain("private-code", "private-state", "private-ticket").contains("OAuth:redacted"));
    } finally {
      org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
      logger.detachAppender(appender); appender.stop();
    }
  }

  @Test
  void redactsSecurityPrincipalAndNestedSecretsInRequestAndResponse() throws Throwable {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestLoggingAspect.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
      UsernamePasswordAuthenticationToken authentication =
          new UsernamePasswordAuthenticationToken("admin", "raw-credential", List.of());
      when(joinPoint.getArgs())
          .thenReturn(new Object[] {
              authentication,
              Map.of("password", "raw-password", "nested", Map.of("client_secret", "raw-secret"))
          });
      when(joinPoint.proceed())
          .thenReturn(Map.of("access_token", "raw-token", "status", "SUCCESS"));

      Object result = new RequestLoggingAspect(new ObjectMapper()).logRequest(joinPoint);

      assertThat(result).isEqualTo(Map.of("access_token", "raw-token", "status", "SUCCESS"));
      String logText = appender.list.stream()
          .map(ILoggingEvent::getFormattedMessage)
          .reduce("", (left, right) -> left + "\n" + right);
      assertThat(logText)
          .contains("[SecurityPrincipal:redacted]", "[REDACTED]")
          .doesNotContain("raw-credential", "raw-password", "raw-secret", "raw-token");
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }
}
