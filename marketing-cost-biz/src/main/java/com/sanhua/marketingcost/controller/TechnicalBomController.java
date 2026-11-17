package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalBomSaveRequest;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/technical-data/products/{productId}/bom")
public class TechnicalBomController {
  private static final String READ="@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')";
  private static final String EDIT="@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')";
  private final TechnicalBomApplicationService service;
  private final TechnicalDataActorProvider actors;
  private final TechnicalDataBomPriceCheck prices;
  public TechnicalBomController(TechnicalBomApplicationService service,TechnicalDataActorProvider actors, TechnicalDataBomPriceCheck prices) { this.service=service; this.actors=actors; this.prices=prices; }
  @GetMapping @PreAuthorize(READ)
  public CommonResult<TechnicalBomApplicationService.Response> read(@PathVariable Long productId,@RequestParam(required=false) Long versionId) {
    return execute(()->service.read(productId,versionId,actors.current()));
  }
  @GetMapping("/references") @PreAuthorize(READ)
  public CommonResult<List<TechnicalBomReferenceQuery.Candidate>> references(@PathVariable Long productId,@RequestParam String keyword) {
    return execute(()->service.search(productId,keyword,actors.current()));
  }
  @GetMapping("/reference") @PreAuthorize(EDIT)
  public CommonResult<TechnicalBomApplicationService.Preview> reference(@PathVariable Long productId,@RequestParam String code) {
    return execute(()->service.reference(productId,code,actors.current()));
  }
  @GetMapping("/template") @PreAuthorize(READ)
  public ResponseEntity<?> template(@PathVariable Long productId,@RequestParam String exampleCode) {
    return download(()->service.template(productId,exampleCode,actors.current()));
  }
  @PostMapping(value="/upload-preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @PreAuthorize(EDIT)
  public CommonResult<TechnicalBomApplicationService.Preview> preview(@PathVariable Long productId,@RequestParam MultipartFile file) {
    return execute(()->{
      if(file.isEmpty()||file.getSize()>TechnicalBomWorkbook.MAX_BYTES) throw new IllegalArgumentException("BOM文件不能为空且不能超过5MB");
      try { return service.preview(productId,file.getOriginalFilename(),file.getBytes(),actors.current()); }
      catch(IOException error) { throw new IllegalArgumentException("无法读取BOM文件",error); }
    });
  }
  @GetMapping("/file") @PreAuthorize(READ)
  public ResponseEntity<?> file(@PathVariable Long productId,@RequestParam(required=false) Long versionId) {
    return download(()->service.file(productId,versionId,actors.current()));
  }
  @PutMapping @PreAuthorize(EDIT)
  public CommonResult<TechnicalBomApplicationService.Response> save(@PathVariable Long productId,@RequestBody TechnicalBomSaveRequest request) {
    return execute(()-> {
      var actor=actors.current();
      service.save(productId,request,actor);
      prices.afterSaved(productId,actor);
      return service.read(productId,null,actor);
    });
  }
  private ResponseEntity<?> download(Supplier<TechnicalDataAttachmentStore.StoredFile> action) {
    var result=execute(action); if(result.getCode()!=0) return ResponseEntity.status(result.getCode()).body(result);
    var file=result.getData();
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString()).body(file.bytes());
  }
  private static <T> CommonResult<T> execute(Supplier<T> action) {
    try { return CommonResult.success(action.get()); }
    catch(TechnicalDataTaskException error) { return CommonResult.error(switch(error.code()) {
      case FORBIDDEN -> 403; case TASK_NOT_FOUND,PRODUCT_NOT_FOUND -> 404; case INVALID_REQUEST -> 400; default -> 409;
    },error.getMessage()); }
    catch(IllegalArgumentException error) { return CommonResult.error(400,error.getMessage()); }
  }
}
