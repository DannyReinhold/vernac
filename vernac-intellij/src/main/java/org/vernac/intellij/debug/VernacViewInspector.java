// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.intellij.debug;

import com.sun.jdi.*;

import java.io.*;
import java.util.*;

/** Reads a tiny, fail-closed subset of getter bytecode. Never invokes code in the debuggee. */
final class VernacViewInspector {
    record Contract(String owner, String name, String role, List<Method> getters) {}

    record Property(String name, ObjectReference object, Field field, boolean optional) {
        String expression() {
            return "this." + name + "()" + (optional ? ".orElse(null)" : "");
        }

        Value value() {
            return object.getValue(field);
        }
    }

    static Optional<Contract> contract(Type type) {
        if (!(type instanceof ClassType c) || !c.name().contains("$")) return Optional.empty();
        String owner = c.name().substring(0, c.name().indexOf('$'));
        int dot = owner.lastIndexOf('.');
        String api = owner.substring(0, dot + 1) + "access." + owner.substring(dot + 1);
        for (String role : List.of("Access", "Read", "Write"))
            for (var face : c.allInterfaces()) {
                if (!face.name().equals(api + role)
                        && !(role.equals("Read") && face.name().startsWith(api + "Access$Input")))
                    continue;
                var getters =
                        face.allMethods().stream()
                                .filter(
                                        m ->
                                                m.isPublic()
                                                        && !m.isStatic()
                                                        && m.signature().startsWith("()")
                                                        && !m.signature().equals("()V"))
                                .toList();
                return Optional.of(new Contract(owner, face.name(), role, getters));
            }
        return Optional.empty();
    }

    static List<Property> properties(ObjectReference view) {
        var contract = contract(view.referenceType());
        if (contract.isEmpty()) return List.of();
        Map<String, Property> result = new TreeMap<>();
        for (var getter : contract.get().getters())
            property(view, getter.name(), getter.signature())
                    .ifPresent(p -> result.putIfAbsent(p.name(), p));
        return List.copyOf(result.values());
    }

    static Optional<Property> property(ObjectReference view, String name, String signature) {
        try {
            if (!signature.startsWith("()") || signature.equals("()V")) return Optional.empty();
            var methods = view.referenceType().methodsByName(name, signature);
            if (methods.size() != 1) return Optional.empty();
            var read = read(view, methods.getFirst(), 0, new HashSet<>());
            if (read == null
                    || !(read.field.name().equals(name) || read.field.name().equals("val$" + name)))
                return Optional.empty();
            if (read.optional != signature.equals("()Ljava/util/Optional;"))
                return Optional.empty();
            return Optional.of(new Property(name, read.object, read.field, read.optional));
        } catch (RuntimeException | IOException unsupported) {
            return Optional.empty();
        }
    }

    private record Read(ObjectReference object, Field field, boolean optional) {}

    private record Member(String owner, String name, String signature) {}

    private static Read read(
            ObjectReference receiver, Method method, int depth, Set<String> visited)
            throws IOException {
        if (depth > 6
                || method.isStatic()
                || !method.signature().startsWith("()")
                || !visited.add(
                        method.declaringType().name() + "." + method.name() + method.signature()))
            return null;
        byte[] code = method.bytecodes();
        if (code.length < 5 || code[0] != 0x2a) return null; // aload_0
        var pool = pool(method.declaringType());
        Value value = receiver;
        Read result = null;
        for (int i = 1; i < code.length; ) {
            int opcode = code[i++] & 255;
            if (opcode == 0xb4) { // getfield
                if (!(value instanceof ObjectReference object) || i + 1 >= code.length) return null;
                var member = member(pool, u2(code, i));
                i += 2;
                var fields =
                        object.referenceType().allFields().stream()
                                .filter(
                                        f ->
                                                !f.isStatic()
                                                        && f.declaringType()
                                                                .name()
                                                                .equals(member.owner)
                                                        && f.name().equals(member.name)
                                                        && f.signature().equals(member.signature))
                                .toList();
                if (fields.size() != 1) return null;
                result = new Read(object, fields.getFirst(), false);
                value = object.getValue(result.field);
            } else if (opcode == 0xb6) { // invokevirtual: only another checked field getter
                if (!(value instanceof ObjectReference object) || i + 1 >= code.length) return null;
                var member = member(pool, u2(code, i));
                i += 2;
                var methods = object.referenceType().methodsByName(member.name, member.signature);
                if (methods.size() != 1) return null;
                result = read(object, methods.getFirst(), depth + 1, visited);
                if (result == null) return null;
                value = result.object.getValue(result.field);
            } else if (opcode
                    == 0xb8) { // no invocation: recognize the known optional storage projection
                if (i + 1 >= code.length || result == null || result.optional) return null;
                var member = member(pool, u2(code, i));
                i += 2;
                if (!member.equals(
                        new Member(
                                "java.util.Optional",
                                "ofNullable",
                                "(Ljava/lang/Object;)Ljava/util/Optional;"))) return null;
                result = new Read(result.object, result.field, true);
            } else if (opcode >= 0xac && opcode <= 0xb0) { // typed return, no trailing instructions
                return i == code.length ? result : null;
            } else
                return null; // guards, calls, branches, writes, casts and computation are not
                             // getters
        }
        return null;
    }

    private static int u2(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 8 | bytes[offset + 1] & 255;
    }

    private static Object[] pool(ReferenceType type) throws IOException {
        Object[] pool = new Object[type.constantPoolCount()];
        try (var in = new DataInputStream(new ByteArrayInputStream(type.constantPool()))) {
            for (int i = 1; i < pool.length; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = in.readUTF();
                    case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 ->
                            pool[i] = new int[] {in.readUnsignedShort(), in.readUnsignedShort()};
                    case 3, 4 -> in.skipNBytes(4);
                    case 5, 6 -> {
                        in.skipNBytes(8);
                        i++;
                    }
                    case 15 -> in.skipNBytes(3);
                    default -> throw new IOException("Unsupported constant-pool tag");
                }
            }
        }
        return pool;
    }

    private static Member member(Object[] pool, int index) {
        int[] ref = (int[]) pool[index], name = (int[]) pool[ref[1]];
        return new Member(
                ((String) pool[(Integer) pool[ref[0]]]).replace('/', '.'),
                (String) pool[name[0]],
                (String) pool[name[1]]);
    }
}
