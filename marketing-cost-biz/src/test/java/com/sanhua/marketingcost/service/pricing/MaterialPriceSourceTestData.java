package com.sanhua.marketingcost.service.pricing;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
/** Disposable Testcontainers fixtures only. */
public final class MaterialPriceSourceTestData {
  private MaterialPriceSourceTestData() {}
  public static void schema(JdbcTemplate j) {
    new ResourceDatabasePopulator(new ClassPathResource("db/V376__material_price_source_attribute.sql"),new ClassPathResource("db/V377__material_price_sources_menu.sql")).execute(j.getDataSource());
    if(j.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_material_price_attribute' AND column_name='price_month'",Integer.class)==0)
      new ResourceDatabasePopulator(new ClassPathResource("db/V378__material_price_source_snapshot.sql")).execute(j.getDataSource());
    j.update("INSERT INTO sys_role(role_name,role_key,role_sort,status,del_flag,create_time,update_time) SELECT '财务验收','finance_reviewer',90,'0','0',NOW(),NOW() WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE role_key='finance_reviewer')");
    new ResourceDatabasePopulator(new ClassPathResource("db/V379__material_price_source_finance_permissions.sql")).execute(j.getDataSource());
  }
  public static void fixed(JdbcTemplate j,String unit,String org,String code,String month,String price) {
    j.update("INSERT INTO lp_price_fixed_item(business_unit_type,org_code,material_code,material_name,pricing_month,source_kind,source_type,fixed_price,unit,tax_included,created_at,updated_at) VALUES(?,?,?,'来源测试料',?,'PUBLIC','PURCHASE_FIXED',?,'只',0,NOW(),NOW())",unit,org,code,month,price);
  }
  public static void linked(JdbcTemplate j,String unit,String org,String code,String month) {
    j.update("INSERT INTO lp_price_linked_item(business_unit_type,org_code,material_code,material_name,pricing_month,source_kind,rule_status,deleted,created_at,updated_at,effective_from,effective_to) VALUES(?,?,?,'来源测试料',?,'PUBLIC','PUBLISHED',0,NOW(),NOW(),'2026-01-01','2099-01-01')",unit,org,code,month);
  }
  public static void tree(JdbcTemplate j,String code,String month) {
    String b="CAT-TREE-"+code+month;
    j.update("INSERT INTO lp_quote_effective_bom_node(build_batch_id,origin_monthly_snapshot_id,effective_variant_hash,top_product_code,cost_period_month,price_org_code,node_key,node_level,node_path,material_code,material_name,material_model,material_spec,material_unit,qty_per_parent,qty_per_top,effective_material_shape,shape_resolution_source,source_bom_type) VALUES(?,1,'TEST','CAT-TOP',?,'210',?,1,?,?,'计价树自制件','TREE-MODEL','TREE-SPEC','只',1,1,'MANUFACTURE','U9','U9')",b,month,code,"/CAT-TOP/"+code,code);
    j.update("INSERT INTO lp_quote_costing_workspace(oa_no,oa_form_item_id,product_code,period_month,business_unit_type,current_bom_build_batch_id) VALUES(?,?,'CAT-TOP',?,'COMMERCIAL',?)",b,(long)Math.abs(b.hashCode()),month,b);
  }
}
