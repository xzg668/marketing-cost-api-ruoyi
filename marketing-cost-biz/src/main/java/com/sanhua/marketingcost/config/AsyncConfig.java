package com.sanhua.marketingcost.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

@Configuration
@EnableAsync
public class AsyncConfig {

  @Bean("costRunExecutor")
  public Executor costRunExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(100);
    executor.setThreadNamePrefix("cost-run-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
    // 上下文传播：
    //   1) MDC：把调用线程的 traceId 等日志上下文带过来
    //   2) SecurityContext（V21）：把当前登录用户的 Authentication 带过来，
    //      这样异步试算线程里的 MyBatis-Plus 拦截器/MetaObjectHandler 才能
    //      拿到 businessUnitType 做数据隔离与写入自动填充。
    executor.setTaskDecorator(runnable -> {
      Map<String, String> contextMap = MDC.getCopyOfContextMap();
      SecurityContext securityContext = SecurityContextHolder.getContext();
      return () -> {
        try {
          if (contextMap != null) {
            MDC.setContextMap(contextMap);
          }
          if (securityContext != null) {
            SecurityContextHolder.setContext(securityContext);
          }
          runnable.run();
        } finally {
          MDC.clear();
          SecurityContextHolder.clearContext();
        }
      };
    });
    executor.initialize();
    return executor;
  }
}
