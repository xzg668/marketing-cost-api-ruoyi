package com.sanhua.marketingcost.config;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
    // MDC 上下文传播：将调用线程的 traceId 等信息传递到异步线程
    executor.setTaskDecorator(runnable -> {
      Map<String, String> contextMap = MDC.getCopyOfContextMap();
      return () -> {
        try {
          if (contextMap != null) {
            MDC.setContextMap(contextMap);
          }
          runnable.run();
        } finally {
          MDC.clear();
        }
      };
    });
    executor.initialize();
    return executor;
  }
}
