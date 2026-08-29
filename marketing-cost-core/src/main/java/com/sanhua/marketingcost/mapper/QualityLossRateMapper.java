package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.QualityLossRate;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface QualityLossRateMapper extends BaseMapper<QualityLossRate> {
  @Insert({
      "<script>",
      "INSERT INTO lp_quality_loss_rate (",
      " business_unit_type, rate_year, bare_product_code, product_name, material_spec,",
      " product_model, business_division, product_category, product_subcategory,",
      " category_spec, fourth_level, loss_rate, remark, source_type, source_batch_no",
      ") VALUES",
      "<foreach collection='rows' item='row' separator=','>",
      " (#{row.businessUnitType}, #{row.rateYear}, #{row.bareProductCode},",
      "  #{row.productName}, #{row.materialSpec}, #{row.productModel},",
      "  #{row.businessDivision}, #{row.productCategory}, #{row.productSubcategory},",
      "  #{row.categorySpec}, #{row.fourthLevel}, #{row.lossRate}, #{row.remark},",
      "  #{row.sourceType}, #{row.sourceBatchNo})",
      "</foreach>",
      "ON DUPLICATE KEY UPDATE",
      " product_name = VALUES(product_name),",
      " material_spec = VALUES(material_spec),",
      " product_model = VALUES(product_model),",
      " business_division = VALUES(business_division),",
      " product_category = VALUES(product_category),",
      " product_subcategory = VALUES(product_subcategory),",
      " category_spec = VALUES(category_spec),",
      " fourth_level = VALUES(fourth_level),",
      " loss_rate = VALUES(loss_rate),",
      " remark = VALUES(remark),",
      " source_type = VALUES(source_type),",
      " source_batch_no = VALUES(source_batch_no),",
      " updated_at = CURRENT_TIMESTAMP",
      "</script>"
  })
  int upsertBatch(@Param("rows") List<QualityLossRate> rows);
}
