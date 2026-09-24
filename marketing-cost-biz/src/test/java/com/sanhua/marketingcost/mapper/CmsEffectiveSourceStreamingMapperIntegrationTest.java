package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.entity.CmsWorkshopLaborRaw;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class CmsEffectiveSourceStreamingMapperIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("cms_streaming_test")
      .withUsername("test")
      .withPassword("test");

  private static CmsWorkshopLaborRawMapper workshopMapper;
  private static CmsProductSubjectCostRawMapper subjectMapper;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    var source = new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("""
        CREATE TABLE cms_workshop_labor_raw (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          period VARCHAR(16), parent_code VARCHAR(64), parent_name VARCHAR(128),
          parent_spec VARCHAR(128), parent_type VARCHAR(64), first_unit_name VARCHAR(128),
          working_cost_cent DECIMAL(18, 4), business_unit_type VARCHAR(32)
        )
        """);
    jdbc.execute("""
        CREATE TABLE cms_product_subject_cost_raw (
          id BIGINT PRIMARY KEY AUTO_INCREMENT,
          parent_code VARCHAR(64), period VARCHAR(16), second_subject_code VARCHAR(32),
          material_price DECIMAL(18, 4), business_unit_type VARCHAR(32)
        )
        """);
    jdbc.update("INSERT INTO cms_workshop_labor_raw "
        + "(period,parent_code,parent_name,working_cost_cent,business_unit_type) VALUES "
        + "('2026-01','A','Product A',100,'COMMERCIAL'),"
        + "('2026-02','B','Product B',250,'COMMERCIAL'),"
        + "('2025-12','C','Product C',300,'COMMERCIAL'),"
        + "('2026-01','D','Product D',400,'HOUSEHOLD')");
    jdbc.update("INSERT INTO cms_product_subject_cost_raw "
        + "(parent_code,period,second_subject_code,material_price,business_unit_type) VALUES "
        + "('A','2026-01','0201',100,'COMMERCIAL'),"
        + "('B','2026-02','0202',250,'COMMERCIAL'),"
        + "('C','2026-01','0302',300,'COMMERCIAL'),"
        + "('D','2026-01','0201',400,'HOUSEHOLD')");

    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(CmsWorkshopLaborRawMapper.class);
    configuration.addMapper(CmsProductSubjectCostRawMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source);
    factory.setConfiguration(configuration);
    var template = new SqlSessionTemplate(factory.getObject());
    workshopMapper = template.getMapper(CmsWorkshopLaborRawMapper.class);
    subjectMapper = template.getMapper(CmsProductSubjectCostRawMapper.class);
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Test
  void streamsOnlyMatchingWorkshopRowsAndMapsSelectedColumns() {
    List<CmsWorkshopLaborRaw> rows = new ArrayList<>();
    workshopMapper.forEachDirectLaborSource(2026, "COMMERCIAL",
        context -> rows.add(context.getResultObject()));

    assertThat(rows).extracting(CmsWorkshopLaborRaw::getParentCode).containsExactly("A", "B");
    assertThat(rows.get(0).getParentName()).isEqualTo("Product A");
    assertThat(rows.get(1).getWorkingCostCent()).isEqualByComparingTo(new BigDecimal("250"));
  }

  @Test
  void streamsOnlyConfiguredAuxiliarySubjects() {
    List<CmsProductSubjectCostRaw> rows = new ArrayList<>();
    subjectMapper.forEachAuxiliarySource(2026, List.of("0201", "0202"), "COMMERCIAL",
        context -> rows.add(context.getResultObject()));

    assertThat(rows).extracting(CmsProductSubjectCostRaw::getParentCode).containsExactly("A", "B");
    assertThat(rows).extracting(CmsProductSubjectCostRaw::getSecondSubjectCode)
        .containsExactly("0201", "0202");
    assertThat(rows.get(1).getMaterialPrice()).isEqualByComparingTo(new BigDecimal("250"));
  }
}
