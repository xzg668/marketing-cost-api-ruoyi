package com.sanhua.marketingcost.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisSaveRequest;
import com.sanhua.marketingcost.dto.PriceLinkedType2CellSnapshot;
import com.sanhua.marketingcost.dto.PriceLinkedType2FormulaConversionResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2PriceReconcileResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2ProductRow;
import com.sanhua.marketingcost.dto.PriceLinkedType2TaxNormalizationResult;
import com.sanhua.marketingcost.entity.FactorUploadBatch;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisRepository;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.Mockito;

final class PriceLinkedImportBasisTestSupport {

  final InMemoryRepository repository = new InMemoryRepository();
  final ObjectMapper objectMapper = new ObjectMapper();
  final MaterialPriceTypeRouteSyncService priceTypeRouteSyncService =
      Mockito.mock(MaterialPriceTypeRouteSyncService.class);
  final PriceLinkedImportBasisServiceImpl service =
      new PriceLinkedImportBasisServiceImpl(
          repository, objectMapper, priceTypeRouteSyncService);

  PriceLinkedImportBasisSaveRequest defaultRequest(long batchId, LocalDate effectiveDate) {
    return request(
        batchId,
        effectiveDate,
        "$E$2+G6",
        "23",
        "113",
        "100",
        "FALSE",
        "COMMERCIAL");
  }

  PriceLinkedImportBasisSaveRequest request(
      long batchId,
      LocalDate effectiveDate,
      String formula,
      String fixedInput,
      String taxIncludedPrice,
      String taxExcludedPrice,
      String taxFlag,
      String businessUnitType) {
    return request(
        batchId,
        effectiveDate,
        formula,
        PriceLinkedType2FormulaTestSupport.value(
            "Sheet1", "G6", "加工费", fixedInput, null, "元/只"),
        taxIncludedPrice,
        taxExcludedPrice,
        taxFlag,
        businessUnitType);
  }

  PriceLinkedImportBasisSaveRequest requestWithBlankFixedInput(
      long batchId, LocalDate effectiveDate) {
    return request(
        batchId,
        effectiveDate,
        "$E$2+G6",
        PriceLinkedType2FormulaTestSupport.blank("G6", "加工费", "元/只"),
        "90",
        null,
        "TRUE",
        "COMMERCIAL");
  }

  private PriceLinkedImportBasisSaveRequest request(
      long batchId,
      LocalDate effectiveDate,
      String formula,
      PriceLinkedType2CellSnapshot fixedInput,
      String taxIncludedPrice,
      String taxExcludedPrice,
      String taxFlag,
      String businessUnitType) {
    PriceLinkedType2ProductRow product = PriceLinkedType2FormulaTestSupport.rowWithPrices(
        "Sheet1",
        6,
        formula,
        taxIncludedPrice,
        taxExcludedPrice,
        PriceLinkedType2FormulaTestSupport.value(
            "Sheet1", "E2", "1#Cu", "90.000", null, "元/公斤"),
        fixedInput);
    PriceLinkedType2MergedRow merged =
        PriceLinkedType2FormulaTestSupport.merged(product, taxFlag);
    PriceLinkedType2FormulaConversionResult conversion =
        PriceLinkedType2FormulaTestSupport.converter().convert(
            product,
            List.of(PriceLinkedType2FormulaTestSupport.factor(
                "Sheet1", "E2", "1#Cu", 191L, "90.000")));
    PriceLinkedType2TaxNormalizationResult tax =
        new PriceLinkedType2TaxNormalizerImpl().normalize(taxFlag, conversion);
    PriceLinkedType2PriceReconcileResult reconcile =
        new PriceLinkedType2PriceReconcilerImpl(
            row -> "FALSE".equalsIgnoreCase(taxFlag)
                ? Optional.of(new BigDecimal("0.13"))
                : Optional.empty())
            .reconcile(merged, conversion, tax, new BigDecimal("0.0001"));
    PriceLinkedItem candidate = new PriceLinkedItem();
    candidate.setBusinessUnitType(businessUnitType);
    candidate.setOrgCode("ORG-01");
    return new PriceLinkedImportBasisSaveRequest(
        candidate,
        batchId,
        merged,
        conversion,
        tax,
        reconcile,
        effectiveDate,
        Map.of(191L, 6191L));
  }

  void login(String businessUnitType) {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            "tester", "n/a", List.of(new SimpleGrantedAuthority("price:linked-item:list")));
    authentication.setDetails(Map.of(
        BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  void loginAdmin() {
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(
            "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  void clearLogin() {
    SecurityContextHolder.clearContext();
  }

  static final class InMemoryRepository implements PriceLinkedImportBasisRepository {

    final List<PriceLinkedItem> items = new ArrayList<>();
    final List<PriceVariableBinding> bindings = new ArrayList<>();
    final Map<Long, FactorUploadBatch> batches = new LinkedHashMap<>();
    int itemInsertCount;
    int itemUpdateCount;
    int bindingInsertCount;
    int bindingReadCount;
    private long nextItemId = 1001L;
    private long nextBindingId = 2001L;

    @Override
    public PriceLinkedItem findCurrentVersion(PriceLinkedItem identity) {
      return items.stream()
          .filter(item -> item.getEffectiveTo() == null)
          .filter(item -> Objects.equals(item.getPricingMonth(), identity.getPricingMonth()))
          .filter(item -> Objects.equals(item.getMaterialCode(), identity.getMaterialCode()))
          .filter(item -> Objects.equals(
              item.getBusinessUnitType(), identity.getBusinessUnitType()))
          .filter(item -> Objects.equals(item.getSupplierCode(), identity.getSupplierCode()))
          .reduce((first, second) -> second)
          .orElse(null);
    }

    @Override
    public PriceLinkedItem findById(Long id) {
      return items.stream()
          .filter(item -> Objects.equals(item.getId(), id))
          .findFirst()
          .orElse(null);
    }

    @Override
    public FactorUploadBatch findUploadBatchById(Long id) {
      return batches.get(id);
    }

    @Override
    public void insertItem(PriceLinkedItem item) {
      item.setId(nextItemId++);
      items.add(item);
      itemInsertCount++;
    }

    @Override
    public void updateItem(PriceLinkedItem item) {
      itemUpdateCount++;
    }

    @Override
    public void insertBinding(PriceVariableBinding binding) {
      binding.setId(nextBindingId++);
      bindings.add(binding);
      bindingInsertCount++;
    }

    @Override
    public List<PriceVariableBinding> findBindings(Long linkedItemId) {
      bindingReadCount++;
      return bindings.stream()
          .filter(binding -> Objects.equals(binding.getLinkedItemId(), linkedItemId))
          .toList();
    }
  }
}
