# Try adapter DTOs and PATCH

This tutorial builds on [outbound ports](outbound-ports.md). DTOs describe external
wire data. They do not change your port's domain-oriented signature.

Add the JSON runtime to the application POM, using the same Vernac version as your
compiler:

```xml
<dependency>
    <groupId>org.vernac</groupId>
    <artifactId>vernac-runtime-json</artifactId>
    <version>${vernac.version}</version>
</dependency>
```

Here `vernac.version` is an application property containing that version. When your
application manages the dependency centrally, use that managed version instead.

## Define local transport records

Create `src/main/vernac/example/integration/archive.vernac`:

```vernac
namespace example.integration;

port Archive {
    void ping();
}

adapter LocalArchive implements Archive {
    behavior {
        public void ping() {
            // A minimal operation while we explore the DTO contract.
        }
    }

    schema DocumentResponse naming snake_case (
        String documentId,
        list<Line> lines,
        String? comment,
        String? reason absent as null
    );

    schema Line(String productCode, int quantity);

    schema DocumentPatch (
        patch String documentId,
        patch String? comment
    );
}
```

Run the application's usual Maven build. Open the generated
`example.integration.adapter.outbound.dto.LocalArchiveDtos` holder. Its nested
records are transport objects, separate from the domain `Archive` interface.

## Read a response

In Java, including a test or runner:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import example.integration.adapter.outbound.dto.LocalArchiveDtos.DocumentResponse;

var json = new ObjectMapper();
var response = json.readValue("""
        {
          "document_id": "doc-7",
          "lines": [{"productCode": "ABC", "quantity": 2, "newField": true}],
          "futureExtension": "ignored"
        }
        """, DocumentResponse.class);

assert response.documentId().equals("doc-7");
assert response.comment().isEmpty();
assert response.lines().getFirst().quantity() == 2;
```

Unknown fields are ignored even inside `Line`. Notice that `Line` did not declare
snake_case: its JSON field remains `productCode`. No convention is silently inherited.

Try removing `quantity`, supplying `null`, or replacing `2` with `"2"`. These are
errors identifying `$.lines[0].quantity`, not implicit zero or automatic conversion.
Replace `lines` with an empty array: that is a valid present empty list.

On serialization, absent `comment` is omitted and absent `reason` becomes JSON null:

```java
String encoded = json.writeValueAsString(response);
var tree = json.readTree(encoded);
assert !tree.has("comment");
assert tree.get("reason").isNull();
```

For direct creation use `DocumentResponse.of(id, lines, null, null)`. Factories
accept nullable ordinary optional arguments; record accessors return Optional.
Lists are copied and cannot be mutated through the DTO.

## Express PATCH intent

```java
import example.integration.adapter.outbound.dto.LocalArchiveDtos.DocumentPatch;
import org.vernac.runtime.ports.PatchField;

var update = DocumentPatch.of(PatchField.unchanged(), PatchField.clear());
assert json.writeValueAsString(update).equals("{\"comment\":null}");

var decoded = json.readValue("{}", DocumentPatch.class);
assert decoded.documentId().isUnchanged();
assert decoded.comment().isUnchanged();
```

Try decoding `{"documentId":null}`. It fails because the field is not clearable.
Try `{"comment":""}`: this sets an empty string and differs from clearing.
Direct Java construction rejects the same illegal clear operation.

Nothing here applies changes to an aggregate or makes a network request. That
separation is intentional: DTO conversion preserves protocol intent; application
and domain code decide how to act on it.

## Use DTOs inside adapter Java

The generated record can be imported in the adapter's `behavior` block:

```vernac
java imports {
    example.integration.adapter.outbound.dto.LocalArchiveDtos.DocumentResponse;
}
```

Keep it out of the domain port signature. Continue with [typed mappings](adapter-mappings.md),
then [generated REST calls](declarative-rest.md). The complete current rules are in the
[DTO contract](../contracts/adapter-dtos.md).
