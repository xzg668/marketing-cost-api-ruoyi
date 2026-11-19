package com.sanhua.marketingcost;

import java.util.Map;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.sanhua.marketingcost.mapper")
public class MarketingCostApiApplication {

  public static void main(String[] args) {
    SpringApplication application = new SpringApplication(MarketingCostApiApplication.class);
    // 统一配置覆盖本地旧配置。容器通过 SPRING_CONFIG_ADDITIONAL_LOCATION 指定唯一配置文件。
    application.setDefaultProperties(Map.of("spring.config.additional-location", String.join(",",
        "optional:file:./config/oa-runtime.properties",
        "optional:file:../config/oa-runtime.properties",
        "optional:file:./marketing-cost-api/config/oa-runtime.properties",
        "optional:file:./config/external-integrations.properties",
        "optional:file:../config/external-integrations.properties",
        "optional:file:./marketing-cost-api/config/external-integrations.properties")));
    application.run(args);
  }
}
