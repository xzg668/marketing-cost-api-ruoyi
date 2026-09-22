package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(
    type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class OaIntegrationSecurityConfig {
  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 19)
  SecurityFilterChain oaQuotationSecurity(HttpSecurity http, OaIntegrationProperties properties,
      ObjectMapper json) throws Exception {
    return http.securityMatcher("/open-api/v1/oa/quotation-requests", "/open-api/v1/oa/quotation-requests/**", "/integration/v1/workflow-events", "/integration/v1/workflow-events/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .addFilterBefore(new OaQuotationAuthenticationFilter(properties, json),
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  @Bean
  @Order(Ordered.HIGHEST_PRECEDENCE + 20)
  SecurityFilterChain oaIntegrationSecurity(HttpSecurity http, OaIntegrationProperties properties,
      ObjectMapper json) throws Exception {
    return http.securityMatcher("/integration/oa/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
        .addFilterBefore(new OaMachineAuthenticationFilter(properties, json),
            UsernamePasswordAuthenticationFilter.class)
        .build();
  }
}
