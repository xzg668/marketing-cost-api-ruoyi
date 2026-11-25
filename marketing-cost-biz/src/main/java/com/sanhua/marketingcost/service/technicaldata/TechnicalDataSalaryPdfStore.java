package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.apache.pdfbox.Loader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 工资模块独立的工时文档，不解析工资金额，也不改变补录或成本版本。 */
@Component
public class TechnicalDataSalaryPdfStore {
  public static final int MAX_BYTES = 10 * 1024 * 1024;
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
      .withZone(ZoneId.of("Asia/Shanghai"));
  private final Path root;
  private final ObjectMapper json;
  private final Clock clock;

  @Autowired
  public TechnicalDataSalaryPdfStore(@Value("${quote.technical-data.salary-pdf-root:${quote.technical-data.attachments-root:./data/technical-data}/salary-pdf}") String root,
      ObjectMapper json) {
    this(root, json, Clock.systemUTC());
  }

  TechnicalDataSalaryPdfStore(String root, ObjectMapper json, Clock clock) {
    if (root == null || root.isBlank()) throw new IllegalArgumentException("请配置工时PDF保存目录");
    this.root = Path.of(root).toAbsolutePath().normalize(); this.json = json; this.clock = clock;
  }

  public record Document(String fileName, String sha256, long size, String uploadedAt, Long uploadedBy) {}

  public Document current(Long productId) {
    Path manifest = manifest(productId);
    if (!Files.exists(manifest)) return null;
    try { return json.readValue(Files.readAllBytes(manifest), Document.class); }
    catch (IOException error) { throw new IllegalStateException("工时PDF信息读取失败", error); }
  }

  public synchronized Document save(Long productId, String identity, byte[] bytes, String expectedSha256, Long actorId) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("工时PDF不能为空，且不能超过10MB");
    if (bytes.length < 5 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-')
      throw new IllegalArgumentException("请选择有效的PDF文件");
    try (var pdf = Loader.loadPDF(bytes)) {
      if (pdf.isEncrypted() || pdf.getNumberOfPages() == 0) throw new IllegalArgumentException("请上传未加密且包含页面的工时PDF");
    } catch (IOException error) { throw new IllegalArgumentException("PDF无法读取，请检查文件是否完整或已加密", error); }
    String identityName = identityName(identity);
    Document previous = current(productId);
    String expected = expectedSha256 == null || expectedSha256.isBlank() ? null : expectedSha256;
    if (!Objects.equals(previous == null ? null : previous.sha256(), expected))
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, "工时PDF已被其他会话更新，请刷新后重新上传");
    String hash = TechnicalDataAttachmentStore.sha256(bytes);
    String prefix = identityName + "工时_";
    if (previous != null && previous.sha256().equals(hash) && previous.fileName().startsWith(prefix)
        && previous.fileName().substring(prefix.length()).matches("\\d{14}(?:_\\d+)?\\.pdf")) return previous;
    Instant uploadedAt = clock.instant();
    String stem = prefix + FILE_TIME.format(uploadedAt);
    Path temporary = null;
    try {
      Files.createDirectories(root);
      String fileName;
      for (int sequence = 1; ; sequence++) {
        fileName = stem + (sequence == 1 ? "" : "_" + sequence) + ".pdf";
        Path target = root.resolve(fileName);
        // CREATE_NEW prevents equal identities uploaded in the same second from overwriting each other.
        try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
          try { output.write(bytes); }
          catch (IOException error) { Files.deleteIfExists(target); throw error; }
          break;
        } catch (FileAlreadyExistsException collision) { /* Keep the existing file and try a suffix. */ }
      }
      var document = new Document(fileName, hash, bytes.length, uploadedAt.toString(), actorId);
      Path manifest = manifest(productId);
      Files.createDirectories(manifest.getParent());
      temporary = Files.createTempFile(manifest.getParent(), ".current-", ".tmp");
      Files.write(temporary, json.writeValueAsBytes(document));
      Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
      return document;
    } catch (IOException error) { throw new IllegalStateException("工时PDF保存失败，请检查附件目录", error); }
    finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* 未发布临时文件不可读。 */ } }
  }

  public TechnicalDataAttachmentStore.StoredFile read(Long productId) {
    Document document = current(productId);
    if (document == null) throw new IllegalArgumentException("尚未上传工时PDF");
    if (!document.sha256().matches("[a-f0-9]{64}") || !Path.of(document.fileName()).getFileName().toString().equals(document.fileName()))
      throw new IllegalStateException("工时PDF信息校验失败");
    try {
      byte[] bytes = Files.readAllBytes(root.resolve(document.fileName()));
      if (bytes.length != document.size() || !TechnicalDataAttachmentStore.sha256(bytes).equals(document.sha256()))
        throw new IllegalStateException("工时PDF校验失败，请联系管理员");
      return new TechnicalDataAttachmentStore.StoredFile(document.fileName(), document.sha256(), bytes);
    } catch (IOException error) { throw new IllegalStateException("工时PDF不存在或无法读取", error); }
  }

  private Path manifest(Long productId) {
    if (productId == null || productId <= 0) throw new IllegalArgumentException("补录产品标识无效");
    return root.resolve(".index").resolve(productId + ".json");
  }

  private static String identityName(String identity) {
    if (identity == null || identity.isBlank()) throw new IllegalArgumentException("请先补齐产品料号或型号，再上传工时PDF");
    String name = identity.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    if (name.length() > 70) name = name.substring(0, name.offsetByCodePoints(0, Math.min(70, name.codePointCount(0, name.length()))));
    return name;
  }
}
