// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.IOException;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.vernac.runtime.ports.PatchField;

/** Strict, local JSON conversion used by generated DTO codecs. No global mapper changes. */
@NullMarked
public final class JsonValues {
    private JsonValues() { }
    /** Preserve decimal scale and floating signed zero without lossy double intermediates. */
    public static JsonNode readTree(JsonParser parser) throws IOException {
        return readTree(parser,"$");
    }
    private static JsonNode readTree(JsonParser parser, String path) throws IOException {
        var token=parser.currentToken();
        if(token==null) token=parser.nextToken();
        if(token==null) throw new DtoContractException(path,"Expected JSON value");
        var factory=JsonNodeFactory.instance;
        return switch(token) {
            case START_OBJECT -> {
                var result=factory.objectNode();
                while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_OBJECT) {
                    if(parser.currentToken()!=com.fasterxml.jackson.core.JsonToken.FIELD_NAME) throw new DtoContractException(path,"Expected JSON field");
                    String name=parser.currentName();
                    if(result.has(name)) throw new DtoContractException(path + "." + name,"Duplicate JSON field");
                    parser.nextToken();result.set(name,readTree(parser,path+"."+name));
                }
                yield result;
            }
            case START_ARRAY -> {
                var result=factory.arrayNode();
                while(parser.nextToken()!=com.fasterxml.jackson.core.JsonToken.END_ARRAY) result.add(readTree(parser,path+"["+result.size()+"]"));
                yield result;
            }
            case VALUE_STRING -> factory.textNode(parser.getText());
            case VALUE_TRUE -> factory.booleanNode(true);
            case VALUE_FALSE -> factory.booleanNode(false);
            case VALUE_NULL -> factory.nullNode();
            case VALUE_NUMBER_INT -> factory.numberNode(parser.getBigIntegerValue());
            case VALUE_NUMBER_FLOAT -> {
                BigDecimal decimal;
                try { decimal=new BigDecimal(parser.getText()); }
                catch(NumberFormatException ex) { throw new DtoContractException(path,"Expected finite JSON number"); }
                yield decimal.signum()==0 && parser.getText().startsWith("-") ? new NegativeZero(decimal) : new DecimalNode(decimal);
            }
            default -> throw new DtoContractException(path,"Expected JSON value");
        };
    }
    private static final class NegativeZero extends DecimalNode {
        private NegativeZero(BigDecimal value) { super(value); }
        @Override public double doubleValue() { return -0.0d; }
        @Override public float floatValue() { return -0.0f; }
    }
    public static ObjectNode object(@Nullable JsonNode node, String path) {
        if (!(node instanceof ObjectNode object)) throw new DtoContractException(path, "Expected JSON object");
        return object;
    }
    public static <T> T require(@Nullable T value, String path) {
        if (value == null) throw new DtoContractException(path, "Required value is missing or null");
        return value;
    }
    public static <T> List<T> copyList(List<T> value, String path) {
        require(value, path);
        for (int i=0; i<value.size(); i++) require(value.get(i), path + "[" + i + "]");
        return List.copyOf(value);
    }
    public static <T> PatchField<T> patchContract(PatchField<T> value, String path, boolean clearable) {
        require(value, path);
        if (value.isClear() && !clearable) throw new DtoContractException(path, "This field cannot be cleared");
        return value;
    }
    public static <T> @Nullable T optional(@Nullable JsonNode node, String path, BiFunction<JsonNode,String,T> reader) {
        return node == null || node.isNull() ? null : reader.apply(node,path);
    }
    public static <T> PatchField<T> patch(@Nullable JsonNode node, String path, boolean clearable, BiFunction<JsonNode,String,T> reader) {
        if (node == null) return PatchField.unchanged();
        if (node.isNull()) return patchContract(PatchField.clear(),path,clearable);
        return PatchField.set(reader.apply(node,path));
    }
    public static <T> List<T> list(@Nullable JsonNode node, String path, BiFunction<JsonNode,String,T> reader) {
        if (node == null || !node.isArray()) throw new DtoContractException(path,"Expected JSON array");
        List<T> result = new ArrayList<>();
        for (int i=0; i<node.size(); i++) {
            if (node.get(i).isNull()) throw new DtoContractException(path + "[" + i + "]", "Null collection elements are not allowed");
            result.add(reader.apply(node.get(i),path + "[" + i + "]"));
        }
        return List.copyOf(result);
    }
    public static <T> ArrayNode array(List<T> values, java.util.function.Function<T,JsonNode> writer) {
        var array=JsonNodeFactory.instance.arrayNode();
        values.forEach(value -> array.add(writer.apply(value)));
        return array;
    }
    @SuppressWarnings("unchecked")
    public static <T> T scalar(@Nullable JsonNode node, String path, Class<T> type) {
        if (node == null || node.isNull()) throw new DtoContractException(path,"Required value is missing or null");
        Object result;
        try {
            if (type == Boolean.class || type == boolean.class) {
                if (!node.isBoolean()) throw new IllegalArgumentException();
                result=node.booleanValue();
            } else if (type == BigDecimal.class) {
                if (!node.isNumber()) throw new IllegalArgumentException();
                result=node.decimalValue();
            } else if (type == Double.class || type == double.class || type == Float.class || type == float.class) {
                if (!node.isNumber()) throw new IllegalArgumentException();
                if (type == Float.class || type == float.class) {
                    float v=node.floatValue(); if (!Float.isFinite(v)) throw new IllegalArgumentException(); result=v;
                } else { double v=node.doubleValue(); if (!Double.isFinite(v)) throw new IllegalArgumentException(); result=v; }
            } else if (type == Byte.class || type == byte.class || type == Short.class || type == short.class
                    || type == Integer.class || type == int.class || type == Long.class || type == long.class || type == BigInteger.class) {
                if (!node.isIntegralNumber()) throw new IllegalArgumentException();
                BigInteger v=node.bigIntegerValue();
                if (type == Byte.class || type == byte.class) result=v.byteValueExact();
                else if (type == Short.class || type == short.class) result=v.shortValueExact();
                else if (type == Integer.class || type == int.class) result=v.intValueExact();
                else if (type == Long.class || type == long.class) result=v.longValueExact();
                else result=v;
            } else {
                if (!node.isTextual()) throw new IllegalArgumentException();
                String v=node.textValue();
                if (type == String.class) result=v;
                else if (type == Character.class || type == char.class) { if(v.length()!=1) throw new IllegalArgumentException(); result=v.charAt(0); }
                else if (type == UUID.class) { result=UUID.fromString(v); if(!result.toString().equalsIgnoreCase(v)) throw new IllegalArgumentException(); }
                else if (type == Currency.class) result=Currency.getInstance(v);
                else if (type == Instant.class) result=Instant.parse(v);
                else if (type == LocalDate.class) result=LocalDate.parse(v);
                else if (type == LocalTime.class) result=LocalTime.parse(v);
                else if (type == LocalDateTime.class) result=LocalDateTime.parse(v);
                else if (type == OffsetTime.class) result=OffsetTime.parse(v);
                else if (type == OffsetDateTime.class) result=OffsetDateTime.parse(v);
                else if (type == ZonedDateTime.class) result=ZonedDateTime.parse(v);
                else if (type == Duration.class) result=Duration.parse(v);
                else if (type == Period.class) result=Period.parse(v);
                else if (type == Year.class) result=Year.parse(v);
                else if (type == YearMonth.class) result=YearMonth.parse(v);
                else if (type == MonthDay.class) result=MonthDay.parse(v);
                else if (type == Month.class) result=Month.valueOf(v);
                else if (type == DayOfWeek.class) result=DayOfWeek.valueOf(v);
                else if (type == ZoneId.class) result=ZoneId.of(v);
                else if (type == ZoneOffset.class) result=ZoneOffset.of(v);
                else throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException ex) {
            throw new DtoContractException(path,"Expected " + type.getSimpleName() + " in its JSON representation");
        }
        return (T)result;
    }
    public static JsonNode scalarNode(Object value) {
        var f=JsonNodeFactory.instance;
        if(value instanceof String v) return f.textNode(v);
        if(value instanceof Character v) return f.textNode(v.toString());
        if(value instanceof Boolean v) return f.booleanNode(v);
        if(value instanceof BigDecimal v) return f.numberNode(v);
        if(value instanceof BigInteger v) return f.numberNode(v);
        if(value instanceof Byte || value instanceof Short || value instanceof Integer) return f.numberNode(((Number)value).intValue());
        if(value instanceof Long v) return f.numberNode(v);
        if(value instanceof Float v) { if(!Float.isFinite(v)) throw new DtoContractException("$","Non-finite JSON number"); return f.numberNode(v); }
        if(value instanceof Double v) { if(!Double.isFinite(v)) throw new DtoContractException("$","Non-finite JSON number"); return f.numberNode(v); }
        if(value instanceof UUID || value instanceof Currency || value instanceof java.time.temporal.TemporalAccessor
                || value instanceof Duration || value instanceof Period || value instanceof ZoneId) return f.textNode(value.toString());
        throw new DtoContractException("$","Unsupported JSON scalar type");
    }
}
