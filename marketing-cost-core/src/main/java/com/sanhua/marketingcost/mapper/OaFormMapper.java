package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.OaForm;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface OaFormMapper extends BaseMapper<OaForm> {

  @Select("SELECT COUNT(*) FROM lp_oa_quote_document WHERE oa_form_id=#{formId}")
  int countOaSourceBindings(@Param("formId") Long formId);

  /** V21：selectList 走数据隔离（按登录用户 business_unit_type 过滤） */
  @DataScope
  @Override
  List<OaForm> selectList(@Param("ew") Wrapper<OaForm> queryWrapper);

  /** OA 重推的来源字段全量采用，包含本次清空的字段。 */
  @Update("""
      UPDATE oa_form
         SET source_type = #{source.sourceType},
             source_system = #{source.sourceSystem},
             external_form_no = #{source.externalFormNo},
             process_code = #{source.processCode},
             process_name = #{source.processName},
             quote_scenario = #{source.quoteScenario},
             classification_status = #{source.classificationStatus},
             business_unit_type = #{source.businessUnitType},
             accounting_period_month = #{source.accountingPeriodMonth},
             form_type = #{source.formType},
             apply_date = #{source.applyDate},
             customer = #{source.customer},
             applicant_unit = #{source.applicantUnit},
             source_company = #{source.sourceCompany},
             source_business_division = #{source.sourceBusinessDivision},
             expense_product_category = #{source.expenseProductCategory},
             applicant_dept = #{source.applicantDept},
             applicant_office = #{source.applicantOffice},
             applicant_name = #{source.applicantName},
             urgency = #{source.urgency},
             product_attr = #{source.productAttr},
             price_link_mode = #{source.priceLinkMode},
             overseas_sales_mode = #{source.overseasSalesMode},
             trade_terms = #{source.tradeTerms},
             exchange_rate = #{source.exchangeRate},
             copper_price = #{source.copperPrice},
             zinc_price = #{source.zincPrice},
             aluminum_price = #{source.aluminumPrice},
             steel_price = #{source.steelPrice},
             silver_price = #{source.silverPrice},
             gold_price = #{source.goldPrice},
             sus304_price = #{source.sus304Price},
             sus316l_price = #{source.sus316lPrice},
             board_tender = #{source.boardTender},
             other_material = #{source.otherMaterial},
             base_shipping = #{source.baseShipping},
             sale_link = #{source.saleLink},
             remark = #{source.remark},
             ingest_log_id = #{source.ingestLogId},
             updated_at = #{source.updatedAt}
       WHERE id = #{source.id} AND deleted = 0
      """)
  int updateOaSourceHeader(@Param("source") OaForm source);
}
