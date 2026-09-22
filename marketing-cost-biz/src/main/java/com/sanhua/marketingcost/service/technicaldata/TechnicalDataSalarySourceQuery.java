package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryCmsSource;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.TechnicalDataSalaryReferenceMapper;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 生效工资与整批发布证据分开查询，空结果不能自行证明 CMS 已核实无资料。 */
@Repository
public class TechnicalDataSalarySourceQuery {
  public record Source(List<CmsCostSourceEffective> rows, Long confirmedBatchId) {}
  private final CmsCostSourceEffectiveMapper sources;
  private final JdbcTemplate jdbc;
  private final TechnicalDataSalaryReferenceMapper search;
  private final MaterialMasterRawMapper materials;
  private final OaMessageCodec json;

  public TechnicalDataSalarySourceQuery(CmsCostSourceEffectiveMapper sources, JdbcTemplate jdbc,
      TechnicalDataSalaryReferenceMapper search, MaterialMasterRawMapper materials, OaMessageCodec json) {
    this.sources = sources;
    this.jdbc = jdbc;
    this.search = search;
    this.materials = materials;
    this.json = json;
  }

  public List<TechnicalDataSalaryCmsSource> searchReferences(
      QuoteTechTask task, QuoteTechProduct product, String keyword, String entryMode) {
    if (keyword == null || keyword.isBlank() || keyword.trim().length() > 100) {
      throw new IllegalArgumentException("请输入 1—100 字的参考成品料号、名称或型号");
    }
    int year = YearMonth.parse(product.getAccountingMonth()).getYear();
    var codes = search.searchProducts(keyword.trim(), materialOrg(task), task.getBusinessUnitType(), year);
    if (codes.size() > 100) throw new IllegalArgumentException("参考成品较多，请输入更具体的查询条件");
    return references(task, product, codes, entryMode);
  }

  public TechnicalDataSalaryCmsSource requireReference(QuoteTechTask task, QuoteTechProduct product,
      String code, String fingerprint, String entryMode) {
    if (code == null || code.isBlank() || code.trim().length() > 64) throw new IllegalArgumentException("请选择工资参考成品");
    var source = references(task, product, List.of(code.trim()), entryMode).getFirst();
    if (!source.issues().isEmpty()) throw new IllegalArgumentException(String.join("；", source.issues()));
    if (!Objects.equals(source.fingerprint(), fingerprint)) throw new TechnicalDataTaskException(
        TechnicalDataTaskErrorCode.VERSION_CONFLICT, "CMS 工资来源已变化，请重新查询；本次选择仍保留");
    return source;
  }

  private List<TechnicalDataSalaryCmsSource> references(QuoteTechTask task, QuoteTechProduct product,
      List<String> codes, String entryMode) {
    if (!Set.of("REFERENCE", "UPLOAD").contains(Objects.toString(entryMode, ""))) throw new IllegalArgumentException("工资录入方式无效");
    if (codes.isEmpty()) return List.of();
    int year = YearMonth.parse(product.getAccountingMonth()).getYear();
    var rows = sources.selectSalarySources(year, task.getBusinessUnitType(), codes);
    var masters = materials.selectByLatestBatchAndCodes(codes, null, materialOrg(task));
    return codes.stream().map(code -> {
      var issues = new ArrayList<String>();
      var direct = "UPLOAD".equals(entryMode) ? null : item(rows, code, "DIRECT", year, issues);
      var indirect = item(rows, code, "INDIRECT", year, issues);
      var found = masters.stream().filter(row -> code.equalsIgnoreCase(row.getMaterialCode())).toList();
      if (found.size() > 1) issues.add("参考成品存在多个有效料品档案，请核实公共资料");
      var master = found.size() == 1 ? found.getFirst() : null;
      var unsigned = new TechnicalDataSalaryCmsSource(code, master == null ? null : master.getMaterialName(),
          master == null ? null : master.getMaterialModel(), year, task.getBusinessUnitType(), null, direct, indirect, issues);
      return new TechnicalDataSalaryCmsSource(code, unsigned.name(), unsigned.model(), year, task.getBusinessUnitType(),
          json.canonicalHash(unsigned), direct, indirect, issues);
    }).toList();
  }

  private TechnicalDataSalaryCmsSource.Item item(List<CmsCostSourceEffective> sources, String code,
      String laborType, int year, List<String> issues) {
    var rows = sources.stream().filter(row -> code.equalsIgnoreCase(row.getParentCode())
        && ("SALARY_" + laborType).equals(row.getSourceType())).toList();
    String label = "DIRECT".equals(laborType) ? "直接人工工资" : "辅助人员工资";
    if (rows.size() != 1) {
      issues.add(rows.isEmpty() ? "参考成品缺少" + label : "参考成品的" + label + "存在重复生效来源");
      return null;
    }
    var row = rows.getFirst();
    if (row.getAmountYuan() == null || row.getAmountYuan().signum() < 0) issues.add(label + "金额为空或小于零");
    if (blank(row.getSubjectCode()) || blank(row.getSubjectName()) || blank(row.getSourceTable()) || blank(row.getSourceRowIds())) {
      issues.add(label + "缺少科目或原始来源，请核实公共资料");
    }
    try {
      if (YearMonth.parse(row.getPeriod()).getYear() != year) issues.add(label + "来源期间不属于本核算年度");
    } catch (RuntimeException exception) { issues.add(label + "来源期间无效"); }
    return new TechnicalDataSalaryCmsSource.Item(row.getId(), laborType, row.getSubjectCode(), row.getSubjectName(),
        row.getPeriod(), row.getSourceTable(), row.getSourceRowIds(), row.getAmountYuan());
  }

  private static boolean blank(String value) { return value == null || value.isBlank(); }
  private static String materialOrg(QuoteTechTask task) { return MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode()).getCode(); }

  public Source read(String productCode, int year, String businessUnit) {
    var rows = sources.selectSalarySources(year, businessUnit, List.of(productCode));
    var batches = jdbc.query("""
        SELECT id,status FROM cms_sync_publish_signal
         WHERE cost_year=? AND (business_unit_type=? OR business_unit_type IS NULL OR business_unit_type='')
         ORDER BY id DESC LIMIT 1
        """, (rs, index) -> "SUCCESS".equals(rs.getString("status")) ? rs.getLong("id") : null,
        year, businessUnit);
    Long confirmed = batches.isEmpty() ? null : batches.getFirst();
    var present = rows.stream().map(CmsCostSourceEffective::getSourceType).toList();
    if (confirmed != null && !present.contains("SALARY_DIRECT")) {
      Integer rawCount = jdbc.queryForObject("""
          SELECT COUNT(*) FROM cms_workshop_labor_raw WHERE parent_code=? AND period LIKE ? AND business_unit_type=?
          """, Integer.class, productCode, year + "-%", businessUnit);
      if (rawCount != null && rawCount > 0) confirmed = null;
    }
    if (confirmed != null && !present.contains("SALARY_INDIRECT")) {
      var subjectCodes = jdbc.queryForList("""
          SELECT DISTINCT TRIM(second_subject_code) FROM cms_subject_setting_raw
           WHERE TRIM(first_subject_name)='工资' AND TRIM(second_subject_name)='辅助人员工资'
             AND business_unit_type=? AND NULLIF(TRIM(second_subject_code),'') IS NOT NULL
          """, String.class, businessUnit);
      if (subjectCodes.size() != 1) confirmed = null;
      else {
        Integer rawCount = jdbc.queryForObject("""
            SELECT COUNT(*) FROM cms_product_subject_cost_raw
             WHERE parent_code=? AND period LIKE ? AND business_unit_type=? AND TRIM(second_subject_code)=?
            """, Integer.class, productCode, year + "-%", businessUnit, subjectCodes.getFirst());
        if (rawCount != null && rawCount > 0) confirmed = null;
      }
    }
    return new Source(rows, confirmed);
  }
}
