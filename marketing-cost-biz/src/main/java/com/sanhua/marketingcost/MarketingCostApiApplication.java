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
    // 支持仓库根目录、biz 模块和工作区目录启动；真实凭证保存在 Git 排除的本地配置。
    // 服务器显式提供 SPRING_CONFIG_ADDITIONAL_LOCATION 时仍以服务器配置为准。
    application.setDefaultProperties(Map.of("spring.config.additional-location", String.join(",",
        "optional:file:./config/oa-runtime.properties",
        "optional:file:../config/oa-runtime.properties",
        "optional:file:./marketing-cost-api/config/oa-runtime.properties")));
    application.run(args);
  }
}
