package com.sanhua.marketingcost.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.sanhua.marketingcost.security.BusinessUnitInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MybatisPlusConfig {

  /**
   * MyBatis Plus 拦截器链。注意顺序：
   * <ol>
   *   <li>{@link BusinessUnitInterceptor} — 先追加业务单元过滤条件（改写 SQL）</li>
   *   <li>{@link PaginationInnerInterceptor} — 后包装分页（COUNT 与 LIMIT）</li>
   * </ol>
   * 先数据权限后分页可保证 COUNT 查询也带业务单元条件，计数准确。
   */
  @Bean
  public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    // 业务单元数据隔离（仅作用于标注 @DataScope 的 Mapper 方法）
    interceptor.addInnerInterceptor(new BusinessUnitInterceptor());
    // 分页
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
  }
}
