package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.SyncMaterialMasterRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface MaterialMasterMapper extends BaseMapper<MaterialMaster> {

  /**
   * T15：批量 UPSERT 同步行到 lp_material_master。
   *
   * <p>UNIQUE KEY = material_code，已存在则刷新业务字段（保留 id/创建时间，更新 updated_at）。
   * 跟 Python sync_material_master.py 同字段、同 ON DUPLICATE KEY UPDATE 语义。
   * 非空覆盖用 COALESCE(VALUES(col), col)，避免 U9 空字段冲掉历史可用主档数据。
   *
   * <p>返回受影响行数：MySQL 对 ON DUPLICATE KEY UPDATE 计 INSERT=1 / UPDATE=2 / 不变=0。
   */
  @Insert({
    "<script>",
    "INSERT INTO lp_material_master (",
    "  material_code, material_name, item_spec, item_model, drawing_no,",
    "  shape_attr, material, net_weight_kg, gross_weight_g,",
    "  business_unit_type, biz_unit, production_dept, production_workshop,",
    "  cost_element, finance_category, purchase_category, production_category, sales_category,",
    "  main_category_code, main_category_name,",
    "  product_property_class, product_property, loss_rate, daily_capacity, lead_time_days, package_size,",
    "  default_supplier, default_buyer, default_planner,",
    "  legacy_u9_code, import_batch_id, source, created_at, updated_at",
    ") VALUES",
    "<foreach collection='rows' item='r' separator=','>",
    "(",
    "  #{r.materialCode}, #{r.materialName}, #{r.itemSpec}, #{r.itemModel}, #{r.drawingNo},",
    "  #{r.shapeAttr}, #{r.material}, #{r.netWeightKg}, #{r.grossWeightG},",
    "  #{r.businessUnitType}, #{r.bizUnit}, #{r.productionDept}, #{r.productionWorkshop},",
    "  #{r.costElement}, #{r.financeCategory}, #{r.purchaseCategory}, #{r.productionCategory}, #{r.salesCategory},",
    "  #{r.mainCategoryCode}, #{r.mainCategoryName},",
    "  #{r.productPropertyClass}, #{r.productProperty}, #{r.lossRate}, #{r.dailyCapacity}, #{r.leadTimeDays}, #{r.packageSize},",
    "  #{r.defaultSupplier}, #{r.defaultBuyer}, #{r.defaultPlanner},",
    "  #{r.legacyU9Code}, #{r.importBatchId}, #{r.source}, NOW(), NOW()",
    ")",
    "</foreach>",
    "ON DUPLICATE KEY UPDATE",
    "  material_name=COALESCE(VALUES(material_name), material_name),",
    "  item_spec=COALESCE(VALUES(item_spec), item_spec),",
    "  item_model=COALESCE(VALUES(item_model), item_model),",
    "  drawing_no=COALESCE(VALUES(drawing_no), drawing_no),",
    "  shape_attr=COALESCE(VALUES(shape_attr), shape_attr),",
    "  material=COALESCE(VALUES(material), material),",
    "  net_weight_kg=COALESCE(VALUES(net_weight_kg), net_weight_kg),",
    "  gross_weight_g=COALESCE(VALUES(gross_weight_g), gross_weight_g),",
    "  business_unit_type=COALESCE(VALUES(business_unit_type), business_unit_type),",
    "  biz_unit=COALESCE(VALUES(biz_unit), biz_unit),",
    "  production_dept=COALESCE(VALUES(production_dept), production_dept),",
    "  production_workshop=COALESCE(VALUES(production_workshop), production_workshop),",
    "  cost_element=COALESCE(VALUES(cost_element), cost_element),",
    "  finance_category=COALESCE(VALUES(finance_category), finance_category),",
    "  purchase_category=COALESCE(VALUES(purchase_category), purchase_category),",
    "  production_category=COALESCE(VALUES(production_category), production_category),",
    "  sales_category=COALESCE(VALUES(sales_category), sales_category),",
    "  main_category_code=COALESCE(VALUES(main_category_code), main_category_code),",
    "  main_category_name=COALESCE(VALUES(main_category_name), main_category_name),",
    "  product_property_class=COALESCE(VALUES(product_property_class), product_property_class),",
    "  product_property=COALESCE(VALUES(product_property), product_property),",
    "  loss_rate=COALESCE(VALUES(loss_rate), loss_rate),",
    "  daily_capacity=COALESCE(VALUES(daily_capacity), daily_capacity),",
    "  lead_time_days=COALESCE(VALUES(lead_time_days), lead_time_days),",
    "  package_size=COALESCE(VALUES(package_size), package_size),",
    "  default_supplier=COALESCE(VALUES(default_supplier), default_supplier),",
    "  default_buyer=COALESCE(VALUES(default_buyer), default_buyer),",
    "  default_planner=COALESCE(VALUES(default_planner), default_planner),",
    "  legacy_u9_code=COALESCE(VALUES(legacy_u9_code), legacy_u9_code),",
    "  import_batch_id=VALUES(import_batch_id),",
    "  source=VALUES(source),",
    "  updated_at=NOW()",
    "</script>"
  })
  int upsertBatch(@Param("rows") List<SyncMaterialMasterRow> rows);
}
