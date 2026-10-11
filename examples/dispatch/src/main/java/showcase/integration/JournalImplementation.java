package showcase.integration;

import java.util.Map;
import showcase.carrier.domain.ShippingQuote;
import showcase.routing.adapter.outbound.access.JdbcDispatchJournalAccess;
import showcase.routing.domain.*;

public final class JournalImplementation {
  private JournalImplementation() {}

  public static void record(
      JdbcDispatchJournalAccess self, TourId tourId, Title title, ShippingQuote shippingQuote) {
    self.jdbc()
        .update(
            """
            INSERT INTO dispatch_receipt(tour_id, title, quote_reference, amount, currency)
            VALUES (:id, :title, :reference, :amount, :currency)
            ON CONFLICT (tour_id) DO NOTHING
            """,
            Map.of(
                "id",
                tourId.value(),
                "title",
                title.string(),
                "reference",
                shippingQuote.reference().string(),
                "amount",
                shippingQuote.price().amount(),
                "currency",
                shippingQuote.price().currency().string()));
  }
}
