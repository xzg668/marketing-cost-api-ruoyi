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
    "  material_name=VALUES(material_name),",
    "  item_spec=VALUES(item_spec),",
    "  item_model=VALUES(item_model),",
    "  drawing_no=VALUES(drawing_no),",
    "  shape_attr=VALUES(shape_attr),",
    "  material=VALUES(material),",
    "  net_weight_kg=VALUES(net_weight_kg),",
    "  gross_weight_g=VALUES(gross_weight_g),",
    "  business_unit_type=VALUES(business_unit_type),",
    "  biz_unit=VALUES(biz_unit),",
    "  production_dept=VALUES(production_dept),",
    "  production_workshop=VALUES(production_workshop),",
    "  cost_element=VALUES(cost_element),",
    "  finance_category=VALUES(finance_category),",
    "  purchase_category=VALUES(purchase_category),",
    "  production_category=VALUES(production_category),",
    "  sales_category=VALUES(sales_category),",
    "  main_category_code=VALUES(main_category_code),",
    "  main_category_name=VALUES(main_category_name),",
    "  product_property_class=VALUES(product_property_class),",
    "  product_property=VALUES(product_property),",
    "  loss_rate=VALUES(loss_rate),",
    "  daily_capacity=VALUES(daily_capacity),",
    "  lead_time_days=VALUES(lead_time_days),",
    "  package_size=VALUES(package_size),",
    "  default_supplier=VALUES(default_supplier),",
    "  default_buyer=VALUES(default_buyer),",
    "  default_planner=VALUES(default_planner),",
    "  legacy_u9_code=VALUES(legacy_u9_code),",
    "  import_batch_id=VALUES(import_batch_id),",
    "  source=VALUES(source),",
    "  updated_at=NOW()",
    "</script>"
  })
  int upsertBatch(@Param("rows") List<SyncMaterialMasterRow> rows);
}
