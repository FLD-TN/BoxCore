package com.dex.boxcore.gem;

import com.dex.boxcore.api.GemResult;
import com.dex.boxcore.api.GemService;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** API implementation (Update 03 §4): thin checks in front of {@link GemLedger}. */
public final class GemServiceImpl implements GemService {

    private final GemLedger ledger;
    private final BooleanSupplier isMainThread;

    public GemServiceImpl(GemLedger ledger, BooleanSupplier isMainThread) {
        this.ledger = ledger;
        this.isMainThread = isMainThread;
    }

    @Override
    public long balance(UUID playerId) {
        return playerId == null ? -1 : ledger.balance(playerId);
    }

    @Override
    public boolean isLoaded(UUID playerId) {
        return playerId != null && ledger.isLoaded(playerId);
    }

    @Override
    public GemResult deposit(UUID playerId, long amount, String reason) {
        requireMainThread("deposit");
        if (playerId == null) return GemResult.NOT_LOADED;
        return ledger.deposit(playerId, amount, reason);
    }

    @Override
    public GemResult withdraw(UUID playerId, long amount, String reason) {
        requireMainThread("withdraw");
        if (playerId == null) return GemResult.NOT_LOADED;
        return ledger.withdraw(playerId, amount, reason);
    }

    @Override
    public String displayName() {
        return ledger.config().displayName();
    }

    @Override
    public String format(long amount) {
        return GemFormat.grouped(amount);
    }

    private void requireMainThread(String method) {
        if (!isMainThread.getAsBoolean()) {
            throw new IllegalStateException("GemService#" + method + " must be called on the main thread");
        }
    }
}
