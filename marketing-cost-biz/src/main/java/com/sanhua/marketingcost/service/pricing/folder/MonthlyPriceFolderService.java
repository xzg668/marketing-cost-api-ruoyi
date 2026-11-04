package com.sanhua.marketingcost.service.pricing.folder;

import com.sanhua.marketingcost.service.pricing.OriginalRuleSignature;

import static com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.*;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.impl.*;
import java.time.*;
import java.util.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 文件读取、逐文件处理和最终补齐都有持久记录；重试不靠浏览器内存判断成功。 */
@Service
public class MonthlyPriceFolderService {
  private static final int MAX_FILES = 128;
  private static final int MAX_FILE_BYTES = 25 * 1024 * 1024;
  private final MonthlyPriceImportMapper imports;
  private final MonthlyPriceImportFileMapper files;
  private final FactorIdentityMapper identities;
  private final MonthlyPriceFolderParser parser;
  private final MonthlyPriceFolderCodec codec;
  private final MonthlyPriceFolderPlan planner;
  private final MonthlyPriceFolderSources sources;
  private final OriginalRuleSignature signatures;
  private final MonthlyPriceFolderFinance finance;
  private final OriginalLinkedImportService linked;
  private final OriginalRangeImportService ranges;
  private final MonthlyPriceRuleCarryForward carry;
  private final MonthlyPriceFolderPublication publication;
  private final TransactionTemplate transaction;

  public MonthlyPriceFolderService(MonthlyPriceImportMapper imports, MonthlyPriceImportFileMapper files,
      FactorIdentityMapper identities, MonthlyPriceFolderParser parser, MonthlyPriceFolderCodec codec,
      MonthlyPriceFolderPlan planner, MonthlyPriceFolderSources sources, OriginalRuleSignature signatures,
      MonthlyPriceFolderFinance finance, OriginalLinkedImportService linked, OriginalRangeImportService ranges,
      MonthlyPriceRuleCarryForward carry, MonthlyPriceFolderPublication publication, PlatformTransactionManager manager) {
    this.imports = imports; this.files = files; this.identities = identities; this.parser = parser;
    this.codec = codec; this.planner = planner; this.sources = sources; this.signatures = signatures;
    this.finance = finance; this.linked = linked; this.ranges = ranges; this.carry = carry;
    this.publication = publication;
    transaction = new TransactionTemplate(manager);
  }

  public Report start(String month, String requestKey, List<String> paths) {
    String unit = unit(); String actor = actor();
    if (month == null || !month.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException("月份格式应为yyyy-MM");
    YearMonth.parse(month);
    if (requestKey == null || !requestKey.matches("[A-Za-z0-9_-]{8,64}")) throw new IllegalArgumentException("导入请求标识无效");
    if (paths == null || paths.isEmpty() || paths.size() > MAX_FILES) throw new IllegalArgumentException("每次选择1至128个文件");
    List<String> normalized = paths.stream().map(this::path).toList();
    if (new HashSet<>(normalized).size() != normalized.size()) throw new IllegalArgumentException("文件夹中路径重复");
    Long id = transaction.execute(tx -> {
      identities.lockForFinancePublication(unit);
      var existing = imports.selectOne(Wrappers.lambdaQuery(MonthlyPriceImport.class)
          .eq(MonthlyPriceImport::getBusinessUnitType, unit).eq(MonthlyPriceImport::getRequestKey, requestKey));
      if (existing != null) {
        if (!existing.getPricingMonth().equals(month)
            || !new TreeSet<>(activeFiles(existing.getId()).stream().map(MonthlyPriceImportFile::getRelativePath).toList()).equals(new TreeSet<>(normalized)))
          throw new IllegalArgumentException("同一请求标识不能更换月份或文件清单");
        return existing.getId();
      }
      if (imports.unfinished(unit, month) != 0) throw new IllegalArgumentException("该月份已有未完成文件夹，请继续处理原批次");
      var now = LocalDateTime.now();
      var batch = new MonthlyPriceImport();
      batch.setBusinessUnitType(unit); batch.setPricingMonth(month); batch.setRequestKey(requestKey);
      batch.setStatus("UPLOADING"); batch.setRevision(1L);
      batch.setCreatedBy(actor); batch.setCreatedAt(now); batch.setUpdatedAt(now); imports.insert(batch);
      for (String relative : normalized) {
        var file = new MonthlyPriceImportFile();
        file.setImportId(batch.getId()); file.setRelativePath(relative); file.setPathHash(signatures.hash(relative));
        file.setKind("PENDING"); file.setStatus("PENDING"); file.setActiveFlag(1);
        file.setCreatedAt(now); file.setUpdatedAt(now); files.insert(file);
      }
      return batch.getId();
    });
    return report(id);
  }

  public Report latest(String month) {
    var rows = imports.selectList(Wrappers.lambdaQuery(MonthlyPriceImport.class)
        .eq(MonthlyPriceImport::getBusinessUnitType, unit()).eq(MonthlyPriceImport::getPricingMonth, month)
        .orderByDesc(MonthlyPriceImport::getId).last("LIMIT 1"));
    return rows.isEmpty() ? null : report(rows.getFirst().getId());
  }

  public record UploadResult(long fileId, long revision, String kind, int errors) {}

  public UploadResult upload(long id, long fileId, byte[] bytes) {
    var batch = owned(id, false);
    var file = ownedFile(batch, fileId);
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_BYTES)
      throw new IllegalArgumentException("文件不能为空且不得超过25MB");
    String hash = OriginalRuleSignature.digest(bytes);
    // 解析不持有数据库写锁；入库后原件与解析结果作为同一个版本保存。
    var parsed = parser.parse(bytes, file.getRelativePath(), batch.getBusinessUnitType());
    return transaction.execute(tx -> {
      var locked = editable(id); var current = ownedFile(locked, fileId);
      if (hash.equals(current.getFileSha256()) && codec.write(parsed).equals(current.getParsedJson()))
        return new UploadResult(current.getId(), locked.getRevision(), current.getKind(), parsed.errors().size());
      if (current.getFileSha256() != null) {
        files.update(null, Wrappers.lambdaUpdate(MonthlyPriceImportFile.class)
            .eq(MonthlyPriceImportFile::getId, current.getId()).set(MonthlyPriceImportFile::getActiveFlag, 0));
        var replacement = new MonthlyPriceImportFile();
        replacement.setImportId(id); replacement.setRelativePath(current.getRelativePath()); replacement.setPathHash(current.getPathHash());
        replacement.setSupersedesId(current.getId()); replacement.setActiveFlag(1); replacement.setCreatedAt(LocalDateTime.now());
        current = replacement;
      }
      current.setFileSha256(hash); current.setSourceWorkbook(bytes); current.setKind(parsed.kind());
      current.setStatus(parsed.errors().isEmpty() ? "SCANNED" : "ERROR"); current.setParsedJson(codec.write(parsed));
      current.setUpdatedAt(LocalDateTime.now());
      if (current.getId() == null) files.insert(current);
      else files.updateById(current);
      clearFileFailure(current.getId()); advance(locked, "REVIEW");
      return new UploadResult(current.getId(), locked.getRevision(), parsed.kind(), parsed.errors().size());
    });
  }

  public void uploadFailed(long id, long fileId, String message) {
    transaction.executeWithoutResult(tx -> {
      var batch = editable(id); var file = ownedFile(batch, fileId);
      if (file.getFileSha256() != null) return; // 网络响应丢失不能把已保存原件重新标成失败。
      file.setStatus("ERROR"); file.setErrorMessage(message == null ? "上传失败，请重试该文件" : message.substring(0, Math.min(500, message.length())));
      file.setUpdatedAt(LocalDateTime.now()); files.updateById(file); advance(batch, "REVIEW");
    });
  }

  /** 每个文件的规则与处理结果在同一个事务提交；某文件失败不回滚其他文件。 */
  public Report process(long id, long fileId, long revision) {
    var initial = owned(id, false); revision(initial, revision);
    if (initial.getStatus().equals("COMPLETE")) return report(id);
    for (var file : List.of(ownedFile(initial, fileId))) {
      try {
        transaction.executeWithoutResult(tx -> publishFile(id, file.getId(), revision));
      } catch (RuntimeException failure) {
        transaction.executeWithoutResult(tx -> {
          var batch = editable(id); revision(batch, revision); var current = ownedFile(batch, file.getId());
          String message = failure instanceof org.springframework.dao.DataAccessException
              ? "保存该文件失败，本次处理已回滚，请重试；其他文件可继续处理"
              : Objects.toString(failure.getMessage(), "处理失败，请重试");
          current.setErrorMessage(message.substring(0, Math.min(2000, message.length())));
          current.setUpdatedAt(LocalDateTime.now()); files.updateById(current); advance(batch, "PARTIAL");
        });
      }
    }
    return report(id);
  }

  public Report addFiles(long id, long revision, List<String> paths) {
    if (paths == null || paths.size() > MAX_FILES) throw new IllegalArgumentException("文件清单无效");
    var normalized = paths.stream().map(this::path).distinct().toList();
    transaction.executeWithoutResult(tx -> {
      var batch = editable(id); revision(batch, revision);
      var old = new HashSet<>(activeFiles(id).stream().map(MonthlyPriceImportFile::getRelativePath).toList());
      var added = normalized.stream().filter(p -> !old.contains(p)).toList();
      if (old.size() + added.size() > MAX_FILES) throw new IllegalArgumentException("每批最多128个文件");
      for (var relative : added) {
        var file = new MonthlyPriceImportFile(); file.setImportId(id); file.setRelativePath(relative);
        file.setPathHash(signatures.hash(relative)); file.setStatus("PENDING"); file.setKind("PENDING");
        file.setActiveFlag(1); file.setCreatedAt(LocalDateTime.now()); file.setUpdatedAt(LocalDateTime.now()); files.insert(file);
      }
      if (!added.isEmpty()) advance(batch, "UPLOADING");
    });
    return report(id);
  }

  private void publishFile(long id, long fileId, long expectedRevision) {
    var batch = editable(id);
    revision(batch, expectedRevision);
    identities.lockForFinancePublication(batch.getBusinessUnitType());
    var file = ownedFile(batch, fileId);
    if (file.getParsedJson() == null || "REFERENCE".equals(file.getKind()) || "ERROR".equals(file.getKind())) return;
    var report = build(batch);
    var selected = report.groups().stream().flatMap(g -> planner.representatives(g.candidates()).stream())
        .filter(c -> Objects.equals(c.fileId(), fileId)).toList();
    if (selected.isEmpty()) {
      boolean unresolved = report.groups().stream().anyMatch(g -> !Set.of("IMPORTED", "PREPARED").contains(g.status())
          && g.candidates().stream().anyMatch(c -> Objects.equals(c.fileId(), fileId)));
      if (!unresolved) clearFileFailure(fileId);
      return;
    }
    Set<String> rowKeys = new LinkedHashSet<>(selected.stream().map(c -> rowKey(c.sheet(), c.row())).toList());
    var parsed = codec.read(file.getParsedJson(), ParsedFile.class);
    var validation = finance.validation();
    parsed.linked().stream().filter(r -> rowKeys.contains(rowKey(r.sheet(), r.row()))).forEach(r -> validation.validate(r, batch.getPricingMonth()));
    parsed.ranges().stream().filter(r -> rowKeys.contains(rowKey(r.sheet(), r.row()))).forEach(r -> validation.validate(r, batch.getPricingMonth()));
    byte[] bytes = workbook(batch, file);
    List<Long> publicationIds;
    if (file.getKind().equals("LINKED")) {
      var command = new PriceLinkedImportCommand(bytes, fileName(file), batch.getPricingMonth(), batch.getBusinessUnitType(),
          false, null, null, null, file.getFileSha256()).withPriceOrgCode("210");
      publicationIds = linked.stageSelectedInTransaction(command, rowKeys).getSourceUploadBatchIds();
    } else {
      publicationIds = ranges.stageSelectedInTransaction(new OriginalRangeImportService.Command(bytes, fileName(file),
          batch.getBusinessUnitType(), batch.getPricingMonth(), file.getFileSha256(), null), rowKeys).batchIds();
    }
    Set<Long> savedIds = new LinkedHashSet<>(codec.publications(file.getPublicationJson())); savedIds.addAll(publicationIds);
    file.setPublicationJson(codec.write(savedIds)); file.setStatus("PROCESSED"); file.setUpdatedAt(LocalDateTime.now());
    files.updateById(file); clearFileFailure(fileId); advance(batch, "PARTIAL");
  }

  public Report complete(long id, long revision) {
    transaction.executeWithoutResult(tx -> {
      var batch = owned(id, true);
      if (batch.getStatus().equals("COMPLETE")) return;
      revision(batch, revision); identities.lockForFinancePublication(batch.getBusinessUnitType());
      var checked = build(batch);
      if (checked.summary().pendingFiles() > 0 || checked.summary().errorRows() > 0
          || checked.groups().stream().anyMatch(g -> !Set.of("IMPORTED", "PREPARED").contains(g.status())))
        throw new IllegalArgumentException("还有未上传、报错或未处理记录，请处理后再补齐上月规则");
      var present = activeFiles(id).stream().filter(f -> f.getParsedJson() != null)
          .flatMap(f -> codec.read(f.getParsedJson(), ParsedFile.class).present().stream()).toList();
      publication.publish(checked.groups(), ownedBatches(id));
      var result = carry.complete(batch.getBusinessUnitType(), batch.getPricingMonth(), present);
      batch.setResultJson(codec.write(result)); advance(batch, "COMPLETE");
      // 保存本次实际核对清单；后续单文件修订不能改变已完成批次的规则和收据。
      batch.setResultJson(codec.write(build(batch))); imports.updateById(batch);
    });
    return report(id);
  }

  public Report report(long id) {
    var batch = owned(id, false);
    return "COMPLETE".equals(batch.getStatus()) ? codec.read(batch.getResultJson(), Report.class) : build(batch);
  }

  private Report build(MonthlyPriceImport batch) {
    List<Candidate> candidates = new ArrayList<>(); List<FileView> views = new ArrayList<>();
    int valid = 0, noPrice = 0, errors = 0, sourceRows = 0, pending = 0;
    for (var file : activeFiles(batch.getId())) {
      var parsed = file.getParsedJson() == null ? null : codec.read(file.getParsedJson(), ParsedFile.class);
      List<Issue> issues = new ArrayList<>(parsed == null ? List.of() : parsed.errors());
      if (file.getErrorMessage() != null) issues.add(new Issue(null, null, null, file.getErrorMessage()));
      if (parsed == null) {
        pending++;
        views.add(new FileView(file.getId(), file.getRelativePath(), file.getKind(), file.getStatus(), file.getFileSha256(),
            -1, 0, 0, List.copyOf(issues), List.of(), "文件尚未成功读取，行数未知"));
        errors += issues.size(); continue;
      }
      int count = parsed.linked().size() + parsed.ranges().size();
      int skipped = (int) parsed.notices().stream().filter(n -> n.code().equals("NO_PRICE")).count();
      int invalidRows = (int) parsed.errors().stream().filter(e -> e.row() != null).count();
      valid += count; noPrice += skipped; sourceRows += count + skipped + invalidRows; errors += issues.size();
      for (var rule : parsed.linked()) candidates.add(new Candidate(candidateId(file, rule.sheet(), rule.row()), file.getId(),
          file.getRelativePath(), rule.sheet(), rule.row(), "LINKED", rule.identity(), signatures.linked(rule), signatures.describe(rule), null, "UPLOADED"));
      for (var rule : parsed.ranges()) candidates.add(new Candidate(candidateId(file, rule.sheet(), rule.row()), file.getId(),
          file.getRelativePath(), rule.sheet(), rule.row(), "RANGE", rule.identity(), signatures.range(rule),
          signatures.describe(rule), null, "UPLOADED"));
      views.add(new FileView(file.getId(), file.getRelativePath(), parsed.kind(), file.getStatus(), file.getFileSha256(),
          "ERROR".equals(parsed.kind()) ? -1 : count + skipped + invalidRows,
          count, skipped, List.copyOf(issues), parsed.notices(), parsed.message()));
    }
    var keys = new HashSet<>(candidates.stream().map(c -> c.identity().identityKey()).toList());
    candidates.addAll(sources.candidates(sources.read(batch.getBusinessUnitType(), batch.getPricingMonth(), false), keys));
    Set<Long> activeBatches = new LinkedHashSet<>();
    activeFiles(batch.getId()).forEach(file -> activeBatches.addAll(codec.publications(file.getPublicationJson())));
    candidates.addAll(sources.candidates(sources.staged(batch.getBusinessUnitType(), batch.getPricingMonth(), activeBatches), keys));
    var groups = planner.build(candidates);
    int alternatives = groups.stream().mapToInt(g -> Math.max(0, g.variantCount() - 1)).sum();
    int imported = (int) groups.stream().flatMap(g -> planner.representatives(g.candidates()).stream()).filter(c -> c.fileId() == null).count();
    int duplicates = groups.stream().mapToInt(g -> {
      var signatures = new HashSet<String>(); int duplicate = 0;
      for (var c : g.candidates()) if (c.fileId() != null && !signatures.add(c.signature())) duplicate++;
      return duplicate;
    }).sum();
    return new Report(batch.getId(), batch.getBusinessUnitType(), batch.getPricingMonth(), batch.getStatus(), batch.getRevision(),
        new Summary(sourceRows, valid, noPrice, errors, groups.stream().mapToInt(Group::variantCount).sum(), alternatives, imported, duplicates, pending),
        List.copyOf(views), groups, batch.getResultJson() == null ? null : codec.read(batch.getResultJson(), Object.class));
  }

  public record Download(String fileName, byte[] bytes) {}
  public Download download(long id, long fileId) {
    var batch = owned(id, false); var file = files.selectById(fileId);
    if (file == null || !Objects.equals(file.getImportId(), id)) throw new IllegalArgumentException("原件不存在");
    return new Download(fileName(file), workbook(batch, file));
  }

  private byte[] workbook(MonthlyPriceImport batch, MonthlyPriceImportFile file) {
    if (!Objects.equals(batch.getId(), file.getImportId())) throw new AccessDeniedException("原件不属于本批次");
    var stored = files.workbook(file.getId());
    byte[] bytes = stored == null ? null : stored.getSourceWorkbook();
    if (bytes == null || !OriginalRuleSignature.digest(bytes).equals(file.getFileSha256()))
      throw new IllegalArgumentException("原件缺失或摘要不一致，不能发布");
    return bytes;
  }
  private MonthlyPriceImport editable(long id) {
    var batch = owned(id, true);
    if (batch.getStatus().equals("COMPLETE")) throw new IllegalArgumentException("该批次已完成，请新建导入批次");
    return batch;
  }
  private MonthlyPriceImport owned(long id, boolean lock) {
    var batch = lock ? imports.lock(id) : imports.selectById(id);
    if (batch == null || !batch.getBusinessUnitType().equals(unit())) throw new AccessDeniedException("导入批次不存在或不属于当前业务范围");
    return batch;
  }
  private MonthlyPriceImportFile ownedFile(MonthlyPriceImport batch, long id) {
    var file = files.selectById(id);
    if (file == null || !file.getImportId().equals(batch.getId()) || !Integer.valueOf(1).equals(file.getActiveFlag()))
      throw new IllegalArgumentException("文件不属于该批次或已被修正版替代，请刷新");
    return file;
  }
  private List<MonthlyPriceImportFile> activeFiles(long id) {
    return files.selectList(Wrappers.lambdaQuery(MonthlyPriceImportFile.class)
        .eq(MonthlyPriceImportFile::getImportId, id).eq(MonthlyPriceImportFile::getActiveFlag, 1)
        .orderByAsc(MonthlyPriceImportFile::getId));
  }
  private Set<Long> ownedBatches(long id) {
    Set<Long> result = new LinkedHashSet<>();
    for (var file : files.selectList(Wrappers.lambdaQuery(MonthlyPriceImportFile.class)
        .select(MonthlyPriceImportFile::getId, MonthlyPriceImportFile::getPublicationJson).eq(MonthlyPriceImportFile::getImportId, id)))
      result.addAll(codec.publications(file.getPublicationJson()));
    return result;
  }
  private void clearFileFailure(long id) {
    files.update(null, Wrappers.lambdaUpdate(MonthlyPriceImportFile.class)
        .eq(MonthlyPriceImportFile::getId, id).set(MonthlyPriceImportFile::getErrorMessage, null));
  }
  private void advance(MonthlyPriceImport batch, String status) {
    batch.setStatus(status); batch.setRevision(batch.getRevision() + 1); batch.setUpdatedAt(LocalDateTime.now()); imports.updateById(batch);
  }
  private void revision(MonthlyPriceImport batch, long expected) {
    if (batch.getRevision() != expected) throw new IllegalArgumentException("导入记录已变化，请刷新后重试");
  }
  private String candidateId(MonthlyPriceImportFile file, String sheet, int row) { return file.getId() + ":" + rowKey(sheet, row); }
  private String fileName(MonthlyPriceImportFile file) {
    String path = file.getRelativePath(); return path.substring(path.lastIndexOf('/') + 1);
  }
  private String path(String value) {
    if (value == null || value.isBlank() || value.length() > 500 || value.contains("\\") || value.startsWith("/")
        || value.chars().anyMatch(c -> c < 32) || Arrays.stream(value.split("/", -1)).anyMatch(s -> s.isBlank() || s.equals(".") || s.equals("..")))
      throw new IllegalArgumentException("文件相对路径无效");
    return value;
  }
  private String unit() {
    String unit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!Set.of("COMMERCIAL", "HOUSEHOLD").contains(Objects.toString(unit, ""))) throw new AccessDeniedException("请先选择业务范围");
    return unit;
  }
  private String actor() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth == null || !auth.isAuthenticated() || auth.getName().equals("anonymousUser")) throw new AccessDeniedException("请登录后导入");
    return auth.getName();
  }
}
