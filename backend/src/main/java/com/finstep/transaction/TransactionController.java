package com.finstep.transaction;

import com.finstep.identity.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {
    private final TransactionService service;
    public TransactionController(TransactionService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<TransactionService.Transaction> create(@AuthenticationPrincipal UserPrincipal owner,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody LedgerCommands.Transaction body) {
        var result=service.create(owner.id(),key,body);
        return ResponseEntity.status(result.replay()?HttpStatus.OK:HttpStatus.CREATED)
            .location(URI.create("/api/v1/transactions/"+result.resource().id())).body(result.resource());
    }
    @GetMapping("/{id}")
    public TransactionService.Transaction get(@AuthenticationPrincipal UserPrincipal owner,@PathVariable UUID id) { return service.get(owner.id(),id); }
    @PutMapping("/{id}")
    public TransactionService.Transaction replace(@AuthenticationPrincipal UserPrincipal owner,@PathVariable UUID id,
        @Valid @RequestBody LedgerCommands.Transaction body) { return service.replace(owner.id(),id,body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal owner,@PathVariable UUID id) { service.delete(owner.id(),id); }
    @GetMapping
    public HistoryPage<TransactionService.Transaction> history(@AuthenticationPrincipal UserPrincipal owner,
        @RequestParam(required=false) OffsetDateTime from,@RequestParam(required=false) OffsetDateTime to,
        @RequestParam(required=false) UUID walletId,@RequestParam(required=false) UUID categoryId, @RequestParam(required=false) com.finstep.category.CategoryType type,
        @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size) {
        return service.history(owner.id(),from,to,walletId,categoryId,type,cursor,size);
    }
}
