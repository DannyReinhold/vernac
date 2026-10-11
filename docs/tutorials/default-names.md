# Default names and explicit roles

Use a Vernac Maven project from the [plugin tutorial](intellij-plugin.md).
Create namespace `example.naming`, then a file within that namespace:

```vernac
namespace example.naming;

id TaskId;
value Title(String);
value Reference(TaskId);
```

Run `mvn compile`. Inspect the generated sources: `Title` exposes `string()`,
`Reference` exposes `taskId()`, and the ID exposes its fixed UUID getter `value()`.

## Choose your public API

Change the title declaration to:

```vernac
value Title(String value);
```

Now its getter is `value()`. There is no second `string()` alias. Java callers must
use the declared API. Explicit field names are useful domain documentation.

To see that field count does not affect defaults, try these alternative versions:

```vernac
value Price(BigDecimal);
```

```vernac
value Price(BigDecimal, Currency);
```

Both expose `bigDecimal()`. The second adds `currency()`. Prefer an explicit
`amount` when that better expresses your domain.

## Explain distinct roles

Add `value Comparison(Title, Title);`. Both fields would be called `title`, so
Vernac rejects it. Correct it to:

```vernac
value Comparison(Title previous, Title current);
```

Vernac will not invent `title2`. Different types can collide too: `int` and a VO
named `IntValue` both produce `intValue` when used as unnamed fields.

## Explore an API conflict

Try `value Description(String toString);`. The getter would collide with the
promised `toString()` method. Use `String text` instead, provided that name is not
already used in your declaration. The editor should show the same compiler error;
if diagnostics seem outdated, check that the installed plugin contains the current
language server.

## FAQ

**Why does my one-field VO no longer expose `value()` automatically?**
All ordinary fields follow one rule. Write the name `value` explicitly for a wrapper
API. Adding another field then cannot silently rename the original getter.

**Can a field be named `equals`?**
Yes, if otherwise valid: its getter `equals()` is distinct from `equals(Object)`.
Method signatures, not a global list of method names, determine collisions.

**How are acronyms treated?**
`URLValue` gives `urlValue`, `HTMLXMLMapper` gives `htmlxmlMapper`, and
`HTML2XMLMapper` gives `html2XMLMapper`. No dictionary is used. `UrlValue` is a
recommended spelling; an explicit field name always takes precedence.

**Does Vernac type-check my Java method body?**
Not as part of these checks. It checks the method declaration against the generated
API. javac continues to check the embedded body.

See the [names contract](../contracts/names-and-unicode.md) for exact rules.
