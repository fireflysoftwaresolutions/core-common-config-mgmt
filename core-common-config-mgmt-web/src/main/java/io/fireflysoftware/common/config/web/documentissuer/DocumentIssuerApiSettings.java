/*
 * Copyright 2026 Firefly Software Foundation
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at https://www.apache.org/licenses/LICENSE-2.0
 */
package io.fireflysoftware.common.config.web.documentissuer;

import java.util.Set;

/** Bound only by the explicit opt-in configuration; never discovered by global properties scanning. */
public record DocumentIssuerApiSettings(boolean enabled,String commandUsername,String commandPassword,
        String readerUsername,String readerPassword,String audience,String tenantClaim,String clientClaim,Set<String> clientIds) {
    public DocumentIssuerApiSettings {
        if(!enabled||!login(commandUsername)||!login(readerUsername)||commandUsername.equals(readerUsername)
                ||blank(commandPassword)||blank(readerPassword)||blank(audience)||blank(tenantClaim)||blank(clientClaim)
                ||clientIds==null||clientIds.isEmpty()||clientIds.stream().anyMatch(DocumentIssuerApiSettings::blank))throw unavailable();
        clientIds=Set.copyOf(clientIds);
    }
    private static boolean login(String value){return value!=null&&value.matches("[a-z][a-z0-9_]{0,62}");}
    private static boolean blank(String value){return value==null||value.isBlank()||value.length()>8192;}
    static IllegalArgumentException unavailable(){return new IllegalArgumentException("Explicit document issuer settings required");}
    @Override public String toString(){return "DocumentIssuerApiSettings";}
}
