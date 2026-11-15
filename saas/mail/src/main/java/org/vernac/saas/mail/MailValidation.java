// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
package org.vernac.saas.mail;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
/** Standard mailbox parser; no DNS, normalization or provider rules. */
public final class MailValidation {
 private MailValidation() {}
 public static boolean address(String value) {
  if(value==null || value.length()>254 || value.isEmpty() || value.chars().anyMatch(c->c<=32 || c>=127)) return false;
  try { var address=new InternetAddress(value,true); address.validate();
   return !address.isGroup() && address.getPersonal()==null && value.equals(address.getAddress());
  } catch(Exception invalid) { return false; }
 }
 public static boolean subject(String value) {
  return value!=null && !value.isBlank() && value.length()<=200
   && value.chars().noneMatch(c->c<32 || c==127) && StandardCharsets.UTF_8.newEncoder().canEncode(value);
 }
 public static boolean body(String value) {
  return value!=null && !value.isBlank() && value.length()<=30000
   && value.chars().noneMatch(c->(c<32 && c!=9 && c!=10 && c!=13)||c==127)
   && StandardCharsets.UTF_8.newEncoder().canEncode(value) && value.getBytes(StandardCharsets.UTF_8).length<=30000;
 }
}
