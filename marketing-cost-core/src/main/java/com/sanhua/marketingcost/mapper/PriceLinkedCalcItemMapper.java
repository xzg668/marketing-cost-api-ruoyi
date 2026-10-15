package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PriceLinkedCalcItemMapper extends BaseMapper<PriceLinkedCalcItem> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<PriceLinkedCalcItem> selectList(@Param("ew") Wrapper<PriceLinkedCalcItem> queryWrapper);

  /** 仅插入新结果；唯一键冲突使事务回滚，不能覆盖其他任务已写入的版本。 */
  @Insert(
      """
      <script>
      INSERT INTO lp_price_linked_calc_item (
        source_kind, oa_no, item_code, shape_attr, bom_qty, part_unit_price, part_amount,
        business_unit_type, calc_scene, pricing_month, factor_source,
        calc_fingerprint, calc_status, calc_message, price_as_of_time, price_org_code,
        source_price_record_id, source_price_batch_no, supplier_name, supplier_code,
        supply_ratio, supply_ratio_record_id, source_effective_from, source_effective_to,
        carried_forward, warning_message, failure_code, trace_json, input_snapshot_json,
        calculator_version, result_revision, created_at, updated_at
      ) VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{r.sourceKind}, #{r.oaNo}, #{r.itemCode}, #{r.shapeAttr}, #{r.bomQty},
         #{r.partUnitPrice}, #{r.partAmount}, #{r.businessUnitType}, #{r.calcScene},
         #{r.pricingMonth}, #{r.factorSource}, #{r.calcFingerprint},
         #{r.calcStatus}, #{r.calcMessage}, #{r.priceAsOfTime}, #{r.priceOrgCode},
         #{r.sourcePriceRecordId}, #{r.sourcePriceBatchNo}, #{r.supplierName}, #{r.supplierCode},
         #{r.supplyRatio}, #{r.supplyRatioRecordId}, #{r.sourceEffectiveFrom},
         #{r.sourceEffectiveTo}, #{r.carriedForward}, #{r.warningMessage}, #{r.failureCode},
         #{r.traceJson}, #{r.inputSnapshotJson}, #{r.calculatorVersion}, #{r.resultRevision},
         #{r.createdAt}, #{r.updatedAt})
      </foreach>
      </script>
      """)
  @Options(useGeneratedKeys = true, keyProperty = "rows.id")
  int insertResults(@Param("rows") List<PriceLinkedCalcItem> rows);

  /** 完整输入的成功版本只追加；并发命中唯一键时不改任何已保存证据。 */
  @Insert(
      """
      <script>
      INSERT INTO lp_price_linked_calc_item (
        source_kind, oa_no, item_code, shape_attr, bom_qty, part_unit_price, part_amount,
        business_unit_type, calc_scene, pricing_month, factor_source,
        calc_fingerprint, calc_status, calc_message, price_as_of_time, price_org_code,
        source_price_record_id, source_price_batch_no, supplier_name, supplier_code,
        supply_ratio, supply_ratio_record_id, source_effective_from, source_effective_to,
        carried_forward, warning_message, failure_code, trace_json, input_snapshot_json,
        calculator_version, result_revision, created_at, updated_at
      ) VALUES
      <foreach collection="rows" item="r" separator=",">
        (#{r.sourceKind}, #{r.oaNo}, #{r.itemCode}, #{r.shapeAttr}, #{r.bomQty},
         #{r.partUnitPrice}, #{r.partAmount}, #{r.businessUnitType}, #{r.calcScene},
         #{r.pricingMonth}, #{r.factorSource}, #{r.calcFingerprint},
         #{r.calcStatus}, #{r.calcMessage}, #{r.priceAsOfTime}, #{r.priceOrgCode},
         #{r.sourcePriceRecordId}, #{r.sourcePriceBatchNo}, #{r.supplierName}, #{r.supplierCode},
         #{r.supplyRatio}, #{r.supplyRatioRecordId}, #{r.sourceEffectiveFrom},
         #{r.sourceEffectiveTo}, #{r.carriedForward}, #{r.warningMessage}, #{r.failureCode},
         #{r.traceJson}, #{r.inputSnapshotJson}, #{r.calculatorVersion}, #{r.resultRevision},
         #{r.createdAt}, #{r.updatedAt})
      </foreach>
      ON DUPLICATE KEY UPDATE id=id
      </script>
      """)
  int insertOrReuseResults(@Param("rows") List<PriceLinkedCalcItem> rows);

  /** upsert 已锁住且保证存在的唯一键，读取并发先提交的版本，避开 RR 的旧快照。 */
  @org.apache.ibatis.annotations.Select("""
      <script>SELECT * FROM lp_price_linked_calc_item WHERE
      <foreach collection="rows" item="r" separator=" OR ">
        (business_unit_type=#{r.businessUnitType} AND calc_scene='QUOTE' AND source_kind='PUBLIC'
         AND factor_source=#{r.factorSource} AND oa_no=#{r.oaNo} AND item_code=#{r.itemCode}
         AND pricing_month=#{r.pricingMonth} AND price_as_of_time=#{r.priceAsOfTime}
         AND price_org_code=#{r.priceOrgCode} AND technical_input_key=''
         AND quote_input_key=#{r.calcFingerprint})
      </foreach>
      ORDER BY id FOR SHARE</script>
      """)
  List<PriceLinkedCalcItem> selectReusedResults(@Param("rows") List<PriceLinkedCalcItem> rows);
}
