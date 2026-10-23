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
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final TransferService service;
    public TransferController(TransferService service) { this.service=service; }
    @PostMapping
    public ResponseEntity<TransferService.Transfer> create(@AuthenticationPrincipal UserPrincipal owner,
        @RequestHeader("Idempotency-Key") String key,@Valid @RequestBody LedgerCommands.Transfer body) {
        var result=service.create(owner.id(),key,body);
        return ResponseEntity.status(result.replay()?HttpStatus.OK:HttpStatus.CREATED)
            .location(URI.create("/api/v1/transfers/"+result.resource().id())).body(result.resource());
    }
    @GetMapping("/{id}")
    public TransferService.Transfer get(@AuthenticationPrincipal UserPrincipal owner,@PathVariable UUID id) { return service.get(owner.id(),id); }
    @PutMapping("/{id}")
    public TransferService.Transfer replace(@AuthenticationPrincipal UserPrincipal owner,@PathVariable UUID id,
        @Valid @RequestBody LedgerCommands.Transfer body) { return service.replace(owner.id(),id,body); }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal owner,@PathVariable UUID id) { service.delete(owner.id(),id); }
    @GetMapping
    public HistoryPage<TransferService.Transfer> history(@AuthenticationPrincipal UserPrincipal owner,
        @RequestParam(required=false) OffsetDateTime from,@RequestParam(required=false) OffsetDateTime to,
        @RequestParam(required=false) UUID walletId,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int size) {
        return service.history(owner.id(),from,to,walletId,cursor,size);
    }
}
