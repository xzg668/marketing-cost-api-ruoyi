package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.FactorIdentity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface FactorIdentityMapper extends BaseMapper<FactorIdentity> {

  /** 财务发布很少发生，锁定同业务单元目录，避免并发月份首次创建两个同义主身份。 */
  @Select("SELECT * FROM lp_factor_identity WHERE business_unit_type=#{businessUnitType} ORDER BY id FOR UPDATE")
  List<FactorIdentity> lockForFinancePublication(@Param("businessUnitType") String businessUnitType);

  /** 按组织保留历次财务管理身份；新修订缺因素时仍报错，不固化成原稿常量。 */
  @Select("""
      SELECT DISTINCT i.canonical_factor_key FROM lp_factor_identity i
      JOIN lp_factor_monthly_price p ON p.factor_identity_id=i.id
      JOIN lp_factor_upload_batch b ON b.id=p.source_upload_batch_id
      WHERE b.business_unit_type=#{businessUnitType} AND b.price_org_code=#{orgCode}
        AND b.import_type='FINANCE_BASE' AND b.status='PUBLISHED'
        AND p.status='PUBLISHED' AND i.canonical_factor_key IS NOT NULL
      ORDER BY i.canonical_factor_key
      """)
  List<String> findFinanceManagedKeys(@Param("businessUnitType") String businessUnitType,
      @Param("orgCode") String orgCode);
}
