package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryCmsSource;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.TechnicalDataAuxiliaryReferenceMapper;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 以选中的参考成品读取 CMS 生效科目，完整来源用于保存和送审时复核。 */
@Service
public class TechnicalDataAuxiliarySourceQuery {
  private final TechnicalDataAuxiliaryReferenceMapper search;
  private final CmsCostSourceEffectiveMapper cms;
  private final MaterialMasterRawMapper materials;
  private final OaMessageCodec json;

  public TechnicalDataAuxiliarySourceQuery(TechnicalDataAuxiliaryReferenceMapper search,
      CmsCostSourceEffectiveMapper cms, MaterialMasterRawMapper materials, OaMessageCodec json) {
    this.search = search; this.cms = cms; this.materials = materials; this.json = json;
  }

  public List<TechnicalDataAuxiliaryCmsSource> search(QuoteTechTask task, QuoteTechProduct product, String keyword) {
    if (keyword == null || keyword.isBlank() || keyword.trim().length() > 100) throw new IllegalArgumentException("请输入 1—100 字的成品料号、名称或型号");
    var organization = MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode());
    var codes = search.searchProducts(keyword.trim(), organization.getCode(), task.getBusinessUnitType(),
        YearMonth.parse(product.getAccountingMonth()).getYear(), product.getAccountingMonth());
    if (codes.size() > 100) throw new IllegalArgumentException("参考成品较多，请输入更具体的查询条件");
    return codes.stream().map(code -> read(task, product, code)).toList();
  }

  public TechnicalDataAuxiliaryCmsSource require(QuoteTechTask task, QuoteTechProduct product, String code, String fingerprint) {
    var source = read(task, product, code);
    if (source.items().isEmpty()) throw new IllegalArgumentException("参考成品在本年度、本业务单元及核算月份没有可用辅料科目");
    if (!Objects.equals(source.fingerprint(), fingerprint)) throw new TechnicalDataTaskException(
        TechnicalDataTaskErrorCode.VERSION_CONFLICT, "CMS 辅料来源已变化，请重新查询；本次输入仍保留");
    for (var item : source.items()) {
      if (item.subjectCode() == null || item.subjectCode().isBlank() || item.subjectName() == null || item.subjectName().isBlank()) {
        throw new IllegalArgumentException("CMS 辅料来源缺少科目编码或名称，请先核实公共资料");
      }
      if (item.sourceAmount() == null || item.sourceAmount().signum() < 0) throw new IllegalArgumentException("CMS 辅料来源金额为空或小于零，请核实公共资料");
    }
    return source;
  }

  private TechnicalDataAuxiliaryCmsSource read(QuoteTechTask task, QuoteTechProduct product, String code) {
    if (code == null || code.isBlank() || code.trim().length() > 64) throw new IllegalArgumentException("请选择参考成品");
    code = code.trim();
    var values = cms.selectList(Wrappers.<CmsCostSourceEffective>lambdaQuery()
        .eq(CmsCostSourceEffective::getParentCode, code).eq(CmsCostSourceEffective::getSourceType, "AUX_SUBJECT")
        .eq(CmsCostSourceEffective::getCostYear, YearMonth.parse(product.getAccountingMonth()).getYear())
        .eq(CmsCostSourceEffective::getBusinessUnitType, task.getBusinessUnitType())
        .le(CmsCostSourceEffective::getPeriod, product.getAccountingMonth())
        .and(q -> q.isNull(CmsCostSourceEffective::getSubjectName).or().apply("TRIM(subject_name) <> {0}", "包装辅料"))
        .orderByAsc(CmsCostSourceEffective::getSubjectCode, CmsCostSourceEffective::getId));
    var items = values.stream().map(row -> new TechnicalDataAuxiliaryCmsSource.Item(row.getId(), row.getSubjectCode(),
        row.getSubjectName(), row.getPeriod(), row.getAmountYuan())).toList();
    var found = materials.selectByLatestBatchAndCodes(List.of(code), null,
        MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode()).getCode());
    if (found.size() > 1) throw new IllegalStateException("参考成品存在多个有效料品档案，请先核实公共资料");
    var material = found.isEmpty() ? null : found.getFirst();
    var unsigned = new TechnicalDataAuxiliaryCmsSource(code, material == null ? null : material.getMaterialName(),
        material == null ? null : material.getMaterialModel(), product.getAccountingMonth(), task.getBusinessUnitType(), null, items);
    return new TechnicalDataAuxiliaryCmsSource(unsigned.materialNo(), unsigned.name(), unsigned.model(), unsigned.accountingMonth(),
        unsigned.businessUnitType(), json.canonicalHash(unsigned), items);
  }
}
