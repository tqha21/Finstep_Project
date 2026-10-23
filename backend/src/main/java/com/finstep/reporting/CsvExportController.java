package com.finstep.reporting;

import com.finstep.category.CategoryType;
import com.finstep.identity.UserPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/exports")
public class CsvExportController {
    private final CsvExportService exports;
    public CsvExportController(CsvExportService exports) { this.exports=exports; }
    @GetMapping("/transactions.csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal UserPrincipal owner,@RequestParam OffsetDateTime from,
        @RequestParam OffsetDateTime to,@RequestParam(required=false) UUID walletId,@RequestParam(required=false) UUID categoryId,
        @RequestParam(required=false) CategoryType type) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE,"text/csv;charset=UTF-8")
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"finstep-transactions.csv\"")
            .cacheControl(CacheControl.noStore()).body(exports.export(owner.id(),from,to,walletId,categoryId,type));
    }
}
