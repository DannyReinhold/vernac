// Copyright 2026 Danny Reinhold
// SPDX-License-Identifier: Apache-2.0
package org.vernac.runtime.jdbc;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.NullMarked;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.vernac.runtime.pagination.*;

/** Versioned authenticated/encrypted cursor boundaries. No domain-object deserialization or stored server-side cursor. */
@NullMarked
public final class JdbcCursorCodec {
    private static final int MAX_BYTES=1_048_576;
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final JdbcCursorCodec PROCESS_DEFAULT=new JdbcCursorCodec(randomKey());
    private final SecretKeySpec key;

    private JdbcCursorCodec(byte[] key) { this.key=new SecretKeySpec(key.clone(),"AES"); }
    private static byte[] randomKey() { byte[] key=new byte[32]; RANDOM.nextBytes(key); return key; }

    /** Blank selects an ephemeral process key. Supply the same Base64 32-byte key across restarts/replicas. */
    public static JdbcCursorCodec configured(String base64Key) {
        Objects.requireNonNull(base64Key);
        if(base64Key.isBlank()) return PROCESS_DEFAULT;
        try {
            byte[] bytes=Base64.getDecoder().decode(base64Key);
            if(bytes.length!=32) throw new IllegalArgumentException();
            return new JdbcCursorCodec(bytes);
        } catch(IllegalArgumentException failure) {
            throw new PaginationContractException("vernac.pagination.cursor-key must be Base64 encoding of exactly 32 random bytes");
        }
    }

    static byte[] context(String query, String model, String table, String where,
            List<JdbcCursorSort> sorting, SqlParameterSource parameters) {
        try {
            var bytes=new ByteArrayOutputStream();
            var out=new DataOutputStream(bytes);
            text(out,"vernac-jdbc-cursor-v1"); text(out,query); text(out,model); text(out,table); text(out,where);
            out.writeInt(sorting.size());
            for(var sort:sorting) {
                text(out,sort.scalar()); out.writeBoolean(sort.descending()); out.writeInt(sort.columns().size());
                for(String column:sort.columns()) text(out,column);
            }
            String[] names=Objects.requireNonNull(parameters.getParameterNames());
            Arrays.sort(names); out.writeInt(names.length);
            for(String name:names) { text(out,name); writeValue(out,Objects.requireNonNull(parameters.getValue(name))); }
            return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        } catch(IOException | GeneralSecurityException failure) {
            throw new PaginationContractException("Could not construct cursor query context");
        }
    }
    public PageCursor seal(byte[] context,List<Object> values) {
        try {
            var bytes=new ByteArrayOutputStream(); var out=new DataOutputStream(bytes);
            out.writeInt(values.size());
            for(Object value:values) writeValue(out,Objects.requireNonNull(value));
            byte[] plain=bytes.toByteArray();
            if(plain.length>MAX_BYTES) throw new PaginationContractException("Cursor boundary exceeds the 1 MiB representation limit");
            byte[] nonce=new byte[12]; RANDOM.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new GCMParameterSpec(128,nonce)); cipher.updateAAD(context);
            byte[] encrypted=cipher.doFinal(plain);
            var token=new ByteArrayOutputStream(); token.write(nonce); token.write(encrypted);
            return PageCursor.of("v1."+Base64.getUrlEncoder().withoutPadding().encodeToString(token.toByteArray()));
        } catch(IOException | GeneralSecurityException failure) {
            throw new PaginationContractException("Could not create pagination cursor");
        }
    }
    public List<Object> open(PageCursor cursor,byte[] context,int expectedValues) {
        try {
            String value=cursor.value();
            if(!value.startsWith("v1.") || value.length()>((MAX_BYTES+28)*4L+2)/3+3) throw new InvalidCursorException();
            String encoded=value.substring(3);
            byte[] token=Base64.getUrlDecoder().decode(encoded);
            if(token.length<32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(token).equals(encoded)) throw new InvalidCursorException();
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,token,0,12)); cipher.updateAAD(context);
            byte[] plain=cipher.doFinal(token,12,token.length-12);
            var in=new DataInputStream(new ByteArrayInputStream(plain));
            int count=in.readInt();
            if(count!=expectedValues) throw new InvalidCursorException();
            List<Object> values=new ArrayList<>();
            for(int i=0;i<count;i++) values.add(readValue(in));
            if(in.available()!=0) throw new InvalidCursorException();
            return List.copyOf(values);
        } catch(IOException | GeneralSecurityException | IllegalArgumentException failure) {
            throw new InvalidCursorException();
        }
    }
    private static void text(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) throw new PaginationContractException("Cursor filter or boundary exceeds the 1 MiB representation limit");
        out.writeInt(bytes.length); out.write(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int size=in.readInt();
        if(size<0 || size>MAX_BYTES || size>in.available()) throw new InvalidCursorException();
        return new String(in.readNBytes(size),StandardCharsets.UTF_8);
    }
    private static void writeValue(DataOutputStream out,Object value) throws IOException {
        String type=switch(value) {
            case String v -> "String"; case BigDecimal v -> "BigDecimal";
            case Byte v -> "Byte"; case Short v -> "Short"; case Integer v -> "Integer"; case Long v -> "Long";
            case Float v -> "Float"; case Double v -> "Double"; case Boolean v -> "Boolean";
            case UUID v -> "UUID"; case LocalDate v -> "LocalDate"; case LocalTime v -> "LocalTime";
            case LocalDateTime v -> "LocalDateTime"; case OffsetDateTime v -> "OffsetDateTime";
            default -> throw new PaginationContractException("Unsupported cursor storage component type");
        };
        text(out,type); text(out,value.toString());
    }
    private static Object readValue(DataInputStream in) throws IOException {
        String type=text(in),value=text(in);
        return switch(type) {
            case "String" -> value; case "BigDecimal" -> new BigDecimal(value);
            case "Byte" -> Byte.valueOf(value); case "Short" -> Short.valueOf(value); case "Integer" -> Integer.valueOf(value); case "Long" -> Long.valueOf(value);
            case "Float" -> Float.valueOf(value); case "Double" -> Double.valueOf(value);
            case "Boolean" -> { if(!value.equals("true") && !value.equals("false")) throw new InvalidCursorException(); yield Boolean.valueOf(value); }
            case "UUID" -> UUID.fromString(value); case "LocalDate" -> LocalDate.parse(value); case "LocalTime" -> LocalTime.parse(value);
            case "LocalDateTime" -> LocalDateTime.parse(value); case "OffsetDateTime" -> OffsetDateTime.parse(value);
            default -> throw new InvalidCursorException();
        };
    }
}
