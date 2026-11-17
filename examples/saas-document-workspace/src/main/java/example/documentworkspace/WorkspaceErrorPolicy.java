// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package example.documentworkspace;
import org.vernac.runtime.http.inbound.InboundHttpErrors;
/** Product-owned presentation for quota/input failures; authorization remains runtime-owned. */
public final class WorkspaceErrorPolicy {
    private WorkspaceErrorPolicy() {}
    public static InboundHttpErrors.Reply handle(String endpoint, Exception error) {
        if (error instanceof org.vernac.saas.restrictions.RestrictionFailure
                || error instanceof org.vernac.runtime.DomainValidationException
                || error instanceof IllegalArgumentException
                || InboundHttpErrors.defaultReply(error).status()==400)
            return new InboundHttpErrors.Reply(409,"ACTION_UNAVAILABLE","Aktion nicht möglich. Bitte Eingaben, Tarif und verfügbares Limit prüfen.");
        return InboundHttpErrors.defaultReply(error);
    }
}
