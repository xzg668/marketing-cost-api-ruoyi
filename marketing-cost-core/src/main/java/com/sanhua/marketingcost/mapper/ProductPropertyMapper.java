package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.ProductProperty;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface ProductPropertyMapper extends BaseMapper<ProductProperty> {

  /** V21：selectList 走数据隔离 */
  @DataScope
  @Override
  List<ProductProperty> selectList(@Param("ew") Wrapper<ProductProperty> queryWrapper);

  @Insert({
      "<script>",
      "INSERT INTO lp_product_property",
      "(business_unit_type, property_year, business_division, product_code, product_name,",
      " product_spec, product_model, product_attr, remark, source_type, source_batch_no, created_at, updated_at)",
      "VALUES",
      "<foreach collection='rows' item='row' separator=','>",
      "(#{row.businessUnitType}, #{row.propertyYear}, #{row.businessDivision}, #{row.productCode},",
      " #{row.productName}, #{row.productSpec}, #{row.productModel}, #{row.productAttr},",
      " #{row.remark}, #{row.sourceType}, #{row.sourceBatchNo}, NOW(), NOW())",
      "</foreach>",
      "ON DUPLICATE KEY UPDATE",
      " business_division=VALUES(business_division), product_name=VALUES(product_name),",
      " product_spec=VALUES(product_spec), product_model=VALUES(product_model),",
      " product_attr=VALUES(product_attr), remark=VALUES(remark),",
      " source_type=VALUES(source_type), source_batch_no=VALUES(source_batch_no), updated_at=NOW()",
      "</script>"
  })
  int upsertBatch(@Param("rows") List<ProductProperty> rows);
}
