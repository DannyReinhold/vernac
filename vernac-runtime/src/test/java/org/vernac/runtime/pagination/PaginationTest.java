// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.pagination;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainList;
import org.vernac.runtime.DomainValidationException;
import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    record Items<T>(List<T> asList) implements DomainList<T> {
        Items { asList = List.copyOf(asList); }
        static <T> Items<T> of(List<T> values) { return new Items<>(values); }
        @Override public Iterator<T> iterator() { return asList.iterator(); }
    }
    @Test void pageAndOffsetRequestsAgreeAndDoNotInventAlignedNumbers() {
        var page = OffsetRequest.ofPage(PageNumber.of(3), PageSize.of(20));
        assertEquals(OffsetRequest.ofOffset(40, 20), page);
        assertEquals(OptionalLong.of(3), page.pageNumber());
        assertEquals(60, page.next().offset());
        assertTrue(OffsetRequest.ofOffset(30, 20).pageNumber().isEmpty());
        assertEquals(OptionalLong.of(1), OffsetRequest.ofPage(1, 20).pageNumber());
    }
    @Test void invalidRequestsLimitsAndOverflowFailInsteadOfClamping() {
        assertThrows(DomainValidationException.class, () -> PageNumber.of(0));
        assertThrows(DomainValidationException.class, () -> PageSize.of(0));
        assertThrows(DomainValidationException.class, () -> OffsetRequest.ofOffset(-1, 20));
        assertThrows(DomainValidationException.class, () -> OffsetRequest.ofPage(Long.MAX_VALUE, 2));
        assertThrows(DomainValidationException.class, () -> OffsetRequest.ofOffset(Long.MAX_VALUE, 1));
        assertThrows(DomainValidationException.class, () -> OffsetRequest.ofOffset(Long.MAX_VALUE, 2).next());
        assertThrows(DomainValidationException.class, () -> PageSize.of(21).checkMaximum(20));
        assertThrows(PaginationContractException.class, () -> PageSize.of(20).checkMaximum(0));
        PageSize.of(20).checkMaximum(20);
        assertEquals(Long.MAX_VALUE - 1, OffsetRequest.ofPage(Long.MAX_VALUE, 1).offset());
    }
    @Test void countedPagesCoverEmptyBeyondLastAndExtremeTotals() {
        var empty = CountedOffsetPage.of(Items.<Integer>of(List.of()), OffsetRequest.ofPage(1, 20), 0);
        assertEquals(0, empty.totalPages()); assertFalse(empty.hasNext());
        var beyond = CountedOffsetPage.of(Items.<Integer>of(List.of()), OffsetRequest.ofPage(9, 20), 23);
        assertEquals(23, beyond.totalElements()); assertEquals(2, beyond.totalPages());
        assertEquals(OptionalLong.of(9), beyond.pageNumber()); assertTrue(beyond.nextRequest().isEmpty());
        var boundary = CountedOffsetPage.of(Items.of(List.of(1, 2)), OffsetRequest.ofPage(2, 2), 4);
        assertEquals(2, boundary.totalPages()); assertFalse(boundary.hasNext());
        var huge = CountedOffsetPage.of(Items.<Integer>of(List.of()), OffsetRequest.ofOffset(Long.MAX_VALUE, 2), Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE / 2 + 1, huge.totalPages());
    }
    @Test void incompatiblePageMetadataIsAProgrammingError() {
        var two = Items.of(List.of(1, 2));
        assertThrows(PaginationContractException.class, () -> OffsetPage.of(two, OffsetRequest.ofPage(1, 1), false));
        assertThrows(PaginationContractException.class, () -> OffsetPage.of(two, OffsetRequest.ofPage(1, 3), true));
        assertThrows(PaginationContractException.class, () -> CountedOffsetPage.of(two, OffsetRequest.ofPage(1, 2), 1));
        assertThrows(PaginationContractException.class, () -> CountedOffsetPage.of(two, OffsetRequest.ofPage(1, 3), 3));
        assertThrows(PaginationContractException.class, () -> CountedOffsetPage.of(two, OffsetRequest.ofPage(1, 2), -1));
    }
    @Test void projectionRetainsCountAndNavigationAndUsesAnExplicitCollectionFactory() {
        var page = CountedOffsetPage.of(Items.of(List.of(1, 2)), OffsetRequest.ofPage(2, 2), 5);
        var mapped = page.mapItems(Items::of, value -> "item-" + value);
        assertEquals(List.of("item-1", "item-2"), mapped.items().asList());
        assertEquals(page.request(), mapped.request()); assertEquals(5, mapped.totalElements());
        assertEquals(3, mapped.totalPages()); assertTrue(mapped.hasNext());
        assertThrows(UnsupportedOperationException.class, () -> mapped.items().asList().clear());
        assertThrows(PaginationContractException.class, () -> page.mapItems(Items::of, value -> null));
        assertThrows(PaginationContractException.class, () -> page.mapItems(values -> Items.<String>of(List.of()), Object::toString));
        assertThrows(PaginationContractException.class, () -> page.mapItems(values -> Items.of(values.reversed()), Object::toString));
    }
    @Test void offsetIterationIsLazyRepeatableAndPreservesMultiplicity() {
        AtomicInteger loads = new AtomicInteger();
        OffsetQuery<Integer, Items<Integer>> query = request -> {
            loads.incrementAndGet();
            return request.offset() == 0
                    ? OffsetPage.of(Items.of(List.of(1, 1)), request, true)
                    : OffsetPage.of(Items.of(List.of(2)), request, false);
        };
        var pages = query.pages(OffsetRequest.ofPage(1, 2));
        var iterator = pages.iterator();
        assertTrue(iterator.hasNext()); assertTrue(iterator.hasNext()); assertEquals(0, loads.get());
        assertEquals(List.of(1, 1), iterator.next().items().asList()); assertEquals(1, loads.get());
        assertEquals(List.of(2), iterator.next().items().asList()); assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        var all = new ArrayList<Integer>(); pages.items().forEach(all::add);
        assertEquals(List.of(1, 1, 2), all); assertEquals(4, loads.get());
    }
    @Test void consumersCanStopBeforeTheNextPageIsLoaded() {
        AtomicInteger loads = new AtomicInteger();
        OffsetQuery<Integer, Items<Integer>> query = request -> {
            loads.incrementAndGet(); return OffsetPage.of(Items.of(List.of(1, 2)), request, true);
        };
        assertThrows(IllegalStateException.class, () -> query.pages(OffsetRequest.ofPage(1, 2)).items()
                .forEach(item -> { throw new IllegalStateException("stop"); }));
        assertEquals(1, loads.get());
    }
    @Test void aFailedTraversalDoesNotRetryImplicitly() {
        AtomicInteger loads = new AtomicInteger();
        OffsetQuery<Integer, Items<Integer>> query = request -> {
            loads.incrementAndGet(); throw new IllegalStateException("load failed");
        };
        var iterator = query.pages(OffsetRequest.ofPage(1, 2)).iterator();
        assertThrows(IllegalStateException.class, iterator::next);
        assertFalse(iterator.hasNext()); assertThrows(NoSuchElementException.class, iterator::next);
        assertEquals(1, loads.get());
    }
    @Test void cursorTokensAreOpaqueAndContinuationPreservesTheRequestedSize() {
        var cursor = PageCursor.of("opaque-value");
        assertFalse(cursor.toString().contains("opaque-value"));
        assertEquals("opaque-value", cursor.value());
        assertThrows(DomainValidationException.class, () -> PageCursor.of(" "));
        var request = CursorRequest.first(PageSize.of(2));
        var page = CursorPage.of(Items.of(List.of(1, 2)), request, Optional.of(cursor));
        assertEquals(CursorRequest.after(cursor, 2), page.nextRequest().orElseThrow());
        var changed = CursorRequest.after(cursor, 7); assertEquals(7, changed.pageSize());
        var mapped = page.mapItems(Items::of, Object::toString);
        assertEquals(page.nextCursor(), mapped.nextCursor());
        assertThrows(PaginationContractException.class, () -> CursorPage.of(Items.of(List.of(3, 4)),
                CursorRequest.after(cursor, 2), Optional.of(cursor)));
    }
    @Test void cursorAndCountedQueriesTraverseThroughTheirOwnResultContracts() {
        var cursor = PageCursor.of("second");
        CursorQuery<Integer, Items<Integer>> query = request -> request.cursor().isEmpty()
                ? CursorPage.of(Items.of(List.of(1, 2)), request, Optional.of(cursor))
                : CursorPage.of(Items.of(List.of(3)), request, Optional.empty());
        var all = new ArrayList<Integer>(); query.pages(CursorRequest.first(2)).items().forEach(all::add);
        assertEquals(List.of(1, 2, 3), all);
        CountedOffsetQuery<Integer, Items<Integer>> counted = request -> CountedOffsetPage.of(
                request.offset() == 0 ? Items.of(List.of(1, 2)) : Items.of(List.of(3)), request, 3);
        var totals = new ArrayList<Long>(); counted.pages(OffsetRequest.ofPage(1, 2)).forEach(page -> totals.add(page.totalElements()));
        assertEquals(List.of(3L, 3L), totals);
    }
    @Test void aLoaderCannotSilentlyJumpToADifferentRequestedWindow() {
        OffsetQuery<Integer, Items<Integer>> query = request -> OffsetPage.of(
                Items.of(List.of(1)), OffsetRequest.ofPage(9, 20), false);
        var iterator = query.pages(OffsetRequest.ofPage(1, 20)).iterator();
        assertThrows(PaginationContractException.class, iterator::next);
        assertFalse(iterator.hasNext());
    }

}
