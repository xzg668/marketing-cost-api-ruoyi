package com.sanhua.marketingcost.mapper.bom;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * BOM 三层架构 4 张表 MapperTest 共用基类。
 *
 * <p>职责：
 * <ul>
 *   <li>启动 testcontainers MySQL 8.4 容器</li>
 *   <li>按顺序落全套迁移脚本（marketing_cost.sql + V2..V16 走 JDBC；V21 / V40 走 mysql CLI）</li>
 *   <li>把容器的 JDBC URL / 账号动态注入到 Spring DataSource</li>
 * </ul>
 *
 * <p>继承：4 个子类共享同一个 {@link DynamicPropertyRegistry} 来源 + {@link SpringBootTest}，
 * Spring TestContext 框架会缓存 ApplicationContext，容器和 Spring 上下文各起一次。
 *
 * <p>运行：子类各自带 {@code @Tag("integration")}（JUnit 5 的 {@code @Tag} 不从父类继承，
 * 所以必须放在子类上），surefire 默认排除 —— 老测试零影响。手动执行：
 * <pre>{@code
 *   mvn -pl marketing-cost-biz test -Dsurefire.excludedGroups= -Dsurefire.groups=integration \
 *       -Dtest='Bom*MapperTest'
 * }</pre>
 */
@SpringBootTest
public abstract class BomMapperTestBase {

  /** 与 V21 E2E 测试保持同一个镜像，省一次拉镜像的时间 */
  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  /**
   * 基础迁移脚本：marketing_cost 主 schema + 附属 schema + V2..V16。
   *
   * <p>普通脚本按 {@code ;} 切分执行，V4 的存储过程通过 mysql CLI 完整执行。
   */
  private static final List<String> BASE_MIGRATION_SCRIPTS = List.of(
      "/db/marketing_cost.sql",
      "/db/oa_form_schema.sql",
      "/db/price_settle.sql",
      "/db/V2__add_indexes.sql",
      "/db/V3__auth_tables.sql",
      "/db/V4__ruoyi_permission_tables.sql",
      "/db/V5__ruoyi_init_data.sql",
      "/db/V6__operation_log_enhance.sql",
      "/db/V7__data_migration.sql",
      "/db/V8__sys_menu_is_cache.sql",
      "/db/V9__bu_director_system_scope.sql",
      "/db/V10__material_price_type_upgrade.sql",
      "/db/V11__product_property_coefficient.sql",
      "/db/V12__formula_engine.sql",
      "/db/V13__price_variable_tax_mode.sql",
      "/db/V14__make_part_spec.sql",
      "/db/V15__raw_material_breakdown.sql",
      "/db/V16__cost_run_result_coefficient.sql");

  /** V24..V37 持续给 lp_price_variable 加字段，Spring 启动时的 VariableRegistry 会依赖这些列 */
  private static final List<String> LATE_MIGRATION_SCRIPTS = List.of(
      "/db/V24__linked_price_variable_extension.sql",
      "/db/V25__finance_base_price_import_support.sql",
      "/db/V26__linked_calc_item_trace.sql",
      "/db/V27__linked_price_menu_perms.sql",
      "/db/V28__linked_price_finance_base_fallback.sql",
      "/db/V31__unified_resolver_model.sql",
      "/db/V32__fix_finance_resolver_params_charset.sql",
      "/db/V33__price_variable_admin_perms.sql",
      "/db/V34__price_variable_binding.sql",
      "/db/V35__price_linked_item_soft_delete.sql",
      "/db/V36__row_local_placeholder.sql",
      "/db/V37__fix_blank_net_weight_unit_scale.sql",
      "/db/V53__material_master_raw_staging.sql",
      "/db/V57__cost_run_cost_item_add_category.sql",
      "/db/V58__aux_subject_unit_price_precision.sql");

  @SuppressWarnings("resource")
  protected static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  static {
    // 静态块：Spring DataSource 初始化前，容器就绪且所有迁移已落
    MYSQL.start();
    try {
      runMigrationsViaJdbc(BASE_MIGRATION_SCRIPTS);
      runScriptViaMysqlCli("/fixtures/price-range-existing-schema.sql", "range-price-base");
      runScriptViaMysqlCli("/db/V21__business_unit_type_isolation.sql", "V21");
      runScriptViaMysqlCli("/db/V22__business_unit_type_isolation_extended.sql", "V22");
      runMigrationsViaJdbc(LATE_MIGRATION_SCRIPTS);
      runScriptViaMysqlCli("/db/V54__material_master_extend_fields.sql", "V54");
      runScriptViaMysqlCli("/db/V59__quote_ingest_schema.sql", "V59");
      runScriptViaMysqlCli("/db/V63__bom_supplement_task_minimal.sql", "V63");
      runScriptViaMysqlCli("/db/V64__cms_cost_source_schema.sql", "V64");
      runScriptViaMysqlCli("/db/V69__cms_material_scrap_ref.sql", "V69");
      runScriptViaMysqlCli("/db/V73__cms_material_scrap_ref_schema_repair.sql", "V73");
      runScriptViaMysqlCli("/db/V75__price_linked_factor_auto_binding_schema.sql", "V75");
      runScriptViaMysqlCli("/db/V78__factor_row_ref_preview_snapshot_columns.sql", "V78");
      runScriptViaMysqlCli("/db/V82__factor_upload_batch_strategy_columns_repair.sql", "V82");
      runScriptViaMysqlCli("/db/V195__factor_upload_row_error.sql", "V195");
      runScriptViaMysqlCli("/db/V76__price_variable_binding_standard_binding_id.sql", "V76");
      runScriptViaMysqlCli("/db/V66__cms_subject_setting_source.sql", "V66");
      runScriptViaMysqlCli("/db/V46__price_fixed_item_source_type.sql", "V46");
      runScriptViaMysqlCli("/db/V47__price_fixed_item_settle_dual_columns.sql", "V47");
      runScriptViaMysqlCli("/db/V92__fixed_price_source_trace_fields.sql", "V92");
      runScriptViaMysqlCli("/db/V95__u9_material_master_raw_20260519.sql", "V95");
      runScriptViaMysqlCli("/db/V98__make_part_price_calc_row.sql", "V98");
      runScriptViaMysqlCli("/db/V170__material_master_raw_organization.sql", "V170");
      runScriptViaMysqlCli("/db/V40__bom_three_layer_and_rules.sql", "V40");
      // T8：V41 含 ALTER TABLE + DELIMITER 存储过程块 + 中文 INSERT，必须走 mysql CLI
      runScriptViaMysqlCli("/db/V41__bom_rule_enhance_and_sub_ref.sql", "V41");
      runScriptViaMysqlCli("/db/V102__price_linked_calc_item_scene_fields.sql", "V102");
      runScriptViaMysqlCli("/db/V103__make_part_price_gap_item.sql", "V103");
      runScriptViaMysqlCli("/db/V104__package_component_price_schema.sql", "V104");
      runScriptViaMysqlCli("/db/V106__package_component_parent_base_qty.sql", "V106");
      runScriptViaMysqlCli("/db/V107__package_component_top_context_key.sql", "V107");
      runScriptViaMysqlCli("/db/V112__package_component_price_oa_no_repair.sql", "V112");
      runScriptViaMysqlCli("/db/V132__package_component_price_as_of_time.sql", "V132");
      runScriptViaMysqlCli("/db/V108__price_prepare_schema.sql", "V108");
      runScriptViaMysqlCli("/db/V113__bom_costing_row_period_month.sql", "V113");
      runScriptViaMysqlCli("/db/V114__product_property_annual_oa_schema.sql", "V114");
      runScriptViaMysqlCli("/db/V116__quality_loss_rate_annual_match_schema.sql", "V116");
      runScriptViaMysqlCli("/db/V119__manufacture_rate_annual_match_schema.sql", "V119");
      runScriptViaMysqlCli(
          "/db/V120__department_fund_rate_annual_subject_schema.sql", "V120");
      runScriptViaMysqlCli("/db/V121__quote_oa_form_excel_model.sql", "V121");
      runScriptViaMysqlCli("/db/V123__quote_oa_form_item_cost_detail_fields.sql", "V123");
      runScriptViaMysqlCli("/db/V131__make_part_price_as_of_time.sql", "V131");
      runScriptViaMysqlCli("/db/V134__price_prepare_message_text.sql", "V134");
      runScriptViaMysqlCli("/db/V137__cost_run_task_queue_schema.sql", "V137");
      runScriptViaMysqlCli("/db/V140__quote_bom_monthly_snapshot.sql", "V140");
      // T11：V43 字典种子 + 老规则停用，纯 INSERT/UPDATE 走 mysql CLI 简单可靠
      runScriptViaMysqlCli("/db/V43__bom_leaf_rollup_dict.sql", "V43");
      // T11 增强：V44 原材料 cost_element 白名单字典（IN_DICT 命中前置硬条件）
      runScriptViaMysqlCli("/db/V44__bom_raw_material_cost_elements_dict.sql", "V44");
      // BSR-01：新 BOM 结算规则表与 costing/sub_ref 新追溯字段，保证旧集成测试 schema 跟实体同步
      // 统一业务变更日志由 V142 建表；T10 技术资料审核把全部决定写入该日志。
      runScriptViaMysqlCli("/db/V142__quote_bom_preparation_schema.sql", "V142");
      runScriptViaMysqlCli("/db/V145__u9_bom_byproduct_master.sql", "V145");
      runScriptViaMysqlCli("/db/V146__bom_settlement_rule_schema.sql", "V146");
      runScriptViaMysqlCli("/db/V153__oa_form_item_calc_status.sql", "V153");
      runScriptViaMysqlCli("/db/V154__make_part_no_scrap_confirmation.sql", "V154");
      runScriptViaMysqlCli("/db/V156__price_prepare_period_month_scope.sql", "V156");
      runScriptViaMysqlCli("/db/V162__quote_costing_row_item_scope.sql", "V162");
      runScriptViaMysqlCli("/db/V165__price_prepare_quote_item_scope.sql", "V165");
      runScriptViaMysqlCli("/db/V166__quote_cost_run_version.sql", "V166");
      runScriptViaMysqlCli(
          "/db/V167__quote_cost_run_workbench_confirmed_version.sql", "V167");
      runScriptViaMysqlCli("/db/V172__bom_raw_hierarchy_source_line_key.sql", "V172");
      runScriptViaMysqlCli("/db/V174__cost_run_trace_snapshot.sql", "V174");
      runScriptViaMysqlCli("/db/V178__cms_sync_publish_signal.sql", "V178");
      runScriptViaMysqlCli("/db/V179__easydata_u9_org_base_tables.sql", "V179");
      runScriptViaMysqlCli("/db/V180__quote_bom_preparation_record_org_scope.sql", "V180");
      runScriptViaMysqlCli("/db/V181__bom_snapshot_package_price_org_scope.sql", "V181");
      runScriptViaMysqlCli("/db/V182__bom_costing_row_org_scope.sql", "V182");
      runScriptViaMysqlCli("/db/V183__cost_run_part_item_org_scope.sql", "V183");
      runScriptViaMysqlCli("/db/V185__quote_price_prepare_as_of_snapshot.sql", "V185");
      runScriptViaMysqlCli("/db/V187__finance_cu_quote_scenario_schema.sql", "V187");
      runScriptViaMysqlCli("/db/V188__finance_cu_quote_base_permissions.sql", "V188");
      runScriptViaMysqlCli("/db/V189__finance_cu_quote_base_page_menu.sql", "V189");
      runScriptViaMysqlCli("/db/V190__price_prepare_settlement_key_uniqueness.sql", "V190");
      runScriptViaMysqlCli("/db/V191__finance_price_prepare_intermediate_isolation.sql", "V191");
      runScriptViaMysqlCli("/db/V198__price_linked_type2_import_basis.sql", "V198");
      runScriptViaMysqlCli("/db/V199__quote_bom_alternative_selection.sql", "V199");
      runScriptViaMysqlCli(
          "/db/V200__quote_bom_alternative_selection_scope_isolation.sql", "V200");
      runScriptViaMysqlCli(
          "/db/V202__quote_effective_bom_and_shape_policy.sql", "V202");
      // 技术资料模块 Mapper 已按最新字段读写，共享集成测试基线必须一次性升级。
      runScriptViaMysqlCli("/db/V218__quote_costing_workspace_and_execution_guard.sql", "V218");
      runScriptViaMysqlCli("/db/V219__price_resolution_evidence.sql", "V219");
      runScriptViaMysqlCli("/db/V220__product_costing_pipeline_state.sql", "V220");
      runScriptViaMysqlCli("/db/V227__quote_cost_algorithm_version.sql", "V227");
      runScriptViaMysqlCli("/db/V239__harden_quote_costing_execution.sql", "V239");
      runScriptViaMysqlCli("/db/V241__replace_quality_loss_rate_with_bare_product_rules.sql", "V241");
      runScriptViaMysqlCli("/db/V242__electronic_drawing_source_node.sql", "V242");
      runScriptViaMysqlCli("/db/V246__quote_technical_data_workspace.sql", "V246");
      runScriptViaMysqlCli("/db/V247__technical_data_workbench_menu.sql", "V247");
      runScriptViaMysqlCli(
          "/db/V248__quote_technical_data_version_immutability.sql", "V248");
      runScriptViaMysqlCli(
          "/db/V249__quote_technical_data_auxiliary_fields.sql", "V249");
      runScriptViaMysqlCli(
          "/db/V250__quote_technical_data_salary_fields.sql", "V250");
      runScriptViaMysqlCli(
          "/db/V251__quote_technical_data_task_submission.sql", "V251");
      runScriptViaMysqlCli(
          "/db/V252__technical_data_review_workflow.sql", "V252");
      runScriptViaMysqlCli(
          "/db/V253__cost_run_effective_technical_data_trace.sql", "V253");
      runScriptViaMysqlCli(
          "/db/V254__technical_data_oa_admin_security.sql", "V254");
      runScriptViaMysqlCli("/db/V256__remove_legacy_collaboration_runtime.sql", "V256");
      runScriptViaMysqlCli(
          "/db/V258__repair_technical_data_menu.sql", "V258");
      runScriptViaMysqlCli(
          "/db/V260__technical_workbench_product_task_versions.sql", "V260");
      runScriptViaMysqlCli("/db/V261__oa_integration_inbox.sql", "V261");
      runScriptViaMysqlCli("/db/V262__oa_technical_workflow.sql", "V262");
      runScriptViaMysqlCli("/db/V263__technical_module_assignees.sql", "V263");
      runScriptViaMysqlCli("/db/V264__technical_source_check_snapshot.sql", "V264");
      runScriptViaMysqlCli("/db/V265__technical_workbench_menu.sql", "V265");
      runScriptViaMysqlCli("/db/V266__technical_workbench_finance_visibility.sql", "V266");
      runScriptViaMysqlCli("/db/V267__technical_workbench_parent_menu.sql", "V267");
      runScriptViaMysqlCli("/db/V268__technical_profile_completeness.sql", "V268");
      runScriptViaMysqlCli("/db/V269__electronic_drawing_weight_unit.sql", "V269");
      runScriptViaMysqlCli("/db/V270__technical_package_optional_legacy_price.sql", "V270");
      runScriptViaMysqlCli("/db/V271__technical_auxiliary_direct_amount.sql", "V271");
      runScriptViaMysqlCli("/db/V272__technical_salary_source_amount.sql", "V272");
      runScriptViaMysqlCli("/db/V273__technical_shared_module_ownership.sql", "V273");
      runScriptViaMysqlCli("/db/V274__technical_price_ownership.sql", "V274");
      runScriptViaMysqlCli("/db/V275__technical_price_sources.sql", "V275");
      runScriptViaMysqlCli("/db/V276__technical_price_calculation_source.sql", "V276");
      runScriptViaMysqlCli("/db/V277__technical_module_source_evidence.sql", "V277");
      runScriptViaMysqlCli("/db/V278__technical_price_monthly_calculation_source.sql", "V278");
      runScriptViaMysqlCli("/db/V279__technical_auxiliary_classification.sql", "V279");
      runScriptViaMysqlCli("/db/V280__technical_price_correction_import.sql", "V280");
      runScriptViaMysqlCli("/db/V281__technical_costing_material_quantities.sql", "V281");
      runScriptViaMysqlCli("/db/V282__make_price_costing_node_identity.sql", "V282");
      runScriptViaMysqlCli("/db/V283__make_price_source_evidence.sql", "V283");
      runScriptViaMysqlCli("/db/V284__quote_final_submission.sql", "V284");
      runScriptViaMysqlCli("/db/V285__oa_workflow_notifications.sql", "V285");
      runScriptViaMysqlCli("/db/V286__oa_submission_source_version.sql", "V286");
      runScriptViaMysqlCli("/db/V287__retire_local_technical_review.sql", "V287");
      runScriptViaMysqlCli("/db/V288__remove_task_level_reviewer.sql", "V288");
      runScriptViaMysqlCli("/db/V289__oa_person_directory.sql", "V289");
      runScriptViaMysqlCli("/db/V290__technical_data_unassigned_preparation.sql", "V290");
      runScriptViaMysqlCli("/db/V292__u9_material_master_product_attr.sql", "V292");
      runScriptViaMysqlCli("/db/V294__sys_user_employee_no.sql", "V294");
      runScriptViaMysqlCli("/db/V295__oa_technical_submission_batch.sql", "V295");
      runScriptViaMysqlCli("/db/V296__oa_material_confirmation.sql", "V296");
      runScriptViaMysqlCli("/db/V297__oa_technical_return_scope.sql", "V297");
      runScriptViaMysqlCli("/db/V298__technical_return_pending_status.sql", "V298");
      runScriptViaMysqlCli("/db/V299__remove_obsolete_oa_event_inbox.sql", "V299");
      runScriptViaMysqlCli("/db/V300__oa_final_cost_native_submission.sql", "V300");
    } catch (Exception e) {
      // 把 root cause 的文字信息拼进 message，避免 surefire 只保留 Caused by 的短描述
      Throwable root = e;
      while (root.getCause() != null) {
        root = root.getCause();
      }
      throw new IllegalStateException(
          "BOM MapperTest 基类初始化失败：root="
              + root.getClass().getName() + " msg=" + root.getMessage(),
          e);
    }
  }

  /**
   * 把容器 URL 注入 Spring DataSource。
   *
   * <p>同时禁用 Redis 自动配置，避免测试环境缺 Redis 时启动失败。
   */
  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> MYSQL.getJdbcUrl()
            + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
            + "&allowPublicKeyRetrieval=true&useSSL=false");
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.data.redis.repositories.enabled", () -> "false");
  }

  // ============================ 私有辅助 ============================

  /** 把一批脚本按 {@code ;} 切分走 JDBC 执行；异常被 ignore（幂等安全）。 */
  private static void runMigrationsViaJdbc(List<String> scripts) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      for (String script : scripts) {
        if (script.endsWith("V4__ruoyi_permission_tables.sql")) {
          runScriptViaMysqlCli(script, "V4");
        } else {
          runScriptViaJdbc(stmt, script);
        }
      }
    }
  }

  /**
   * JDBC 朴素按 {@code ;} 切分执行单个脚本。
   *
   * <p>含 DELIMITER 的脚本由调用方交给 mysql CLI，不在此处拆分。
   */
  private static void runScriptViaJdbc(Statement stmt, String classpathResource) throws Exception {
    try (InputStream in = BomMapperTestBase.class.getResourceAsStream(classpathResource)) {
      if (in == null) {
        throw new IllegalStateException("迁移脚本不存在：" + classpathResource);
      }
      String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      for (String raw : content.split(";")) {
        String sql = raw.trim();
        if (sql.isEmpty() || (sql.startsWith("/*") && sql.endsWith("*/"))) {
          continue;
        }
        try {
          stmt.execute(sql);
        } catch (Exception ignore) {
          // 幂等安全：已存在/不存在/DELIMITER 切分错 —— 底层 table 照样落
        }
      }
    }
  }

  /**
   * 用容器内 mysql CLI 执行脚本。
   *
   * <p>V21 含 DELIMITER 存储过程块；V40 含中文 INSERT + 多行 CREATE 复杂结构，
   * JDBC 按 {@code ;} 切分容易切坏某条语句。CLI 一次性把整个文件交给 mysql server
   * 解析，与手工 {@code docker exec ... mysql < file.sql} 行为一致（已手工验证）。
   */
  private static void runScriptViaMysqlCli(String classpathResource, String logTag) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(classpathResource), "/tmp/" + logTag + ".sql");
    ExecResult result =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
                + " " + MYSQL.getDatabaseName() + " < /tmp/" + logTag + ".sql");
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          logTag + " mysql CLI 执行失败: exit=" + result.getExitCode()
              + "\nstdout:\n" + result.getStdout()
              + "\nstderr:\n" + result.getStderr());
    }
  }

  protected static Connection openConnection() throws Exception {
    String url = MYSQL.getJdbcUrl()
        + "?allowMultiQueries=true&useUnicode=true&characterEncoding=utf8"
        + "&allowPublicKeyRetrieval=true&useSSL=false";
    return DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword());
  }

}
