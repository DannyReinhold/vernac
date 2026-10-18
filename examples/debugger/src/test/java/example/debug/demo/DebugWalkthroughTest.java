package example.debug.demo;
import example.debug.domain.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DebugWalkthroughTest {
    @Test void adapterAndMappingProbeRunsAndRejectsInvalidEvents() {
        AdapterMappingProbe.main(new String[0]);
        AdapterMappingProbe.main(new String[] {"event-invalid"});
    }
    @Test void listenerMappingProbesRunInBothContexts() throws Exception {
        ListenerMappingProbe.main(new String[0]);
        ListenerMappingProbe.main(new String[] {"background"});
    }
    @Test void validationProbesAcceptAndRejectTheirExpectedInputs() {
        for (String kind : new String[] {"value", "entity", "aggregate", "usecase", "service"}) {
            ValidationMappingProbe.main(new String[] {kind});
            ValidationMappingProbe.main(new String[] {kind, "invalid"});
        }
    }
    @Test void serviceMappingProbeRuns() {
        ServiceMappingProbe.main(new String[0]);
    }
    @Test void isolatedUsecaseMappingProbeRuns() {
        UsecaseMappingProbe.main(new String[0]);
    }
    @Test void exampleRunsThroughInlinePrivateAndHandwrittenBehavior() {
        var label=Label.of(" parcel ");assertEquals("PARCEL!",label.decorate("!"));
        assertEquals(" parcel !",label.withMarker());assertEquals("Java: parcel",label.externallyDecorated());
        var parcel=Parcel.create(label,Units.of(1));parcel.changeUnits(Units.of(2));
        assertEquals(2,parcel.units().intValue());assertEquals("PARCEL!",parcel.display());
    }
}
