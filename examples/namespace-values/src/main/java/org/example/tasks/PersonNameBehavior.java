// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.example.tasks;

import org.jspecify.annotations.NullMarked;
import org.example.tasks.domain.PersonName;

@NullMarked
public final class PersonNameBehavior {
    private PersonNameBehavior() {}

    public static int length(PersonName self) {
        return self.string().length();
    }
}
