// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;
import java.util.List;
import org.vernac.compiler.ast.EventNode;
/** Immutable payload mapping plus emit-time listener identities. */
public record EventStoragePlan(String namespace, EventNode event, List<StoragePlan.Relation> relations) { }
