// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.http;

import org.jspecify.annotations.NullMarked;
import org.vernac.runtime.VernacException;
import org.vernac.runtime.ports.AdapterInvocation;

/** Translate a non-2xx response to an exception. Arguments may be captured by the call's lambda. */
@NullMarked
@FunctionalInterface
public interface HttpErrorHandler {
    VernacException failure(AdapterInvocation invocation, HttpResponseData response);
}
