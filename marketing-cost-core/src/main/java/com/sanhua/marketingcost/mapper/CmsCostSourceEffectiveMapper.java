package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CmsCostSourceEffectiveMapper extends BaseMapper<CmsCostSourceEffective> {
  /** 工资使用年度已选定的生效来源；两项来源期间可不同，不再按报价月份截断。 */
  default List<CmsCostSourceEffective> selectSalarySources(
      int costYear, String businessUnit, Collection<String> productCodes) {
    if (productCodes == null || productCodes.isEmpty()) return List.of();
    return selectList(Wrappers.<CmsCostSourceEffective>lambdaQuery()
        .eq(CmsCostSourceEffective::getCostYear, costYear)
        .eq(CmsCostSourceEffective::getBusinessUnitType, businessUnit)
        .in(CmsCostSourceEffective::getParentCode, productCodes)
        .in(CmsCostSourceEffective::getSourceType, List.of("SALARY_DIRECT", "SALARY_INDIRECT"))
        .orderByAsc(CmsCostSourceEffective::getParentCode, CmsCostSourceEffective::getSourceType,
            CmsCostSourceEffective::getId));
  }
}
