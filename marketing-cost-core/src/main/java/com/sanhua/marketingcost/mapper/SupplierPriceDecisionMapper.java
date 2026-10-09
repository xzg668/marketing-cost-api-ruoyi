package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.SupplierPriceDecision;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SupplierPriceDecisionMapper extends BaseMapper<SupplierPriceDecision> {
  @Select("SELECT * FROM lp_supplier_price_decision WHERE scope_key=#{scopeKey} ORDER BY id DESC LIMIT 1")
  SupplierPriceDecision latest(@Param("scopeKey") String scopeKey);

  @Select("""
      SELECT d.* FROM lp_supplier_price_decision d
      WHERE d.business_unit_type=#{businessUnit} AND d.decision='WAIT_IMPORT'
        AND d.period_month=#{month} AND d.retry_status IN ('WAITING','FAILED')
        AND NOT EXISTS (SELECT 1 FROM lp_supplier_price_decision n
                        WHERE n.scope_key=d.scope_key AND n.id>d.id)
      ORDER BY d.id
      """)
  List<SupplierPriceDecision> waiting(@Param("businessUnit") String businessUnit,
      @Param("month") String month);
}
