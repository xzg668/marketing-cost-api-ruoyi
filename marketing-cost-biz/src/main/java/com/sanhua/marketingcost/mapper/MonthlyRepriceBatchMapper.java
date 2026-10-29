package com.sanhua.marketingcost.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import org.apache.ibatis.annotations.Mapper;
/** Historical reads; current contract writes use MonthlyRepriceContractMapper. */
@Mapper
public interface MonthlyRepriceBatchMapper extends BaseMapper<MonthlyRepriceBatch> {}
