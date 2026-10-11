package showcase;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.vernac.runtime.DomainValidationException;
import showcase.routing.domain.*;

class PlanningTest {
  private Stop stop(int units) {
    return Stop.create(Customer.of("Studio"), Address.of("Street", "Bremen"), Units.of(units));
  }

  private Tour tour(int capacity) {
    return Tour.create(Title.of("Tour"), Capacity.of(capacity), Stops.empty(), TourState.PLANNED);
  }

  @Test
  void rejectedTransferLeavesBothPlansIntact() {
    var source = tour(20);
    var target = tour(3);
    var stop = stop(8);
    source.add(stop);
    assertThatThrownBy(() -> new TourPlanning().moveStop(source, target, stop.id()))
        .isInstanceOf(DomainValidationException.class);
    assertThat(source.stops().by(stop.id())).containsSame(stop);
    assertThat(target.stops().isEmpty()).isTrue();
  }

  @Test
  void movePreservesIdentityAndListOrder() {
    var source = tour(20);
    var target = tour(20);
    var first = stop(2);
    var second = stop(3);
    source.add(first);
    source.add(second);
    new TourPlanning().moveStop(source, target, first.id());
    assertThat(source.stops().asList()).containsExactly(second);
    assertThat(target.stops().by(first.id())).containsSame(first);
  }

  @Test
  void stateMachineRejectsEmptyReleaseAndRepeatedDispatch() {
    var tour = tour(20);
    assertThatThrownBy(tour::ready).isInstanceOf(DomainValidationException.class);
    tour.add(stop(4));
    tour.ready();
    assertThatThrownBy(() -> tour.add(stop(1))).isInstanceOf(DomainValidationException.class);
    tour.dispatch();
    assertThatThrownBy(tour::dispatch).isInstanceOf(DomainValidationException.class);
    assertThat(tour.tourState()).isEqualTo(TourState.DISPATCHED);
  }

  @Test
  void duplicateMembershipAndInvalidValuesFailEarly() {
    var tour = tour(20);
    var stop = stop(4);
    tour.add(stop);
    assertThatThrownBy(() -> tour.add(stop)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> Units.of(0)).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> Title.of(" ")).isInstanceOf(DomainValidationException.class);
  }
}
