package com.finstep.wallet;

import com.finstep.identity.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/wallets")
public class WalletController {
    private final WalletService wallets;
    public WalletController(WalletService wallets) { this.wallets=wallets; }
    public record Create(@NotBlank @Size(max=100) String name, @NotNull WalletType type,
        @NotNull @Pattern(regexp="-?(0|[1-9][0-9]{0,14})") String openingBalance,
        @NotNull @PastOrPresent OffsetDateTime effectiveAt) { }
    public record Patch(@Size(max=100) @Pattern(regexp="(?s).*\\S.*") String name,Boolean archived) { }
    @GetMapping
    public List<WalletService.Wallet> list(@AuthenticationPrincipal UserPrincipal user,
        @RequestParam(defaultValue="false") boolean includeArchived) { return wallets.list(user.id(),includeArchived); }
    @GetMapping("/{id}")
    public WalletService.Wallet get(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id) { return wallets.get(user.id(),id); }
    @PostMapping
    public ResponseEntity<WalletService.Wallet> create(@AuthenticationPrincipal UserPrincipal user,@Valid @RequestBody Create body) {
        var wallet=wallets.create(user.id(),body.name(),body.type(),body.openingBalance(),body.effectiveAt());
        return ResponseEntity.created(URI.create("/api/v1/wallets/"+wallet.id())).body(wallet);
    }
    @PatchMapping("/{id}")
    public WalletService.Wallet update(@AuthenticationPrincipal UserPrincipal user,@PathVariable UUID id,@Valid @RequestBody Patch body) {
        return wallets.update(user.id(),id,body.name(),body.archived());
    }
}
