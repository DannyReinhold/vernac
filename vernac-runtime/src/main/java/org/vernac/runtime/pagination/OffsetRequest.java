// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.OptionalLong;
import java.util.Objects;
import org.vernac.runtime.DomainValidationException;

/** An offset request; numbered pages and arbitrary row windows share this contract. */
public record OffsetRequest(long offset, PageSize size) {
    public OffsetRequest {
        Objects.requireNonNull(size, "size");
        if (offset < 0) throw new DomainValidationException("Offset must not be negative");
        if (offset % size.value() == 0 && offset / size.value() == Long.MAX_VALUE)
            throw new DomainValidationException("Page number cannot be represented");
    }
    public static OffsetRequest ofOffset(long offset, int size) { return ofOffset(offset, PageSize.of(size)); }
    public static OffsetRequest ofOffset(long offset, PageSize size) { return new OffsetRequest(offset, size); }
    public static OffsetRequest ofPage(long page, int size) { return ofPage(PageNumber.of(page), PageSize.of(size)); }
    public static OffsetRequest ofPage(PageNumber page, PageSize size) {
        Objects.requireNonNull(page, "page"); Objects.requireNonNull(size, "size");
        try { return new OffsetRequest(Math.multiplyExact(page.value() - 1, (long) size.value()), size); }
        catch (ArithmeticException overflow) { throw new DomainValidationException("Page offset cannot be represented"); }
    }
    public int pageSize() { return size.value(); }
    /** Empty only when the window does not start on a numbered page boundary. */
    public OptionalLong pageNumber() {
        return offset % size.value() == 0 ? OptionalLong.of(offset / size.value() + 1) : OptionalLong.empty();
    }
    /** Move one full requested window forward; arithmetic never silently wraps. */
    public OffsetRequest next() {
        try { return new OffsetRequest(Math.addExact(offset, size.value()), size); }
        catch (ArithmeticException overflow) { throw new DomainValidationException("Next offset cannot be represented"); }
    }
}
