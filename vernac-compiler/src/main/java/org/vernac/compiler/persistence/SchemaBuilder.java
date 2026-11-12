// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.compiler.persistence;

import org.vernac.compiler.analyzer.*;
import org.vernac.compiler.ast.*;
import org.vernac.compiler.pipeline.*;
import org.vernac.compiler.symbols.*;
import java.util.*;
import static org.vernac.compiler.persistence.SqlNames.*;
import static org.vernac.compiler.persistence.SchemaModel.*;

/** Builds the reviewed relational slice: repository roots, values and aggregate-owned entity graphs. */
public final class SchemaBuilder {
    public record Options(boolean module,boolean applicationDelivery) {
        public static Options forProject(ResolvedProject project) {
            return new Options(java.nio.file.Files.isRegularFile(org.vernac.compiler.modules.ModuleArtifacts.projectDirectory(project.project().sourceRoot()).resolve(org.vernac.compiler.modules.ModuleDefinition.FILE)),false);
        }
    }
    private final Options options;
    private final ResolvedProject project;
    private final SchemaModel previous;
    private final Map<String, Map<String, String>> enumOverrides;
    private final Map<String, TopLevelDefinition> definitions = new HashMap<>();
    private final Map<String, Map<String, String>> codes = new TreeMap<>();
    private final List<Table> tables = new ArrayList<>();
    private final Map<String, MutableTable> entities = new HashMap<>();
    private final Set<String> expandingEntities = new HashSet<>();
    private MutableTable root;
    private boolean tenantRoot;
    private boolean eventRoot;
    private final List<StoragePlan> plans = new ArrayList<>();
    private final List<StoragePlan.Relation> relations = new ArrayList<>();
    private final List<EventStoragePlan> eventPlans = new ArrayList<>();
    public List<EventStoragePlan> eventPlans() { return List.copyOf(eventPlans); }
    public List<StoragePlan> plans() { return List.copyOf(plans); }
    private void publish(MutableTable table) {
        var schema = table.freeze();
        if(tenantRoot) schema=TenantSchema.table(schema,root.origin,table==root,
                schema.constraints().stream().map(c->table.tenantConstraints.getOrDefault(c.name(),c)).toList());
        if(eventRoot) schema=EventSchema.table(schema,root.origin,table==root,
                schema.constraints().stream().map(c->table.eventConstraints.getOrDefault(c.name(),c)).toList());
        tables.add(schema);
        relations.add(new StoragePlan.Relation(schema, eventRoot?scoped(table.predicate):table.predicate, table.depth, List.copyOf(table.properties)));
    }
    public SchemaBuilder(ResolvedProject project, SchemaModel previous) {
        this(project, previous, Map.of());
    }
    public SchemaBuilder(ResolvedProject project, SchemaModel previous, Map<String, Map<String, String>> enumOverrides) {
        this(project,previous,enumOverrides,Options.forProject(project));
    }
    public SchemaBuilder(ResolvedProject project,SchemaModel previous,Map<String,Map<String,String>> enumOverrides,Options options) {
        this.options=options;
        this.enumOverrides = Map.copyOf(enumOverrides);
        this.project = project;
        this.previous = previous;
        for (var source : project.project().sources()) for (var def : source.unit().definitions()) {
            if (def instanceof ValueObjectNode v) definitions.put(source.unit().namespace() + "." + v.name(), v);
            if (def instanceof EntityNode e) definitions.put(source.unit().namespace() + "." + e.name(), e);
            if (def instanceof AggregateNode a) definitions.put(source.unit().namespace() + "." + a.name(), a);
        }
    }
    public SchemaModel build() {
        Set<String> roots = new HashSet<>();
        for (var source : project.project().sources()) if(!source.external()) for (var def : source.unit().definitions()) {
            if (!(def instanceof RepositoryNode repo)) continue;
            var lookup = project.scopes().get(source.path()).resolve(repo.aggregateName(), repo.location());
            if (lookup.type().orElse(null) instanceof ResolvedType.Declared type
                    && type.symbol().kind() == TypeSymbol.Kind.AGGREGATE) {
                String key = type.symbol().identity().qualifiedName();
                if (!roots.add(key)) fail(repo.location(), "Only one persistence repository per aggregate is supported: " + key);
                AggregateNode a = (AggregateNode) definitions.get(key);
                MutableTable t = new MutableTable(type.symbol().identity().namespace(), a.name(), key, List.of("id"));
                root = t;
                tenantRoot = a.tenant();
                relations.clear();
                t.predicate = name("id") + " = :aggregateId";
                entities.clear();
                expandingEntities.clear();
                t.column("id", "UUID", false, "UUID", key + ".id");
                t.column("@version", "BIGINT", false, "persistence-version", key);
                // Technical timestamps use the same lossless Instant layout as domain values.
                scalar(t, "@createdAt", "Instant", "TRUE", false, key);
                scalar(t, "@updatedAt", "Instant", "TRUE", false, key);
                for (var f : a.fields()) t.properties.add(new StoragePlan.Property(f.name(), field(t, f.name(), project.typeOf(f.type()), f.type().isOptional(), "TRUE", f.location())));
                publish(t);
                plans.add(new StoragePlan(repo, source.unit().namespace(), a, type.symbol().identity().namespace(), List.copyOf(relations)));
            } else fail(repo.location(), "Repository requires a resolved aggregate: " + repo.aggregateName());
        }
        buildEvents();
        if (!codes.keySet().containsAll(enumOverrides.keySet())) throw new IllegalArgumentException("Enum-code override names an enum not used by persistence.");
        return new SchemaModel(tables.stream().anyMatch(t->t.eventIsolation()!=null)?3:tables.stream().anyMatch(t->t.tenantIsolation()!=null)?2:1, 1, tables, codes);
    }
    private void buildEvents() {
        tenantRoot=false;
        eventRoot=true;
        Set<String> deferredEvents = new HashSet<>();
        for(var source:project.project().sources()) if(!source.external()) for(var definition:source.unit().definitions()) {
            if(definition instanceof ListenerNode listener && listener.deferred()) {
                var type=(ResolvedType.Declared)project.typeOf(listener.eventType());
                deferredEvents.add(type.symbol().identity().qualifiedName());
            }
        }
        for(var source:project.project().sources()) if(!source.external()) for(var definition:source.unit().definitions()) {
            if(!(definition instanceof EventNode event)) continue;
            String identity=source.unit().namespace()+"."+event.name();
            if(!options.module() && !event.recorded() && !deferredEvents.contains(identity)) continue;
            MutableTable t=new MutableTable(source.unit().namespace(),"@event:"+event.name(),identity,List.of("@eventId"));
            root=t; relations.clear(); entities.clear(); expandingEntities.clear();
            t.predicate=name("@eventId")+" = :aggregateId";
            t.column("@eventId","UUID",false,"EventId",identity);
            scalar(t,"@occurredAt","Instant","TRUE",false,identity);
            for(var f:event.fields()) t.properties.add(new StoragePlan.Property(f.name(),field(t,f.name(),project.typeOf(f.type()),f.type().isOptional(),"TRUE",f.location())));
            publish(t);
            eventPlans.add(new EventStoragePlan(source.unit().namespace(),event,List.copyOf(relations)));
        }
        eventRoot=false;
        if(!options.module() && (!deferredEvents.isEmpty() || options.applicationDelivery())) {
            MutableTable t=new MutableTable("vernac","@delivery","@event-delivery",List.of("id"));
            for(String c:List.of("id","eventId")) t.column(c,"UUID",false,"UUID",t.origin);
            for(String c:List.of("eventType","listenerId","status")) t.column(c,"TEXT",false,"String",t.origin);
            t.column("tenantId","UUID",true,"delivery-tenant-id",t.origin);
            t.column("attempts","INTEGER",false,"int",t.origin);
            for(String c:List.of("createdAt","nextAttemptAt")) t.column(c,"TIMESTAMPTZ(6)",false,"delivery-time",t.origin);
            for(String c:List.of("lastAttemptAt","leaseUntil","completedAt")) t.column(c,"TIMESTAMPTZ(6)",true,"delivery-time",t.origin);
            t.column("claimToken","UUID",true,"UUID",t.origin);
            t.column("lastError","TEXT",true,"String",t.origin);
            t.constraints.add(new Constraint("@unique:delivery","UNIQUE ("+sqlNames(List.of("eventType","eventId","listenerId"))+")"));
            t.check("attempts",name("attempts")+" >= 0");
            t.check("status",name("status")+" IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'BLOCKED')");
            t.check("claim","("+name("status")+" = 'PROCESSING' AND "+name("claimToken")+" IS NOT NULL AND "+name("leaseUntil")+" IS NOT NULL) OR ("+name("status")+" <> 'PROCESSING' AND "+name("claimToken")+" IS NULL AND "+name("leaseUntil")+" IS NULL)");
            tables.add(t.freeze());
        }
    }
    private StoragePlan.Value field(MutableTable t, String path, ResolvedType type, boolean optional, String parent, SourceLocation loc) {
        if (type instanceof ResolvedType.Builtin builtin) {
            scalar(t, path, builtin.javaType().getSimpleName(), parent, optional, t.origin + "." + path);
            return leaf(type, optional, path, builtin.javaType().getSimpleName());
        }
        var symbol = ((ResolvedType.Declared) type).symbol();
        String identity = symbol.identity().qualifiedName();
        if (symbol.kind() == TypeSymbol.Kind.ID) { scalar(t, path, "UUID", parent, optional, identity); return leaf(type, optional, path, "UUID"); }
        if (symbol.kind() == TypeSymbol.Kind.ENTITY) {
            MutableTable target = entity(symbol, loc);
            t.column(path, "UUID", optional || !parent.equals("TRUE"), "entity-reference:" + identity, identity);
            t.foreignKey("@fk:entity:" + path, List.of(aggregateColumn(t),path),target.namespace,target.name,
                    List.of("@aggregateId","id"),true);
            return new StoragePlan.Value(type, optional, path, null, List.of(path), List.of(), target.freeze().identity(), null, null, false);
        }
        if (symbol.kind() == TypeSymbol.Kind.COLLECTION) return collection(t, path, symbol, optional, parent, loc);
        if (symbol.kind() != TypeSymbol.Kind.VALUE_OBJECT && symbol.kind() != TypeSymbol.Kind.ENUM) {
            fail(loc, "Entity persistence is not implemented yet; cannot persist field '" + path + "' of type " + identity);
        }
        ValueObjectNode value = (ValueObjectNode) definitions.get(identity);
        if (value.isEnum()) return enumValue(t, path, type, optional, parent, loc, value, identity);
        return flattenedValue(t, path, type, optional, parent, value, identity);
    }

    private StoragePlan.Value enumValue(MutableTable t, String path, ResolvedType type, boolean optional,
                                       String parent, SourceLocation loc, ValueObjectNode value, String identity) {
        Map<String, String> mapping = new TreeMap<>();
        var old = previous.enumCodes().getOrDefault(identity, Map.of());
        value.enumConstants().forEach(c -> mapping.put(c.name(), old.getOrDefault(c.name(), c.name())));
        if (enumOverrides.containsKey(identity)) {
            var override = enumOverrides.get(identity);
            if (!override.keySet().equals(mapping.keySet()) || override.values().stream().anyMatch(Objects::isNull))
                fail(loc, "Enum-code override must map every current constant exactly once: " + identity);
            mapping.clear(); mapping.putAll(override);
        }
        if (new HashSet<>(mapping.values()).size() != mapping.size()) fail(loc, "Duplicate persistence codes for " + identity);
        codes.put(identity, mapping);
        scalar(t, path, "String", parent, optional, identity);
        t.check("enum:" + path, name(path) + " IN (" + String.join(", ", mapping.values().stream().map(SqlNames::literal).toList()) + ")");
        return leaf(type, optional, path, "String");
    }

    private StoragePlan.Value flattenedValue(MutableTable t, String path, ResolvedType type, boolean optional,
                                            String parent, ValueObjectNode value, String identity) {
        Set<String> before = new HashSet<>(t.columns.keySet());
        String active = parent;
        String presence = null, markerName = null;
        if (optional) {
            String witness = witness(type, path);
            presence = witness;
            if (witness == null) {
                String marker = "@present:" + path + ":" + identity;
                markerName = marker;
                presence = marker;
                t.column(marker, "BOOLEAN", !parent.equals("TRUE"), "presence", identity);
                if (!parent.equals("TRUE")) t.check("presence:" + path + ":" + identity, "((" + parent + ") AND " + name(marker) + " IS NOT NULL) OR ((NOT (" + parent + ")) AND " + name(marker) + " IS NULL)");
                active = and(parent, name(marker) + " IS TRUE");
            } else active = and(parent, name(witness) + " IS NOT NULL");
        }
        List<StoragePlan.Property> nested = new ArrayList<>();
        for (var f : value.fields()) nested.add(new StoragePlan.Property(f.name(), field(t, value.fields().size() == 1 ? path : path + "." + f.name(),
                project.typeOf(f.type()), f.type().isOptional(), active, f.location())));
        // No stray optional children are allowed when the containing object is absent.
        List<String> children = t.columns.keySet().stream().filter(n -> !before.contains(n) && !n.equals("@present:" + path + ":" + identity)).toList();
        // A sole column witnessing its own presence needs no absence constraint:
        // column IS NOT NULL OR column IS NULL is always true. Retain guards
        // involving an outer owner or any additional flattened component.
        boolean soleWitness = children.size() == 1 && active.equals(name(children.getFirst()) + " IS NOT NULL");
        if (!children.isEmpty() && !active.equals("TRUE") && !soleWitness) t.check("absent:" + path + ":" + identity,
                "(" + active + ") OR (" + String.join(" AND ", children.stream().map(n -> name(n) + " IS NULL").toList()) + ")");
        return new StoragePlan.Value(type, optional, presence, markerName, List.of(), nested, null, null, null, false);
    }
    private static StoragePlan.Value leaf(ResolvedType type, boolean optional, String path, String scalar) {
        var columns = ScalarMappings.of(scalar).stream().map(p -> partName(path, p.component())).toList();
        return new StoragePlan.Value(type, optional, optional ? columns.getFirst() : null, null, columns, List.of(), null, null, null, false);
    }
    private String witness(ResolvedType type, String path) {
        if (type instanceof ResolvedType.Builtin b) return partName(path, ScalarMappings.of(b.javaType().getSimpleName()).getFirst().component());
        var symbol = ((ResolvedType.Declared) type).symbol();
        if (symbol.kind() == TypeSymbol.Kind.ID) return path;
        if (symbol.kind() == TypeSymbol.Kind.COLLECTION) return "@present:" + path;
        if (symbol.kind() != TypeSymbol.Kind.VALUE_OBJECT && symbol.kind() != TypeSymbol.Kind.ENUM) return null;
        ValueObjectNode v = (ValueObjectNode) definitions.get(symbol.identity().qualifiedName());
        if (v.isEnum()) return path;
        for (var f : v.fields()) {
            String childPath = v.fields().size() == 1 ? path : path + "." + f.name();
            ResolvedType child = project.typeOf(f.type());
            if (!f.type().isOptional()) {
                String found = witness(child, childPath);
                if (found != null) return found;
            } else if (child instanceof ResolvedType.Declared declared) {
                if (declared.symbol().kind() == TypeSymbol.Kind.COLLECTION) return "@present:" + childPath;
                if (declared.symbol().kind() == TypeSymbol.Kind.VALUE_OBJECT && witness(child, childPath) == null)
                    return "@present:" + childPath + ":" + declared.symbol().identity().qualifiedName();
            }
        }
        return null;
    }
    private static String partName(String path, String component) { return component.isEmpty() ? path : "@" + component + ":" + path; }
    private void scalar(MutableTable t, String path, String type, String parent, boolean optional, String origin) {
        var parts = ScalarMappings.of(type);
        List<String> names = new ArrayList<>();
        for (var p : parts) {
            String n = partName(path, p.component()); names.add(n);
            t.column(n, p.sqlType(), optional || !parent.equals("TRUE"), type + ":" + p.component(), origin);
            if (!p.check().isEmpty()) t.check("range:" + n, p.check().formatted(name(n)));
        }
        String notNull = String.join(" AND ", names.stream().map(n -> name(n) + " IS NOT NULL").toList());
        String allNull = String.join(" AND ", names.stream().map(n -> name(n) + " IS NULL").toList());
        // NOT NULL already covers required top-level components. A nullable single
        // column has no partial representation; the enclosing VO owns absence checks.
        if (parent.equals("TRUE")) {
            if (optional && names.size() > 1) t.check("shape:" + path, "(" + notNull + ") OR (" + allNull + ")");
        } else if (!optional && !(names.size() == 1 && parent.equals(notNull))) {
            t.check("shape:" + path, "((" + parent + ") AND (" + notNull
                    + ")) OR ((NOT (" + parent + ")) AND (" + allNull + "))");
        } else if (names.size() > 1) {
            t.check("shape:" + path, "(" + notNull + ") OR (" + allNull + ")");
        }
    }
    private static String and(String parent, String condition) {
        return parent.equals("TRUE") || parent.equals(condition) ? condition : "(" + parent + ") AND " + condition;
    }
    private String aggregateColumn(MutableTable owner) {
        return owner == root ? "id" : "@aggregateId";
    }
    private MutableTable entity(TypeSymbol symbol, SourceLocation loc) {
        String identity = symbol.identity().qualifiedName();
        if (expandingEntities.contains(identity)) fail(loc, "Cyclic entity containment: " + identity);
        MutableTable existing = entities.get(identity);
        if (existing != null) return existing;
        EntityNode node = (EntityNode) definitions.get(identity);
        MutableTable t = new MutableTable(root.namespace, root.name + ".@entity:" + identity,
                identity, List.of("@aggregateId", "id"));
        t.depth = 1;
        t.predicate = name("@aggregateId") + " = :aggregateId";
        entities.put(identity, t);
        expandingEntities.add(identity);
        t.column("@aggregateId", "UUID", false, "aggregate-owner", root.origin);
        t.column("id", "UUID", false, "UUID", identity + ".id");
        t.foreignKey("@fk:aggregate",List.of("@aggregateId"),root.namespace,root.name,List.of("id"),false);
        for (FieldNode f : node.fields()) t.properties.add(new StoragePlan.Property(f.name(), field(t, f.name(), project.typeOf(f.type()),
                f.type().isOptional(), "TRUE", f.location())));
        expandingEntities.remove(identity);
        publish(t);
        return t;
    }
    private MutableTable entityCollection(MutableTable owner, String path, TypeSymbol element, boolean list, SourceLocation loc) {
        MutableTable target = entity(element, loc);
        boolean rootOwner = owner == root;
        List<String> ownerColumns = rootOwner ? List.of("@aggregateId") : List.of("@aggregateId", "@ownerId");
        List<String> key = new ArrayList<>(ownerColumns);
        key.add(list ? "@position" : "@entityId");
        MutableTable link = new MutableTable(root.namespace, owner.name + "." + path,
                owner.origin + "." + path, key);
        link.depth = owner.depth + 1;
        link.column("@aggregateId", "UUID", false, "aggregate-owner", root.origin);
        if (!rootOwner) link.column("@ownerId", "UUID", false, "entity-owner", owner.origin);
        link.column("@entityId", "UUID", false, "entity-reference:" + element.identity().qualifiedName(), element.identity().qualifiedName());
        if (list) {
            link.column("@position", "INTEGER", false, "list-position", path);
            link.check("position", name("@position") + " >= 0");
        }
        link.foreignKey("@fk:owner",ownerColumns,owner.namespace,owner.name,
                rootOwner?List.of("id"):List.of("@aggregateId","id"),false);
        link.foreignKey("@fk:entity",List.of("@aggregateId","@entityId"),target.namespace,target.name,
                List.of("@aggregateId","id"),true);
        link.predicate = name("@aggregateId") + " = :aggregateId";
        publish(link);
        return link;
    }
    private static String sqlNames(List<String> columns) {
        return String.join(", ", columns.stream().map(SqlNames::name).toList());
    }

    private StoragePlan.Value collection(MutableTable owner, String path, TypeSymbol symbol,
                                         boolean optional, String parent, SourceLocation loc) {
        var declaration = CollectionTypes.find(project.project(), symbol.identity()).orElseThrow();
        var element = project.project().symbols()
                .find(symbol.identity().namespace() + "." + declaration.elementName()).orElseThrow();
        if (!declaration.valueElements() && element.kind() != TypeSymbol.Kind.ENTITY)
            fail(loc, "Aggregate collections cannot be contained: " + path);

        String marker = collectionPresence(owner, path, symbol, optional, parent);
        boolean list = declaration.definition().kind() == CollectionDefinitionNode.Kind.LIST;
        if (element.kind() == TypeSymbol.Kind.ENTITY)
            return entityCollectionValue(owner, path, symbol, element, optional, marker, list, loc);
        return valueCollectionValue(owner, path, symbol, element, optional, marker, list, loc);
    }

    private String collectionPresence(MutableTable owner, String path, TypeSymbol symbol,
                                      boolean optional, String parent) {
        if (!optional && parent.equals("TRUE")) return null;
        String marker = "@present:" + path;
        owner.column(marker, "BOOLEAN", !parent.equals("TRUE"), "collection-presence", symbol.identity().qualifiedName());
        if (!parent.equals("TRUE")) owner.check("presence:" + path,
                "((" + parent + ") AND " + name(marker) + (optional ? " IS NOT NULL" : " IS TRUE")
                        + ") OR ((NOT (" + parent + ")) AND " + name(marker) + " IS NULL)");
        return marker;
    }

    private StoragePlan.Value entityCollectionValue(MutableTable owner, String path, TypeSymbol symbol,
            TypeSymbol element, boolean optional, String marker, boolean list, SourceLocation loc) {
        MutableTable link = entityCollection(owner, path, element, list, loc);
        var item = new StoragePlan.Value(new ResolvedType.Declared(element), false, null, null,
                List.of("@entityId"), List.of(), entities.get(element.identity().qualifiedName()).freeze().identity(),
                null, null, false);
        return collectionValue(symbol, optional, marker, item, link,
                owner == root ? "@aggregateId" : "@ownerId", "id", list);
    }

    private StoragePlan.Value valueCollectionValue(MutableTable owner, String path, TypeSymbol symbol,
            TypeSymbol element, boolean optional, String marker, boolean list, SourceLocation loc) {
        MutableTable child = valueCollectionTable(owner, path, symbol, list);
        String ownerKey = valueCollectionOwnerKey(owner);
        linkValueCollection(owner, child, ownerKey);
        child.predicate = valueCollectionPredicate(owner, ownerKey);
        var item = field(child, "value", new ResolvedType.Declared(element), false, "TRUE", loc);
        publish(child);
        return collectionValue(symbol, optional, marker, item, child, "@ownerId", ownerKey, list);
    }

    private MutableTable valueCollectionTable(MutableTable owner, String path, TypeSymbol symbol, boolean list) {
        List<String> key = list ? (isEntityTable(owner)
                ? List.of("@aggregateId", "@ownerId", "@position") : List.of("@ownerId", "@position"))
                : List.of("@rowId");
        MutableTable child = new MutableTable(owner.namespace, owner.name + "." + path,
                symbol.identity().qualifiedName(), key);
        child.depth = owner.depth + 1;
        child.column("@rowId", "UUID", false, "collection-row", path);
        child.column("@ownerId", "UUID", false, "collection-owner", path);
        if (list) {
            child.column("@position", "INTEGER", false, "list-position", path);
            child.check("position", name("@position") + " >= 0");
            child.constraints.add(new Constraint("@unique:row:" + child.name, "UNIQUE (" + name("@rowId") + ")"));
        }
        return child;
    }

    private void linkValueCollection(MutableTable owner, MutableTable child, String ownerKey) {
        if (isEntityTable(owner) || tenantRoot)
            child.column("@aggregateId", "UUID", false, "aggregate-owner", root.origin);
        if (isEntityTable(owner) || (tenantRoot && owner != root)) {
            child.foreignKey("@fk:owner", List.of("@aggregateId", "@ownerId"), owner.namespace, owner.name,
                    List.of("@aggregateId", ownerKey), false);
        } else {
            child.foreignKey("@fk:owner", List.of("@ownerId"), owner.namespace, owner.name, List.of(ownerKey), false);
        }
        if (tenantRoot && owner == root)
            child.check("aggregate-owner", name("@aggregateId") + " = " + name("@ownerId"));
    }

    private String valueCollectionPredicate(MutableTable owner, String ownerKey) {
        if (isEntityTable(owner)) return name("@aggregateId") + " = :aggregateId";
        return name("@ownerId") + " IN (SELECT " + name(ownerKey) + " FROM " + table(owner.namespace, owner.name)
                + " WHERE " + scoped(owner.predicate) + ")";
    }

    private String valueCollectionOwnerKey(MutableTable owner) {
        if (isEntityTable(owner)) return "id";
        return owner == root ? root.primaryKey.getFirst() : "@rowId";
    }

    private static boolean isEntityTable(MutableTable table) {
        return table.primaryKey.equals(List.of("@aggregateId", "id"));
    }

    private static StoragePlan.Value collectionValue(TypeSymbol symbol, boolean optional, String marker,
            StoragePlan.Value item, MutableTable table, String ownerColumn, String ownerKey, boolean list) {
        return new StoragePlan.Value(new ResolvedType.Declared(symbol), optional, null, marker, List.of(),
                List.of(new StoragePlan.Property("value", item)), table.freeze().identity(), ownerColumn, ownerKey, list);
    }
    private String scoped(String predicate) {
        if(eventRoot) return name(EventSchema.SCOPE)+" = :__vernacEventScope AND "+name(EventSchema.TENANT)
                +" IS NOT DISTINCT FROM CAST(:__vernacEventTenant AS uuid) AND ("+predicate+")";
        return tenantRoot ? name(TenantSchema.COLUMN)+" = :__vernacTenant AND ("+predicate+")" : predicate;
    }
    private static void fail(SourceLocation loc, String message) {
        throw new SemanticValidationException(List.of(CompilerDiagnostic.error(loc, message)));
    }
    private static final class MutableTable {
        final String namespace, name, origin;
        final List<String> primaryKey;
        final Map<String, Column> columns = new TreeMap<>();
        final List<Constraint> constraints = new ArrayList<>();
        final Map<String,Constraint> tenantConstraints=new HashMap<>();
        final Map<String,Constraint> eventConstraints=new HashMap<>();
        final List<StoragePlan.Property> properties = new ArrayList<>();
        String predicate;
        int depth;
        MutableTable(String namespace, String name, String origin, List<String> key) {
            this.namespace = namespace; this.name = name; this.origin = origin; primaryKey = key;
        }
        void column(String name, String type, boolean nullable, String codec, String origin) {
            if (columns.putIfAbsent(name, new Column(name, type, nullable, codec, origin)) != null)
                throw new IllegalArgumentException("Flattened column collision: " + this.name + "." + name);
        }
        void foreignKey(String name,List<String> from,String namespace,String target,List<String> to,boolean deferred) {
            constraints.add(new Constraint(name,"FOREIGN KEY ("+sqlNames(from)+") REFERENCES "+table(namespace,target)
                    +" ("+sqlNames(to)+")"+(deferred?" DEFERRABLE INITIALLY DEFERRED":"")));
            tenantConstraints.put(name,TenantSchema.foreignKey(name,from,namespace,target,to,deferred));
            eventConstraints.put(name,EventSchema.foreignKey(name,from,namespace,target,to,deferred));
        }
        void check(String name, String expression) { constraints.add(new Constraint("@check:" + name, "CHECK (" + expression + ")")); }
        Table freeze() { return new Table(namespace, name, origin, List.copyOf(columns.values()), primaryKey, constraints); }
    }
}
