package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective.SalaryModelMatch;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** CMS salary for the costing product, with exact-model lookup after confirmed absence. */
@Component
public class CmsQuoteSalarySources {
  private static final String DIRECT = "SALARY_DIRECT";
  private static final String INDIRECT = "SALARY_INDIRECT";
  private final CmsCostSourceEffectiveMapper effective;
  private final MaterialMasterRawMapper materials;
  private final JdbcTemplate jdbc;

  public CmsQuoteSalarySources(CmsCostSourceEffectiveMapper effective, MaterialMasterRawMapper materials,
      JdbcTemplate jdbc) {
    this.effective = effective;
    this.materials = materials;
    this.jdbc = jdbc;
  }

  public record Source(List<CmsCostSourceEffective> rows, Long confirmedBatchId) {}

  public Source read(String code, int year, String unit, String organization) {
    var rows = effective.selectSalarySources(year, unit, List.of(code));
    Long published = latestSuccessfulBatch(year, unit);
    Long confirmed = confirmsAbsence(code, year, unit, rows, published) ? published : null;
    if (rows.isEmpty() && confirmed != null && StringUtils.hasText(organization)) {
      rows = exactModelSalary(code, year, unit, organization, published);
    }
    return new Source(rows, confirmed);
  }

  public List<CmsCostSourceEffective> modelFallback(String code, int year, String unit, String organization) {
    return effective.selectSalarySources(year, unit, List.of(code)).isEmpty()
        ? complete(List.of(code), year, unit, organization, List.of()) : List.of();
  }

  /** Preserve the original prepared rows and resolve only products with neither type of salary. */
  public List<CmsCostSourceEffective> complete(
      Collection<String> codes, int year, String unit, String organization,
      List<CmsCostSourceEffective> original) {
    if (!StringUtils.hasText(organization)) return original;
    var missing = codes.stream().filter(code ->
        original.stream().noneMatch(row -> code.equalsIgnoreCase(row.getParentCode()))).toList();
    if (missing.isEmpty()) return original;
    Long published = latestSuccessfulBatch(year, unit);
    if (published == null) return original;
    var result = new ArrayList<>(original);
    for (String code : missing) {
      if (confirmsAbsence(code, year, unit, List.of(), published)) {
        result.addAll(exactModelSalary(code, year, unit, organization, published));
      }
    }
    return result;
  }

  private List<CmsCostSourceEffective> exactModelSalary(
      String code, int year, String unit, String organization, Long published) {
    var master = materials.selectByLatestBatchAndCodes(List.of(code), null, organization);
    if (master.size() != 1 || !StringUtils.hasText(master.getFirst().getMaterialModel())) return List.of();
    String model = master.getFirst().getMaterialModel().trim();
    var codes = materials.selectActiveCodesByExactModel(model, organization).stream()
        .filter(candidate -> !code.equalsIgnoreCase(candidate)).distinct().toList();
    if (codes.isEmpty()) return List.of();
    var sources = effective.selectSalarySources(year, unit, codes).stream()
        .collect(Collectors.groupingBy(CmsCostSourceEffective::getParentCode));
    var eligible = sources.entrySet().stream()
        .filter(entry -> eligible(entry.getKey(), year, unit, entry.getValue(), published)).toList();
    if (eligible.size() != 1) return List.of();
    var match = new SalaryModelMatch(code, model);
    return eligible.getFirst().getValue().stream().map(row -> {
      var copy = new CmsCostSourceEffective();
      BeanUtils.copyProperties(row, copy);
      copy.setSalaryModelMatch(match);
      return copy;
    }).toList();
  }

  private boolean eligible(String code, int year, String unit, List<CmsCostSourceEffective> rows, Long published) {
    return rows.stream().map(CmsCostSourceEffective::getSourceType).distinct().count() == rows.size()
        && rows.stream().allMatch(row -> usable(row, year))
        && confirmsAbsence(code, year, unit, rows, published);
  }

  private boolean usable(CmsCostSourceEffective row, int year) {
    if (row.getAmountYuan() == null || row.getAmountYuan().signum() < 0
        || !StringUtils.hasText(row.getSourceRowIds())
        || !StringUtils.hasText(row.getPeriod())
        || !StringUtils.hasText(row.getSubjectCode())
        || !(DIRECT.equals(row.getSourceType()) && "cms_workshop_labor_raw".equals(row.getSourceTable())
            || INDIRECT.equals(row.getSourceType()) && "cms_product_subject_cost_raw".equals(row.getSourceTable()))) {
      return false;
    }
    try {
      return YearMonth.parse(row.getPeriod()).getYear() == year;
    } catch (DateTimeParseException exception) {
      return false;
    }
  }

  private Long latestSuccessfulBatch(int year, String unit) {
    var batches = jdbc.query("""
        SELECT id,status FROM cms_sync_publish_signal
        WHERE cost_year=? AND (business_unit_type=? OR business_unit_type IS NULL OR business_unit_type='')
        ORDER BY id DESC LIMIT 1
        """, (rs, index) -> "SUCCESS".equals(rs.getString("status")) ? rs.getLong("id") : null, year, unit);
    return batches.isEmpty() ? null : batches.getFirst();
  }

  private boolean confirmsAbsence(
      String code, int year, String unit, List<CmsCostSourceEffective> rows, Long published) {
    if (published == null) return false;
    Set<String> present = rows.stream().map(CmsCostSourceEffective::getSourceType).collect(Collectors.toSet());
    String periods = year + "-%";
    if (!present.contains(DIRECT) && Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM cms_workshop_labor_raw
          WHERE parent_code=? AND period LIKE ? AND business_unit_type=?)
        """, Boolean.class, code, periods, unit))) return false;
    if (present.contains(INDIRECT)) return true;
    var subjectCodes = jdbc.queryForList("""
        SELECT DISTINCT TRIM(second_subject_code) FROM cms_subject_setting_raw
        WHERE TRIM(first_subject_name)='工资' AND TRIM(second_subject_name)='辅助人员工资'
          AND business_unit_type=? AND NULLIF(TRIM(second_subject_code),'') IS NOT NULL
        """, String.class, unit);
    if (subjectCodes.size() != 1) return false;
    return Boolean.FALSE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM cms_product_subject_cost_raw
          WHERE parent_code=? AND period LIKE ? AND business_unit_type=? AND TRIM(second_subject_code)=?)
        """, Boolean.class, code, periods, unit, subjectCodes.getFirst()));
  }
}
