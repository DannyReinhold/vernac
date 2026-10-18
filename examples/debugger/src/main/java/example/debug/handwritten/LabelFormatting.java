// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.handwritten;
import example.debug.domain.Label;
public final class LabelFormatting {
    private LabelFormatting() { }
    public static String externallyDecorated(Label self) {
        String text=self.string().strip();
        return "Java: "+text;
    }
}
