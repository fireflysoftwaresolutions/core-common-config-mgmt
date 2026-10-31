/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.core.authority;

import java.time.*;
import java.util.function.LongSupplier;
import org.fireflyframework.core.transaction.R2dbcOperationBudget;

/** Native observation data, not authentication of its supplier. */
public record DocumentIssuerNativeWindow(Instant recordedAt,long dbNowMillis,long untilMillis,long beforeNativeNanos) {
    public R2dbcOperationBudget cap(DocumentIssuerAuthorization.Permit original,Clock clock,LongSupplier nanos){
        var deadline=deadline(original);
        return original.budget().intersect(R2dbcOperationBudget.observed(original.observedAt(),deadline,original.beforeNanos(),Duration.ofSeconds(5),clock,nanos));
    }
    public Instant deadline(DocumentIssuerAuthorization.Permit original){
        if(original==null||recordedAt==null||recordedAt.toEpochMilli()!=dbNowMillis||dbNowMillis<0
                ||untilMillis<=dbNowMillis||untilMillis-dbNowMillis>5000||untilMillis>original.effectiveUntil().toEpochMilli())throw invalid();
        long nativeLife=Math.subtractExact(Math.multiplyExact(untilMillis-dbNowMillis,1_000_000L),1_000_000L);
        long offset=beforeNativeNanos-original.beforeNanos();
        if(nativeLife<=0||offset<0)throw invalid();
        long lifetime=Math.addExact(offset,nativeLife);
        var deadline=original.effectiveUntil();var nativeUntil=Instant.ofEpochMilli(untilMillis);
        if(nativeUntil.isBefore(deadline))deadline=nativeUntil;
        var elapsedDeadline=original.observedAt().plusNanos(lifetime);
        if(elapsedDeadline.isBefore(deadline))deadline=elapsedDeadline;
        return deadline;
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid finite document issuer native window");}
    @Override public String toString(){return "DocumentIssuerNativeWindow";}
}
