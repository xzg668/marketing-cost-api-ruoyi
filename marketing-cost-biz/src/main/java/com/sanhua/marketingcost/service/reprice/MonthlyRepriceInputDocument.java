package com.sanhua.marketingcost.service.reprice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.service.pricing.monthly.ExactJsonDocument;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Immutable equality contract for resolved calculation inputs. Customer/OA ownership and source
 * row coordinates live in the consumer binding. Adapters must supply every section from the
 * actual selected inputs, never from a previous total or a whole-table checksum.
 */
public final class MonthlyRepriceInputDocument {
  public static final int SCHEMA_VERSION = 1;
  public enum Section {
    BOM, QUANTITIES_AND_PACKAGING, MATERIAL_PRICES, SUPPLIER_TERMS,
    MANUFACTURING, TECHNICAL_INPUTS, COPPER_SCENARIOS, POLICIES
  }

  public record Scope(String businessUnitType, YearMonth month, String productCode,
      QuoteDataOrganization organization, String algorithmVersion, String feePolicyVersion) {
    public Scope {
      MonthlyRepriceContract.unit(businessUnitType);
      Objects.requireNonNull(month, "目标月份必填");
      if (!month.toString().matches("[0-9]{4}-(0[1-9]|1[0-2])"))
        throw new IllegalArgumentException("目标月份格式无效");
      requireText(productCode, "产品料号");
      Objects.requireNonNull(organization, "完整组织范围必填");
      requireText(organization.priceOrgCode(), "价格组织");
      requireText(organization.materialOrganizationCode(), "物料组织");
      var priceOrganization = MaterialOrganization.fromPriceOrgCode(organization.priceOrgCode());
      if (priceOrganization != MaterialOrganization.fromCode(organization.materialOrganizationCode()))
        throw new IllegalArgumentException("价格组织与物料组织不一致");
      organization = priceOrganization.toQuoteDataOrganization();
      requireText(algorithmVersion, "算法版本");
      requireText(feePolicyVersion, "费用口径版本");
      if (productCode.length() > 64 || algorithmVersion.length() > 64 || feePolicyVersion.length() > 64)
        throw new IllegalArgumentException("料号或算法口径超过保存长度");
    }
  }

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
  private final Scope scope;
  private final String canonical;
  private final String digest;

  private MonthlyRepriceInputDocument(Scope scope, String canonical, Function<String, String> hash) {
    this.scope = scope;
    this.canonical = canonical;
    digest = Objects.requireNonNull(hash.apply(canonical));
    if (!digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("输入摘要格式无效");
  }

  public static MonthlyRepriceInputDocument capture(Scope scope, Map<Section, String> sections) {
    return capture(scope, sections, MonthlyPriceDocumentCodec::sha256);
  }

  // Injectable digest is confined to this package, to verify collision handling without weakening
  // production equality. The exact canonical document is always compared after candidate lookup.
  static MonthlyRepriceInputDocument capture(Scope scope, Map<Section, String> sections,
      Function<String, String> hash) {
    return capture(scope, sections, hash, ExactJsonDocument.MAX_DOCUMENT_BYTES);
  }

  // A smaller package-local budget lets boundary tests exercise aggregate growth without huge fixtures.
  static MonthlyRepriceInputDocument capture(Scope scope, Map<Section, String> sections,
      Function<String, String> hash, int maximumBytes) {
    if (maximumBytes <= 0 || maximumBytes > ExactJsonDocument.MAX_DOCUMENT_BYTES)
      throw new IllegalArgumentException("核算输入文档大小限制无效");
    Objects.requireNonNull(scope);
    var stable = new EnumMap<Section, String>(Section.class);
    if (sections == null) throw new IllegalArgumentException("完整核算输入区块不能为空");
    stable.putAll(sections);
    if (stable.size() != Section.values().length)
      throw new IllegalArgumentException("完整核算输入必须包含所有解析区块");
    try {
      var result = new StringBuilder("{\"schemaVersion\":").append(SCHEMA_VERSION)
          .append(",\"scope\":")
          .append(SourceEvidence.ofJson(JSON.writeValueAsString(scope)).json());
      int bytes = result.toString().getBytes(StandardCharsets.UTF_8).length + 1;
      for (var section : Section.values()) {
        // A collection contains many individually bounded facts. Bound the whole collection,
        // retaining the original exact-number, duplicate-key, depth and array-order rules.
        String prefix = ",\"" + section.name() + "\":";
        int prefixBytes = prefix.getBytes(StandardCharsets.UTF_8).length;
        String canonical = canonicalSection(section, stable.get(section), maximumBytes - bytes - prefixBytes);
        result.append(prefix).append(canonical);
        bytes += prefixBytes + canonical.getBytes(StandardCharsets.UTF_8).length;
      }
      return new MonthlyRepriceInputDocument(scope, result.append('}').toString(), hash);
    } catch (JsonProcessingException failure) {
      throw new IllegalArgumentException("核算范围无法保存", failure);
    }
  }

  private static String canonicalSection(Section section, String document, int remainingBytes) {
    try { return ExactJsonDocument.canonicalObject(document, remainingBytes); }
    catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("核算输入区块 " + section + " 无效：" + invalid.getMessage(),invalid);
    }
  }

  public String canonical() { return canonical; }
  public String digest() { return digest; }
  public Scope scope() { return scope; }

  public boolean sameInputs(MonthlyRepriceInputDocument other) {
    return other != null && digest.equals(other.digest) && canonical.equals(other.canonical);
  }

  /** Use after the database digest lookup; a hash collision is a different group, never a hit. */
  public boolean matchesStored(String candidateDigest, String candidateDocument) {
    return digest.equals(candidateDigest) && canonical.equals(candidateDocument);
  }

  private static void requireText(String value, String label) {
    if (value == null || value.isBlank() || !value.equals(value.trim()))
      throw new IllegalArgumentException(label + "必填且不可含首尾空白");
  }
}
