package com.finstep.transaction;

import com.finstep.category.CategoryType;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class LedgerCommands {
    private LedgerCommands() { }
    public record Transaction(@NotNull UUID walletId, @NotNull UUID categoryId,
        @NotNull CategoryType type, @NotNull @Pattern(regexp="[1-9][0-9]{0,14}") String amount,
        @NotNull @PastOrPresent OffsetDateTime occurredAt, @Size(max=1000) String note) { }
    public record Transfer(@NotNull UUID fromWalletId, @NotNull UUID toWalletId,
        @NotNull @Pattern(regexp="[1-9][0-9]{0,14}") String amount,
        @NotNull @PastOrPresent OffsetDateTime occurredAt, @Size(max=1000) String note) { }
}
