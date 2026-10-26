package com.sanhua.marketingcost.db;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.List;
import org.testcontainers.utility.MountableFile;

/** 在已有完整测试库上执行原稿取价迁移，保持公共 BOM 测试基类不变。 */
public abstract class OriginalPricingIntegrationTestBase extends BomMapperTestBase {
  static {
    // V223's column is needed by current gap consumers. This fresh test database has no
    // historical gaps to backfill; avoid its legacy cross-collation data-update statements.
    try {
      var added = MYSQL.execInContainer("sh", "-c",
          "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot marketing_cost -e "
              + "'ALTER TABLE lp_price_prepare_gap ADD COLUMN material_name VARCHAR(255) NULL AFTER gap_material_code'");
      if (added.getExitCode() != 0) throw new IllegalStateException(added.getStderr());
    } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    for (String script :
        List.of(
            "V51__add_unique_keys_to_high_risk_tables.sql",
            "V52__price_linked_calc_item_precision_6.sql",
            "V56__cost_run_cost_item_add_remark.sql",
            "V171__cost_run_cost_item_remark_text.sql",
            "V83__factor_monthly_price_adjust_columns_repair.sql",
            "V89__supplier_supply_ratio_schema.sql",
            "V122__three_expense_accounting_dimension.sql",
            "V124__three_expense_rate_title_matrix_year_schema.sql",
            "V125__drop_three_expense_rate_raw_period.sql",
            "V152__supplier_supply_ratio_dedupe_key.sql",
            "V155__make_part_no_scrap_calc_row_fields.sql",
            "V177__market_factor_range_price_schema.sql",
            "V186__make_part_child_net_weight.sql",
            "V217__department_fund_rate_calculation_mode.sql",
            "V226__linked_price_import_version_selection.sql",
            "V233__u9_monthly_first_query_snapshot.sql",
            "V240__replace_product_property_with_business_import_rules.sql",
            "V243__costing_row_preserve_bom_occurrence.sql",
            "V293__quote_bom_monthly_snapshot_detail.sql",
            "V304__supplier_supply_ratio_active_batches.sql",
            "V305__supplier_supply_ratio_nullable_value.sql",
            "V306__supplier_price_decision.sql",
            "V308__original_price_version_contract.sql",
            "V309__finance_factor_publication_note.sql",
            "V310__finance_factor_maintenance_menu.sql",
            "V311__original_linked_expression_capacity.sql",
            "V312__range_original_calculation_snapshot.sql",
            "V313__board_final_tender_input.sql",
            "V314__price_preparation_input_scope.sql",
            "V315__original_price_month_lookup_indexes.sql",
            "V316__price_prepare_finance_basis.sql",
            "V318__technical_factor_source_reference.sql",
            "V320__technical_calculation_input_versions.sql",
            "V321__retire_factor_adjustment.sql",
            "V323__monthly_material_price_priority.sql",
            "V325__finance_effective_price.sql",
            "V326__original_workbook_source.sql",
            "V327__price_prepare_gap_price_type.sql",
            "V328__quote_linked_result_input_versions.sql",
            "V329__quote_cost_version_sequence.sql",
            "V330__scrap_price_monthly.sql",
            "V332__settlement_price_versions.sql")) {
      try {
        MYSQL.copyFileToContainer(
            MountableFile.forClasspathResource("db/" + script), "/tmp/" + script);
        var result =
            MYSQL.execInContainer(
                "sh",
                "-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                    // 仅 Testcontainers 初始演示种子；不对业务库开放旧顺序重置。
                    + (script.startsWith("V323__") ? " --init-command='SET @allow_material_price_type_reset=1'" : "")
                    + " marketing_cost < /tmp/"
                    + script);
        if (result.getExitCode() != 0)
          throw new IllegalStateException(script + ": " + result.getStderr());
      } catch (Exception failure) {
        throw new ExceptionInInitializerError(failure);
      }
    }
  }
}
