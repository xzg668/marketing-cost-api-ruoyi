package com.sanhua.marketingcost.service.household;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.mapper.SysMenuMapper;
import com.sanhua.marketingcost.service.impl.SysMenuServiceImpl;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

@Tag("integration")
class MonthlyMaterialPriceMenuIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(
              DockerImageName.parse(System.getProperty("mpp.mysql.image", "mysql:8.4"))
                  .asCompatibleSubstituteFor("mysql"))
          .withDatabaseName("mpp_menu_test")
          .withUsername("root")
          .withPassword("mpp-menu-test-only")
          .withConfigurationOverride("monthly-pricing/mysql");

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @Test
  void productionMenuMigrationIsIdempotentAndVisibleThroughTheActualMenuMapper() throws Exception {
    MYSQL.start();
    var dataSource =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    var jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("SELECT @@version", String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version", "8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("household-inquiry/pre-menu-schema.sql"))
        .execute(dataSource);
    jdbc.update(
        "INSERT INTO sys_role(role_id,role_name,role_key,role_sort,status)"
            + " VALUES(1,'admin','admin',1,'0'),(2,'ordinary','staff',2,'0'),(10,'报价处长','bu_director',10,'0'),(11,'报价员','bu_staff',11,'0'),(12,'技术协作者','technical_collaborator',12,'0'),(13,'补录财务审核员','finance_reviewer',13,'0')");
    jdbc.update(
        "INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,menu_type,visible,status)"
            + " VALUES(40160,'价格源管理',0,1,'price','M','0','0'),(10,'保留菜单',0,2,'kept','C','0','0')");
    jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(1,40160),(2,10),(10,40160),(11,40160)");
    var originalMenus = jdbc.queryForList("SELECT * FROM sys_menu ORDER BY menu_id");
    var originalGrants = jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id");
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V346__monthly_material_price_menu.sql"),
        "/tmp/household-menu.sql");
    var first =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                + " mpp_menu_test < /tmp/household-menu.sql");
    assertThat(first.getExitCode()).withFailMessage(first.getStderr()).isZero();
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(SysMenuMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(dataSource);
    factory.setConfiguration(configuration);
    var service =
        new SysMenuServiceImpl(
            new SqlSessionTemplate(factory.getObject()).getMapper(SysMenuMapper.class));
    var added =
        service.listAll().stream()
            .filter(
                menu ->
                    menu.getPerms() != null
                        && menu.getPerms().startsWith("price:monthly-material:"))
            .toList();
    assertThat(added).hasSize(3);
    var page =
        added.stream().filter(menu -> menu.getMenuType().equals("C")).findFirst().orElseThrow();
    assertThat(page.getPath()).isEqualTo("/price/monthly-material");
    assertThat(page.getComponent()).isEqualTo("pages:MonthlyMaterialPricePage");
    assertThat(page.getParentId()).isEqualTo(40160L);
    assertThat(page.getBusinessUnitType()).isNull();
    assertThat(added)
        .extracting(menu -> menu.getPerms())
        .containsExactlyInAnyOrder(
            "price:monthly-material:list",
            "price:monthly-material:query",
            "price:monthly-material:references");
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM sys_menu WHERE menu_id IN (10,40160) ORDER BY menu_id"))
        .isEqualTo(originalMenus);
    assertThat(
            jdbc.queryForList(
                "SELECT * FROM sys_role_menu WHERE menu_id IN (10,40160) ORDER"
                    + " BY role_id,menu_id"))
        .isEqualTo(originalGrants);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=1", Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_role_menu WHERE role_id=2", Integer.class))
        .isEqualTo(1);
    for(int role:List.of(10,11))
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=?",Integer.class,role)).isEqualTo(4);
    for(int role:List.of(12,13))
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id=?",Integer.class,role)).isZero();
    assertThat(jdbc.queryForList("SELECT DISTINCT r.role_key FROM sys_role r JOIN sys_role_menu rm ON rm.role_id=r.role_id JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms LIKE 'price:monthly-material:%'",String.class))
        .containsExactlyInAnyOrder("admin","bu_director","bu_staff");
    var menus = jdbc.queryForList("SELECT * FROM sys_menu ORDER BY menu_id");
    var grants = jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id");
    var repeated =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                + " mpp_menu_test < /tmp/household-menu.sql");
    assertThat(repeated.getExitCode()).isZero();
    assertThat(jdbc.queryForList("SELECT * FROM sys_menu ORDER BY menu_id")).isEqualTo(menus);
    assertThat(jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id"))
        .isEqualTo(grants);
  }
}
