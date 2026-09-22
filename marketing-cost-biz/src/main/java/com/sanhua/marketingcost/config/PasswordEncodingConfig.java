package com.sanhua.marketingcost.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 供 API 和非 Web worker 共用的密码编码基础设施。 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncodingConfig {

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
