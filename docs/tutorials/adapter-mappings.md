# Map DTOs to validated domain values

Read [adapter DTOs](adapter-dtos.md) first and keep the `vernac-runtime-json`
dependency. The Catalog model below is a separate exercise, not an addition to
the archive declarations. This tutorial uses no HTTP server or database.

Create `src/main/vernac/example/catalog/catalog.vernac`:

```vernac
namespace example.catalog;

value ProductName(String) validates {
    require(!self.string().isBlank(), "Product name must not be blank");
};
value Quantity(int);
value Item(ProductName, Quantity) list Items;
value Catalog(Items);

port CatalogSource {
    Catalog load();
}

adapter LocalCatalog implements CatalogSource {
    behavior {
        java imports {
            example.catalog.adapter.outbound.dto.LocalCatalogDtos.CatalogPayload;
            example.catalog.adapter.outbound.dto.LocalCatalogDtos.ItemPayload;
            example.catalog.adapter.outbound.mapping.LocalCatalogMappings;
            java.util.List;
        }
        public Catalog load() {
            var payload = CatalogPayload.of(List.of(ItemPayload.of("Notebook", 2)));
            return LocalCatalogMappings.toCatalog(payload);
        }
    }

    schema CatalogPayload(list<ItemPayload> items);
    schema ItemPayload(String name, int quantity);

    mapping toCatalog: Catalog <- CatalogPayload {
        items <- source.items using toItem;
    }

    mapping toItem: Item <- ItemPayload {
        productName <- source.name;
        quantity <- source.quantity;
    }

    mapping toPayload: ItemPayload <- Item {
        name <- source.productName;
        quantity <- source.quantity;
    }
}
```

Build the application. `toItem` wraps String in ProductName and int in Quantity,
using their validated factories. The inverse mapping unwraps them. The collection
mapping preserves list order and duplicate entries. No property-name guessing is
involved: every assignment is visible in Vernac.

## Try the generated API

In a test or runner:

```java
import example.catalog.adapter.outbound.dto.LocalCatalogDtos.ItemPayload;
import example.catalog.adapter.outbound.mapping.LocalCatalogMappings;

var payload = ItemPayload.of("Notebook", 2);
var item = LocalCatalogMappings.toItem(payload);
assert item.productName().string().equals("Notebook");
assert item.quantity().intValue() == 2;
assert LocalCatalogMappings.toPayload(item).equals(payload);
```

Change the name to an empty string. The ProductName factory rejects it, and the
mapping reports the target path `$.productName` with the original validation cause.
With an invalid item inside toCatalog, the path includes its index:
`$.items[0].productName`.

Now change `String name` in ItemPayload to `String? name`. The existing required
ProductName assignment fails during Vernac compilation. The compiler will not guess
a default or silently construct an invalid domain object.

## Make a deliberate default explicit

Replace just `toItem` with a Java mapping:

```vernac
mapping toItem: Item <- ItemPayload {
    java {
        return Item.of(
            ProductName.of(source.name().orElse("Unnamed product")),
            Quantity.of(source.quantity())
        );
    }
}
```

This version is for the optional `name` experiment. The target remains validated;
the fallback is now an explicit application decision. Restore the initial declaration
and declarative mapping when finished.

For real integrations, decide whether a missing name should be accepted at all.
Keeping the compiler error and tightening the response contract may be preferable
to introducing a fallback.

## Keep extending without building another mapping language

For nested objects, add a named mapping and write `address <- source.address using
toAddress`. For whole collection conversion, enum-code translation or deliberate
normalization, use a Java mapping. To move the Java body to a handwritten class:

```vernac
mapping toItem: Item <- ItemPayload
    implemented by example.catalog.integration.ItemConversions;
```

The class provides a static `Item toItem(ItemPayload source)` method. It needs no
Spring registration and can use its own Java imports.

The [mapping contract](../contracts/adapter-mappings.md) covers optional paths,
set/list distinctions and PATCH intent. Continue with [REST transport](declarative-rest.md) and [error rules](declarative-errors.md).
The mappings also work independently of HTTP.
