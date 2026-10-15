// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.demo;

import example.debug.adapter.inbound.http.LabelApi;
import example.debug.adapter.inbound.http.dto.LabelApiDtos.Request;
import example.debug.adapter.inbound.http.mapping.LabelApiMappings;
import example.debug.adapter.outbound.LocalLabelArchive;
import example.debug.adapter.outbound.mapping.LocalLabelArchiveMappings;
import example.debug.domain.Label;
import example.debug.domain.LabelChecked;
import example.debug.usecase.FormatLabel;

/** Direct calls isolate adapter/mapping debugging; no HTTP server, database or event delivery. */
public final class AdapterMappingProbe {
    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("event-invalid")) {
            try {
                LabelChecked.create(Label.of(""));
                throw new AssertionError("Invalid event payload must fail validation");
            } catch (org.vernac.runtime.DomainValidationException expected) {
                System.out.println(expected.getMessage());
            }
            return;
        }
        Label input = Label.of(" parcel ");
        Label label = new LocalLabelArchive().load(input);
        var outbound = LocalLabelArchiveMappings.toPayload(label);
        var manual = LabelApiMappings.manual(label);
        var response =
                new LabelApi(
                                new FormatLabel(
                                        new org.springframework.beans.factory.support
                                                        .DefaultListableBeanFactory()
                                                .getBeanProvider(
                                                        org.vernac.runtime.events.EventHandler
                                                                .class)))
                        .format(Request.of(label.string(), "!"));
        var event = LabelChecked.create(label);
        System.out.println(outbound + " / " + manual + " / " + response.getBody() + " / " + event);
    }
}
