package com.sanhua.marketingcost;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.sanhua.marketingcost.mapper")
public class MarketingCostApiApplication {

  public static void main(String[] args) {
    SpringApplication.run(MarketingCostApiApplication.class, args);
  }
}
