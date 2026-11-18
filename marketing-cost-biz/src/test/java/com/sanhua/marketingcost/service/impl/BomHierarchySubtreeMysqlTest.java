package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/** 实际 MySQL 上验证筛选/剪枝/子树用量；只在一次性数据库复制现有实体表。 */
@Tag("integration")
class BomHierarchySubtreeMysqlTest {
  @Test void realSqlScopesVersionsSourcesAndPurposesWithoutChangingStoredRows() {
    try (var mysql = new MySQLContainer<>("mysql:8.0.36")) {
      mysql.start();
      var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
      var jdbc = new JdbcTemplate(ds);
      var factory = factory(ds, jdbc);
      try (var session = factory.openSession(true)) {
        var mapper = session.getMapper(BomRawHierarchyMapper.class);
        long id = 0;
        var commercial = BomHierarchySubtreeQueryTest.tree("TOP", "5");
        var historical = BomHierarchySubtreeQueryTest.tree("TOP", "2");
        historical.forEach(row -> row.setEffectiveTo(LocalDate.of(2026, 10, 31)));
        commercial.forEach(row -> row.setEffectiveFrom(LocalDate.of(2026, 11, 1)));
        var manual = BomHierarchySubtreeQueryTest.tree("MANUAL-TOP", "8");
        manual.forEach(row -> row.setSourceType("MANUAL"));
        manual.get(2).setQtyPerParent(new BigDecimal("7"));
        var otherPurpose = BomHierarchySubtreeQueryTest.tree("LEAN-TOP", "6");
        otherPurpose.forEach(row -> row.setBomPurpose("精益"));
        otherPurpose.get(2).setQtyPerParent(new BigDecimal("9"));
        var otherOrganization = BomHierarchySubtreeQueryTest.tree("PLATE-TOP", "9");
        otherOrganization.forEach(row -> row.setPriceOrgCode("220"));
        var orphan = BomHierarchySubtreeQueryTest.tree("ORPHAN", "3");
        orphan.removeFirst();
        orphan.getFirst().setMaterialCode("ORPHAN-MAKE");
        for (var rows : List.of(commercial, historical, manual, otherPurpose, otherOrganization, orphan)) {
          for (var row : rows) { row.setId(++id); mapper.insert(row); }
        }
        var before = jdbc.queryForList("SELECT * FROM lp_bom_raw_hierarchy ORDER BY id");
        var service = BomHierarchySubtreeQueryTest.service(mapper);
        var date = BomHierarchySubtreeQueryTest.DATE;
        var make = service.getHierarchyTree("MAKE", "主制造", date, "U9", "210");
        assertThat(make.getPath()).isEqualTo("/MAKE/");
        assertThat(make.getChildren().getFirst().getQtyPerTop()).isEqualByComparingTo("2.5");
        assertThat(service.getHierarchyTree("TOP", "主制造", date, "U9", "210")
            .getChildren().getFirst().getQtyPerParent()).isEqualByComparingTo("5");
        assertThat(service.getHierarchyTree("TOP", "主制造", LocalDate.of(2026, 10, 15), "U9", "210")
            .getChildren().getFirst().getQtyPerParent()).isEqualByComparingTo("2");
        assertThat(service.getHierarchyTree("MAKE", "主制造", date, "MANUAL", "210")
            .getChildren().getFirst().getQtyPerTop()).isEqualByComparingTo("7");
        assertThat(service.getHierarchyTree("MAKE", "精益", date, "U9", "210")
            .getChildren().getFirst().getQtyPerTop()).isEqualByComparingTo("9");
        assertThatThrownBy(() -> service.getHierarchyTree("MAKE", null, date, "U9", "210"))
            .hasMessageContaining("不同的下级 BOM");
        assertThat(service.getHierarchyTree("TOP", "主制造", date, "U9", "220")).isNull();
        assertThat(service.getHierarchyTree("MAKE", "主制造", date, "E_DRAWING", "210")).isNull();
        assertThat(service.getHierarchyTree("ORPHAN-MAKE", "主制造", date, "U9", "210")).isNull();
        assertThat(service.getHierarchyTree("MAKE", "主制造", LocalDate.of(2025, 1, 1), "U9", "210")).isNull();
        assertThat(jdbc.queryForList("SELECT * FROM lp_bom_raw_hierarchy ORDER BY id")).isEqualTo(before);
      }
    }
  }

  static SqlSessionFactory factory(DriverManagerDataSource ds, JdbcTemplate jdbc) {
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setEnvironment(new Environment("bom-subtree-test", new JdbcTransactionFactory(), ds));
    configuration.addMapper(BomRawHierarchyMapper.class);
    var factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    var info = TableInfoHelper.getTableInfo(BomRawHierarchy.class);
    var columns = new ArrayList<String>();
    columns.add("id BIGINT PRIMARY KEY AUTO_INCREMENT");
    for (var field : info.getFieldList()) {
      Class<?> type = field.getPropertyType();
      String sqlType = type == BigDecimal.class ? "DECIMAL(20,8)"
          : type == LocalDate.class ? "DATE" : type == LocalDateTime.class ? "DATETIME"
          : type == Long.class ? "BIGINT" : type == Integer.class ? "INT" : "VARCHAR(256)";
      columns.add("`" + field.getColumn() + "` " + sqlType);
    }
    jdbc.execute("CREATE TABLE lp_bom_raw_hierarchy (" + String.join(",", columns) + ")");
    return factory;
  }
}
