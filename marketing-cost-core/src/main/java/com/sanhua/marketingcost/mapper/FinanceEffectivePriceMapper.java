package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.FinanceEffectivePrice;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FinanceEffectivePriceMapper extends BaseMapper<FinanceEffectivePrice> {
  @Insert(
      """
<script>
INSERT INTO lp_finance_effective_price
(business_unit_type,org_code,price_type,original_id,original_revision,original_month,
 rule_identity_key,rule_fingerprint,material_code,supplier_code,supplier_name,specification,unit,
 finance_batch_id,finance_month,scheme_code,condition_code,effective_from_month,
 calculator_version,input_fingerprint,unit_price,calculation_json,created_at)
VALUES
<foreach collection="rows" item="r" separator=",">
(#{r.businessUnitType},#{r.orgCode},#{r.priceType},#{r.originalId},#{r.originalRevision},#{r.originalMonth},
 #{r.ruleIdentityKey},#{r.ruleFingerprint},#{r.materialCode},#{r.supplierCode},#{r.supplierName},#{r.specification},#{r.unit},
 #{r.financeBatchId},#{r.financeMonth},#{r.schemeCode},#{r.conditionCode},#{r.effectiveFromMonth},
 #{r.calculatorVersion},#{r.inputFingerprint},#{r.unitPrice},#{r.calculationJson},#{r.createdAt})
</foreach>
</script>
""")
  int insertBatch(@Param("rows") List<FinanceEffectivePrice> rows);
}
