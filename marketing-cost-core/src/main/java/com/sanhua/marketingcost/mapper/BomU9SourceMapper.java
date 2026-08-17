package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomU9Source;
import org.apache.ibatis.annotations.Mapper;

/**
 * EasyData 写入的 {@code lp_bom_u9_source} 单层 BOM 只读访问层。
 *
 * <p>报价系统不提供 Excel 导入、批量写入或 Java 展开能力；运行时仅通过 BaseMapper 查询消费。
 */
@Mapper
public interface BomU9SourceMapper extends BaseMapper<BomU9Source> {}
