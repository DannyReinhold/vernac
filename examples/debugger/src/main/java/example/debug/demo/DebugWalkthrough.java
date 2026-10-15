// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package example.debug.demo;
import example.debug.domain.*;
public final class DebugWalkthrough {
    public static void main(String[] args) {
        var label=Label.of(" parcel ");
        String decorated=label.decorate("!");
        System.out.println(decorated);
        System.out.println(label.withMarker());
        System.out.println(label.externallyDecorated());
        var parcel=Parcel.create(label,Units.of(1));
        parcel.changeUnits(Units.of(2));
        System.out.println(parcel.display()+" / units="+parcel.units().intValue());
    }
}
