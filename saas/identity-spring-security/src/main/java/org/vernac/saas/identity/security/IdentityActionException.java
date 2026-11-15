// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.identity.security;
public final class IdentityActionException extends IllegalStateException {
    public enum Code { PASSWORD_POLICY, REJECTED, ACCESS_DENIED, THROTTLED, AUTHENTICATION_REQUIRED, UNAVAILABLE }
    private final Code code;private final int retry;
    public IdentityActionException(Code code) {this(code,0);}
    public IdentityActionException(Code code,int retry) {super("Identity action unavailable: "+code);this.code=java.util.Objects.requireNonNull(code);this.retry=code==Code.THROTTLED?Math.max(1,Math.min(86400,retry)):0;}
    public Code code(){return code;}public int retryAfterSeconds(){return retry;}
}
