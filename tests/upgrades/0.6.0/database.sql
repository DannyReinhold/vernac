--
-- PostgreSQL database dump
--

\restrict 4Cct6haiZdkyfue9On95Hd39AdrqybKJhhbSSyQPEyMY2mLaSRiyjH4Iy431o2S

-- Dumped from database version 17.11
-- Dumped by pg_dump version 17.11 (Debian 17.11-0+deb13u1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: example.documentworkspace; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "example.documentworkspace";


--
-- Name: identity_sessions; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA identity_sessions;


--
-- Name: org.vernac.saas.identity.persistence; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "org.vernac.saas.identity.persistence";


--
-- Name: org.vernac.saas.invoicing; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "org.vernac.saas.invoicing";


--
-- Name: org.vernac.saas.payments.local; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "org.vernac.saas.payments.local";


--
-- Name: org.vernac.saas.payments.persistence; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "org.vernac.saas.payments.persistence";


--
-- Name: org.vernac.saas.restrictions; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "org.vernac.saas.restrictions";


--
-- Name: org.vernac.saas.subscriptions; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA "org.vernac.saas.subscriptions";


--
-- Name: vernac_module_02461e43c5c7661d9593ea86c5b38038; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vernac_module_02461e43c5c7661d9593ea86c5b38038;


--
-- Name: vernac_module_223223bacb1f2ab497f2765c2eb0f1e8; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vernac_module_223223bacb1f2ab497f2765c2eb0f1e8;


--
-- Name: vernac_module_6064720f0e9bcf7784a9cd54030bbfb2; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vernac_module_6064720f0e9bcf7784a9cd54030bbfb2;


--
-- Name: vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6;


--
-- Name: vernac_module_b274b5f08da39dfeb273e3b9cf17453c; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vernac_module_b274b5f08da39dfeb273e3b9cf17453c;


--
-- Name: vernac_module_f6523acb5b7e870448bad5ceed3dddb3; Type: SCHEMA; Schema: -; Owner: -
--

CREATE SCHEMA vernac_module_f6523acb5b7e870448bad5ceed3dddb3;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: Document; Type: TABLE; Schema: example.documentworkspace; Owner: -
--

CREATE TABLE "example.documentworkspace"."Document" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@tenantId" uuid NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    text text NOT NULL,
    title text NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);

ALTER TABLE ONLY "example.documentworkspace"."Document" FORCE ROW LEVEL SECURITY;


--
-- Name: spring_session; Type: TABLE; Schema: identity_sessions; Owner: -
--

CREATE TABLE identity_sessions.spring_session (
    primary_id character(36) NOT NULL,
    session_id character(36) NOT NULL,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name character varying(100)
);


--
-- Name: spring_session_attributes; Type: TABLE; Schema: identity_sessions; Owner: -
--

CREATE TABLE identity_sessions.spring_session_attributes (
    session_primary_id character(36) NOT NULL,
    attribute_name character varying(200) NOT NULL,
    attribute_bytes bytea NOT NULL
);


--
-- Name: Account; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."Account" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    kind text NOT NULL,
    status text NOT NULL,
    tenant uuid NOT NULL,
    CONSTRAINT "@check:enum:kind" CHECK ((kind = ANY (ARRAY['ORGANIZATION'::text, 'PERSONAL'::text]))),
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CLOSED'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: AccountInvitation; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."AccountInvitation" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:expiresAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    delivery text NOT NULL,
    "expiresAt" timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    issuer uuid NOT NULL,
    recipient text NOT NULL,
    roles text NOT NULL,
    verifier text NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:expiresAt" CHECK ((("@nanoRemainder:expiresAt" >= 0) AND ("@nanoRemainder:expiresAt" <= 999)))
);


--
-- Name: AccountMembership; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."AccountMembership" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    id uuid NOT NULL,
    "memberKey" text NOT NULL COLLATE pg_catalog."C",
    owner boolean NOT NULL,
    roles text NOT NULL,
    status text NOT NULL,
    subject uuid NOT NULL,
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'REMOVED'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: Identity; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."Identity" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    status text NOT NULL,
    "securityEpoch" bigint NOT NULL,
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'CLOSED'::text, 'DISABLED'::text, 'PENDING_VERIFICATION'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityAdmissionConfiguration; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionConfiguration" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    cursor uuid,
    id uuid NOT NULL,
    "keyLabel" text NOT NULL,
    observed bigint NOT NULL,
    "policyCheck" text NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityAdmissionKey; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityAdmissionKey" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    digest text NOT NULL,
    expires bigint NOT NULL,
    id uuid NOT NULL,
    kind text NOT NULL,
    used bigint NOT NULL,
    "window" bigint NOT NULL,
    CONSTRAINT "@check:enum:kind" CHECK ((kind = ANY (ARRAY['CHALLENGE_LOCATOR'::text, 'CHALLENGE_SOURCE'::text, 'LOGIN_KEY'::text, 'LOGIN_SOURCE'::text, 'MAIL_KEY'::text, 'MAIL_SOURCE'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityCapacity; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityCapacity" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    "limit" bigint NOT NULL,
    used bigint NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityChallenge; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityChallenge" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:expiresAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    "expiresAt" timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    purpose text NOT NULL,
    subject uuid NOT NULL,
    verifier text NOT NULL,
    CONSTRAINT "@check:enum:purpose" CHECK ((purpose = ANY (ARRAY['INVITATION'::text, 'RECOVERY'::text, 'VERIFICATION'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:expiresAt" CHECK ((("@nanoRemainder:expiresAt" >= 0) AND ("@nanoRemainder:expiresAt" <= 999)))
);


--
-- Name: IdentityCredential; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityCredential" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    encoding text NOT NULL,
    id uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityEncryptionKey; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    attempts bigint NOT NULL,
    fingerprint text NOT NULL COLLATE pg_catalog."C",
    id uuid NOT NULL,
    "keyName" text NOT NULL COLLATE pg_catalog."C",
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityEncryptionRegistry; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityEncryptionRegistry" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    used bigint NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityIngress; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityIngress" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    "limit" bigint NOT NULL,
    minute bigint NOT NULL,
    used bigint NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityLoginAddress; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityLoginAddress" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    "deliveryAddress" text NOT NULL,
    id uuid NOT NULL,
    "loginKey" text NOT NULL COLLATE pg_catalog."C",
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityMailJob; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityMailJob" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:due" smallint NOT NULL,
    "@nanoRemainder:expiresAt" smallint NOT NULL,
    "@nanoRemainder:leaseUntil" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    attempts bigint NOT NULL,
    challenge uuid NOT NULL,
    due timestamp(6) with time zone NOT NULL,
    envelope text NOT NULL,
    "expiresAt" timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    "leaseToken" uuid NOT NULL,
    "leaseUntil" timestamp(6) with time zone NOT NULL,
    purpose text NOT NULL,
    state text NOT NULL,
    subject uuid NOT NULL,
    CONSTRAINT "@check:enum:purpose" CHECK ((purpose = ANY (ARRAY['INVITATION'::text, 'RECOVERY'::text, 'VERIFICATION'::text]))),
    CONSTRAINT "@check:enum:state" CHECK ((state = ANY (ARRAY['CANCELLED'::text, 'LEASED'::text, 'READY'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ((("@nanoRemainder:due" >= 0) AND ("@nanoRemainder:due" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:expiresAt" CHECK ((("@nanoRemainder:expiresAt" >= 0) AND ("@nanoRemainder:expiresAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:leaseUntil" CHECK ((("@nanoRemainder:leaseUntil" >= 0) AND ("@nanoRemainder:leaseUntil" <= 999)))
);


--
-- Name: IdentityMailSweep; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityMailSweep" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    after uuid NOT NULL,
    id uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IdentityPendingRegistration; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."IdentityPendingRegistration" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:deadline" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    deadline timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:deadline" CHECK ((("@nanoRemainder:deadline" >= 0) AND ("@nanoRemainder:deadline" <= 999)))
);


--
-- Name: PersonalAccount; Type: TABLE; Schema: org.vernac.saas.identity.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.identity.persistence"."PersonalAccount" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    id uuid NOT NULL,
    subject uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: BillingProfile; Type: TABLE; Schema: org.vernac.saas.invoicing; Owner: -
--

CREATE TABLE "org.vernac.saas.invoicing"."BillingProfile" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    "buyer.contact" text NOT NULL,
    "buyer.customer" text NOT NULL,
    "buyer.party.city" text NOT NULL,
    "buyer.party.name" text NOT NULL,
    "buyer.party.postalCode" text NOT NULL,
    "buyer.party.street" text NOT NULL,
    id uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: Invoice; Type: TABLE; Schema: org.vernac.saas.invoicing; Owner: -
--

CREATE TABLE "org.vernac.saas.invoicing"."Invoice" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:content.paymentPostedAt" smallint NOT NULL,
    "@nanoRemainder:content.period.end" smallint NOT NULL,
    "@nanoRemainder:content.period.start" smallint NOT NULL,
    "@nanoRemainder:due" smallint NOT NULL,
    "@nanoRemainder:lease.until" smallint,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    "content.amounts.gross" bigint NOT NULL,
    "content.amounts.net" bigint NOT NULL,
    "content.amounts.tax" bigint NOT NULL,
    "content.buyer.contact" text NOT NULL,
    "content.buyer.customer" text NOT NULL,
    "content.buyer.party.city" text NOT NULL,
    "content.buyer.party.name" text NOT NULL,
    "content.buyer.party.postalCode" text NOT NULL,
    "content.buyer.party.street" text NOT NULL,
    "content.description" text NOT NULL,
    "content.environment" text NOT NULL,
    "content.issued" date NOT NULL,
    "content.kind" text NOT NULL,
    "content.number" text NOT NULL,
    "content.original.issued" date,
    "content.original.number" text,
    "content.paymentPostedAt" timestamp(6) with time zone NOT NULL,
    "content.period.end" timestamp(6) with time zone NOT NULL,
    "content.period.start" timestamp(6) with time zone NOT NULL,
    "content.seller.contact" text NOT NULL,
    "content.seller.party.city" text NOT NULL,
    "content.seller.party.name" text NOT NULL,
    "content.seller.party.postalCode" text NOT NULL,
    "content.seller.party.street" text NOT NULL,
    "content.seller.taxIdentifier" text NOT NULL,
    "content.seller.treatment" text NOT NULL,
    "content.terms.benefits" text NOT NULL,
    "content.terms.currency" text NOT NULL,
    "content.terms.interval" text NOT NULL,
    "content.terms.plan" text NOT NULL,
    "content.terms.price" bigint NOT NULL,
    "content.terms.revision" text NOT NULL,
    "cumulativeRefund" bigint NOT NULL,
    due timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    "lease.token" uuid,
    "lease.until" timestamp(6) with time zone,
    number text NOT NULL COLLATE pg_catalog."C",
    payment uuid NOT NULL,
    source text NOT NULL COLLATE pg_catalog."C",
    status text NOT NULL,
    CONSTRAINT "@check:absent:content.original~8bd45770db5f00de9089f0cbcc499e0d" CHECK ((("content.original.number" IS NOT NULL) OR (("content.original.issued" IS NULL) AND ("content.original.number" IS NULL)))),
    CONSTRAINT "@check:absent:content.original~a72167f02e16cea6f886b19423239207" CHECK ((("content.original.number" IS NOT NULL) OR ("content.original.issued" IS NULL))),
    CONSTRAINT "@check:absent:lease:org.vernac.saas.invoicing.InvoiceWorkLease" CHECK ((("lease.token" IS NOT NULL) OR (("@nanoRemainder:lease.until" IS NULL) AND ("lease.token" IS NULL) AND ("lease.until" IS NULL)))),
    CONSTRAINT "@check:enum:content.environment" CHECK (("content.environment" = ANY (ARRAY['LIVE'::text, 'TEST'::text]))),
    CONSTRAINT "@check:enum:content.kind" CHECK (("content.kind" = ANY (ARRAY['CORRECTION'::text, 'INVOICE'::text]))),
    CONSTRAINT "@check:enum:content.seller.treatment" CHECK (("content.seller.treatment" = ANY (ARRAY['SMALL_BUSINESS_EXEMPT'::text, 'STANDARD_19'::text]))),
    CONSTRAINT "@check:enum:content.terms.currency" CHECK (("content.terms.currency" = 'EUR'::text)),
    CONSTRAINT "@check:enum:content.terms.interval" CHECK (("content.terms.interval" = ANY (ARRAY['MONTHLY'::text, 'YEARLY'::text]))),
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['PENDING'::text, 'READY'::text, 'REVIEW'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:content.paymentPostedAt" CHECK ((("@nanoRemainder:content.paymentPostedAt" >= 0) AND ("@nanoRemainder:content.paymentPostedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:content.period.end" CHECK ((("@nanoRemainder:content.period.end" >= 0) AND ("@nanoRemainder:content.period.end" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:content.period.start" CHECK ((("@nanoRemainder:content.period.start" >= 0) AND ("@nanoRemainder:content.period.start" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ((("@nanoRemainder:due" >= 0) AND ("@nanoRemainder:due" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ((("@nanoRemainder:lease.until" >= 0) AND ("@nanoRemainder:lease.until" <= 999))),
    CONSTRAINT "@check:shape:content.original.issued" CHECK (((("content.original.number" IS NOT NULL) AND ("content.original.issued" IS NOT NULL)) OR ((NOT ("content.original.number" IS NOT NULL)) AND ("content.original.issued" IS NULL)))),
    CONSTRAINT "@check:shape:lease.until" CHECK (((("lease.token" IS NOT NULL) AND (("lease.until" IS NOT NULL) AND ("@nanoRemainder:lease.until" IS NOT NULL))) OR ((NOT ("lease.token" IS NOT NULL)) AND (("lease.until" IS NULL) AND ("@nanoRemainder:lease.until" IS NULL)))))
);


--
-- Name: InvoiceNotice; Type: TABLE; Schema: org.vernac.saas.invoicing; Owner: -
--

CREATE TABLE "org.vernac.saas.invoicing"."InvoiceNotice" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:due" smallint NOT NULL,
    "@nanoRemainder:lease.until" smallint,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    calls integer NOT NULL,
    correlation uuid NOT NULL,
    due timestamp(6) with time zone NOT NULL,
    id uuid NOT NULL,
    invoice uuid NOT NULL,
    "lease.token" uuid,
    "lease.until" timestamp(6) with time zone,
    reason text NOT NULL,
    status text NOT NULL,
    CONSTRAINT "@check:absent:lease:org.vernac.saas.invoicing.InvoiceWorkLease" CHECK ((("lease.token" IS NOT NULL) OR (("@nanoRemainder:lease.until" IS NULL) AND ("lease.token" IS NULL) AND ("lease.until" IS NULL)))),
    CONSTRAINT "@check:enum:reason" CHECK ((reason = ANY (ARRAY['CONFIGURATION'::text, 'NONE'::text, 'OUTCOME_UNKNOWN'::text, 'PROVIDER_REJECTED'::text]))),
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['ACCEPTED'::text, 'PENDING'::text, 'REVIEW'::text, 'SIMULATED'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ((("@nanoRemainder:due" >= 0) AND ("@nanoRemainder:due" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ((("@nanoRemainder:lease.until" >= 0) AND ("@nanoRemainder:lease.until" <= 999))),
    CONSTRAINT "@check:shape:lease.until" CHECK (((("lease.token" IS NOT NULL) AND (("lease.until" IS NOT NULL) AND ("@nanoRemainder:lease.until" IS NOT NULL))) OR ((NOT ("lease.token" IS NOT NULL)) AND (("lease.until" IS NULL) AND ("@nanoRemainder:lease.until" IS NULL)))))
);


--
-- Name: InvoiceSequence; Type: TABLE; Schema: org.vernac.saas.invoicing; Owner: -
--

CREATE TABLE "org.vernac.saas.invoicing"."InvoiceSequence" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    last bigint NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: IssuedInvoiceDocument; Type: TABLE; Schema: org.vernac.saas.invoicing; Owner: -
--

CREATE TABLE "org.vernac.saas.invoicing"."IssuedInvoiceDocument" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    "document.pdf" text NOT NULL,
    "document.xml" text NOT NULL,
    id uuid NOT NULL,
    invoice uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: LocalCustomer; Type: TABLE; Schema: org.vernac.saas.payments.local; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.local"."LocalCustomer" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    mandate text NOT NULL,
    CONSTRAINT "@check:enum:mandate" CHECK ((mandate = ANY (ARRAY['ABSENT'::text, 'VALID'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: LocalPayment; Type: TABLE; Schema: org.vernac.saas.payments.local; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.local"."LocalPayment" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    amount bigint NOT NULL,
    checkout text,
    cursor integer NOT NULL,
    customer text NOT NULL,
    "customerOperation" uuid NOT NULL,
    id uuid NOT NULL,
    kind text NOT NULL,
    mandate text,
    refunded bigint NOT NULL,
    status text NOT NULL,
    "@nanoRemainder:postedAt" smallint,
    "postedAt" timestamp(6) with time zone,
    CONSTRAINT "@check:absent:postedAt:org.vernac.saas.payments.PaymentPostedAt" CHECK ((("postedAt" IS NOT NULL) OR (("@nanoRemainder:postedAt" IS NULL) AND ("postedAt" IS NULL)))),
    CONSTRAINT "@check:enum:kind" CHECK ((kind = ANY (ARRAY['CHECKOUT'::text, 'RECURRING'::text]))),
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['CANCELLED'::text, 'EXPIRED'::text, 'FAILED'::text, 'OPEN'::text, 'PAID'::text, 'PENDING'::text, 'REFUNDED'::text, 'UNKNOWN'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:postedAt" CHECK ((("@nanoRemainder:postedAt" >= 0) AND ("@nanoRemainder:postedAt" <= 999))),
    CONSTRAINT "@check:shape:postedAt" CHECK (((("postedAt" IS NOT NULL) AND (("postedAt" IS NOT NULL) AND ("@nanoRemainder:postedAt" IS NOT NULL))) OR ((NOT ("postedAt" IS NOT NULL)) AND (("postedAt" IS NULL) AND ("@nanoRemainder:postedAt" IS NULL)))))
);


--
-- Name: LocalRefund; Type: TABLE; Schema: org.vernac.saas.payments.local; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.local"."LocalRefund" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    amount bigint NOT NULL,
    id uuid NOT NULL,
    "paymentOperation" uuid NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: BillingAgreement; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."BillingAgreement" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:nextBillingAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    customer text NOT NULL,
    id uuid NOT NULL,
    mandate text NOT NULL,
    "nextBillingAt" timestamp(6) with time zone NOT NULL,
    profile text NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:nextBillingAt" CHECK ((("@nanoRemainder:nextBillingAt" >= 0) AND ("@nanoRemainder:nextBillingAt" <= 999)))
);


--
-- Name: PaymentAttempt; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."PaymentAttempt" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:due" smallint NOT NULL,
    "@nanoRemainder:financialStarted" smallint,
    "@nanoRemainder:lease.until" smallint,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    calls integer NOT NULL,
    checkout text,
    customer text,
    "customerOperation" uuid NOT NULL,
    due timestamp(6) with time zone NOT NULL,
    "financialStarted" timestamp(6) with time zone,
    id uuid NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    kind text NOT NULL,
    "lease.token" uuid,
    "lease.until" timestamp(6) with time zone,
    mandate text,
    operation uuid NOT NULL,
    "order" uuid NOT NULL,
    profile text NOT NULL,
    reason text NOT NULL,
    refunded bigint NOT NULL,
    remote text,
    stage text NOT NULL,
    state text NOT NULL,
    "@nanoRemainder:postedAt" smallint,
    "postedAt" timestamp(6) with time zone,
    CONSTRAINT "@check:absent:financialStarted~efd8e794335cc0092cbe3fb34438d606" CHECK ((("financialStarted" IS NOT NULL) OR (("@nanoRemainder:financialStarted" IS NULL) AND ("financialStarted" IS NULL)))),
    CONSTRAINT "@check:absent:lease:org.vernac~a0a3e9c31e4ff6509962488cac58dd2a" CHECK ((("lease.token" IS NOT NULL) OR (("@nanoRemainder:lease.until" IS NULL) AND ("lease.token" IS NULL) AND ("lease.until" IS NULL)))),
    CONSTRAINT "@check:absent:postedAt:org.vernac.saas.payments.PaymentPostedAt" CHECK ((("postedAt" IS NOT NULL) OR (("@nanoRemainder:postedAt" IS NULL) AND ("postedAt" IS NULL)))),
    CONSTRAINT "@check:enum:kind" CHECK ((kind = ANY (ARRAY['FIRST'::text, 'RECURRING'::text]))),
    CONSTRAINT "@check:enum:reason" CHECK ((reason = ANY (ARRAY['DUPLICATE_PAID'::text, 'INVALID_PROVIDER_FACTS'::text, 'MANDATE_UNAVAILABLE'::text, 'NONE'::text, 'OUTCOME_UNKNOWN'::text, 'PROVIDER_UNAVAILABLE'::text, 'REFUND_UNCERTAIN'::text, 'SETTLEMENT_CONFLICT'::text]))),
    CONSTRAINT "@check:enum:stage" CHECK ((stage = ANY (ARRAY['APPLY'::text, 'CUSTOMER_READY'::text, 'DONE'::text, 'NEW'::text, 'PREPARING'::text, 'REVIEW'::text, 'REVOKE'::text, 'WAITING'::text]))),
    CONSTRAINT "@check:enum:state" CHECK ((state = ANY (ARRAY['CANCELLED'::text, 'EXPIRED'::text, 'FAILED'::text, 'OPEN'::text, 'PAID'::text, 'PENDING'::text, 'REFUNDED'::text, 'UNKNOWN'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ((("@nanoRemainder:due" >= 0) AND ("@nanoRemainder:due" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:financialStarted" CHECK ((("@nanoRemainder:financialStarted" >= 0) AND ("@nanoRemainder:financialStarted" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ((("@nanoRemainder:lease.until" >= 0) AND ("@nanoRemainder:lease.until" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:postedAt" CHECK ((("@nanoRemainder:postedAt" >= 0) AND ("@nanoRemainder:postedAt" <= 999))),
    CONSTRAINT "@check:shape:financialStarted" CHECK (((("financialStarted" IS NOT NULL) AND (("financialStarted" IS NOT NULL) AND ("@nanoRemainder:financialStarted" IS NOT NULL))) OR ((NOT ("financialStarted" IS NOT NULL)) AND (("financialStarted" IS NULL) AND ("@nanoRemainder:financialStarted" IS NULL))))),
    CONSTRAINT "@check:shape:lease.until" CHECK (((("lease.token" IS NOT NULL) AND (("lease.until" IS NOT NULL) AND ("@nanoRemainder:lease.until" IS NOT NULL))) OR ((NOT ("lease.token" IS NOT NULL)) AND (("lease.until" IS NULL) AND ("@nanoRemainder:lease.until" IS NULL))))),
    CONSTRAINT "@check:shape:postedAt" CHECK (((("postedAt" IS NOT NULL) AND (("postedAt" IS NOT NULL) AND ("@nanoRemainder:postedAt" IS NOT NULL))) OR ((NOT ("postedAt" IS NOT NULL)) AND (("postedAt" IS NULL) AND ("@nanoRemainder:postedAt" IS NULL)))))
);


--
-- Name: PaymentOrder; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."PaymentOrder" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:period.end" smallint NOT NULL,
    "@nanoRemainder:period.start" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    attempts integer NOT NULL,
    cycle text NOT NULL,
    id uuid NOT NULL,
    "paidAttempt" uuid,
    "period.end" timestamp(6) with time zone NOT NULL,
    "period.start" timestamp(6) with time zone NOT NULL,
    reason text NOT NULL,
    request text NOT NULL COLLATE pg_catalog."C",
    status text NOT NULL,
    subscription uuid NOT NULL,
    "terms.benefits" text NOT NULL,
    "terms.currency" text NOT NULL,
    "terms.interval" text NOT NULL,
    "terms.plan" text NOT NULL,
    "terms.price" bigint NOT NULL,
    "terms.revision" text NOT NULL,
    CONSTRAINT "@check:enum:reason" CHECK ((reason = ANY (ARRAY['DUPLICATE_PAID'::text, 'INVALID_PROVIDER_FACTS'::text, 'MANDATE_UNAVAILABLE'::text, 'NONE'::text, 'OUTCOME_UNKNOWN'::text, 'PROVIDER_UNAVAILABLE'::text, 'REFUND_UNCERTAIN'::text, 'SETTLEMENT_CONFLICT'::text]))),
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['APPLIED'::text, 'CLOSED'::text, 'OPEN'::text, 'PAID'::text, 'REVIEW'::text]))),
    CONSTRAINT "@check:enum:terms.currency" CHECK (("terms.currency" = 'EUR'::text)),
    CONSTRAINT "@check:enum:terms.interval" CHECK (("terms.interval" = ANY (ARRAY['MONTHLY'::text, 'YEARLY'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:period.end" CHECK ((("@nanoRemainder:period.end" >= 0) AND ("@nanoRemainder:period.end" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:period.start" CHECK ((("@nanoRemainder:period.start" >= 0) AND ("@nanoRemainder:period.start" <= 999)))
);


--
-- Name: PaymentRefund; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."PaymentRefund" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:due" smallint NOT NULL,
    "@nanoRemainder:financialStarted" smallint,
    "@nanoRemainder:lease.until" smallint,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    amount bigint NOT NULL,
    attempt uuid NOT NULL,
    calls integer NOT NULL,
    due timestamp(6) with time zone NOT NULL,
    "financialStarted" timestamp(6) with time zone,
    id uuid NOT NULL,
    "lease.token" uuid,
    "lease.until" timestamp(6) with time zone,
    operation uuid NOT NULL,
    reason text NOT NULL,
    remote text,
    state text NOT NULL,
    CONSTRAINT "@check:absent:financialStarted~efd8e794335cc0092cbe3fb34438d606" CHECK ((("financialStarted" IS NOT NULL) OR (("@nanoRemainder:financialStarted" IS NULL) AND ("financialStarted" IS NULL)))),
    CONSTRAINT "@check:absent:lease:org.vernac~a0a3e9c31e4ff6509962488cac58dd2a" CHECK ((("lease.token" IS NOT NULL) OR (("@nanoRemainder:lease.until" IS NULL) AND ("lease.token" IS NULL) AND ("lease.until" IS NULL)))),
    CONSTRAINT "@check:enum:reason" CHECK ((reason = ANY (ARRAY['DUPLICATE_PAID'::text, 'INVALID_PROVIDER_FACTS'::text, 'MANDATE_UNAVAILABLE'::text, 'NONE'::text, 'OUTCOME_UNKNOWN'::text, 'PROVIDER_UNAVAILABLE'::text, 'REFUND_UNCERTAIN'::text, 'SETTLEMENT_CONFLICT'::text]))),
    CONSTRAINT "@check:enum:state" CHECK ((state = ANY (ARRAY['FAILED'::text, 'PENDING'::text, 'REFUNDED'::text, 'UNKNOWN'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:due" CHECK ((("@nanoRemainder:due" >= 0) AND ("@nanoRemainder:due" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:financialStarted" CHECK ((("@nanoRemainder:financialStarted" >= 0) AND ("@nanoRemainder:financialStarted" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:lease.until" CHECK ((("@nanoRemainder:lease.until" >= 0) AND ("@nanoRemainder:lease.until" <= 999))),
    CONSTRAINT "@check:shape:financialStarted" CHECK (((("financialStarted" IS NOT NULL) AND (("financialStarted" IS NOT NULL) AND ("@nanoRemainder:financialStarted" IS NOT NULL))) OR ((NOT ("financialStarted" IS NOT NULL)) AND (("financialStarted" IS NULL) AND ("@nanoRemainder:financialStarted" IS NULL))))),
    CONSTRAINT "@check:shape:lease.until" CHECK (((("lease.token" IS NOT NULL) AND (("lease.until" IS NOT NULL) AND ("@nanoRemainder:lease.until" IS NOT NULL))) OR ((NOT ("lease.token" IS NOT NULL)) AND (("lease.until" IS NULL) AND ("@nanoRemainder:lease.until" IS NULL)))))
);


--
-- Name: ProviderRegistration; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."ProviderRegistration" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    environment text NOT NULL,
    id uuid NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    kind text NOT NULL,
    CONSTRAINT "@check:enum:environment" CHECK ((environment = ANY (ARRAY['PRODUCTION'::text, 'SIMULATED'::text, 'TEST'::text]))),
    CONSTRAINT "@check:enum:kind" CHECK ((kind = ANY (ARRAY['LOCAL'::text, 'MOLLIE'::text, 'STRIPE'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: RegisteredPaymentResource; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    attempt uuid NOT NULL,
    id uuid NOT NULL,
    profile text NOT NULL,
    remote text NOT NULL,
    route text NOT NULL COLLATE pg_catalog."C",
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: RegisteredRefundResource; Type: TABLE; Schema: org.vernac.saas.payments.persistence; Owner: -
--

CREATE TABLE "org.vernac.saas.payments.persistence"."RegisteredRefundResource" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    refund uuid NOT NULL,
    route text NOT NULL COLLATE pg_catalog."C",
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999)))
);


--
-- Name: QuotaBudget; Type: TABLE; Schema: org.vernac.saas.restrictions; Owner: -
--

CREATE TABLE "org.vernac.saas.restrictions"."QuotaBudget" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:window.end" smallint NOT NULL,
    "@nanoRemainder:window.start" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    benefit text NOT NULL,
    consumed bigint NOT NULL,
    id uuid NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    reserved bigint NOT NULL,
    "window.end" timestamp(6) with time zone NOT NULL,
    "window.start" timestamp(6) with time zone NOT NULL,
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:window.end" CHECK ((("@nanoRemainder:window.end" >= 0) AND ("@nanoRemainder:window.end" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:window.start" CHECK ((("@nanoRemainder:window.start" >= 0) AND ("@nanoRemainder:window.start" <= 999)))
);


--
-- Name: QuotaReservation; Type: TABLE; Schema: org.vernac.saas.restrictions; Owner: -
--

CREATE TABLE "org.vernac.saas.restrictions"."QuotaReservation" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:window.end" smallint NOT NULL,
    "@nanoRemainder:window.start" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    amount bigint NOT NULL,
    benefit text NOT NULL,
    budget uuid NOT NULL,
    id uuid NOT NULL,
    key text NOT NULL COLLATE pg_catalog."C",
    status text NOT NULL,
    "window.end" timestamp(6) with time zone NOT NULL,
    "window.start" timestamp(6) with time zone NOT NULL,
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['CONFIRMED'::text, 'IN_DOUBT'::text, 'RELEASED'::text, 'RESERVED'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:window.end" CHECK ((("@nanoRemainder:window.end" >= 0) AND ("@nanoRemainder:window.end" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:window.start" CHECK ((("@nanoRemainder:window.start" >= 0) AND ("@nanoRemainder:window.start" <= 999)))
);


--
-- Name: Subscription; Type: TABLE; Schema: org.vernac.saas.subscriptions; Owner: -
--

CREATE TABLE "org.vernac.saas.subscriptions"."Subscription" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:period.end" smallint NOT NULL,
    "@nanoRemainder:period.start" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    account uuid NOT NULL,
    id uuid NOT NULL,
    "period.end" timestamp(6) with time zone NOT NULL,
    "period.start" timestamp(6) with time zone NOT NULL,
    renewal text NOT NULL,
    "renewalTerms.benefits" text NOT NULL,
    "renewalTerms.currency" text NOT NULL,
    "renewalTerms.interval" text NOT NULL,
    "renewalTerms.plan" text NOT NULL,
    "renewalTerms.price" bigint NOT NULL,
    "renewalTerms.revision" text NOT NULL,
    status text NOT NULL,
    "terms.benefits" text NOT NULL,
    "terms.currency" text NOT NULL,
    "terms.interval" text NOT NULL,
    "terms.plan" text NOT NULL,
    "terms.price" bigint NOT NULL,
    "terms.revision" text NOT NULL,
    CONSTRAINT "@check:enum:renewal" CHECK ((renewal = ANY (ARRAY['CANCELLED'::text, 'ENABLED'::text]))),
    CONSTRAINT "@check:enum:renewalTerms.currency" CHECK (("renewalTerms.currency" = 'EUR'::text)),
    CONSTRAINT "@check:enum:renewalTerms.interval" CHECK (("renewalTerms.interval" = ANY (ARRAY['MONTHLY'::text, 'YEARLY'::text]))),
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['ACTIVE'::text, 'AWAITING_PAYMENT'::text, 'ENDED'::text, 'SUSPENDED'::text]))),
    CONSTRAINT "@check:enum:terms.currency" CHECK (("terms.currency" = 'EUR'::text)),
    CONSTRAINT "@check:enum:terms.interval" CHECK (("terms.interval" = ANY (ARRAY['MONTHLY'::text, 'YEARLY'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:period.end" CHECK ((("@nanoRemainder:period.end" >= 0) AND ("@nanoRemainder:period.end" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:period.start" CHECK ((("@nanoRemainder:period.start" >= 0) AND ("@nanoRemainder:period.start" <= 999)))
);


--
-- Name: SubscriptionSettlement; Type: TABLE; Schema: org.vernac.saas.subscriptions; Owner: -
--

CREATE TABLE "org.vernac.saas.subscriptions"."SubscriptionSettlement" (
    "@createdAt" timestamp(6) with time zone NOT NULL,
    "@nanoRemainder:@createdAt" smallint NOT NULL,
    "@nanoRemainder:@updatedAt" smallint NOT NULL,
    "@nanoRemainder:period.end" smallint NOT NULL,
    "@nanoRemainder:period.start" smallint NOT NULL,
    "@updatedAt" timestamp(6) with time zone NOT NULL,
    "@version" bigint NOT NULL,
    id uuid NOT NULL,
    operation text NOT NULL COLLATE pg_catalog."C",
    "period.end" timestamp(6) with time zone NOT NULL,
    "period.start" timestamp(6) with time zone NOT NULL,
    subscription uuid NOT NULL,
    "terms.benefits" text NOT NULL,
    "terms.currency" text NOT NULL,
    "terms.interval" text NOT NULL,
    "terms.plan" text NOT NULL,
    "terms.price" bigint NOT NULL,
    "terms.revision" text NOT NULL,
    status text NOT NULL,
    CONSTRAINT "@check:enum:status" CHECK ((status = ANY (ARRAY['REVOKED'::text, 'SETTLED'::text]))),
    CONSTRAINT "@check:enum:terms.currency" CHECK (("terms.currency" = 'EUR'::text)),
    CONSTRAINT "@check:enum:terms.interval" CHECK (("terms.interval" = ANY (ARRAY['MONTHLY'::text, 'YEARLY'::text]))),
    CONSTRAINT "@check:range:@nanoRemainder:@createdAt" CHECK ((("@nanoRemainder:@createdAt" >= 0) AND ("@nanoRemainder:@createdAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:@updatedAt" CHECK ((("@nanoRemainder:@updatedAt" >= 0) AND ("@nanoRemainder:@updatedAt" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:period.end" CHECK ((("@nanoRemainder:period.end" >= 0) AND ("@nanoRemainder:period.end" <= 999))),
    CONSTRAINT "@check:range:@nanoRemainder:period.start" CHECK ((("@nanoRemainder:period.start" >= 0) AND ("@nanoRemainder:period.start" <= 999)))
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: vernac_module_02461e43c5c7661d9593ea86c5b38038; Owner: -
--

CREATE TABLE vernac_module_02461e43c5c7661d9593ea86c5b38038.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: vernac_module_223223bacb1f2ab497f2765c2eb0f1e8; Owner: -
--

CREATE TABLE vernac_module_223223bacb1f2ab497f2765c2eb0f1e8.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: vernac_module_6064720f0e9bcf7784a9cd54030bbfb2; Owner: -
--

CREATE TABLE vernac_module_6064720f0e9bcf7784a9cd54030bbfb2.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6; Owner: -
--

CREATE TABLE vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: vernac_module_b274b5f08da39dfeb273e3b9cf17453c; Owner: -
--

CREATE TABLE vernac_module_b274b5f08da39dfeb273e3b9cf17453c.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Name: flyway_schema_history; Type: TABLE; Schema: vernac_module_f6523acb5b7e870448bad5ceed3dddb3; Owner: -
--

CREATE TABLE vernac_module_f6523acb5b7e870448bad5ceed3dddb3.flyway_schema_history (
    installed_rank integer NOT NULL,
    version character varying(50),
    description character varying(200) NOT NULL,
    type character varying(20) NOT NULL,
    script character varying(1000) NOT NULL,
    checksum integer,
    installed_by character varying(100) NOT NULL,
    installed_on timestamp without time zone DEFAULT now() NOT NULL,
    execution_time integer NOT NULL,
    success boolean NOT NULL
);


--
-- Data for Name: Document; Type: TABLE DATA; Schema: example.documentworkspace; Owner: -
--

COPY "example.documentworkspace"."Document" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@tenantId", "@updatedAt", "@version", id, text, title) FROM stdin;
2026-11-16 18:20:21.592568+01	766	766	debc29ea-4db6-4c71-a411-d313ee419330	2026-11-16 18:20:21.592568+01	1	6d3f203b-362e-4ba9-82a0-448ce75b17aa	Retained document <markup> & exact text	Unicode äöü — 你好
2026-11-16 18:20:21.746526+01	666	666	debc29ea-4db6-4c71-a411-d313ee419330	2026-11-16 18:20:21.746526+01	1	cbef9ab1-2823-4e71-bedb-82d34371bc87	Paid capacity persists	Second paid document
2026-11-16 18:20:21.867205+01	909	909	debc29ea-4db6-4c71-a411-d313ee419330	2026-11-16 18:20:21.867205+01	1	16a58eb1-1f4b-421e-92d2-102c4721e88f	Capacity boundary	Third paid document
2026-11-16 18:20:21.957901+01	37	37	debc29ea-4db6-4c71-a411-d313ee419330	2026-11-16 18:20:21.957901+01	1	ce91f0dc-b698-4d73-a607-cbdadcfd6d43	Over Free capacity	Fourth paid document
2026-11-16 18:20:22.097323+01	753	753	a491350e-53e5-48f7-a872-451aac7e9857	2026-11-16 18:20:22.097323+01	1	6ba7c21d-c388-4f59-adaf-8384854d547e	Must remain isolated	Foreign private document
2026-11-16 18:20:22.708156+01	530	530	941ad750-cc22-45a4-b0e7-fe539f851210	2026-11-16 18:20:22.708156+01	1	0968d7c0-e376-436a-877d-52570b075662	Read-only membership	Shared organization document
\.


--
-- Data for Name: spring_session; Type: TABLE DATA; Schema: identity_sessions; Owner: -
--

COPY identity_sessions.spring_session (primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name) FROM stdin;
9d70a99d-5f9d-455f-98db-b7ea1cf03a16	e24955b1-cba6-4cb5-8a51-a696b600db47	1794849611774	1794849611774	1800	1794851411774	\N
8656cb52-fe32-44bc-8067-67f89b95b62c	6c11a74d-aeb3-45f2-866c-279e92d6f7aa	1794849614986	1794849623926	1800	1794851423926	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14
be464f09-46f8-4d1c-b5f8-379e80b26b07	5eb3bba7-9b13-4953-82b3-cd6690851111	1794849617014	1794849624948	1800	1794851424948	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7
334cdc7e-887d-4040-b8f3-de9ef624edc7	af8fdd2e-ae0c-4a6a-8fe7-f20288eb2e41	1794849613219	1794849625340	1800	1794851425340	0aab495f-7765-49b6-8a47-9e116d0b0d84
\.


--
-- Data for Name: spring_session_attributes; Type: TABLE DATA; Schema: identity_sessions; Owner: -
--

COPY identity_sessions.spring_session_attributes (session_primary_id, attribute_name, attribute_bytes) FROM stdin;
9d70a99d-5f9d-455f-98db-b7ea1cf03a16	org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN	\\xaced0005737200366f72672e737072696e676672616d65776f726b2e73656375726974792e7765622e637372662e44656661756c7443737266546f6b656e5aefb7c82fa2fbd50200034c000a6865616465724e616d657400124c6a6176612f6c616e672f537472696e673b4c000d706172616d657465724e616d6571007e00014c0005746f6b656e71007e0001787074000c582d435352462d544f4b454e7400055f6373726674002464356164393665622d666464662d346161382d393265642d346637633432383730383064
334cdc7e-887d-4040-b8f3-de9ef624edc7	SPRING_SECURITY_CONTEXT	\\xaced00057372003d6f72672e737072696e676672616d65776f726b2e73656375726974792e636f72652e636f6e746578742e5365637572697479436f6e74657874496d706c000000000000026c0200014c000e61757468656e7469636174696f6e7400324c6f72672f737072696e676672616d65776f726b2f73656375726974792f636f72652f41757468656e7469636174696f6e3b78707372004f6f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e557365726e616d6550617373776f726441757468656e7469636174696f6e546f6b656e000000000000026c0200024c000b63726564656e7469616c737400124c6a6176612f6c616e672f4f626a6563743b4c00097072696e636970616c71007e0004787200476f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e416273747261637441757468656e7469636174696f6e546f6b656ed3aa287e6e47640e0200035a000d61757468656e746963617465644c000b617574686f7269746965737400164c6a6176612f7574696c2f436f6c6c656374696f6e3b4c000764657461696c7371007e0004787001737200266a6176612e7574696c2e436f6c6c656374696f6e7324556e6d6f6469666961626c654c697374fc0f2531b5ec8e100200014c00046c6973747400104c6a6176612f7574696c2f4c6973743b7872002c6a6176612e7574696c2e436f6c6c656374696f6e7324556e6d6f6469666961626c65436f6c6c656374696f6e19420080cb5ef71e0200014c00016371007e00067870737200136a6176612e7574696c2e41727261794c6973747881d21d99c7619d03000149000473697a657870000000007704000000007871007e000d737200366f72672e7665726e61632e736161732e6964656e746974792e73656375726974792e4964656e7469747953657373696f6e5374616d7000000000000000010200034a000565706f636849000776657273696f6e4c000f61757468656e7469636174656441747400134c6a6176612f74696d652f496e7374616e743b78700000000000000000000000017372000d6a6176612e74696d652e536572955d84ba1b2248b20c00007870770d02000000006968d6c0000000007870737200336f72672e7665726e61632e736161732e6964656e746974792e73656375726974792e4964656e746974795072696e636970616c00000000000000010200014c00077375626a6563747400104c6a6176612f7574696c2f555549443b78707372000e6a6176612e7574696c2e55554944bc9903f7986d852f0200024a000c6c65617374536967426974734a000b6d6f73745369674269747378708a479e116d0b0d840aab495f776549b6
334cdc7e-887d-4040-b8f3-de9ef624edc7	org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN	\\xaced0005737200366f72672e737072696e676672616d65776f726b2e73656375726974792e7765622e637372662e44656661756c7443737266546f6b656e5aefb7c82fa2fbd50200034c000a6865616465724e616d657400124c6a6176612f6c616e672f537472696e673b4c000d706172616d657465724e616d6571007e00014c0005746f6b656e71007e0001787074000c582d435352462d544f4b454e7400055f6373726674002462623339316537362d643539392d343463322d623131332d653665643532626563316463
8656cb52-fe32-44bc-8067-67f89b95b62c	SPRING_SECURITY_CONTEXT	\\xaced00057372003d6f72672e737072696e676672616d65776f726b2e73656375726974792e636f72652e636f6e746578742e5365637572697479436f6e74657874496d706c000000000000026c0200014c000e61757468656e7469636174696f6e7400324c6f72672f737072696e676672616d65776f726b2f73656375726974792f636f72652f41757468656e7469636174696f6e3b78707372004f6f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e557365726e616d6550617373776f726441757468656e7469636174696f6e546f6b656e000000000000026c0200024c000b63726564656e7469616c737400124c6a6176612f6c616e672f4f626a6563743b4c00097072696e636970616c71007e0004787200476f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e416273747261637441757468656e7469636174696f6e546f6b656ed3aa287e6e47640e0200035a000d61757468656e746963617465644c000b617574686f7269746965737400164c6a6176612f7574696c2f436f6c6c656374696f6e3b4c000764657461696c7371007e0004787001737200266a6176612e7574696c2e436f6c6c656374696f6e7324556e6d6f6469666961626c654c697374fc0f2531b5ec8e100200014c00046c6973747400104c6a6176612f7574696c2f4c6973743b7872002c6a6176612e7574696c2e436f6c6c656374696f6e7324556e6d6f6469666961626c65436f6c6c656374696f6e19420080cb5ef71e0200014c00016371007e00067870737200136a6176612e7574696c2e41727261794c6973747881d21d99c7619d03000149000473697a657870000000007704000000007871007e000d737200366f72672e7665726e61632e736161732e6964656e746974792e73656375726974792e4964656e7469747953657373696f6e5374616d7000000000000000010200034a000565706f636849000776657273696f6e4c000f61757468656e7469636174656441747400134c6a6176612f74696d652f496e7374616e743b78700000000000000000000000017372000d6a6176612e74696d652e536572955d84ba1b2248b20c00007870770d02000000006968d6c0000000007870737200336f72672e7665726e61632e736161732e6964656e746974792e73656375726974792e4964656e746974795072696e636970616c00000000000000010200014c00077375626a6563747400104c6a6176612f7574696c2f555549443b78707372000e6a6176612e7574696c2e55554944bc9903f7986d852f0200024a000c6c65617374536967426974734a000b6d6f737453696742697473787087d70a3ee3975c140c0b6e54b0e94700
8656cb52-fe32-44bc-8067-67f89b95b62c	org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN	\\xaced0005737200366f72672e737072696e676672616d65776f726b2e73656375726974792e7765622e637372662e44656661756c7443737266546f6b656e5aefb7c82fa2fbd50200034c000a6865616465724e616d657400124c6a6176612f6c616e672f537472696e673b4c000d706172616d657465724e616d6571007e00014c0005746f6b656e71007e0001787074000c582d435352462d544f4b454e7400055f6373726674002462616235616535632d613436632d343330392d383739322d613739653836646462616231
8656cb52-fe32-44bc-8067-67f89b95b62c	org.vernac.saas.identity.selectedAccount	\\xaced000574002432656161613430362d393430372d343731612d623035652d363037346636336238663039
be464f09-46f8-4d1c-b5f8-379e80b26b07	SPRING_SECURITY_CONTEXT	\\xaced00057372003d6f72672e737072696e676672616d65776f726b2e73656375726974792e636f72652e636f6e746578742e5365637572697479436f6e74657874496d706c000000000000026c0200014c000e61757468656e7469636174696f6e7400324c6f72672f737072696e676672616d65776f726b2f73656375726974792f636f72652f41757468656e7469636174696f6e3b78707372004f6f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e557365726e616d6550617373776f726441757468656e7469636174696f6e546f6b656e000000000000026c0200024c000b63726564656e7469616c737400124c6a6176612f6c616e672f4f626a6563743b4c00097072696e636970616c71007e0004787200476f72672e737072696e676672616d65776f726b2e73656375726974792e61757468656e7469636174696f6e2e416273747261637441757468656e7469636174696f6e546f6b656ed3aa287e6e47640e0200035a000d61757468656e746963617465644c000b617574686f7269746965737400164c6a6176612f7574696c2f436f6c6c656374696f6e3b4c000764657461696c7371007e0004787001737200266a6176612e7574696c2e436f6c6c656374696f6e7324556e6d6f6469666961626c654c697374fc0f2531b5ec8e100200014c00046c6973747400104c6a6176612f7574696c2f4c6973743b7872002c6a6176612e7574696c2e436f6c6c656374696f6e7324556e6d6f6469666961626c65436f6c6c656374696f6e19420080cb5ef71e0200014c00016371007e00067870737200136a6176612e7574696c2e41727261794c6973747881d21d99c7619d03000149000473697a657870000000007704000000007871007e000d737200366f72672e7665726e61632e736161732e6964656e746974792e73656375726974792e4964656e7469747953657373696f6e5374616d7000000000000000010200034a000565706f636849000776657273696f6e4c000f61757468656e7469636174656441747400134c6a6176612f74696d652f496e7374616e743b78700000000000000000000000017372000d6a6176612e74696d652e536572955d84ba1b2248b20c00007870770d02000000006968d6c0000000007870737200336f72672e7665726e61632e736161732e6964656e746974792e73656375726974792e4964656e746974795072696e636970616c00000000000000010200014c00077375626a6563747400104c6a6176612f7574696c2f555549443b78707372000e6a6176612e7574696c2e55554944bc9903f7986d852f0200024a000c6c65617374536967426974734a000b6d6f7374536967426974737870aa776d67f0e396e7a4ef4f8e4ded4e0d
be464f09-46f8-4d1c-b5f8-379e80b26b07	org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN	\\xaced0005737200366f72672e737072696e676672616d65776f726b2e73656375726974792e7765622e637372662e44656661756c7443737266546f6b656e5aefb7c82fa2fbd50200034c000a6865616465724e616d657400124c6a6176612f6c616e672f537472696e673b4c000d706172616d657465724e616d6571007e00014c0005746f6b656e71007e0001787074000c582d435352462d544f4b454e7400055f6373726674002433373337346464662d396438332d343934632d616464352d343136646632363161353135
be464f09-46f8-4d1c-b5f8-379e80b26b07	org.vernac.saas.identity.selectedAccount	\\xaced000574002430353038666232622d353763382d343434312d393639372d313732333136303564393335
334cdc7e-887d-4040-b8f3-de9ef624edc7	org.vernac.saas.identity.selectedAccount	\\xaced000574002431303161613131392d316138302d343566382d383633312d383561376432633838366438
\.


--
-- Data for Name: Account; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."Account" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, kind, status, tenant) FROM stdin;
2026-11-16 18:20:13.20357+01	100	100	2026-11-16 18:20:13.20357+01	1	101aa119-1a80-45f8-8631-85a7d2c886d8	PERSONAL	ACTIVE	debc29ea-4db6-4c71-a411-d313ee419330
2026-11-16 18:20:14.974924+01	521	521	2026-11-16 18:20:14.974924+01	1	2eaaa406-9407-471a-b05e-6074f63b8f09	PERSONAL	ACTIVE	a491350e-53e5-48f7-a872-451aac7e9857
2026-11-16 18:20:17.005654+01	220	220	2026-11-16 18:20:17.005654+01	1	0508fb2b-57c8-4441-9697-17231605d935	PERSONAL	ACTIVE	cd2349c6-30b2-4e39-8638-268524aafdc2
2026-11-16 18:20:22.472741+01	220	220	2026-11-16 18:20:22.472741+01	1	a6b56c9f-3b87-4cfb-9650-4d1ea626472e	ORGANIZATION	ACTIVE	941ad750-cc22-45a4-b0e7-fe539f851210
\.


--
-- Data for Name: AccountInvitation; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."AccountInvitation" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:expiresAt", "@updatedAt", "@version", account, delivery, "expiresAt", id, issuer, recipient, roles, verifier) FROM stdin;
\.


--
-- Data for Name: AccountMembership; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."AccountMembership" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", account, id, "memberKey", owner, roles, status, subject) FROM stdin;
2026-11-16 18:20:13.20629+01	108	108	2026-11-16 18:20:13.20629+01	1	101aa119-1a80-45f8-8631-85a7d2c886d8	ad0ecee1-ac57-4d94-971d-3cea5fae0a94	101aa119-1a80-45f8-8631-85a7d2c886d8/0aab495f-7765-49b6-8a47-9e116d0b0d84	t	example.workspace_editor	ACTIVE	0aab495f-7765-49b6-8a47-9e116d0b0d84
2026-11-16 18:20:14.975439+01	686	686	2026-11-16 18:20:14.975439+01	1	2eaaa406-9407-471a-b05e-6074f63b8f09	c169bc63-6adc-496a-b42e-5c224df45e0d	2eaaa406-9407-471a-b05e-6074f63b8f09/0c0b6e54-b0e9-4700-87d7-0a3ee3975c14	t	example.workspace_editor	ACTIVE	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14
2026-11-16 18:20:17.006168+01	913	913	2026-11-16 18:20:17.006168+01	1	0508fb2b-57c8-4441-9697-17231605d935	a67e206c-4677-4198-8c9b-d7fc83364ac2	0508fb2b-57c8-4441-9697-17231605d935/a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7	t	example.workspace_editor	ACTIVE	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7
2026-11-16 18:20:22.473318+01	317	317	2026-11-16 18:20:22.473318+01	1	a6b56c9f-3b87-4cfb-9650-4d1ea626472e	638e4af5-c4dd-4f50-a990-25cb71acb38d	a6b56c9f-3b87-4cfb-9650-4d1ea626472e/0aab495f-7765-49b6-8a47-9e116d0b0d84	t	example.workspace_editor	ACTIVE	0aab495f-7765-49b6-8a47-9e116d0b0d84
2026-11-16 18:20:23.914673+01	593	593	2026-11-16 18:20:23.914673+01	1	a6b56c9f-3b87-4cfb-9650-4d1ea626472e	56c33300-63d7-4dd1-86bb-b4efa9a66895	a6b56c9f-3b87-4cfb-9650-4d1ea626472e/0c0b6e54-b0e9-4700-87d7-0a3ee3975c14	f	example.workspace_reader	ACTIVE	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14
\.


--
-- Data for Name: Identity; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."Identity" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, status, "securityEpoch") FROM stdin;
2026-01-15 13:00:00+01	0	555	2026-11-16 18:20:13.197118+01	2	0aab495f-7765-49b6-8a47-9e116d0b0d84	ACTIVE	0
2026-01-15 13:00:00+01	0	984	2026-11-16 18:20:14.971645+01	2	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14	ACTIVE	0
2026-01-15 13:00:00+01	0	927	2026-11-16 18:20:17.003016+01	2	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7	ACTIVE	0
\.


--
-- Data for Name: IdentityAdmissionConfiguration; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityAdmissionConfiguration" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", cursor, id, "keyLabel", observed, "policyCheck") FROM stdin;
2026-01-15 13:00:00+01	0	764	2026-11-16 18:20:21.832729+01	26	\N	61646d69-7373-496f-8000-000000000001	case-v1	1768478520	5b0be76e8098847c7f1d632ae3556b6daf7542c58ea02940189fee9ec619d752
\.


--
-- Data for Name: IdentityAdmissionKey; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityAdmissionKey" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", digest, expires, id, kind, used, "window") FROM stdin;
2026-01-15 13:00:00+01	0	338	2026-11-16 18:20:12.209494+01	1	09749562e6eb0d0f68d3de1d977dcc5ab96d626f004b00c7b36d52f025e3593a	1768482000	09749562-e6eb-0d0f-68d3-de1d977dcc5a	MAIL_KEY	1	491244
2026-01-15 13:00:00+01	0	362	2026-11-16 18:20:13.13626+01	1	7f2d1df3392d4e3de5a1a569228f4c56d2be97bb5e09f0092b268de5cb617532	1768479300	7f2d1df3-392d-4e3d-e5a1-a569228f4c56	CHALLENGE_LOCATOR	1	1964976
2026-01-15 13:00:00+01	0	341	2026-11-16 18:20:13.403706+01	1	1a99430628d8c98b426e378c1016cea8aacb8ac4787bd56d9a26392017ad575b	1768479300	1a994306-28d8-c98b-426e-378c1016cea8	LOGIN_KEY	1	1964976
2026-01-15 13:00:00+01	0	811	2026-11-16 18:20:14.915627+01	1	bba5fc969412337473f0360168b979fa6722428a46c0f4e541277e1d68107d97	1768479300	bba5fc96-9412-3374-73f0-360168b979fa	CHALLENGE_LOCATOR	1	1964976
2026-01-15 13:00:00+01	0	753	2026-11-16 18:20:15.15071+01	1	fb14807d3d90eca0b5900fc3a45a526f4808e20b5028f7d782f6d91f3ca75d86	1768479300	fb14807d-3d90-eca0-b590-0fc3a45a526f	LOGIN_KEY	1	1964976
2026-01-15 13:00:00+01	0	62	2026-11-16 18:20:15.599539+01	1	fd81f5d5a86e645b03ae67660aecae39c8f2fe6f257bd7a204f5de17451745eb	1768482000	fd81f5d5-a86e-645b-03ae-67660aecae39	MAIL_KEY	1	491244
2026-01-15 13:00:00+01	0	552	2026-11-16 18:20:16.963301+01	1	86a6368252b975ad77d7a474e029d667e1b20eeb3bfc512fd08296f7fd670993	1768479300	86a63682-52b9-75ad-77d7-a474e029d667	CHALLENGE_LOCATOR	1	1964976
2026-01-15 13:00:00+01	0	557	2026-11-16 18:20:17.14691+01	3	3f11718a31aca4dca001646ff2d478b8036da7bba15cccb6413b6f55d6ae74d5	1768479300	3f11718a-31ac-a4dc-a001-646ff2d478b8	LOGIN_SOURCE	3	1964976
2026-01-15 13:00:00+01	0	252	2026-11-16 18:20:17.147646+01	1	8179e653b50f3ee7928f0b1e6dd056c069f7c0b0f7852f7273e5dfbb32c8fbb0	1768479300	8179e653-b50f-3ee7-928f-0b1e6dd056c0	LOGIN_KEY	1	1964976
2026-01-15 13:00:00+01	0	573	2026-11-16 18:20:22.787133+01	4	b268dd6c82e537299990dc07c59326b6efc6c14f5dea55bdfae184b739384ce6	1768482000	b268dd6c-82e5-3729-9990-dc07c59326b6	MAIL_SOURCE	4	491244
2026-01-15 13:00:00+01	0	108	2026-11-16 18:20:22.787969+01	2	c473d000d8d70d610e8cf318ed46ef70ea92c7e733338edb6d3165646fb5a56c	1768482000	c473d000-d8d7-0d61-0e8c-f318ed46ef70	MAIL_KEY	2	491244
2026-01-15 13:00:00+01	0	363	2026-11-16 18:20:23.903281+01	4	ca38d30043034ce6645b848355af62ced56115568e8a2267c90e0081cd388735	1768479300	ca38d300-4303-4ce6-645b-848355af62ce	CHALLENGE_SOURCE	4	1964976
2026-01-15 13:02:00+01	0	185	2026-11-16 18:20:23.903788+01	1	2c55a3167e1ffc1d0eec181f81cb538eff4fdf59785abc7cba8a9ff389717e09	1768479300	2c55a316-7e1f-fc1d-0eec-181f81cb538e	CHALLENGE_LOCATOR	1	1964976
\.


--
-- Data for Name: IdentityCapacity; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityCapacity" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, "limit", used) FROM stdin;
2026-11-16 18:20:10.188192+01	537	537	2026-11-16 18:20:10.188192+01	1	8b0f5c39-b70d-4bc5-8bac-000000000005	20000	0
2026-11-16 18:20:10.188192+01	537	513	2026-11-16 18:20:17.004491+01	7	8b0f5c39-b70d-4bc5-8bac-000000000001	10000	0
2026-11-16 18:20:10.188192+01	537	739	2026-11-16 18:20:23.777164+01	9	8b0f5c39-b70d-4bc5-8bac-000000000003	10000	0
2026-11-16 18:20:10.188192+01	537	132	2026-11-16 18:20:23.777646+01	13	8b0f5c39-b70d-4bc5-8bac-000000000004	67108864	0
2026-11-16 18:20:10.188192+01	537	952	2026-11-16 18:20:23.904218+01	22	8b0f5c39-b70d-4bc5-8bac-000000000006	50000	15
2026-11-16 18:20:10.188192+01	537	144	2026-11-16 18:20:23.916926+01	9	8b0f5c39-b70d-4bc5-8bac-000000000002	20000	0
\.


--
-- Data for Name: IdentityChallenge; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityChallenge" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:expiresAt", "@updatedAt", "@version", "expiresAt", id, purpose, subject, verifier) FROM stdin;
\.


--
-- Data for Name: IdentityCredential; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityCredential" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", encoding, id) FROM stdin;
2026-11-16 18:20:13.194562+01	724	724	2026-11-16 18:20:13.194562+01	1	{vernac-argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$HYMYYTdBv5QLbtP8JTSJKg$sto4mWqQ9YgWoH2s/5UfNPzq7ff2uGeL3zGvzfmAfoQ	0aab495f-7765-49b6-8a47-9e116d0b0d84
2026-11-16 18:20:14.970677+01	799	799	2026-11-16 18:20:14.970677+01	1	{vernac-argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$n7F5ekx6DXF21RCcyvfqng$evRgr9ccMhjBZOtyi80oMJIsJNc5D1UsDHa6D0sRrzE	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14
2026-11-16 18:20:17.001945+01	16	16	2026-11-16 18:20:17.001945+01	1	{vernac-argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$iN5btx1AgILwX2TalLWXmg$tE71c/SZ8svbfdbQPhAewPRT+StT2mB6LhKjYKM4KP8	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7
\.


--
-- Data for Name: IdentityEncryptionKey; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityEncryptionKey" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", attempts, fingerprint, id, "keyName") FROM stdin;
2026-11-16 18:20:12.22427+01	614	511	2026-11-16 18:20:22.79169+01	4	4	9acd9377f576f6c246380744220e1e11a6cc968e403c537fcc8578f33c587fd0	8421e67b-b455-4241-a970-f2a6f46a2df5	case-v1
\.


--
-- Data for Name: IdentityEncryptionRegistry; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityEncryptionRegistry" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, used) FROM stdin;
2026-01-15 13:00:00+01	0	702	2026-11-16 18:20:12.223563+01	2	4b455952-6567-4973-8000-000000000001	1
\.


--
-- Data for Name: IdentityIngress; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityIngress" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, "limit", minute, used) FROM stdin;
2026-01-15 13:00:00+01	0	844	2026-11-16 18:20:25.332081+01	95	696e6772-6573-4373-8000-000000000001	300	29474643	4
\.


--
-- Data for Name: IdentityLoginAddress; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityLoginAddress" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", "deliveryAddress", id, "loginKey") FROM stdin;
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	1	baseline-owner@case.example.test	0aab495f-7765-49b6-8a47-9e116d0b0d84	baseline-owner@case.example.test
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	1	baseline-member@case.example.test	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14	baseline-member@case.example.test
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	1	baseline-pending@case.example.test	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7	baseline-pending@case.example.test
\.


--
-- Data for Name: IdentityMailJob; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityMailJob" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:due", "@nanoRemainder:expiresAt", "@nanoRemainder:leaseUntil", "@updatedAt", "@version", attempts, challenge, due, envelope, "expiresAt", id, "leaseToken", "leaseUntil", purpose, state, subject) FROM stdin;
\.


--
-- Data for Name: IdentityMailSweep; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityMailSweep" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", after, id) FROM stdin;
2026-01-15 13:00:00+01	0	278	2026-11-16 18:20:24.780364+01	15	00000000-0000-0000-0000-000000000000	6d61696c-7377-4565-8000-000000000001
2026-01-15 13:00:00+01	0	712	2026-11-16 18:20:23.856627+01	15	00000000-0000-0000-0000-000000000000	6d61696c-7377-4565-8000-000000000002
2026-01-15 13:00:00+01	0	995	2026-11-16 18:20:17.751309+01	15	00000000-0000-0000-0000-000000000000	6d61696c-7377-4565-8000-000000000003
2026-01-15 13:00:00+01	0	664	2026-11-16 18:20:23.859749+01	15	00000000-0000-0000-0000-000000000000	6d61696c-7377-4565-8000-000000000005
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	15	00000000-0000-0000-0000-000000000000	6d61696c-7377-4565-8000-000000000004
\.


--
-- Data for Name: IdentityPendingRegistration; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."IdentityPendingRegistration" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:deadline", "@updatedAt", "@version", deadline, id) FROM stdin;
\.


--
-- Data for Name: PersonalAccount; Type: TABLE DATA; Schema: org.vernac.saas.identity.persistence; Owner: -
--

COPY "org.vernac.saas.identity.persistence"."PersonalAccount" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", account, id, subject) FROM stdin;
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	1	101aa119-1a80-45f8-8631-85a7d2c886d8	0aab495f-7765-49b6-8a47-9e116d0b0d84	0aab495f-7765-49b6-8a47-9e116d0b0d84
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	1	2eaaa406-9407-471a-b05e-6074f63b8f09	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14	0c0b6e54-b0e9-4700-87d7-0a3ee3975c14
2026-01-15 13:00:00+01	0	0	2026-01-15 13:00:00+01	1	0508fb2b-57c8-4441-9697-17231605d935	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7	a4ef4f8e-4ded-4e0d-aa77-6d67f0e396e7
\.


--
-- Data for Name: BillingProfile; Type: TABLE DATA; Schema: org.vernac.saas.invoicing; Owner: -
--

COPY "org.vernac.saas.invoicing"."BillingProfile" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", "buyer.contact", "buyer.customer", "buyer.party.city", "buyer.party.name", "buyer.party.postalCode", "buyer.party.street", id) FROM stdin;
2026-01-15 13:00:00+01	0	177	2026-11-16 18:20:24.6285+01	2	baseline-owner@case.example.test	ACCOUNT-101aa119-1a80-45f8-8631-85a7d2c886d8	Bremen	Future customer profile	28199	Example 1	101aa119-1a80-45f8-8631-85a7d2c886d8
2026-01-15 13:02:00+01	0	0	2026-01-15 13:02:00+01	1	baseline-pending@case.example.test	ACCOUNT-0508fb2b-57c8-4441-9697-17231605d935	Bremen	Customer Snapshot	28199	Example 1	0508fb2b-57c8-4441-9697-17231605d935
\.


--
-- Data for Name: Invoice; Type: TABLE DATA; Schema: org.vernac.saas.invoicing; Owner: -
--

COPY "org.vernac.saas.invoicing"."Invoice" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:content.paymentPostedAt", "@nanoRemainder:content.period.end", "@nanoRemainder:content.period.start", "@nanoRemainder:due", "@nanoRemainder:lease.until", "@updatedAt", "@version", account, "content.amounts.gross", "content.amounts.net", "content.amounts.tax", "content.buyer.contact", "content.buyer.customer", "content.buyer.party.city", "content.buyer.party.name", "content.buyer.party.postalCode", "content.buyer.party.street", "content.description", "content.environment", "content.issued", "content.kind", "content.number", "content.original.issued", "content.original.number", "content.paymentPostedAt", "content.period.end", "content.period.start", "content.seller.contact", "content.seller.party.city", "content.seller.party.name", "content.seller.party.postalCode", "content.seller.party.street", "content.seller.taxIdentifier", "content.seller.treatment", "content.terms.benefits", "content.terms.currency", "content.terms.interval", "content.terms.plan", "content.terms.price", "content.terms.revision", "cumulativeRefund", due, id, "lease.token", "lease.until", number, payment, source, status) FROM stdin;
2026-11-16 18:20:17.929155+01	103	689	0	0	0	0	\N	2026-11-16 18:20:21.27237+01	3	101aa119-1a80-45f8-8631-85a7d2c886d8	1200	1008	192	baseline-owner@case.example.test	ACCOUNT-101aa119-1a80-45f8-8631-85a7d2c886d8	Bremen	Customer Snapshot	28199	Example 1	Document Workspace Abonnement	TEST	2026-01-15	INVOICE	TEST-DOC-2026-00000001	\N	\N	2026-01-15 13:01:30+01	2026-02-15 13:00:00+01	2026-01-15 13:00:00+01	seller@example.test	Bremen	Vernac Local Demonstration	28199	Beispielstrasse 1	DE123456789	STANDARD_19	document-characters=10000,documents=100,monthly-exports=100	EUR	MONTHLY	pro	1200	v1	0	2026-01-15 13:01:30+01	c7817032-5e38-4331-a3bc-2b916aa1b38a	\N	\N	TEST-DOC-2026-00000001	65d8b4e7-fe62-49c1-882b-2defdde2b771	65d8b4e7-fe62-49c1-882b-2defdde2b771/0	READY
2026-11-16 18:20:24.107605+01	33	596	0	0	0	0	\N	2026-11-16 18:20:24.510278+01	3	101aa119-1a80-45f8-8631-85a7d2c886d8	300	252	48	baseline-owner@case.example.test	ACCOUNT-101aa119-1a80-45f8-8631-85a7d2c886d8	Bremen	Customer Snapshot	28199	Example 1	Document Workspace Abonnement	TEST	2026-01-15	CORRECTION	TEST-DOC-2026-00000002	2026-01-15	TEST-DOC-2026-00000001	2026-01-15 13:01:30+01	2026-02-15 13:00:00+01	2026-01-15 13:00:00+01	seller@example.test	Bremen	Vernac Local Demonstration	28199	Beispielstrasse 1	DE123456789	STANDARD_19	document-characters=10000,documents=100,monthly-exports=100	EUR	MONTHLY	pro	1200	v1	300	2026-01-15 13:02:00+01	79337465-f018-4cdb-a6cd-1d7479a06011	\N	\N	TEST-DOC-2026-00000002	65d8b4e7-fe62-49c1-882b-2defdde2b771	65d8b4e7-fe62-49c1-882b-2defdde2b771/300	READY
\.


--
-- Data for Name: InvoiceNotice; Type: TABLE DATA; Schema: org.vernac.saas.invoicing; Owner: -
--

COPY "org.vernac.saas.invoicing"."InvoiceNotice" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:due", "@nanoRemainder:lease.until", "@updatedAt", "@version", calls, correlation, due, id, invoice, "lease.token", "lease.until", reason, status) FROM stdin;
2026-11-16 18:20:21.273772+01	255	943	0	\N	2026-11-16 18:20:21.28795+01	3	1	05cd3d58-18e1-4196-ad0a-62a0f2328ce4	2026-01-15 13:01:30+01	76b92768-80b1-4e75-aed8-ee57f15edfed	c7817032-5e38-4331-a3bc-2b916aa1b38a	\N	\N	NONE	SIMULATED
2026-11-16 18:20:24.510297+01	645	782	0	\N	2026-11-16 18:20:24.518326+01	3	1	cb0d6b7e-f7c3-4845-a1fb-7460c6712af6	2026-01-15 13:02:00+01	f946ede8-ce2c-4649-add4-70203c41a363	79337465-f018-4cdb-a6cd-1d7479a06011	\N	\N	NONE	SIMULATED
\.


--
-- Data for Name: InvoiceSequence; Type: TABLE DATA; Schema: org.vernac.saas.invoicing; Owner: -
--

COPY "org.vernac.saas.invoicing"."InvoiceSequence" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, key, last) FROM stdin;
2026-11-16 18:20:17.923641+01	297	528	2026-11-16 18:20:24.106443+01	2	8520cc2c-e233-4b67-99f4-19d403035117	TEST/DOC/2026	2
\.


--
-- Data for Name: IssuedInvoiceDocument; Type: TABLE DATA; Schema: org.vernac.saas.invoicing; Owner: -
--

COPY "org.vernac.saas.invoicing"."IssuedInvoiceDocument" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", "document.pdf", "document.xml", id, invoice) FROM stdin;
2026-11-16 18:20:21.227266+01	926	926	2026-11-16 18:20:21.227266+01	1	JVBERi0xLjQKJfbk/N8KMjEgMCBvYmoKPDwKL1R5cGUgL0NhdGFsb2cKL1ZlcnNpb24gLzEuNAovUGFnZXMgMjIgMCBSCi9NZXRhZGF0YSAyMyAwIFIKL091dHB1dEludGVudHMgWzI0IDAgUl0KL01hcmtJbmZvIDIyIDAgUgovU3RydWN0VHJlZVJvb3QgMjUgMCBSCi9OYW1lcyAyNiAwIFIKL0FGIFsyNyAwIFJdCj4+CmVuZG9iagoyOCAwIG9iago8PAovVGl0bGUgKFRFU1QtRE9DLTIwMjYtMDAwMDAwMDEpCi9DcmVhdG9yIChWZXJuYWMpCi9Qcm9kdWNlciAoVmVybmFjIFwodmlhIG11c3Rhbmdwcm9qZWN0Lm9yZyAke3Byb2plY3QudmVyc2lvbn1cKSkKL0NyZWF0aW9uRGF0ZSAoRDoyMDI2MTExNjE4MjAxOCswMScwMCcpCi9Nb2REYXRlIChEOjIwMjYxMTE2MTgyMDE4KzAxJzAwJykKPj4KZW5kb2JqCjIyIDAgb2JqCjw8Ci9UeXBlIC9QYWdlcwovS2lkcyBbMjkgMCBSXQovQ291bnQgMQovTWFya2VkIHRydWUKPj4KZW5kb2JqCjIzIDAgb2JqCjw8Ci9MZW5ndGggNDI2NgovVHlwZSAvTWV0YWRhdGEKL1N1YnR5cGUgL1hNTAo+PgpzdHJlYW0NCjw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+PHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyI+CiAgIDxyZGY6UkRGIHhtbG5zOmRjPSJodHRwOi8vcHVybC5vcmcvZGMvZWxlbWVudHMvMS4xLyIKICAgICAgICAgICAgeG1sbnM6cGRmPSJodHRwOi8vbnMuYWRvYmUuY29tL3BkZi8xLjMvIgogICAgICAgICAgICB4bWxuczpwZGZhUHJvcGVydHk9Imh0dHA6Ly93d3cuYWlpbS5vcmcvcGRmYS9ucy9wcm9wZXJ0eSMiCiAgICAgICAgICAgIHhtbG5zOnBkZmFTY2hlbWE9Imh0dHA6Ly93d3cuYWlpbS5vcmcvcGRmYS9ucy9zY2hlbWEjIgogICAgICAgICAgICB4bWxuczpwZGZhaWQ9Imh0dHA6Ly93d3cuYWlpbS5vcmcvcGRmYS9ucy9pZC8iCiAgICAgICAgICAgIHhtbG5zOnJkZj0iaHR0cDovL3d3dy53My5vcmcvMTk5OS8wMi8yMi1yZGYtc3ludGF4LW5zIyI+CiAgICAgIDxyZGY6RGVzY3JpcHRpb24gcmRmOmFib3V0PSIiPgogICAgICAgICA8cGRmOlByb2R1Y2VyPlZlcm5hYzwvcGRmOlByb2R1Y2VyPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiI+CiAgICAgICAgIDxwZGZhaWQ6Y29uZm9ybWFuY2U+QjwvcGRmYWlkOmNvbmZvcm1hbmNlPgogICAgICAgICA8cGRmYWlkOnBhcnQ+MzwvcGRmYWlkOnBhcnQ+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIj4KICAgICAgICAgPGRjOmZvcm1hdD5hcHBsaWNhdGlvbi9wZGY8L2RjOmZvcm1hdD4KICAgICAgICAgPGRjOmNyZWF0b3I+CiAgICAgICAgICAgIDxyZGY6U2VxPgogICAgICAgICAgICAgICA8cmRmOmxpPlZlcm5hYzwvcmRmOmxpPgogICAgICAgICAgICA8L3JkZjpTZXE+CiAgICAgICAgIDwvZGM6Y3JlYXRvcj4KICAgICAgICAgPGRjOmRhdGU+CiAgICAgICAgICAgIDxyZGY6U2VxPgogICAgICAgICAgICAgICA8cmRmOmxpPjIwMjYtMTEtMTZUMTg6MjA6MTgrMDE6MDA8L3JkZjpsaT4KICAgICAgICAgICAgPC9yZGY6U2VxPgogICAgICAgICA8L2RjOmRhdGU+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIgcmRmOmFib3V0PSIiPgogICAgICAgICA8eG1wOkNyZWF0b3JUb29sPm11c3Rhbmdwcm9qZWN0PC94bXA6Q3JlYXRvclRvb2w+CiAgICAgICAgIDx4bXA6Q3JlYXRlRGF0ZT4yMDI2LTExLTE2VDE4OjIwOjE4KzAxOjAwPC94bXA6Q3JlYXRlRGF0ZT4KICAgICAgPC9yZGY6RGVzY3JpcHRpb24+CiAgICAgIDxyZGY6RGVzY3JpcHRpb24geG1sbnM6Zng9InVybjpmYWN0dXIteDpwZGZhOkNyb3NzSW5kdXN0cnlEb2N1bWVudDppbnZvaWNlOjFwMCMiCiAgICAgICAgICAgICAgICAgICAgICAgcmRmOmFib3V0PSIiPgogICAgICAgICA8Zng6Q29uZm9ybWFuY2VMZXZlbD5FTiAxNjkzMTwvZng6Q29uZm9ybWFuY2VMZXZlbD4KICAgICAgICAgPGZ4OkRvY3VtZW50VHlwZT5JTlZPSUNFPC9meDpEb2N1bWVudFR5cGU+CiAgICAgICAgIDxmeDpEb2N1bWVudEZpbGVOYW1lPmZhY3R1ci14LnhtbDwvZng6RG9jdW1lbnRGaWxlTmFtZT4KICAgICAgICAgPGZ4OlZlcnNpb24+MS4wPC9meDpWZXJzaW9uPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiB4bWxuczpwZGZhRXh0ZW5zaW9uPSJodHRwOi8vd3d3LmFpaW0ub3JnL3BkZmEvbnMvZXh0ZW5zaW9uLyIgcmRmOmFib3V0PSIiPgogICAgICAgICA8cGRmYUV4dGVuc2lvbjpzY2hlbWFzPgogICAgICAgICAgICA8cmRmOkJhZz4KICAgICAgICAgICAgICAgPHJkZjpsaSByZGY6cGFyc2VUeXBlPSJSZXNvdXJjZSI+CiAgICAgICAgICAgICAgICAgIDxwZGZhU2NoZW1hOnNjaGVtYT5GYWN0dXItWCBQREZBIEV4dGVuc2lvbiBTY2hlbWE8L3BkZmFTY2hlbWE6c2NoZW1hPgogICAgICAgICAgICAgICAgICA8cGRmYVNjaGVtYTpuYW1lc3BhY2VVUkk+dXJuOmZhY3R1ci14OnBkZmE6Q3Jvc3NJbmR1c3RyeURvY3VtZW50Omludm9pY2U6MXAwIzwvcGRmYVNjaGVtYTpuYW1lc3BhY2VVUkk+CiAgICAgICAgICAgICAgICAgIDxwZGZhU2NoZW1hOnByZWZpeD5meDwvcGRmYVNjaGVtYTpwcmVmaXg+CiAgICAgICAgICAgICAgICAgIDxwZGZhU2NoZW1hOnByb3BlcnR5PgogICAgICAgICAgICAgICAgICAgICA8cmRmOlNlcT4KICAgICAgICAgICAgICAgICAgICAgICAgPHJkZjpsaSByZGY6cGFyc2VUeXBlPSJSZXNvdXJjZSI+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6bmFtZT5Eb2N1bWVudEZpbGVOYW1lPC9wZGZhUHJvcGVydHk6bmFtZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+VGV4dDwvcGRmYVByb3BlcnR5OnZhbHVlVHlwZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpjYXRlZ29yeT5leHRlcm5hbDwvcGRmYVByb3BlcnR5OmNhdGVnb3J5PgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPm5hbWUgb2YgdGhlIGVtYmVkZGVkIFhNTCBpbnZvaWNlIGZpbGU8L3BkZmFQcm9wZXJ0eTpkZXNjcmlwdGlvbj4KICAgICAgICAgICAgICAgICAgICAgICAgPC9yZGY6bGk+CiAgICAgICAgICAgICAgICAgICAgICAgIDxyZGY6bGkgcmRmOnBhcnNlVHlwZT0iUmVzb3VyY2UiPgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5Om5hbWU+RG9jdW1lbnRUeXBlPC9wZGZhUHJvcGVydHk6bmFtZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+VGV4dDwvcGRmYVByb3BlcnR5OnZhbHVlVHlwZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpjYXRlZ29yeT5leHRlcm5hbDwvcGRmYVByb3BlcnR5OmNhdGVnb3J5PgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPklOVk9JQ0U8L3BkZmFQcm9wZXJ0eTpkZXNjcmlwdGlvbj4KICAgICAgICAgICAgICAgICAgICAgICAgPC9yZGY6bGk+CiAgICAgICAgICAgICAgICAgICAgICAgIDxyZGY6bGkgcmRmOnBhcnNlVHlwZT0iUmVzb3VyY2UiPgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5Om5hbWU+VmVyc2lvbjwvcGRmYVByb3BlcnR5Om5hbWU+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6dmFsdWVUeXBlPlRleHQ8L3BkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6Y2F0ZWdvcnk+ZXh0ZXJuYWw8L3BkZmFQcm9wZXJ0eTpjYXRlZ29yeT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpkZXNjcmlwdGlvbj5UaGUgYWN0dWFsIHZlcnNpb24gb2YgdGhlIFpVR0ZlUkQgWE1MIHNjaGVtYTwvcGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPgogICAgICAgICAgICAgICAgICAgICAgICA8L3JkZjpsaT4KICAgICAgICAgICAgICAgICAgICAgICAgPHJkZjpsaSByZGY6cGFyc2VUeXBlPSJSZXNvdXJjZSI+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6bmFtZT5Db25mb3JtYW5jZUxldmVsPC9wZGZhUHJvcGVydHk6bmFtZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+VGV4dDwvcGRmYVByb3BlcnR5OnZhbHVlVHlwZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpjYXRlZ29yeT5leHRlcm5hbDwvcGRmYVByb3BlcnR5OmNhdGVnb3J5PgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPlRoZSBzZWxlY3RlZCBaVUdGZVJEIHByb2ZpbGUgY29tcGxldGVuZXNzPC9wZGZhUHJvcGVydHk6ZGVzY3JpcHRpb24+CiAgICAgICAgICAgICAgICAgICAgICAgIDwvcmRmOmxpPgogICAgICAgICAgICAgICAgICAgICA8L3JkZjpTZXE+CiAgICAgICAgICAgICAgICAgIDwvcGRmYVNjaGVtYTpwcm9wZXJ0eT4KICAgICAgICAgICAgICAgPC9yZGY6bGk+CiAgICAgICAgICAgIDwvcmRmOkJhZz4KICAgICAgICAgPC9wZGZhRXh0ZW5zaW9uOnNjaGVtYXM+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICA8L3JkZjpSREY+CjwveDp4bXBtZXRhPgo8P3hwYWNrZXQgZW5kPSJ3Ij8+Cg0KZW5kc3RyZWFtCmVuZG9iagoyNCAwIG9iago8PAovVHlwZSAvT3V0cHV0SW50ZW50Ci9TIC9HVFNfUERGQTEKL0Rlc3RPdXRwdXRQcm9maWxlIDMwIDAgUgovSW5mbyAoc1JHQiBJRUM2MTk2Ni0yLjEpCi9PdXRwdXRDb25kaXRpb25JZGVudGlmaWVyIChzUkdCIElFQzYxOTY2LTIuMSkKL1JlZ2lzdHJ5TmFtZSAoaHR0cDovL3d3dy5jb2xvci5vcmcpCj4+CmVuZG9iagoyNSAwIG9iago8PAovVHlwZSAvU3RydWN0VHJlZVJvb3QKPj4KZW5kb2JqCjI2IDAgb2JqCjw8Ci9FbWJlZGRlZEZpbGVzIDMxIDAgUgo+PgplbmRvYmoKMjcgMCBvYmoKPDwKL1R5cGUgL0ZpbGVzcGVjCi9GIChmYWN0dXIteC54bWwpCi9BRlJlbGF0aW9uc2hpcCAvQWx0ZXJuYXRpdmUKL1VGIChmYWN0dXIteC54bWwpCi9EZXNjIChJbnZvaWNlIG1ldGFkYXRhIGNvbmZvcm1pbmcgdG8gWlVHRmVSRCBzdGFuZGFyZCBcKGh0dHBzOi8vd3d3LmZlcmQtbmV0LmRlL2VuL3N0YW5kYXJkcy96dWdmZXJkL2ZhY3R1ci14XCkpCi9FRiAzMiAwIFIKPj4KZW5kb2JqCjI5IDAgb2JqCjw8Ci9UeXBlIC9QYWdlCi9NZWRpYUJveCBbMC4wIDAuMCA1OTUuMjc1NjMgODQxLjg4OThdCi9QYXJl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csz7TFWjw9y3Ln26sF3scYHrFmO2TT7mPq7sBZY7KPfyvAK2F+V82aeXaz7jwvZMhVMeLz6HumBfxT/gD73TkikNCmVuZHN0cmVhbQplbmRvYmoKMzYgMCBvYmoKPDwKL0xlbmd0aCA5MgovRmlsdGVyIC9GbGF0ZURlY29kZQo+PgpzdHJlYW0NCnicC+RyCuHSdzNUsFQISeMyNVEwslAISeHSYDBniNFgMANSAgzqDEYMakBalEEYiCWBLGEUKMLAzBAPxGYMHgw+DOFAkhksFskQxBAIYmsqhGRxuYZwAQDpeAxRDQplbmRzdHJlYW0KZW5kb2JqCjM3IDAgb2JqCjw8Ci9MZW5ndGggNTYyMAovVHlwZSAvRW1iZWRkZWRGaWxlCi9TdWJ0eXBlIC90ZXh0IzJGeG1sCi9QYXJhbXMgMzkgMCBSCj4+CnN0cmVhbQ0KPD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz4KCjxyc206Q3Jvc3NJbmR1c3RyeUludm9pY2UgeG1sbnM6cnNtPSJ1cm46dW46dW5lY2U6dW5jZWZhY3Q6ZGF0YTpzdGFuZGFyZDpDcm9zc0luZHVzdHJ5SW52b2ljZToxMDAiIHhtbG5zOnhzaT0iaHR0cDovL3d3dy53My5vcmcvMjAwMS9YTUxTY2hlbWEtaW5zdGFuY2UiIHhtbG5zOnJhbT0idXJuOnVuOnVuZWNlOnVuY2VmYWN0OmRhdGE6c3RhbmRhcmQ6UmV1c2FibGVBZ2dyZWdhdGVCdXNpbmVzc0luZm9ybWF0aW9uRW50aXR5OjEwMCIgeG1sbnM6dWR0PSJ1cm46dW46dW5lY2U6dW5jZWZhY3Q6ZGF0YTpzdGFuZGFyZDpVbnF1YWxpZmllZERhdGFUeXBlOjEwMCIgeG1sbnM6cWR0PSJ1cm46dW46dW5lY2U6dW5jZWZhY3Q6ZGF0YTpzdGFuZGFyZDpRdWFsaWZpZWREYXRhVHlwZToxMDAiPgogIDwhLS0gZ2VuZXJhdGVkIGJ5OiBtdXN0YW5ncHJvamVjdC5vcmcgLS0+CiAgPHJzbTpFeGNoYW5nZWREb2N1bWVudENvbnRleHQ+CiAgICA8cmFtOkd1aWRlbGluZVNwZWNpZmllZERvY3VtZW50Q29udGV4dFBhcmFtZXRlcj4KICAgICAgPHJhbTpJRD51cm46Y2VuLmV1OmVuMTY5MzE6MjAxNzwvcmFtOklEPgogICAgPC9yYW06R3VpZGVsaW5lU3BlY2lmaWVkRG9jdW1lbnRDb250ZXh0UGFyYW1ldGVyPgogIDwvcnNtOkV4Y2hhbmdlZERvY3VtZW50Q29udGV4dD4KICA8cnNtOkV4Y2hhbmdlZERvY3VtZW50PgogICAgPHJhbTpJRD5URVNULURPQy0yMDI2LTAwMDAwMDAxPC9yYW06SUQ+CiAgICA8cmFtOlR5cGVDb2RlPjM4MDwvcmFtOlR5cGVDb2RlPgogICAgPHJhbTpJc3N1ZURhdGVUaW1lPgogICAgICA8dWR0OkRhdGVUaW1lU3RyaW5nIGZvcm1hdD0iMTAyIj4yMDI2MDExNTwvdWR0OkRhdGVUaW1lU3RyaW5nPgogICAgPC9yYW06SXNzdWVEYXRlVGltZT4KICAgIDxyYW06SW5jbHVkZWROb3RlPgogICAgICA8cmFtOkNvbnRlbnQ+VXJzcHLDvG5nbGljaGUgWmFobHVuZyBiZWltIFphaGx1bmdzYW5iaWV0ZXIgdmVyYnVjaHQgYW0gMTUuMDEuMjAyNiAxMzowMSAoRXVyb3BlL0JlcmxpbikuPC9yYW06Q29udGVudD4KICAgIDwvcmFtOkluY2x1ZGVkTm90ZT4KICA8L3JzbTpFeGNoYW5nZWREb2N1bWVudD4KICA8cnNtOlN1cHBseUNoYWluVHJhZGVUcmFuc2FjdGlvbj4KICAgIDxyYW06SW5jbHVkZWRTdXBwbHlDaGFpblRyYWRlTGluZUl0ZW0+CiAgICAgIDxyYW06QXNzb2NpYXRlZERvY3VtZW50TGluZURvY3VtZW50PgogICAgICAgIDxyYW06TGluZUlEPjE8L3JhbTpMaW5lSUQ+CiAgICAgIDwvcmFtOkFzc29jaWF0ZWREb2N1bWVudExpbmVEb2N1bWVudD4KICAgICAgPHJhbTpTcGVjaWZpZWRUcmFkZVByb2R1Y3Q+CiAgICAgICAgPHJhbTpOYW1lPkRvY3VtZW50IFdvcmtzcGFjZSBBYm9ubmVtZW50PC9yYW06TmFtZT4KICAgICAgICA8cmFtOkRlc2NyaXB0aW9uPlNhYVMtQWJvbm5lbWVudCBwcm88L3JhbTpEZXNjcmlwdGlvbj4KICAgICAgPC9yYW06U3BlY2lmaWVkVHJhZGVQcm9kdWN0PgogICAgICA8cmFtOlNwZWNpZmllZExpbmVUcmFkZUFncmVlbWVudD4KICAgICAgICA8cmFtOk5ldFByaWNlUHJvZHVjdFRyYWRlUHJpY2U+CiAgICAgICAgICA8cmFtOkNoYXJnZUFtb3VudD4xMC4wODAwPC9yYW06Q2hhcmdlQW1vdW50PgogICAgICAgICAgPHJhbTpCYXNpc1F1YW50aXR5IHVuaXRDb2RlPSJDNjIiPjEuMDAwMDwvcmFtOkJhc2lzUXVhbnRpdHk+CiAgICAgICAgPC9yYW06TmV0UHJpY2VQcm9kdWN0VHJhZGVQcmljZT4KICAgICAgPC9yYW06U3BlY2lmaWVkTGluZVRyYWRlQWdyZWVtZW50PgogICAgICA8cmFtOlNwZWNpZmllZExpbmVUcmFkZURlbGl2ZXJ5PgogICAgICAgIDxyYW06QmlsbGVkUXVhbnRpdHkgdW5pdENvZGU9IkM2MiI+MS4wMDAwPC9yYW06QmlsbGVkUXVhbnRpdHk+CiAgICAgIDwvcmFtOlNwZWNpZmllZExpbmVUcmFkZURlbGl2ZXJ5PgogICAgICA8cmFtOlNwZWNpZmllZExpbmVUcmFkZVNldHRsZW1lbnQ+CiAgICAgICAgPHJhbTpBcHBsaWNhYmxlVHJhZGVUYXg+CiAgICAgICAgICA8cmFtOlR5cGVDb2RlPlZBVDwvcmFtOlR5cGVDb2RlPgogICAgICAgICAgPHJhbTpDYXRlZ29yeUNvZGU+UzwvcmFtOkNhdGVnb3J5Q29kZT4KICAgICAgICAgIDxyYW06UmF0ZUFwcGxpY2FibGVQZXJjZW50PjE5LjAwPC9yYW06UmF0ZUFwcGxpY2FibGVQZXJjZW50PgogICAgICAgIDwvcmFtOkFwcGxpY2FibGVUcmFkZVRheD4KICAgICAgICA8cmFtOlNwZWNpZmllZFRyYWRlU2V0dGxlbWVudExpbmVNb25ldGFyeVN1bW1hdGlvbj4KICAgICAgICAgIDxyYW06TGluZVRvdGFsQW1vdW50PjEwLjA4PC9yYW06TGluZVRvdGFsQW1vdW50PgogICAgICAgIDwvcmFtOlNwZWNpZmllZFRyYWRlU2V0dGxlbWVudExpbmVNb25ldGFyeVN1bW1hdGlvbj4KICAgICAgPC9yYW06U3BlY2lmaWVkTGluZVRyYWRlU2V0dGxlbWVudD4KICAgIDwvcmFtOkluY2x1ZGVkU3VwcGx5Q2hhaW5UcmFkZUxpbmVJdGVtPgogICAgPHJhbTpBcHBsaWNhYmxlSGVhZGVyVHJhZGVBZ3JlZW1lbnQ+CiAgICAgIDxyYW06U2VsbGVyVHJhZGVQYXJ0eT4KICAgICAgICA8cmFtOk5hbWU+VmVybmFjIExvY2FsIERlbW9uc3RyYXRpb248L3JhbTpOYW1lPgogICAgICAgIDxyYW06UG9zdGFsVHJhZGVBZGRyZXNzPgogICAgICAgICAgPHJhbTpQb3N0Y29kZUNvZGU+MjgxOTk8L3JhbTpQb3N0Y29kZUNvZGU+CiAgICAgICAgICA8cmFtOkxpbmVPbmU+QmVpc3BpZWxzdHJhc3NlIDE8L3JhbTpMaW5lT25lPgogICAgICAgICAgPHJhbTpDaXR5TmFtZT5CcmVtZW48L3JhbTpDaXR5TmFtZT4KICAgICAgICAgIDxyYW06Q291bnRyeUlEPkRFPC9yYW06Q291bnRyeUlEPgogICAgICAgIDwvcmFtOlBvc3RhbFRyYWRlQWRkcmVzcz4KICAgICAgICA8cmFtOlVSSVVuaXZlcnNhbENvbW11bmljYXRpb24+CiAgICAgICAgICA8cmFtOlVSSUlEIHNjaGVtZUlEPSJFTSI+c2VsbGVyQGV4YW1wbGUudGVzdDwvcmFtOlVSSUlEPgogICAgICAgIDwvcmFtOlVSSVVuaXZlcnNhbENvbW11bmljYXRpb24+CiAgICAgICAgPHJhbTpTcGVjaWZpZWRUYXhSZWdpc3RyYXRpb24+CiAgICAgICAgICA8cmFtOklEIHNjaGVtZUlEPSJWQSI+REUxMjM0NTY3ODk8L3JhbTpJRD4KICAgICAgICA8L3JhbTpTcGVjaWZpZWRUYXhSZWdpc3RyYXRpb24+CiAgICAgIDwvcmFtOlNlbGxlclRyYWRlUGFydHk+CiAgICAgIDxyYW06QnV5ZXJUcmFkZVBhcnR5PgogICAgICAgIDxyYW06SUQ+QUNDT1VOVC0xMDFhYTExOS0xYTgwLTQ1ZjgtODYzMS04NWE3ZDJjODg2ZDg8L3JhbTpJRD4KICAgICAgICA8cmFtOk5hbWU+Q3VzdG9tZXIgU25hcHNob3Q8L3JhbTpOYW1lPgogICAgICAgIDxyYW06UG9zdGFsVHJhZGVBZGRyZXNzPgogICAgICAgICAgPHJhbTpQb3N0Y29kZUNvZGU+MjgxOTk8L3JhbTpQb3N0Y29kZUNvZGU+CiAgICAgICAgICA8cmFtOkxpbmVPbmU+RXhhbXBsZSAxPC9yYW06TGluZU9uZT4KICAgICAgICAgIDxyYW06Q2l0eU5hbWU+QnJlbWVuPC9yYW06Q2l0eU5hbWU+CiAgICAgICAgICA8cmFtOkNvdW50cnlJRD5ERTwvcmFtOkNvdW50cnlJRD4KICAgICAgICA8L3JhbTpQb3N0YWxUcmFkZUFkZHJlc3M+CiAgICAgICAgPHJhbTpVUklVbml2ZXJzYWxDb21tdW5pY2F0aW9uPgogICAgICAgICAgPHJhbTpVUklJRCBzY2hlbWVJRD0iRU0iPmJhc2VsaW5lLW93bmVyQGNhc2UuZXhhbXBsZS50ZXN0PC9yYW06VVJJSUQ+CiAgICAgICAgPC9yYW06VVJJVW5pdmVyc2FsQ29tbXVuaWNhdGlvbj4KICAgICAgPC9yYW06QnV5ZXJUcmFkZVBhcnR5PgogICAgPC9yYW06QXBwbGljYWJsZUhlYWRlclRyYWRlQWdyZWVtZW50PgogICAgPHJhbTpBcHBsaWNhYmxlSGVhZGVyVHJhZGVEZWxpdmVyeS8+CiAgICA8cmFtOkFwcGxpY2FibGVIZWFkZXJUcmFkZVNldHRsZW1lbnQ+CiAgICAgIDxyYW06SW52b2ljZUN1cnJlbmN5Q29kZT5FVVI8L3JhbTpJbnZvaWNlQ3VycmVuY3lDb2RlPgogICAgICA8cmFtOkFwcGxpY2FibGVUcmFkZVRheD4KICAgICAgICA8cmFtOkNhbGN1bGF0ZWRBbW91bnQ+MS45MjwvcmFtOkNhbGN1bGF0ZWRBbW91bnQ+CiAgICAgICAgPHJhbTpUeXBlQ29kZT5WQVQ8L3JhbTpUeXBlQ29kZT4KICAgICAgICA8cmFtOkJhc2lzQW1vdW50PjEwLjA4PC9yYW06QmFzaXNBbW91bnQ+CiAgICAgICAgPHJhbTpDYXRlZ29yeUNvZGU+UzwvcmFtOkNhdGVnb3J5Q29kZT4KICAgICAgICA8cmFtOlJhdGVBcHBsaWNhYmxlUGVyY2VudD4xOS4wMDwvcmFtOlJhdGVBcHBsaWNhYmxlUGVyY2VudD4KICAgICAgPC9yYW06QXBwbGljYWJsZVRyYWRlVGF4PgogICAgICA8cmFtOkJpbGxpbmdTcGVjaWZpZWRQZXJpb2Q+CiAgICAgICAgPHJhbTpTdGFydERhdGVUaW1lPgogICAgICAgICAgPHVkdDpEYXRlVGltZVN0cmluZyBmb3JtYXQ9IjEwMiI+MjAyNjAxMTU8L3VkdDpEYXRlVGltZVN0cmluZz4KICAgICAgICA8L3JhbTpTdGFydERhdGVUaW1lPgogICAgICAgIDxyYW06RW5kRGF0ZVRpbWU+CiAgICAgICAgICA8dWR0OkRhdGVUaW1lU3RyaW5nIGZvcm1hdD0iMTAyIj4yMDI2MDIxNTwvdWR0OkRhdGVUaW1lU3RyaW5nPgogICAgICAgIDwvcmFtOkVuZERhdGVUaW1lPgogICAgICA8L3JhbTpCaWxsaW5nU3BlY2lmaWVkUGVyaW9kPgogICAgICA8cmFtOlNwZWNpZmllZFRyYWRlUGF5bWVudFRlcm1zPgogICAgICAgIDxyYW06RGVzY3JpcHRpb24+QmVyZWl0cyBiZXphaGx0LjwvcmFtOkRlc2NyaXB0aW9uPgogICAgICAgIDxyYW06RHVlRGF0ZURhdGVUaW1lPgogICAgICAgICAgPHVkdDpEYXRlVGltZVN0cmluZyBmb3JtYXQ9IjEwMiI+MjAyNjAxMTU8L3VkdDpEYXRlVGltZVN0cmluZz4KICAgICAgICA8L3JhbTpEdWVEYXRlRGF0ZVRpbWU+CiAgICAgIDwvcmFtOlNwZWNpZmllZFRyYWRlUGF5bWVudFRlcm1zPgogICAgICA8cmFtOlNwZWNpZmllZFRyYWRlU2V0dGxlbWVudEhlYWRlck1vbmV0YXJ5U3VtbWF0aW9uPgogICAgICAgIDxyYW06TGluZVRvdGFsQW1vdW50PjEwLjA4PC9yYW06TGluZVRvdGFsQW1vdW50PgogICAgICAgIDxyYW06Q2hhcmdlVG90YWxBbW91bnQ+MC4wMDwvcmFtOkNoYXJnZVRvdGFsQW1vdW50PgogICAgICAgIDxyYW06QWxsb3dhbmNlVG90YWxBbW91bnQ+MC4wMDwvcmFtOkFsbG93YW5jZVRvdGFsQW1vdW50PgogICAgICAgIDxyYW06VGF4QmFzaXNUb3RhbEFtb3VudD4xMC4wODwvcmFtOlRheEJhc2lzVG90YWxBbW91bnQ+CiAgICAgICAgPHJhbTpUYXhUb3RhbEFtb3VudCBjdXJyZW5jeUlEPSJFVVIiPjEuOTI8L3JhbTpUYXhUb3RhbEFtb3VudD4KICAgICAgICA8cmFtOkdyYW5kVG90YWxBbW91bnQ+MTIuMDA8L3JhbTpHcmFuZFRvdGFsQW1vdW50PgogICAgICAgIDxyYW06VG90YWxQcmVwYWlkQW1vdW50PjEyLjAwPC9yYW06VG90YWxQcmVwYWlkQW1vdW50PgogICAgICAgIDxyYW06RHVlUGF5YWJsZUFtb3VudD4wLjAwPC9yYW06RHVlUGF5YWJsZUFtb3VudD4KICAgICAgPC9yYW06U3BlY2lmaWVkVHJhZGVTZXR0bGVtZW50SGVhZGVyTW9uZXRhcnlTdW1tYXRpb24+CiAgICA8L3JhbTpBcHBsaWNhYmxlSGVhZGVyVHJhZGVTZXR0bGVtZW50PgogIDwvcnNtOlN1cHBseUNoYWluVHJhZGVUcmFuc2FjdGlvbj4KPC9yc206Q3Jvc3NJbmR1c3RyeUludm9pY2U+Cg0KZW5kc3RyZWFtCmVuZG9iagozOCAwIG9iago8PAovRjEgNDAgMCBSCj4+CmVuZG9iagozOSAwIG9iago8PAovU2l6ZSA1NjIwCi9DcmVhdGlvbkRhdGUgKEQ6MjAyNjExMTYxODIwMTgrMDEnMDAnKQovTW9kRGF0ZSAoRDoyMDI2MTExNjE4MjAxOCswMScwMCcpCj4+CmVuZG9iago0MCAwIG9iago8PAovVHlwZSAvRm9udAovQmFzZUZvbnQgL0FBQVpQSCtMaWJlcmF0aW9uU2FucwovU3VidHlwZSAvVHlwZTAKL0VuY29kaW5nIC9JZGVudGl0eS1ICi9EZXNjZW5kYW50Rm9udHMgWzQxIDAgUl0KL1RvVW5pY29kZSA0MiAwIFIKPj4KZW5kb2JqCjQxIDAgb2JqCjw8Ci9UeXBlIC9Gb250Ci9TdWJ0eXBlIC9DSURGb250VHlwZTIKL0Jhc2VGb250IC9BQUFaUEgrTGliZXJhdGlvblNhbnMKL0NJRFN5c3RlbUluZm8gNDMgMCBSCi9Gb250RGVzY3JpcHRvciA0NCAwIFIKL1cgWzAgWzc1MF0KIDMgWzI3OF0KIDggWzg4OV0KIDExIFszMzMgMzMzXQogMTUgWzI3OCAzMzMgMjc4IDI3OCA1NTYgNTU2IDU1NiA1NTYgNTU2IDU1Ngo1NTYgNTU2IDU1NiA1NTYgMjc4XQozNSBbMTAxNSA2NjcgNjY3IDcyMiA3MjIgNjY3XQogNDIgWzc3OCA3MjIgMjc4XQogNDYgWzY2NyA1NTZdCiA0OSBbNzIyIDc3OF0KIDUzIFs3MjIgNjY3IDYxMSA3MjIgNjY3IDk0NF0KNjEgWzYxMV0KIDY4IFs1NTYgNTU2IDUwMCA1NTYgNTU2IDI3OCA1NTYgNTU2IDIyMl0KIDc4IFs1MDAgMjIyIDgzMyA1NTYgNTU2IDU1Nl0KIDg1IFszMzMgNTAwIDI3OCA1NTYgNTAwXQogOTEgWzUwMF0KOTMgWzUwMF0KIDk1IFsyNjBdCiAxMDYgWzMzM10KIDE2NiBbNTU2XQogMTkwIFs1NTZdCl0KL0NJRFRvR0lETWFwIDQ1IDAgUgo+PgplbmRvYmoKNDIgMCBvYmoKPDwKL0xlbmd0aCAzNTUKL0ZpbHRlciAvRmxhdGVEZWNvZGUKPj4Kc3RyZWFtDQp4nF2S3WqDQBCF7/cp9jK9CMaf1QREMCYBL/pDbR/A6BiEuspqLnz7rnNMCl2Qw3zOmZllx8nyU67bSTofpq8KmmTT6trQ2N9NRfJKt1YL15N1W01rJFiqrhyEY93FPE7U5brpRRxL59P+HCczy01a91d6Ec67qcm0+iY331lh4+I+DD/UkZ7kTiSJrKkRttJrObyVHUmHfdu8tgntNG+tiVM442seSHqwuJin6msah7IiU+obiXhnTyLjiz2JIF3/+y/cA3zX5s/gW8NTvF3CcA8I8RTgETAD3ANeOHJPgBlDz3+kLBKgppc+UhhGgGdAVAmODH2XI98DPAMqQFRRHiDarqJShkEAHxqFLiAaKUwWopFCTXVgiVBT4ZqrRLimWjtAIjRSmHqVCHcPMeAqKexpiAhyDhgeMdIql4xf7PEy/HrLmj13o7obY9eCl5H3YdmEVtNzX4d+WFz8/QJKqb0vDQplbmRzdHJlYW0KZW5kb2JqCjQzIDAgb2JqCjw8Ci9SZWdpc3RyeSAoQWRvYmUpCi9PcmRlcmluZyAoSWRlbnRpdHkpCi9TdXBwbGVtZW50IDAKPj4KZW5kb2JqCjQ0IDAgb2JqCjw8Ci9UeXBlIC9Gb250RGVzY3JpcHRvcgovRm9udE5hbWUgL0FBQVpQSCtMaWJlcmF0aW9uU2FucwovRmxhZ3MgNAovRm9udFdlaWdodCA0MDAuMAovSXRhbGljQW5nbGUgMC4wCi9Gb250QkJveCBbLTU0My45NDUzIC0zMDMuMjIyNjYgMTMwMS43NTc4IDk3OS45ODA0N10KL0FzY2VudCA5MDUuMjczNDQKL0Rlc2NlbnQgLTIxMS45MTQwNgovQ2FwSGVpZ2h0IDY4Ny45ODgzCi9YSGVpZ2h0IDUyOC4zMjAzCi9TdGVtViAyMzkuOTQxMzkKL0ZvbnRGaWxlMiA0NiAwIFIKPj4KZW5kb2JqCjQ1IDAgb2JqCjw8Ci9MZW5ndGggMTM0Ci9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJy1ikcOwgAQA4feWyB0CDUJEHrv8P9PsVoJwSEXDoxke20tKAHeBNVDhDUjRIkRJ0GSFGkyZMmRp4BBkQ8lTMpUqFKTVqdBU7JFW9yioz9devQZMMTW7vCNy4gxEzymzJizkG3JijUbtuyk7Tlw5MRZ7ovoKrrhx913/T+PH/+fL6hwCGINCmVuZHN0cmVhbQplbmRvYmoKNDYgMCBvYmoKPDwKL0xlbmd0aCAxMDk3OQovRmlsdGVyIC9GbGF0ZURlY29kZQovTGVuZ3RoMSAxNjQzNgo+PgpzdHJlYW0NCnicrXsJXBTH0njXnHsNe7MLA+4uC3isCrKiokYmKgRDjHigrASBBK9copjDmAh4JIox4os5jBdJjInG6KKomMRIEnOqT3K/PD8jud7Ll0vjM5fC8NXM7CKYvPf9f///f6Rnuqv6qK6qrq7qXgkQQgykhtDEe9OdC73X/j3/IkJ2EUKtnFU5+7Yl5QeshNCDCeGbZpdXVRI30WODb7COZfati2aNeIj1Yfk3QtLmzJlZXmEkqxsJGV6G+CEKQFhPH8Hy41hOnnPbwrs9N4u9sfwyIaC7dd5N5YStshAy4iiWTbeV311JN9JfETKyFet7KxfMrOzYfc04LJ9DGsawx9nj5D62ljjJIvXd42GGEwe5i5DO75XS5bc8jfx/fXTap4kcJntIQw/USrJE41235wh5nTyv5jaSNf+h20NkZyS3nmwgD/zbejeTZdjPNhz/8lOG0EXkcRy5mTyLnEyCII56SwR7irzz513B5/AO+Qt5Dmv+hRzE90aU+mLqPPkLNYncTn1C15KlZBXOcSvMJWuxfhnZBsVkBkK1ZwaZSeZd0WkdqSfPkHtQo7oetrbzX0Ro34eUr8J+HiVzyXyUpLm9V+d5Mpj5BxHkD8kR2oO07yb71Sa10bZ8Hn0zdYCiOh7GwjoyG1M5fIp0rqGv/g/c/H9+uFpmDnEwxxQd6vxArkbaT6GEXkRunJSuKZ4eKiqcMnnSxIIJ14+/Lv/acXnX5OaMHTP6ail71FUjRwzPGjZ0SOag9LSBA/r36Z2akuxP8nncDqvFHCMYDXodz7EMTQHp7w1DWU6YTvFac8v9Of7yvAH9vTnuOWMH9M/x55aFveXeMH6YVH9engryl4e9Zd5wKn7Ku4HLwhLWnHVFTUmrKXXVBIt3JBmpDOH3hk+M9XubYfrEIsyvGesPecM/qPnxap5JVQsCFnw+bKFSpVDrzQnn3jmnLqcMaYRGo2GMf8xMw4D+pNFgxKwRc+E+/spG6DMK1AzVJ2d4I0V0gjIszjSnvCJcMLEoZ6zo84UG9B8XjvGPVVFkjNplmBsT5tUuvXMV0slqb2P/lroHmy3kxrKAqcJfUX5DUZgux7Z1dE5d3QNhayDc1z823Peer9w485nh/v6xOeGA0mv+pK5x8i8PCWE2xeL31v1McDr+H77vCSmPQLgUy89EyYapMWGYVORTHjEXeV1Xl+v35taV1ZU3d9bc6Pda/HWNJlNdZQ6ymxQUYRfNnS+uFsO5D4bClrI5MDwUmXrupPywfWJxUZhKyfXOKUcI/mX7fcNEn7WrTsG/QxNkCzIHOezzKWxY3SyRG7EQrplYpJW95EZxL5HSAqEwVaZgWqIYZ6GCqYliupqX+VG2+ZOL6sJMyrgKfw5yfHV5uOZG1K6bFcH4LeGYX0Sfv85m9WalhdS6XqRqXMVcb5hNRSZhq+4NUG+UJnUWtRDzi/b5QcQBUq02b5Yfu1H6yfHnlEX+7pzjxg68yOi8gKYIU4rC0ljMSOURieU0pqdhi/IyFNjcsaoww2n+yrDDP7pLugpZOXMnF6lNIs3CjjFhUnZTpFU4LUddV96curKxGglKX/6JRYdIsLOtcbBX3Bckg0lorFI5dgxqWWpOXVHFrLCnTKzAdTfLWyT6wlIIJRzyF80MKWqHHOrbJqrKEVJ1ZUpR/mR//sTpRcMihGgIpTsmJeeKbvxFotYNKmBYl6LzFlEiHcKKFgR4czHjHz0S32E+RYfJggxXoYrijh7pLQKRRGsjGeG+3pyZYyP1lHKPTllFncbkRXvjlCL2MyZP9IV82jOgP4Vob2RgbKFTmJoXRaGZQoQO9XNMngpSeOlWlN5b5J/pD/nneMNSQZEyN4U9KpcjzFB5HpHVlB6lbsxCNhEfoqMFhZnh3IDYnbnha9RyVzHvCvS4KNpbp/PnT65TOvdHOiRI+bgwUVRYGmYVVVugLGg/2l6vBZe0uqDrGiVJWcxzhiud+MdV1PknF41Ua6M9uU+8RxnLRvIhf8roAf3RtI1u9MPKiY0SrJw8vegQOjPelVOK9lJAjSkbHWpMRlzRIS8hkgqlFKgCVApepaD0NAkLOrW+eEgipEbFMipALd/UDESF6aIwIDc1UxrMog2Uqg4kEQoxjIaRorUZhOk0WI0KU59GorBMMrCSTtJLJkqgxEZQQHsR8iL6hXog+0wggNiIrSap4GaoadRLolajBmtIGoUrCy8PXTi9aJ+JYDP1jQONVh5UF/ccFDZuKzneCkVR7g3NqSsLKYuNxKJo8A/C4B+FYvKPQkI4U9jgnzk6bPSPVuDZCjxbg3MKnEcVhVjA5jUo+4IwKBpQXOTDJemNf0ess/ygSCqERqXO8vUA3NJpdN0oYiXQ2Yl5ipxAzyQD/Uia8MQjCRTH0hyt17E0g6DsE2knrDbIyrIGrcFB6Xaf1We3+qwnmJmXNl5Hn2BrL1azmZdczH+rDg4Z13mB+5BdQ4zETETSmwTJNCkzgSRxMWaT0Mc+MC7OLpg5wmUOjh10IGSJbS62BPDLxNCCTe85EKL1zcV0Cn7tJDsQCBB3drbyRQpcKgXRf4PSwcH5k1IzBw8JZsSCH4IpGbHOKAgGp/qTOKcjNpjRI899+NSWTVs3bN/2+KURG+knNl86s/WJrVu2bH2CLRlfXDxxQnHRpEvnrps+o6DghmkTYe8n//z89Fdnvu6oZGtNbZ99+u03p86caU/Z9+SWA88/8yz1ZviprfteeOY5nDeQarmI2oK+XAxJkiw8MRpoxoDsM1sMIk1Idna3CSATLbahQY6yWmwufyplrd7/8u6X9rxwePfhJsoBPjh+rFXuL38rfycP/OA4nAAP9m/C/gOX+6cZo4EwBqV/QotX9g8WivcPsVktVO9grM1KBXCAl3fveUkZwCKfkQcfex/eAxf+e/+943JQ/pyoc2iWL0Itend6HMPKsETH6gxGwj5XrCMbMaWpYogqQgoyGwfJ9GdCbWqfxTOKTj1380NXr1xySuurGFqpCVQlKpBHsiK5LJCXQlvhJFBpAEDSSuZrnSEzMn3OYrgArQ0N2O4lbLwEaaCJSzIg55AM2FiMQZVaXRk4mBl0vvT6qcg46JVSWcgXmtgPUixhEGJX2AEqkU4IAmzcIs91sG0X0fZQpKDze8bPPor66SJ9JIeNMxGOuOP05qqQnqedVSE6DhUP1a77XJGf/iRFXL4MGx3NBzNsjP/3f/3rwg9Afv/h4Jqntq97uGHreupVeav8ICyAm+AWuFn+i7wBBoFNPi8fkz+Uv4UEpHkTTsuM681AApKD0VGU0cQyDM1xOiCwMISRJY5Ogu7sYDAtqE1bnbfPymamBK0+5yaYLb8G47fDtA3MyC93fn3JvUHhxWzs14Rz60VGSd4EEmPWOROdZsJ4vLqEGJvNWBWy8UASSEJ0DBvJcqtD2bJ66E8wcxSbqS4bvvcoCCoLKwZ4/PM5ZwcffmprzYSVi6oeEZodv7720df569+rWtmLOlN9x7519967curCmvvmW3e8/c6hSU89tXPGY7kbVDldj3yPQ9r6kJukLJ4TE5xJJkKSUiwJHNe3X4rVYrUsDFnd9qXj8QXjzVawsFYrLXo87qqQh6f1VSFeEU1Qk41CsjutdEaJahtIVg/7ELUNvYfG+jKG4EQCkBlUM91nxPHOXsDE/faPjzvdLyaDeeXGxmdn3bj+6RXL7nrYtB+n9uF3j9VvCcOK1z9+9bD14v3Lq2o31S6Yv+yeeTEvvPZG+IEdvRjrXqLoVBryfagqTxsZIsVbWRtF6YAFu4MwVqYqpLNawchxgDzPRrrTggr9EfWKEmz1W32ZitI6AfkMZvDR83d2zKFWHH5TrqcGC/JjQyxwHrLlVyH7QfpA+3UP0XdxM+wd31/rUPk7CfmbiDQkkBlSps3udjkcxM5zbjtyOdbOMYm94lHF4+Nph8O1MOTgFIbO5iGWhyp+GU9pvC0pKYmoPipGWg+ja8tSXwpricbayxz1231OH63YYSZR/vW7N857D2R9v27bMw+OW5IdTqN9HcvEO3a3/grHznSSXU8739uzYcW2gUOpXzbIV0+/gPybE9GNWJJECqRAopUzGV2EGDnan2yNd8TfEXI4aL0+pipkNq01UQbWhEvVe3mpBhU9iNKscjZKtqoK2nolQa+dT1Wyqvz5brsCE3f+ox/bgUP2Tt6Vue+JHYP2Vr3+9cFH71+y8cklS9fDiTOyDDfCJLgdVsqfe3bJn8vniksvfLxh+8O1T7fuUfk/V117tWhX+kkOHcOyRK8nJoHoDfqFIQPHKLK/LHaFmxlIm4Fy+i028GX6GNPf9oZe/hpMHUb6aeasfECuk9e/DjFUIazYgNYthDyKRx7FkWTUt0JpYIDzCPH2FDR3sXqB49IHxeqT+iT1uSNkTgI7l5REWywJd4QsPD3gju42jUSWjZL781WD2+fQzIGgbayRZUIP9kWZZdcYZ8GVxcT/9s0XnVsWV6346VjrT/cvfODRz+SL1StW3Ve9wr9pzaonoO/D9bDq9b9//Ebdyw5GbFr05NtHn13U5GJiD1HC2bvvWlR9R0f7shVr75NPr1HWURnO0YZzdOEcp0gDe9lQf1F9ORudkmrymX0of7PHTMXQZjPtdIpVIadqF1w8RNT3yjl26ULXvtiluzZ7DKAyqLO0dVOGUcDY5F9/fuatwK4hzRt3Mn1eW/jKV7+d/u780U3Llj76aM3194+nTsuPyPes3iiGwQvG6bcB88npDnnbnp0nGx97Yt81S1WboO5jzHDVp+ot2WmeZwijR8XYVIx71KZiMKsiSeu+zdhxZ6PV3e311+lbTp5sf+TkSaWvJ+Wv+dtxj8sm15MlUq7I9x12jZmXJJfL7SNkkNs8jJ1QwCR+FGIYk+naj0P2MR+HXHazyWOijLTJbrIPHjky5ePQSEKlg41JHzx4wMehwURdK8imCMuQZwrTrMGAuvek/dHl0lSEUnVkqF9hH+qKDfnnCg4N0sg+hXuZg6lkfxJDOR02JpiRPNTFMf6kZCrTYiO+DJSuwukY0FRp6BD+9qoT1TfdSnOjX5+/+YXab9acmsT13rQwZWLBA9c/IX/22nn5wQ83Q98310G/hqHr5Bd2/yQ/duR3eBFsP8GMQx1PLb995ubppTfd19rHRf3rRfmDbUVFuYsXH9+/DeK3H2iQuW2hGXXnH94Hzkc+lm+7+Kr86Zb8aZXTio9A+T/ADe+Szv3ygS9m3Vf9+bK7V76/+QbkNYNO7CRcxzyxkGJpiICeF0VzrA5dGEbH0zariSoNmUyqc2wL26DABuds0GKDehuU2SDdBmk2KFGf+fNJdkZ2MKuLlRmoiLasLJvi9fhoH41Oqx54jsdsam9m7ZMdS556k8r+lBrSUayPG9REmfcnJMAmuULxsZmfEiYvlQfBeznTVA8evU7mW6RTQKvZi8yRhhvtOrsoMjE6tJw6hvZ4jfZ4e3xpyJ5sp8ab7UCPsgODXwtrt6N5spWGcApiaYixXWkbSktK5//Jrhqx/owfIwCvFe1CL1BcaywpRqEvKF/mW/nHCx1HKQLnHqx57oD846b18hG4esNjE+Wn5E1QtacB1rz8Hlsr77xvZ6LjEFxccKM8uqqj83eZWar5c7gXsItUOzdDyqItrlidXh9roeNFswsE2uWy2wnOiSE6i07SFejqdQ26Vl2bTmeiMZk4lI3dK0JJxINSpnM519NHTiLaptCloYqCuviBQLu/ldvB/E/o88imafIbrR/J7zwNt8Loz2HgNfsHfcpclD+QL8od8huQcv2BVxph3OcwEZaEXxi5WJ0DpciGnYmy0aMr2l9ymVkDBkYOJxdTGuJo1oyct3mdKo1X+pkOSmMvoS1E46iNnblTfvt4x0/wPsyCFS3K3iP/BMM3freEOvl3+dBuZOYGeT9wYL/UuBJUHiq6Iat70RQpg8V9yEDzuDsJrK40tJaFF1lYxK5iKTMLOho3KgCmNAQ00ZeGiM0rdCMsohAlmrPuilCpeKC+SHqOGdD+Fzqj/a/0Y2ztJnnkE7Jzk0rDbJRjg+qL3iiN4DmB2N1uzqn4orFOHCcW3HRsrEiLltKQaKcNpaF0XuKper4NfRGeZmq8UOYFr1dZSmjZlXDhyv2rRzwVlZ1fkyralkzcVK2aaGdDJYz/FpInHBj5weYLuJfbztedvVYupgor5ZdeOS237KDegmlw95bdQ+6+Xf5UviD/LB+bkic3yPEL7gtDfoSnrFfl6QLJxdM0o0MHhTGaBB7tQQEPbbgJNXd+Lg20j1vEr+IpMw86nterZgJ52iJAWIAGAWoEqBSgTIACARReR56oqUBmB6Kh0WWri5xG/xD5jokp7+COHKEuHqHWdFSxtR27qCkXq7W1cxhf96px/INSuRo7oVRtEgvpLHhZXPlAWMg6x0KYhQYWKlkoY6GABUlFILwlitKAlih8Dwv1Petjd12kR54FkWeGCtVCUi1gQ6oPH1GMWISP2SofB0siMer0Ci+NegNuXYKBBwqtLEsTxSgF0Vd2Xemi+IBXOaFYTmZSx4lmZMWzn3c8R+G/Bzu+Qn6Mol7r2NT+ZXQdpOFYLMagMQo/OB4ZQitqrql4hM0aiajMR6i32dpL4qZu8hbQI00jBoPAMwwrsOYYI3C0juAKNkOLGcJmaDBDjRkqzVBmhgIzILybUIM4i+CV0gSP4u4HITVTkefwjhiW3fkZddG0iwmXP9tehCTkHS2iNyG/KFKO6+gTXEcxJJ6kS/FOnZnoiJhgRPttZBg32m97TQJ4E6Dk39mTLttm4xX3R1sfhP1kh3z0k0/lN7ZjqHrtJzDy2dfl38+dl38D4w8XgKXeOi037Q3D+M/Q7b3vefnFzzAo6S//DVfGr/I7MKDLzs2J7EHTpDQwmex6O4ozRk8EQc/QLreJsuPyQJsd3XCIrcYNlW7wurWVnZH95457JOJQmKWoPlKveKLoh0Y2HOZBjKzHHaEe+xHog09C/W/PbpZHwInHnqHGdRxkaz96ZfPHCR1P0t8vru34bY2iCyXoW/6GfBxAHpI8JpKY4I/lWDY2ATVkoMlij80bZwqZ5pposwn8zZ3npCwE5fqn+mf5acEPJsbkp+PivKWheYkQSoT8RFSkRNCziXEMjXazjINJHIzlUDfsl+OooLqZlkSc7VJ1bZcE/uB0Mz4vPbQXqPHUQKr3QDpzcLIvI1YzZ+hW9QJXL5b5TT4pf9fRMemQt3XfoXeyF2wpe/aFikxwAnVODr7s2f3Ejr05S1+7uvbO2dcFlJgVZqVU31W9OGfqsNTYlGuL75mw/+jDjb7KmZXzri4cETB7AsOnLEC+DMRl0aT4O5AufYrLDyNBvQ4tB6OIC2yL9ZCvhxF6SNbDJT0c08NLetioh9V6qNYDVaqHCXpI14NZD7PP6OGkHsJ6WKsHDYHQc3rQ4Hv0sFUPlSpK0oNHD2dVFALnqcBsFUj0MBQRrXqo10ONiivQQ5qKaFV7qVeH1uDYkVcPFj106qFND0f00KBWKFNR2SoWieBnlJRcaa6usFd/glERpZdxlyM4V9blmN3npFpfkROY+5mvL4nM15s2abZ4C67bvshXOxkn9RcsPGNBDY5B04ZbHbHj9t/ihLATGpxQ44RKJ5Q5ocAJiltw2XioOtRtObNJyarJUJ1nNcMx1KkXZPmhI0cPvfLBK+vkXx1Lzm2na9vXvvr2ybfoivZ1z/+2TKNHj2t1PNKjI6OkVF6Hhp2mKFZHG/ReQ4GBSjeUGeoNLYZzBjbNADxFs6CZYKRnvtWmKaxmvdBrBddQCNIxb3a8+g7cP2UKLH+HrW33/v473RYdi78fx4qDdqnTHadTDvwcvBV0FrBa0IvmwUjzgkGvFxhHHCvGrxZBbLm3Oi9LhH4ixIlgEOF3Eb4V4VMR3hWhWYRV4gZxh0jfLcJcEYaL14rTRbqvCPEimESY0yHC9yKcFuG4CIdFeF6ETSJgr/eKcIsIN4iQL8JIEQIiJIhgFKFdhO9E+C8RjonwcrQ+WSNCtQi3iVAqwngR0sRskUoUwSwC9n9W7f+k2v8eETaLsFape59IFau1R4gwAKchgiDCsEsi/CDCKRFOiNI8eEmEF0TYKAIOsFgdIF8sFqkslaA4laDfVYJOqwRpE9isTuA+dQIl6gSuEkFp4BFx2YnV4lbxiHhG7BQ5IoLObWH0tEMQQKdIDeWEryCgApfOn186v/uzoOfTbWH0WAOlf169Z32lQqBLO9C+KTpSUqK8NE3BsCYGlDDHHusaYrMrn6GjAILsP7+64B4Yn9z5lVz+VseAVHf2Lwd/HubVi0mge4uunvLpwk3tGPW01z6393Zg6NntD3/yiL9qHb1X0+Wr0WY9rfrX10oBHUXzep6hGIMR/UbNblE6QFcXbDVGqDRCmREKjCAZIeKYaGeryjvYzavtC5nKOYwTfMzTl7bQ09vP0t+2b6dXrWWmblp9abuy303DPaSKuZ74STruIlO9ffvyvDPGPJCmzc54JmNQontiKDHWS6x834khnreS7Bgwx8yLwSg8JsZqNRaEcO9NLkDvtyUDGjKgPgNqMqAyA8oyoCAD0lVglwii23kkeEE+q5GkdmTbMzZXTINybpMN0TNOG24gTtVQDHWqR6EYr/fG8Pwq4GMwNo+FLU9vO/3LvyrvXnS78eWBsPz4X/uNiPeNvaaimONyDk6/6YnQG9XLcksdux59roljRixfMGm6FZJfapQHFkzkKy1zK++d/cD0zZNDDJVeMbGoTIt7lqtnkMeJCzlUIg3txcbECG70DpJTWCvldIoFIadFIAYn5SsIUbHhFMhOgfoUqEwBTwp0pkBbCrSkgKZYCxZo88/O7jqqyup2qGfxKXPSbiAUQ8gHlVM9G939VC9RXrD46SClo3ZzTQyT8cw9J149fPcDj69euWHlIiqp493QTZ5qw5AdzA9y6OqiOdPl7+Uvvjza+sVHx95BHUNZ0++irOPILCmHCA47x/N2AeNQi6sg5HFUO9Y6zjgYh8Ni8XKVXA3XyrVxLOEsXJlabEEAr6c5zmCgC0KGWE/PmHS+EtL0jGFAOxJxqcdS6vVC1AMA+8pVZbXmA862XV+ePde2/VTCoZgFc9fWUEl/a51zq2nTi+hP2sEKnl2PxUy/+RVNFlOR/rMoC+VMdao0KJHExJhdnJlL9tucMRi6YLjsLQjpMLIuCNGx9clQmQyeZOhMhrZkaEmOSKFbJIhql91TCCkRUhVfJdhbkYLLPxBXkePy0TCdqbIdHlq8LYOimrhdNN/x97sf2FBX99jKRbvnTAcHuKkh029cBK9esu8YYlnYDyq/PPrhmU/efiey3twoAxtK4S4p127l+DhCTCbeSovxHEfoOFIQEuLQg4qL05vNsQUhs0WP7NbHtorQIkKDCPUi1IhQKUKZCAUipIsw/8r11eNI1J32B/dMEwvl06TitTp7q1LhwfHE+jvWxG0pl587d+nSf8PpF831DyzbwMGvL747I29AJ4FeEA8m6NXxqrvu+c17tHuQRBTOSPZd4iRrpTmCHeN2inIyTsYVazCjohBCczgDO2cGp8eV5prgKnVVu9a6trp4sysbs3tcR1xnXGdd/IhSzFEajjZj1T0qnHVJUyvyXFLv/nleV7qrzEVLLrQpAYwq0XgrPkwwav9QmBmqlx3UDqPRnfFnBtUTUZdTkV4iBJ0wt+nxx5fenz94gD9n1Af0wfZx9MFl96xfalqly72hfJmma/I0+iyTT7zoTW6VKnwuvd7D0H2sVtpDp6clmF0GR4wjpSDksMQECkIxsYRHS8AAx4CRIaKUDt50OJkO4XSoV/MkHQrOpENLOkxIh4Z0qEmHtHQwp8O5dGhVM7oZke2na0tSD9tmRO4vuulsD3Gqxz6pvRVn22vN9HdfasHBQ4YGOafVQg+OuN3qYTCV3Ph+r/22xRUgUMG9d7310jsnqnYMpHTM89y+vGWT65bcubZweZ48bXVNfP5EGLF7zlzQgagEeHPLe63nh+xsf0MeRr+5/MjMt9s+e63iJVUHHkIp/4hr003KpBFOq9Wm4218XLwdwTbeSQu4Ii2t8dASD+F4OKe+O+OhLR66gA3xUBkPXdvwgqi1DNqyui9SnK522YWijUxLjZ6VUOqq4U/fF352f7+ywuoNTU0YWtXefNOev3akUbsXzBscfqRjKXtcXnLVUgPKdxUSfZV6x8qT26U8mucJhul61sw4gUwOAdG8b3TmW1T/f6vquVdG/flz3VCaez5BRZX8wceImPzIlWfXkThSvKqpqYn17tp1sY0ZfulN5GEd+gA00mQka6TZOj0Y9AaGGBUXgBFMHiFboJRXqdApMGZBy1YLbJYgTZ6aVybUCA1Ci9AqsGcEIIJWZohgEdIFKYJsE84Jep4C3sDozCxhnLhkcTfKdmWhX4VEB/C9QFs+GVblWPePBxR0uvyX5U1NcOoDeRz8FX68Ta5mj7eXU4Kc1vGYNgcYpfL1TmkijQ66cnvtPMdCGwtnoqcxW1moUc9ePCyY1dOYM90OaupZmMBCp9qkVYV3Ve7p0XVnco+Tmbom9vjFwaperiSE86O97QtL0G/vS4hP7/PadHqvPtAvAddvgsVtJU4no+zjJrNPT5wVAcgPQHYAAgHwBMAcgO8CcCYALwXg+QCsDsDiAMwLwAgVawzAzYg+pqL3qOjqABQHYEIAxABcCsBZtXFXhfUB0AYIqBWYAFwIwKlo19j2lgAMVlE4cNYlFYctG9SWC9Wu86OkGdUBtOG3qXRpWFHttDUAVIvasj4AZQpF6C6mByAtACSgWZuSSDT4J470jB4e8R8q/CH4REuVkd1loaIHNtEzD/W8T/NjlGXbO9iLcqneTWzko4I1PE2mVlbdv4/bCRRN0cMfvXXx2gR62Nb52x7ZO7XyzmXU7s13hxs61tCTD/dj+2dNqJp+4y23le09pqzyzXfvebJjTVTu9I8o93hSLo206fUGEm+IFxNssSSWLQjFWgSzgThbE6AlAcIJcE59dyZAWwJ0ARsSoDLhCoMUnWMPg+TrZoi6zw3nktXvhtDSR5sikxn19KK9z1C7b7lz8N4tl2dQWdJ4vCNN82+YRKTZgB5OntTfyhkJR1xuXYzq1DgUp6bBDfVu0M6bytxQ4IZ0N5xxd9mdf/87D9+VnuTFH384D1//9u3hFZu3rFn9yFOrqV7yV/K34AMrlS6flT9vO3byvz7+pDXiB8vTkLbx6j0m+sFu4rHqdHqiT02xMk5K84NNZp1IJal+cCpkp0J9KlSmgicVOlOhLRVaUv83PzhCsC+ptz+2i5nObm5w9JZN8YMvTmWZJm43YHyUvqX27TcP37PilkXZKzfcv1hxhF/WPSWHWO7ZIcygWfaKEvmCfPqL16Yf2fDRu29E9YNaiby2E79k4ey4T5kcTjNnsDBmzSgGu59SBBVSYjVKND/CaX2I26ljApWzklOSR1beSY9aUNecsnqW4RnDq00dx9UxstBfPYA+RD9SIY3kuSRngigQIjo5JtBfSKLdbg/aH7eFNhSEeDrW0h9IfzjXH9r6Q0t/KOsPNf0huz8gvMsxiPxWJPgfrrp7R4/dUtNgoHYX3PPYjaYP/LP13VO+ra76mlXVRTfWblx27Qfv7vsg4SnzstvvWZg+47G1S8b1gcCG7SvWeKZNnDJFKohP6jP+9oL1G5esduSNvzZ/4Mh+KclXXVuuzNHTeY5CLSYOkiMlCw6H0WzWM0ysM4bV4TozmvVgovWSzkzZFL2oiY3exMSfUGLr6K8HNH9N/QkWziFT8dmGBp1Bp18L96h+oZK/3bc88+633w5mJ4/VuX+m3l92/vyyjsLrs2O0+HkF8vo7Zjiu91JphE2nM0KcMS5BtLHqco8VnHpi/r9c7iTY0/2wOjQOR6IDqnfk3tgKw/+42pnhHZPU9U5Vtb9web1T7yHNPNqo35FmA9wg/QaE0xtoiuIMtNGkp8wcODeaYLkJykwwxQRjTeA1gcMEjAnaTPChCY6aoMEE63vW0SrM1tAarjvilArX+i1W4WJP+GoVnq/CjSYYiohjPRHZ/2eEdNX5YwWqwARpJrCYgJgix5il/2Gr+eNh5p8d3FyOgILZV/xaxqcd1mSDPUjN/Ei+q+VHYZi/9y9HUDRSnzfuuJN6DWXhQll8i7IwwhSpQbnDMTDAcyxF0yyvN7KCabkAdwowVpgiVAj0EAGSBYgVgBHgFwG+EuBjAY4KcECAbUq9+4VHBbpCAE6IFVKFXGGqwM7m1K+CeVP4WPiHoNsgfCpQWGmq0i1071JB/yLQR5UOUoUh2JAZOkvYLhxQ4azQ3NkiDblqdF6WAEkCgOLkUReUC7lWdPHoJuVGrh4dPnqheis3RQBJgMHq3ZzaNMnmzmsQgFLaFQiVglKbQ58QGJ6mdJyZUM6uqyrlxK20JNBNLqULFgQWzOgujj/4CtHjM2R/dIvUg1+v+I+KC+mTT8unXoNaed1bEAOmd+R1cD+8LI+l+lMxcjE803Gh431lTU9GmTyBMtHDjVI7hf490JyOMRo4msF4kjEDr0RdxPmhEY4aockI24yw3gjLjbDQCBUoSfWEbLARvEZwGAFle8EIbUbA+i3/pv5YtX6yERgjfBXttkGtVvNn1RxqzaEX1NoaHdvUHivUSow6ZBeq+5BaBW0kpIlqVYkKq6PVq2d8BSrhSHW3o/7S/9Udu3Idzb/ywLP7YWH3G/DoUaFzMlXS8QZt6XiKqlpFp65e1f731ZqNPShfDyWKbw9XS98oN7GELGdhoXqFOoWFsSwMZiFZ9ds/ZOEoC00sbGNBq1OhXrA6WNyvYc4FFr5S0ZUqoquxFgNgm/XRZiNYCERvbalz3WKCahbmRW9uu98FDzvZ8y7YE73wbet2C6w1OKvGHVj/SPQ6WAs0sEJaNDbh/3A6PL/nZUqXZP7I7z+xTyRNDVQwCESuH9wuX88v+W2p4l8NYF6nprOL1Xg0WbLwDEElxxiQZdh1IcKQHj+wVLchjCJ9mODg0x0fPMO83oxPZ6d2nssutqWSPoRYeZJNvkPZJUp6mho0yJpKU5nNne82ZQbp+JSANTOTqC9stxr3z5/UdsM5rd0PWjsKRNGaSoGnufNfTZ7EOKFbO+W3yoSK2+D+4tjOUvPIn4lH+/+Tb0u/P6F8Ty8eGHtpe8fDhpv5TwhRf6EPJPIbZ36UfD0ZY2i6tP3iPYabI/DLz2iOkBNUFtZLJOOonaQakwnLzfgtxu9LCo6pIgWYNmGajel6TGmYJmGag2kuphCmMqW+zkOeZKei1k4lz7FvkTmYnlPyXBaZreSxzmGljPUVvAIvj9QpYb4kAzG/BfN6fg3RY/lqrDcN03KGqN+pShn7SIzkH4K3yCrE1eG3jttJViJsZQS3nFLKhGRhfQ+WV2AeBU5cmCZTDnIQ8QMi/a+O8OM6LcFYTNuQ6choagqmBzG9jOl7tJWVmN5GrlRg2C1hOoqxbw12jW14bKNDn1ePbfQNhBhNmJrQ6cX2QgEmdPNjEGdOxoR9WG7F9DfUIAemTYTYhmH6ihB7O0bzWM+J+Vhs5w4TEoeaFp+Hji3WFT8kJOFxVBxs3wtp8CBNXi8mHMt3NyFJSDcOQZKxnILtUxGXupyQ3nMwnSOkD9LZL52QANI+AMcZiP0PVP7/cbryf5BVLRkNk8gUcgOGRhSxkDTMEWobxeAagat9uFdkE4AsUgijIt/RIKF36oGr8evB7wgShOEIH4ZfxBMJtxH0Y9X3VmCkndDSAXs6gHSAYcIl8F6Cnwv6eM7n9vH8lNvPcy434Ck9W32WMp+dcLb07Nqze86yxq+/6uX58otcj/kLkL7IjfV83pbrOdl2pu1sGy21BYfktuW6PT/+0On5Ab4p/D7vu8JvM0jhf3/zTeE/80jhP0in5/RVZwrPAF342VV04X/RnR7zR56PKPUlvesWc0++BodbRnpeLUj1vPxKH0/nIShormyuaaaVzbyz2ZaR6zmYfXDCwXkHqw9uPbjnIO8+AJV7G/aG99LmvVC/H8L7wbwfdOZ92fvO7qNrwvVhKhxuCbeG6bQ92XuohhfCL1AtL7S+QKXtyt5FbX0eWna27qQm7Fi7g0rbMW/HkR2dO5hNG5M9BRth3qNw5FF4NDfR88h6l8e83rO+ev3a9Z3r2fR10jqqZh1Urq1ZS9WvhZa1rWupCQ+WPjjvQfr+3E7P1hWwfNkgz8KqbE8VTmTe7SM9t+dmeuLBXRgXdBfyQbqQw6mXIa4U0w25gzzF0/M80/Frz7AVssgeJoMuvJXGgGIkfR19K30vzZ6d2ClVTKSkiZnDcqWJKX1yTxbAuFyvJw97vgbTnlw4k3s2l6rJhdgMZ6EVzIWWDHMhBSh/Ah6POdtcaq42M2ZzmnmCeZ55rfmMudPMZyPsrJmeR2ACAYxZWGiG+sYpkwOB/Ga+c1J+mC8oDsPKcMpk5S1NnB7mVoZJ4fTiokaAh0Ir1qwhoxPzwxmTi8JliaH8cAVmJCVTgxlLYmMsGR2qWli18I6A8oCWIQsDgaoqJQdKKaDh1BwEqhCN1bARFhbeQaoCVQuhqmohqVqI8CqYgfmqKlKF8CrAJpiqApH+u3rCAWZgR/haqA1RVYXtqrCfqshw7hnkfwDL6pLADQplbmRzdHJlYW0KZW5kb2JqCnhyZWYKMCA0NwowMDAwMDAwMDAxIDY1NTM1IGYNCjAwMDAwMDAwMDIgNjU1MzUgZg0KMDAwMDAwMDAwMyA2NTUzNSBmDQowMDAwMDAwMDA0IDY1NTM1IGYNCjAwMDAwMDAwMDUgNjU1MzUgZg0KMDAwMDAwMDAwNiA2NTUzNSBmDQowMDAwMDAwMDA3IDY1NTM1IGYNCjAwMDAwMDAwMDggNjU1MzUgZg0KMDAwMDAwMDAwOSA2NTUzNSBmDQowMDAwMDAwMDEwIDY1NTM1IGYNCjAwMDAwMDAwMTEgNjU1MzUgZg0KMDAwMDAwMDAxMiA2NTUzNSBmDQowMDAwMDAwMDEzIDY1NTM1IGYNCjAwMDAwMDAwMTQgNjU1MzUgZg0KMDAwMDAwMDAxNSA2NTUzNSBmDQowMDAwMDAwMDE2IDY1NTM1IGYNCjAwMDAwMDAwMTcgNjU1MzUgZg0KMDAwMDAwMDAxOCA2NTUzNSBmDQowMDAwMDAwMDE5IDY1NTM1IGYNCjAwMDAwMDAwMjAgNjU1MzUgZg0KMDAwMDAwMDAwMCA2NTUzNSBmDQowMDAwMDAwMDE1IDAwMDAwIG4NCjAwMDAwMDA0MDAgMDAwMDAgbg0KMDAwMDAwMDQ3MiAwMDAwMCBuDQowMDAwMDA0ODIzIDAwMDAwIG4NCjAwMDAwMDUwMTUgMDAwMDAgbg0KMDAwMDAwNTA1OSAwMDAwMCBuDQowMDAwMDA1MTAzIDAwMDAwIG4NCjAwMDAwMDAxODggMDAwMDAgbg0KMDAwMDAwNTMzMiAwMDAwMCBuDQowMDAwMDA1NDcxIDAwMDAwIG4NCjAwMDAwMDc5NzEgMDAwMDAgbg0KMDAwMDAwODAyNCAwMDAwMCBuDQowMDAwMDA4MDY3IDAwMDAwIG4NCjAwMDAwMDgxMDIgMDAwMDAgbg0KMDAwMDAwODE1NiAwMDAwMCBuDQowMDAwMDA5MDg0IDAwMDAwIG4NCjAwMDAwMDkyNTAgMDAwMDAgbg0KMDAwMDAxNDk4MSAwMDAwMCBuDQowMDAwMDE1MDE0IDAwMDAwIG4NCjAwMDAwMTUxMjIgMDAwMDAgbg0KMDAwMDAxNTI3MSAwMDAwMCBuDQowMDAwMDE1ODEzIDAwMDAwIG4NCjAwMDAwMTYyNDMgMDAwMDAgbg0KMDAwMDAxNjMxOCAwMDAwMCBuDQowMDAwMDE2NjAzIDAwMDAwIG4NCjAwMDAwMTY4MTIgMDAwMDAgbg0KdHJhaWxlcgo8PAovUm9vdCAyMSAwIFIKL0luZm8gMjggMCBSCi9JRCBbPDM2Q0M5RjdGMURCQkZEN0NENzg2NDhENTU3QzNCRjFFRjBGNjRGMjhCMDgwNkM1MkRFRDM0QUJEQjc0OTU3NDc+IDwzNkNDOUY3RjFEQkJGRDdDRDc4NjQ4RDU1N0MzQkYxRUYwRjY0RjI4QjA4MDZDNTJERUQzNEFCREI3NDk1NzQ3Pl0KL1NpemUgNDcKPj4Kc3RhcnR4cmVmCjI3ODgzCiUlRU9GCg==	<?xml version="1.0" encoding="UTF-8"?>\n\n<rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100" xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100" xmlns:qdt="urn:un:unece:uncefact:data:standard:QualifiedDataType:100">\n  <!-- generated by: mustangproject.org -->\n  <rsm:ExchangedDocumentContext>\n    <ram:GuidelineSpecifiedDocumentContextParameter>\n      <ram:ID>urn:cen.eu:en16931:2017</ram:ID>\n    </ram:GuidelineSpecifiedDocumentContextParameter>\n  </rsm:ExchangedDocumentContext>\n  <rsm:ExchangedDocument>\n    <ram:ID>TEST-DOC-2026-00000001</ram:ID>\n    <ram:TypeCode>380</ram:TypeCode>\n    <ram:IssueDateTime>\n      <udt:DateTimeString format="102">20260115</udt:DateTimeString>\n    </ram:IssueDateTime>\n    <ram:IncludedNote>\n      <ram:Content>Ursprüngliche Zahlung beim Zahlungsanbieter verbucht am 15.01.2026 13:01 (Europe/Berlin).</ram:Content>\n    </ram:IncludedNote>\n  </rsm:ExchangedDocument>\n  <rsm:SupplyChainTradeTransaction>\n    <ram:IncludedSupplyChainTradeLineItem>\n      <ram:AssociatedDocumentLineDocument>\n        <ram:LineID>1</ram:LineID>\n      </ram:AssociatedDocumentLineDocument>\n      <ram:SpecifiedTradeProduct>\n        <ram:Name>Document Workspace Abonnement</ram:Name>\n        <ram:Description>SaaS-Abonnement pro</ram:Description>\n      </ram:SpecifiedTradeProduct>\n      <ram:SpecifiedLineTradeAgreement>\n        <ram:NetPriceProductTradePrice>\n          <ram:ChargeAmount>10.0800</ram:ChargeAmount>\n          <ram:BasisQuantity unitCode="C62">1.0000</ram:BasisQuantity>\n        </ram:NetPriceProductTradePrice>\n      </ram:SpecifiedLineTradeAgreement>\n      <ram:SpecifiedLineTradeDelivery>\n        <ram:BilledQuantity unitCode="C62">1.0000</ram:BilledQuantity>\n      </ram:SpecifiedLineTradeDelivery>\n      <ram:SpecifiedLineTradeSettlement>\n        <ram:ApplicableTradeTax>\n          <ram:TypeCode>VAT</ram:TypeCode>\n          <ram:CategoryCode>S</ram:CategoryCode>\n          <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>\n        </ram:ApplicableTradeTax>\n        <ram:SpecifiedTradeSettlementLineMonetarySummation>\n          <ram:LineTotalAmount>10.08</ram:LineTotalAmount>\n        </ram:SpecifiedTradeSettlementLineMonetarySummation>\n      </ram:SpecifiedLineTradeSettlement>\n    </ram:IncludedSupplyChainTradeLineItem>\n    <ram:ApplicableHeaderTradeAgreement>\n      <ram:SellerTradeParty>\n        <ram:Name>Vernac Local Demonstration</ram:Name>\n        <ram:PostalTradeAddress>\n          <ram:PostcodeCode>28199</ram:PostcodeCode>\n          <ram:LineOne>Beispielstrasse 1</ram:LineOne>\n          <ram:CityName>Bremen</ram:CityName>\n          <ram:CountryID>DE</ram:CountryID>\n        </ram:PostalTradeAddress>\n        <ram:URIUniversalCommunication>\n          <ram:URIID schemeID="EM">seller@example.test</ram:URIID>\n        </ram:URIUniversalCommunication>\n        <ram:SpecifiedTaxRegistration>\n          <ram:ID schemeID="VA">DE123456789</ram:ID>\n        </ram:SpecifiedTaxRegistration>\n      </ram:SellerTradeParty>\n      <ram:BuyerTradeParty>\n        <ram:ID>ACCOUNT-101aa119-1a80-45f8-8631-85a7d2c886d8</ram:ID>\n        <ram:Name>Customer Snapshot</ram:Name>\n        <ram:PostalTradeAddress>\n          <ram:PostcodeCode>28199</ram:PostcodeCode>\n          <ram:LineOne>Example 1</ram:LineOne>\n          <ram:CityName>Bremen</ram:CityName>\n          <ram:CountryID>DE</ram:CountryID>\n        </ram:PostalTradeAddress>\n        <ram:URIUniversalCommunication>\n          <ram:URIID schemeID="EM">baseline-owner@case.example.test</ram:URIID>\n        </ram:URIUniversalCommunication>\n      </ram:BuyerTradeParty>\n    </ram:ApplicableHeaderTradeAgreement>\n    <ram:ApplicableHeaderTradeDelivery/>\n    <ram:ApplicableHeaderTradeSettlement>\n      <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>\n      <ram:ApplicableTradeTax>\n        <ram:CalculatedAmount>1.92</ram:CalculatedAmount>\n        <ram:TypeCode>VAT</ram:TypeCode>\n        <ram:BasisAmount>10.08</ram:BasisAmount>\n        <ram:CategoryCode>S</ram:CategoryCode>\n        <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>\n      </ram:ApplicableTradeTax>\n      <ram:BillingSpecifiedPeriod>\n        <ram:StartDateTime>\n          <udt:DateTimeString format="102">20260115</udt:DateTimeString>\n        </ram:StartDateTime>\n        <ram:EndDateTime>\n          <udt:DateTimeString format="102">20260215</udt:DateTimeString>\n        </ram:EndDateTime>\n      </ram:BillingSpecifiedPeriod>\n      <ram:SpecifiedTradePaymentTerms>\n        <ram:Description>Bereits bezahlt.</ram:Description>\n        <ram:DueDateDateTime>\n          <udt:DateTimeString format="102">20260115</udt:DateTimeString>\n        </ram:DueDateDateTime>\n      </ram:SpecifiedTradePaymentTerms>\n      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n        <ram:LineTotalAmount>10.08</ram:LineTotalAmount>\n        <ram:ChargeTotalAmount>0.00</ram:ChargeTotalAmount>\n        <ram:AllowanceTotalAmount>0.00</ram:AllowanceTotalAmount>\n        <ram:TaxBasisTotalAmount>10.08</ram:TaxBasisTotalAmount>\n        <ram:TaxTotalAmount currencyID="EUR">1.92</ram:TaxTotalAmount>\n        <ram:GrandTotalAmount>12.00</ram:GrandTotalAmount>\n        <ram:TotalPrepaidAmount>12.00</ram:TotalPrepaidAmount>\n        <ram:DuePayableAmount>0.00</ram:DuePayableAmount>\n      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n    </ram:ApplicableHeaderTradeSettlement>\n  </rsm:SupplyChainTradeTransaction>\n</rsm:CrossIndustryInvoice>\n	2981e62d-3bc4-4c75-a348-ddcbfb4d8d7d	c7817032-5e38-4331-a3bc-2b916aa1b38a
2026-11-16 18:20:24.507828+01	644	644	2026-11-16 18:20:24.507828+01	1	JVBERi0xLjQKJfbk/N8KMjEgMCBvYmoKPDwKL1R5cGUgL0NhdGFsb2cKL1ZlcnNpb24gLzEuNAovUGFnZXMgMjIgMCBSCi9NZXRhZGF0YSAyMyAwIFIKL091dHB1dEludGVudHMgWzI0IDAgUl0KL01hcmtJbmZvIDIyIDAgUgovU3RydWN0VHJlZVJvb3QgMjUgMCBSCi9OYW1lcyAyNiAwIFIKL0FGIFsyNyAwIFJdCj4+CmVuZG9iagoyOCAwIG9iago8PAovVGl0bGUgKFRFU1QtRE9DLTIwMjYtMDAwMDAwMDIpCi9DcmVhdG9yIChWZXJuYWMpCi9Qcm9kdWNlciAoVmVybmFjIFwodmlhIG11c3Rhbmdwcm9qZWN0Lm9yZyAke3Byb2plY3QudmVyc2lvbn1cKSkKL0NyZWF0aW9uRGF0ZSAoRDoyMDI2MTExNjE4MjAyNCswMScwMCcpCi9Nb2REYXRlIChEOjIwMjYxMTE2MTgyMDI0KzAxJzAwJykKPj4KZW5kb2JqCjIyIDAgb2JqCjw8Ci9UeXBlIC9QYWdlcwovS2lkcyBbMjkgMCBSXQovQ291bnQgMQovTWFya2VkIHRydWUKPj4KZW5kb2JqCjIzIDAgb2JqCjw8Ci9MZW5ndGggNDI2NgovVHlwZSAvTWV0YWRhdGEKL1N1YnR5cGUgL1hNTAo+PgpzdHJlYW0NCjw/eHBhY2tldCBiZWdpbj0i77u/IiBpZD0iVzVNME1wQ2VoaUh6cmVTek5UY3prYzlkIj8+PHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyI+CiAgIDxyZGY6UkRGIHhtbG5zOmRjPSJodHRwOi8vcHVybC5vcmcvZGMvZWxlbWVudHMvMS4xLyIKICAgICAgICAgICAgeG1sbnM6cGRmPSJodHRwOi8vbnMuYWRvYmUuY29tL3BkZi8xLjMvIgogICAgICAgICAgICB4bWxuczpwZGZhUHJvcGVydHk9Imh0dHA6Ly93d3cuYWlpbS5vcmcvcGRmYS9ucy9wcm9wZXJ0eSMiCiAgICAgICAgICAgIHhtbG5zOnBkZmFTY2hlbWE9Imh0dHA6Ly93d3cuYWlpbS5vcmcvcGRmYS9ucy9zY2hlbWEjIgogICAgICAgICAgICB4bWxuczpwZGZhaWQ9Imh0dHA6Ly93d3cuYWlpbS5vcmcvcGRmYS9ucy9pZC8iCiAgICAgICAgICAgIHhtbG5zOnJkZj0iaHR0cDovL3d3dy53My5vcmcvMTk5OS8wMi8yMi1yZGYtc3ludGF4LW5zIyI+CiAgICAgIDxyZGY6RGVzY3JpcHRpb24gcmRmOmFib3V0PSIiPgogICAgICAgICA8cGRmOlByb2R1Y2VyPlZlcm5hYzwvcGRmOlByb2R1Y2VyPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiByZGY6YWJvdXQ9IiI+CiAgICAgICAgIDxwZGZhaWQ6Y29uZm9ybWFuY2U+QjwvcGRmYWlkOmNvbmZvcm1hbmNlPgogICAgICAgICA8cGRmYWlkOnBhcnQ+MzwvcGRmYWlkOnBhcnQ+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIj4KICAgICAgICAgPGRjOmZvcm1hdD5hcHBsaWNhdGlvbi9wZGY8L2RjOmZvcm1hdD4KICAgICAgICAgPGRjOmNyZWF0b3I+CiAgICAgICAgICAgIDxyZGY6U2VxPgogICAgICAgICAgICAgICA8cmRmOmxpPlZlcm5hYzwvcmRmOmxpPgogICAgICAgICAgICA8L3JkZjpTZXE+CiAgICAgICAgIDwvZGM6Y3JlYXRvcj4KICAgICAgICAgPGRjOmRhdGU+CiAgICAgICAgICAgIDxyZGY6U2VxPgogICAgICAgICAgICAgICA8cmRmOmxpPjIwMjYtMTEtMTZUMTg6MjA6MjQrMDE6MDA8L3JkZjpsaT4KICAgICAgICAgICAgPC9yZGY6U2VxPgogICAgICAgICA8L2RjOmRhdGU+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHhtbG5zOnhtcD0iaHR0cDovL25zLmFkb2JlLmNvbS94YXAvMS4wLyIgcmRmOmFib3V0PSIiPgogICAgICAgICA8eG1wOkNyZWF0b3JUb29sPm11c3Rhbmdwcm9qZWN0PC94bXA6Q3JlYXRvclRvb2w+CiAgICAgICAgIDx4bXA6Q3JlYXRlRGF0ZT4yMDI2LTExLTE2VDE4OjIwOjI0KzAxOjAwPC94bXA6Q3JlYXRlRGF0ZT4KICAgICAgPC9yZGY6RGVzY3JpcHRpb24+CiAgICAgIDxyZGY6RGVzY3JpcHRpb24geG1sbnM6Zng9InVybjpmYWN0dXIteDpwZGZhOkNyb3NzSW5kdXN0cnlEb2N1bWVudDppbnZvaWNlOjFwMCMiCiAgICAgICAgICAgICAgICAgICAgICAgcmRmOmFib3V0PSIiPgogICAgICAgICA8Zng6Q29uZm9ybWFuY2VMZXZlbD5FTiAxNjkzMTwvZng6Q29uZm9ybWFuY2VMZXZlbD4KICAgICAgICAgPGZ4OkRvY3VtZW50VHlwZT5JTlZPSUNFPC9meDpEb2N1bWVudFR5cGU+CiAgICAgICAgIDxmeDpEb2N1bWVudEZpbGVOYW1lPmZhY3R1ci14LnhtbDwvZng6RG9jdW1lbnRGaWxlTmFtZT4KICAgICAgICAgPGZ4OlZlcnNpb24+MS4wPC9meDpWZXJzaW9uPgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgICAgPHJkZjpEZXNjcmlwdGlvbiB4bWxuczpwZGZhRXh0ZW5zaW9uPSJodHRwOi8vd3d3LmFpaW0ub3JnL3BkZmEvbnMvZXh0ZW5zaW9uLyIgcmRmOmFib3V0PSIiPgogICAgICAgICA8cGRmYUV4dGVuc2lvbjpzY2hlbWFzPgogICAgICAgICAgICA8cmRmOkJhZz4KICAgICAgICAgICAgICAgPHJkZjpsaSByZGY6cGFyc2VUeXBlPSJSZXNvdXJjZSI+CiAgICAgICAgICAgICAgICAgIDxwZGZhU2NoZW1hOnNjaGVtYT5GYWN0dXItWCBQREZBIEV4dGVuc2lvbiBTY2hlbWE8L3BkZmFTY2hlbWE6c2NoZW1hPgogICAgICAgICAgICAgICAgICA8cGRmYVNjaGVtYTpuYW1lc3BhY2VVUkk+dXJuOmZhY3R1ci14OnBkZmE6Q3Jvc3NJbmR1c3RyeURvY3VtZW50Omludm9pY2U6MXAwIzwvcGRmYVNjaGVtYTpuYW1lc3BhY2VVUkk+CiAgICAgICAgICAgICAgICAgIDxwZGZhU2NoZW1hOnByZWZpeD5meDwvcGRmYVNjaGVtYTpwcmVmaXg+CiAgICAgICAgICAgICAgICAgIDxwZGZhU2NoZW1hOnByb3BlcnR5PgogICAgICAgICAgICAgICAgICAgICA8cmRmOlNlcT4KICAgICAgICAgICAgICAgICAgICAgICAgPHJkZjpsaSByZGY6cGFyc2VUeXBlPSJSZXNvdXJjZSI+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6bmFtZT5Eb2N1bWVudEZpbGVOYW1lPC9wZGZhUHJvcGVydHk6bmFtZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+VGV4dDwvcGRmYVByb3BlcnR5OnZhbHVlVHlwZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpjYXRlZ29yeT5leHRlcm5hbDwvcGRmYVByb3BlcnR5OmNhdGVnb3J5PgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPm5hbWUgb2YgdGhlIGVtYmVkZGVkIFhNTCBpbnZvaWNlIGZpbGU8L3BkZmFQcm9wZXJ0eTpkZXNjcmlwdGlvbj4KICAgICAgICAgICAgICAgICAgICAgICAgPC9yZGY6bGk+CiAgICAgICAgICAgICAgICAgICAgICAgIDxyZGY6bGkgcmRmOnBhcnNlVHlwZT0iUmVzb3VyY2UiPgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5Om5hbWU+RG9jdW1lbnRUeXBlPC9wZGZhUHJvcGVydHk6bmFtZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+VGV4dDwvcGRmYVByb3BlcnR5OnZhbHVlVHlwZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpjYXRlZ29yeT5leHRlcm5hbDwvcGRmYVByb3BlcnR5OmNhdGVnb3J5PgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPklOVk9JQ0U8L3BkZmFQcm9wZXJ0eTpkZXNjcmlwdGlvbj4KICAgICAgICAgICAgICAgICAgICAgICAgPC9yZGY6bGk+CiAgICAgICAgICAgICAgICAgICAgICAgIDxyZGY6bGkgcmRmOnBhcnNlVHlwZT0iUmVzb3VyY2UiPgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5Om5hbWU+VmVyc2lvbjwvcGRmYVByb3BlcnR5Om5hbWU+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6dmFsdWVUeXBlPlRleHQ8L3BkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6Y2F0ZWdvcnk+ZXh0ZXJuYWw8L3BkZmFQcm9wZXJ0eTpjYXRlZ29yeT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpkZXNjcmlwdGlvbj5UaGUgYWN0dWFsIHZlcnNpb24gb2YgdGhlIFpVR0ZlUkQgWE1MIHNjaGVtYTwvcGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPgogICAgICAgICAgICAgICAgICAgICAgICA8L3JkZjpsaT4KICAgICAgICAgICAgICAgICAgICAgICAgPHJkZjpsaSByZGY6cGFyc2VUeXBlPSJSZXNvdXJjZSI+CiAgICAgICAgICAgICAgICAgICAgICAgICAgIDxwZGZhUHJvcGVydHk6bmFtZT5Db25mb3JtYW5jZUxldmVsPC9wZGZhUHJvcGVydHk6bmFtZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTp2YWx1ZVR5cGU+VGV4dDwvcGRmYVByb3BlcnR5OnZhbHVlVHlwZT4KICAgICAgICAgICAgICAgICAgICAgICAgICAgPHBkZmFQcm9wZXJ0eTpjYXRlZ29yeT5leHRlcm5hbDwvcGRmYVByb3BlcnR5OmNhdGVnb3J5PgogICAgICAgICAgICAgICAgICAgICAgICAgICA8cGRmYVByb3BlcnR5OmRlc2NyaXB0aW9uPlRoZSBzZWxlY3RlZCBaVUdGZVJEIHByb2ZpbGUgY29tcGxldGVuZXNzPC9wZGZhUHJvcGVydHk6ZGVzY3JpcHRpb24+CiAgICAgICAgICAgICAgICAgICAgICAgIDwvcmRmOmxpPgogICAgICAgICAgICAgICAgICAgICA8L3JkZjpTZXE+CiAgICAgICAgICAgICAgICAgIDwvcGRmYVNjaGVtYTpwcm9wZXJ0eT4KICAgICAgICAgICAgICAgPC9yZGY6bGk+CiAgICAgICAgICAgIDwvcmRmOkJhZz4KICAgICAgICAgPC9wZGZhRXh0ZW5zaW9uOnNjaGVtYXM+CiAgICAgIDwvcmRmOkRlc2NyaXB0aW9uPgogICA8L3JkZjpSREY+CjwveDp4bXBtZXRhPgo8P3hwYWNrZXQgZW5kPSJ3Ij8+Cg0KZW5kc3RyZWFtCmVuZG9iagoyNCAwIG9iago8PAovVHlwZSAvT3V0cHV0SW50ZW50Ci9TIC9HVFNfUERGQTEKL0Rlc3RPdXRwdXRQcm9maWxlIDMwIDAgUgovSW5mbyAoc1JHQiBJRUM2MTk2Ni0yLjEpCi9PdXRwdXRDb25kaXRpb25JZGVudGlmaWVyIChzUkdCIElFQzYxOTY2LTIuMSkKL1JlZ2lzdHJ5TmFtZSAoaHR0cDovL3d3dy5jb2xvci5vcmcpCj4+CmVuZG9iagoyNSAwIG9iago8PAovVHlwZSAvU3RydWN0VHJlZVJvb3QKPj4KZW5kb2JqCjI2IDAgb2JqCjw8Ci9FbWJlZGRlZEZpbGVzIDMxIDAgUgo+PgplbmRvYmoKMjcgMCBvYmoKPDwKL1R5cGUgL0ZpbGVzcGVjCi9GIChmYWN0dXIteC54bWwpCi9BRlJlbGF0aW9uc2hpcCAvQWx0ZXJuYXRpdmUKL1VGIChmYWN0dXIteC54bWwpCi9EZXNjIChJbnZvaWNlIG1ldGFkYXRhIGNvbmZvcm1pbmcgdG8gWlVHRmVSRCBzdGFuZGFyZCBcKGh0dHBzOi8vd3d3LmZlcmQtbmV0LmRlL2VuL3N0YW5kYXJkcy96dWdmZXJkL2ZhY3R1ci14XCkpCi9FRiAzMiAwIFIKPj4KZW5kb2JqCjI5IDAgb2JqCjw8Ci9UeXBlIC9QYWdlCi9NZWRpYUJveCBbMC4wIDAuMCA1OTUuMjc1NjMgODQxLjg4OThdCi9QYXJl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vZJklliFfV4Htp/N9UyZ5nU/l/LrVK7/9I1Iu5+NdeaT18Nc1lMMHa/GvE8Xr4sw8+2vfDqwHexxgOvkY7Z1NuY+LuwFltNZ7eV5BWw3yviyTze7fhgXtqsLnLJ48TnUNfvKr6PfMW2l4g0KZW5kc3RyZWFtCmVuZG9iagozNiAwIG9iago8PAovTGVuZ3RoIDkzCi9GaWx0ZXIgL0ZsYXRlRGVjb2RlCj4+CnN0cmVhbQ0KeJwL5HIK4dJ3M1SwVAhJ4zI1UTCyUAhJ4dJgMGeI0WAwA1ICDOoMRgxqQFqUQRiIJYEsYRQoysDMEA/EZgweDD4M4UCSmUEEiCMZghgCQWxNhZAsLtcQLgDpoQxSDQplbmRzdHJlYW0KZW5kb2JqCjM3IDAgb2JqCjw8Ci9MZW5ndGggNTkxNgovVHlwZSAvRW1iZWRkZWRGaWxlCi9TdWJ0eXBlIC90ZXh0IzJGeG1sCi9QYXJhbXMgMzkgMCBSCj4+CnN0cmVhbQ0KPD94bWwgdmVyc2lvbj0iMS4wIiBlbmNvZGluZz0iVVRGLTgiPz4KCjxyc206Q3Jvc3NJbmR1c3RyeUludm9pY2UgeG1sbnM6cnNtPSJ1cm46dW46dW5lY2U6dW5jZWZhY3Q6ZGF0YTpzdGFuZGFyZDpDcm9zc0luZHVzdHJ5SW52b2ljZToxMDAiIHhtbG5zOnhzaT0iaHR0cDovL3d3dy53My5vcmcvMjAwMS9YTUxTY2hlbWEtaW5zdGFuY2UiIHhtbG5zOnJhbT0idXJuOnVuOnVuZWNlOnVuY2VmYWN0OmRhdGE6c3RhbmRhcmQ6UmV1c2FibGVBZ2dyZWdhdGVCdXNpbmVzc0luZm9ybWF0aW9uRW50aXR5OjEwMCIgeG1sbnM6dWR0PSJ1cm46dW46dW5lY2U6dW5jZWZhY3Q6ZGF0YTpzdGFuZGFyZDpVbnF1YWxpZmllZERhdGFUeXBlOjEwMCIgeG1sbnM6cWR0PSJ1cm46dW46dW5lY2U6dW5jZWZhY3Q6ZGF0YTpzdGFuZGFyZDpRdWFsaWZpZWREYXRhVHlwZToxMDAiPgogIDwhLS0gZ2VuZXJhdGVkIGJ5OiBtdXN0YW5ncHJvamVjdC5vcmcgLS0+CiAgPHJzbTpFeGNoYW5nZWREb2N1bWVudENvbnRleHQ+CiAgICA8cmFtOkd1aWRlbGluZVNwZWNpZmllZERvY3VtZW50Q29udGV4dFBhcmFtZXRlcj4KICAgICAgPHJhbTpJRD51cm46Y2VuLmV1OmVuMTY5MzE6MjAxNzwvcmFtOklEPgogICAgPC9yYW06R3VpZGVsaW5lU3BlY2lmaWVkRG9jdW1lbnRDb250ZXh0UGFyYW1ldGVyPgogIDwvcnNtOkV4Y2hhbmdlZERvY3VtZW50Q29udGV4dD4KICA8cnNtOkV4Y2hhbmdlZERvY3VtZW50PgogICAgPHJhbTpJRD5URVNULURPQy0yMDI2LTAwMDAwMDAyPC9yYW06SUQ+CiAgICA8cmFtOlR5cGVDb2RlPjM4MTwvcmFtOlR5cGVDb2RlPgogICAgPHJhbTpJc3N1ZURhdGVUaW1lPgogICAgICA8dWR0OkRhdGVUaW1lU3RyaW5nIGZvcm1hdD0iMTAyIj4yMDI2MDExNTwvdWR0OkRhdGVUaW1lU3RyaW5nPgogICAgPC9yYW06SXNzdWVEYXRlVGltZT4KICAgIDxyYW06SW5jbHVkZWROb3RlPgogICAgICA8cmFtOkNvbnRlbnQ+VXJzcHLDvG5nbGljaGUgWmFobHVuZyBiZWltIFphaGx1bmdzYW5iaWV0ZXIgdmVyYnVjaHQgYW0gMTUuMDEuMjAyNiAxMzowMSAoRXVyb3BlL0JlcmxpbikuPC9yYW06Q29udGVudD4KICAgIDwvcmFtOkluY2x1ZGVkTm90ZT4KICA8L3JzbTpFeGNoYW5nZWREb2N1bWVudD4KICA8cnNtOlN1cHBseUNoYWluVHJhZGVUcmFuc2FjdGlvbj4KICAgIDxyYW06SW5jbHVkZWRTdXBwbHlDaGFpblRyYWRlTGluZUl0ZW0+CiAgICAgIDxyYW06QXNzb2NpYXRlZERvY3VtZW50TGluZURvY3VtZW50PgogICAgICAgIDxyYW06TGluZUlEPjE8L3JhbTpMaW5lSUQ+CiAgICAgIDwvcmFtOkFzc29jaWF0ZWREb2N1bWVudExpbmVEb2N1bWVudD4KICAgICAgPHJhbTpTcGVjaWZpZWRUcmFkZVByb2R1Y3Q+CiAgICAgICAgPHJhbTpOYW1lPkRvY3VtZW50IFdvcmtzcGFjZSBBYm9ubmVtZW50PC9yYW06TmFtZT4KICAgICAgICA8cmFtOkRlc2NyaXB0aW9uPlNhYVMtQWJvbm5lbWVudCBwcm88L3JhbTpEZXNjcmlwdGlvbj4KICAgICAgPC9yYW06U3BlY2lmaWVkVHJhZGVQcm9kdWN0PgogICAgICA8cmFtOlNwZWNpZmllZExpbmVUcmFkZUFncmVlbWVudD4KICAgICAgICA8cmFtOk5ldFByaWNlUHJvZHVjdFRyYWRlUHJpY2U+CiAgICAgICAgICA8cmFtOkNoYXJnZUFtb3VudD4yLjUyMDA8L3JhbTpDaGFyZ2VBbW91bnQ+CiAgICAgICAgICA8cmFtOkJhc2lzUXVhbnRpdHkgdW5pdENvZGU9IkM2MiI+MS4wMDAwPC9yYW06QmFzaXNRdWFudGl0eT4KICAgICAgICA8L3JhbTpOZXRQcmljZVByb2R1Y3RUcmFkZVByaWNlPgogICAgICA8L3JhbTpTcGVjaWZpZWRMaW5lVHJhZGVBZ3JlZW1lbnQ+CiAgICAgIDxyYW06U3BlY2lmaWVkTGluZVRyYWRlRGVsaXZlcnk+CiAgICAgICAgPHJhbTpCaWxsZWRRdWFudGl0eSB1bml0Q29kZT0iQzYyIj4xLjAwMDA8L3JhbTpCaWxsZWRRdWFudGl0eT4KICAgICAgPC9yYW06U3BlY2lmaWVkTGluZVRyYWRlRGVsaXZlcnk+CiAgICAgIDxyYW06U3BlY2lmaWVkTGluZVRyYWRlU2V0dGxlbWVudD4KICAgICAgICA8cmFtOkFwcGxpY2FibGVUcmFkZVRheD4KICAgICAgICAgIDxyYW06VHlwZUNvZGU+VkFUPC9yYW06VHlwZUNvZGU+CiAgICAgICAgICA8cmFtOkNhdGVnb3J5Q29kZT5TPC9yYW06Q2F0ZWdvcnlDb2RlPgogICAgICAgICAgPHJhbTpSYXRlQXBwbGljYWJsZVBlcmNlbnQ+MTkuMDA8L3JhbTpSYXRlQXBwbGljYWJsZVBlcmNlbnQ+CiAgICAgICAgPC9yYW06QXBwbGljYWJsZVRyYWRlVGF4PgogICAgICAgIDxyYW06U3BlY2lmaWVkVHJhZGVTZXR0bGVtZW50TGluZU1vbmV0YXJ5U3VtbWF0aW9uPgogICAgICAgICAgPHJhbTpMaW5lVG90YWxBbW91bnQ+Mi41MjwvcmFtOkxpbmVUb3RhbEFtb3VudD4KICAgICAgICA8L3JhbTpTcGVjaWZpZWRUcmFkZVNldHRsZW1lbnRMaW5lTW9uZXRhcnlTdW1tYXRpb24+CiAgICAgIDwvcmFtOlNwZWNpZmllZExpbmVUcmFkZVNldHRsZW1lbnQ+CiAgICA8L3JhbTpJbmNsdWRlZFN1cHBseUNoYWluVHJhZGVMaW5lSXRlbT4KICAgIDxyYW06QXBwbGljYWJsZUhlYWRlclRyYWRlQWdyZWVtZW50PgogICAgICA8cmFtOlNlbGxlclRyYWRlUGFydHk+CiAgICAgICAgPHJhbTpOYW1lPlZlcm5hYyBMb2NhbCBEZW1vbnN0cmF0aW9uPC9yYW06TmFtZT4KICAgICAgICA8cmFtOlBvc3RhbFRyYWRlQWRkcmVzcz4KICAgICAgICAgIDxyYW06UG9zdGNvZGVDb2RlPjI4MTk5PC9yYW06UG9zdGNvZGVDb2RlPgogICAgICAgICAgPHJhbTpMaW5lT25lPkJlaXNwaWVsc3RyYXNzZSAxPC9yYW06TGluZU9uZT4KICAgICAgICAgIDxyYW06Q2l0eU5hbWU+QnJlbWVuPC9yYW06Q2l0eU5hbWU+CiAgICAgICAgICA8cmFtOkNvdW50cnlJRD5ERTwvcmFtOkNvdW50cnlJRD4KICAgICAgICA8L3JhbTpQb3N0YWxUcmFkZUFkZHJlc3M+CiAgICAgICAgPHJhbTpVUklVbml2ZXJzYWxDb21tdW5pY2F0aW9uPgogICAgICAgICAgPHJhbTpVUklJRCBzY2hlbWVJRD0iRU0iPnNlbGxlckBleGFtcGxlLnRlc3Q8L3JhbTpVUklJRD4KICAgICAgICA8L3JhbTpVUklVbml2ZXJzYWxDb21tdW5pY2F0aW9uPgogICAgICAgIDxyYW06U3BlY2lmaWVkVGF4UmVnaXN0cmF0aW9uPgogICAgICAgICAgPHJhbTpJRCBzY2hlbWVJRD0iVkEiPkRFMTIzNDU2Nzg5PC9yYW06SUQ+CiAgICAgICAgPC9yYW06U3BlY2lmaWVkVGF4UmVnaXN0cmF0aW9uPgogICAgICA8L3JhbTpTZWxsZXJUcmFkZVBhcnR5PgogICAgICA8cmFtOkJ1eWVyVHJhZGVQYXJ0eT4KICAgICAgICA8cmFtOklEPkFDQ09VTlQtMTAxYWExMTktMWE4MC00NWY4LTg2MzEtODVhN2QyYzg4NmQ4PC9yYW06SUQ+CiAgICAgICAgPHJhbTpOYW1lPkN1c3RvbWVyIFNuYXBzaG90PC9yYW06TmFtZT4KICAgICAgICA8cmFtOlBvc3RhbFRyYWRlQWRkcmVzcz4KICAgICAgICAgIDxyYW06UG9zdGNvZGVDb2RlPjI4MTk5PC9yYW06UG9zdGNvZGVDb2RlPgogICAgICAgICAgPHJhbTpMaW5lT25lPkV4YW1wbGUgMTwvcmFtOkxpbmVPbmU+CiAgICAgICAgICA8cmFtOkNpdHlOYW1lPkJyZW1lbjwvcmFtOkNpdHlOYW1lPgogICAgICAgICAgPHJhbTpDb3VudHJ5SUQ+REU8L3JhbTpDb3VudHJ5SUQ+CiAgICAgICAgPC9yYW06UG9zdGFsVHJhZGVBZGRyZXNzPgogICAgICAgIDxyYW06VVJJVW5pdmVyc2FsQ29tbXVuaWNhdGlvbj4KICAgICAgICAgIDxyYW06VVJJSUQgc2NoZW1lSUQ9IkVNIj5iYXNlbGluZS1vd25lckBjYXNlLmV4YW1wbGUudGVzdDwvcmFtOlVSSUlEPgogICAgICAgIDwvcmFtOlVSSVVuaXZlcnNhbENvbW11bmljYXRpb24+CiAgICAgIDwvcmFtOkJ1eWVyVHJhZGVQYXJ0eT4KICAgIDwvcmFtOkFwcGxpY2FibGVIZWFkZXJUcmFkZUFncmVlbWVudD4KICAgIDxyYW06QXBwbGljYWJsZUhlYWRlclRyYWRlRGVsaXZlcnkvPgogICAgPHJhbTpBcHBsaWNhYmxlSGVhZGVyVHJhZGVTZXR0bGVtZW50PgogICAgICA8cmFtOkludm9pY2VDdXJyZW5jeUNvZGU+RVVSPC9yYW06SW52b2ljZUN1cnJlbmN5Q29kZT4KICAgICAgPHJhbTpBcHBsaWNhYmxlVHJhZGVUYXg+CiAgICAgICAgPHJhbTpDYWxjdWxhdGVkQW1vdW50PjAuNDg8L3JhbTpDYWxjdWxhdGVkQW1vdW50PgogICAgICAgIDxyYW06VHlwZUNvZGU+VkFUPC9yYW06VHlwZUNvZGU+CiAgICAgICAgPHJhbTpCYXNpc0Ftb3VudD4yLjUyPC9yYW06QmFzaXNBbW91bnQ+CiAgICAgICAgPHJhbTpDYXRlZ29yeUNvZGU+UzwvcmFtOkNhdGVnb3J5Q29kZT4KICAgICAgICA8cmFtOlJhdGVBcHBsaWNhYmxlUGVyY2VudD4xOS4wMDwvcmFtOlJhdGVBcHBsaWNhYmxlUGVyY2VudD4KICAgICAgPC9yYW06QXBwbGljYWJsZVRyYWRlVGF4PgogICAgICA8cmFtOkJpbGxpbmdTcGVjaWZpZWRQZXJpb2Q+CiAgICAgICAgPHJhbTpTdGFydERhdGVUaW1lPgogICAgICAgICAgPHVkdDpEYXRlVGltZVN0cmluZyBmb3JtYXQ9IjEwMiI+MjAyNjAxMTU8L3VkdDpEYXRlVGltZVN0cmluZz4KICAgICAgICA8L3JhbTpTdGFydERhdGVUaW1lPgogICAgICAgIDxyYW06RW5kRGF0ZVRpbWU+CiAgICAgICAgICA8dWR0OkRhdGVUaW1lU3RyaW5nIGZvcm1hdD0iMTAyIj4yMDI2MDIxNTwvdWR0OkRhdGVUaW1lU3RyaW5nPgogICAgICAgIDwvcmFtOkVuZERhdGVUaW1lPgogICAgICA8L3JhbTpCaWxsaW5nU3BlY2lmaWVkUGVyaW9kPgogICAgICA8cmFtOlNwZWNpZmllZFRyYWRlUGF5bWVudFRlcm1zPgogICAgICAgIDxyYW06RGVzY3JpcHRpb24+QmVyZWl0cyBlcnN0YXR0ZXQuPC9yYW06RGVzY3JpcHRpb24+CiAgICAgICAgPHJhbTpEdWVEYXRlRGF0ZVRpbWU+CiAgICAgICAgICA8dWR0OkRhdGVUaW1lU3RyaW5nIGZvcm1hdD0iMTAyIj4yMDI2MDExNTwvdWR0OkRhdGVUaW1lU3RyaW5nPgogICAgICAgIDwvcmFtOkR1ZURhdGVEYXRlVGltZT4KICAgICAgPC9yYW06U3BlY2lmaWVkVHJhZGVQYXltZW50VGVybXM+CiAgICAgIDxyYW06U3BlY2lmaWVkVHJhZGVTZXR0bGVtZW50SGVhZGVyTW9uZXRhcnlTdW1tYXRpb24+CiAgICAgICAgPHJhbTpMaW5lVG90YWxBbW91bnQ+Mi41MjwvcmFtOkxpbmVUb3RhbEFtb3VudD4KICAgICAgICA8cmFtOkNoYXJnZVRvdGFsQW1vdW50PjAuMDA8L3JhbTpDaGFyZ2VUb3RhbEFtb3VudD4KICAgICAgICA8cmFtOkFsbG93YW5jZVRvdGFsQW1vdW50PjAuMDA8L3JhbTpBbGxvd2FuY2VUb3RhbEFtb3VudD4KICAgICAgICA8cmFtOlRheEJhc2lzVG90YWxBbW91bnQ+Mi41MjwvcmFtOlRheEJhc2lzVG90YWxBbW91bnQ+CiAgICAgICAgPHJhbTpUYXhUb3RhbEFtb3VudCBjdXJyZW5jeUlEPSJFVVIiPjAuNDg8L3JhbTpUYXhUb3RhbEFtb3VudD4KICAgICAgICA8cmFtOkdyYW5kVG90YWxBbW91bnQ+My4wMDwvcmFtOkdyYW5kVG90YWxBbW91bnQ+CiAgICAgICAgPHJhbTpUb3RhbFByZXBhaWRBbW91bnQ+My4wMDwvcmFtOlRvdGFsUHJlcGFpZEFtb3VudD4KICAgICAgICA8cmFtOkR1ZVBheWFibGVBbW91bnQ+MC4wMDwvcmFtOkR1ZVBheWFibGVBbW91bnQ+CiAgICAgIDwvcmFtOlNwZWNpZmllZFRyYWRlU2V0dGxlbWVudEhlYWRlck1vbmV0YXJ5U3VtbWF0aW9uPgogICAgICA8cmFtOkludm9pY2VSZWZlcmVuY2VkRG9jdW1lbnQ+CiAgICAgICAgPHJhbTpJc3N1ZXJBc3NpZ25lZElEPlRFU1QtRE9DLTIwMjYtMDAwMDAwMDE8L3JhbTpJc3N1ZXJBc3NpZ25lZElEPgogICAgICAgIDxyYW06Rm9ybWF0dGVkSXNzdWVEYXRlVGltZT4KICAgICAgICAgIDxxZHQ6RGF0ZVRpbWVTdHJpbmcgZm9ybWF0PSIxMDIiPjIwMjYwMTE1PC9xZHQ6RGF0ZVRpbWVTdHJpbmc+CiAgICAgICAgPC9yYW06Rm9ybWF0dGVkSXNzdWVEYXRlVGltZT4KICAgICAgPC9yYW06SW52b2ljZVJlZmVyZW5jZWREb2N1bWVudD4KICAgIDwvcmFtOkFwcGxpY2FibGVIZWFkZXJUcmFkZVNldHRsZW1lbnQ+CiAgPC9yc206U3VwcGx5Q2hhaW5UcmFkZVRyYW5zYWN0aW9uPgo8L3JzbTpDcm9zc0luZHVzdHJ5SW52b2ljZT4KDQplbmRzdHJlYW0KZW5kb2JqCjM4IDAgb2JqCjw8Ci9GMSA0MCAwIFIKPj4KZW5kb2JqCjM5IDAgb2JqCjw8Ci9TaXplIDU5MTYKL0NyZWF0aW9uRGF0ZSAoRDoyMDI2MTExNjE4MjAyNCswMScwMCcpCi9Nb2REYXRlIChEOjIwMjYxMTE2MTgyMDI0KzAxJzAwJykKPj4KZW5kb2JqCjQwIDAgb2JqCjw8Ci9UeXBlIC9Gb250Ci9CYXNlRm9udCAvQUFBWlBIK0xpYmVyYXRpb25TYW5zCi9TdWJ0eXBlIC9UeXBlMAovRW5jb2RpbmcgL0lkZW50aXR5LUgKL0Rlc2NlbmRhbnRGb250cyBbNDEgMCBSXQovVG9Vbmljb2RlIDQyIDAgUgo+PgplbmRvYmoKNDEgMCBvYmoKPDwKL1R5cGUgL0ZvbnQKL1N1YnR5cGUgL0NJREZvbnRUeXBlMgovQmFzZUZvbnQgL0FBQVpQSCtMaWJlcmF0aW9uU2FucwovQ0lEU3lzdGVtSW5mbyA0MyAwIFIKL0ZvbnREZXNjcmlwdG9yIDQ0IDAgUgovVyBbMCBbNzUwXQogMyBbMjc4XQogOCBbODg5XQogMTEgWzMzMyAzMzNdCiAxNSBbMjc4IDMzMyAyNzggMjc4IDU1NiA1NTYgNTU2IDU1NiA1NTYgNTU2CjU1NiA1NTYgNTU2IDU1NiAyNzhdCjM1IFsxMDE1IDY2NyA2NjcgNzIyIDcyMiA2NjddCiA0MiBbNzc4IDcyMiAyNzhdCiA0NiBbNjY3IDU1Nl0KIDQ5IFs3MjIgNzc4XQogNTMgWzcyMiA2NjcgNjExIDcyMiA2NjcgOTQ0XQo2MSBbNjExXQogNjggWzU1NiA1NTYgNTAwIDU1NiA1NTYgMjc4IDU1NiA1NTYgMjIyXQogNzggWzUwMCAyMjIgODMzIDU1NiA1NTYgNTU2XQogODUgWzMzMyA1MDAgMjc4IDU1NiA1MDBdCiA5MSBbNTAwXQo5MyBbNTAwXQogOTUgWzI2MF0KIDEwNiBbMzMzXQogMTY2IFs1NTZdCiAxOTAgWzU1Nl0KXQovQ0lEVG9HSURNYXAgNDUgMCBSCj4+CmVuZG9iago0MiAwIG9iago8PAovTGVuZ3RoIDM1NQovRmlsdGVyIC9GbGF0ZURlY29kZQo+Pgpz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OCAwMDAwMCBuDQowMDAwMDA1MzMyIDAwMDAwIG4NCjAwMDAwMDU0NzEgMDAwMDAgbg0KMDAwMDAwNzk3MSAwMDAwMCBuDQowMDAwMDA4MDI0IDAwMDAwIG4NCjAwMDAwMDgwNjcgMDAwMDAgbg0KMDAwMDAwODEwMiAwMDAwMCBuDQowMDAwMDA4MTU2IDAwMDAwIG4NCjAwMDAwMDkxMjUgMDAwMDAgbg0KMDAwMDAwOTI5MiAwMDAwMCBuDQowMDAwMDE1MzE5IDAwMDAwIG4NCjAwMDAwMTUzNTIgMDAwMDAgbg0KMDAwMDAxNTQ2MCAwMDAwMCBuDQowMDAwMDE1NjA5IDAwMDAwIG4NCjAwMDAwMTYxNTEgMDAwMDAgbg0KMDAwMDAxNjU4MSAwMDAwMCBuDQowMDAwMDE2NjU2IDAwMDAwIG4NCjAwMDAwMTY5NDEgMDAwMDAgbg0KMDAwMDAxNzE1MCAwMDAwMCBuDQp0cmFpbGVyCjw8Ci9Sb290IDIxIDAgUgovSW5mbyAyOCAwIFIKL0lEIFs8RTEzQjlCNTk0RjFDMEYwRkNGNUM4NzNFQzU4MDM4MUIwQ0UxQzgyMUE2QTFBQkJFMjcyNzdBOUE3QThENjJFRD4gPEUxM0I5QjU5NEYxQzBGMEZDRjVDODczRUM1ODAzODFCMENFMUM4MjFBNkExQUJCRTI3Mjc3QTlBN0E4RDYyRUQ+XQovU2l6ZSA0Nwo+PgpzdGFydHhyZWYKMjgyMjEKJSVFT0YK	<?xml version="1.0" encoding="UTF-8"?>\n\n<rsm:CrossIndustryInvoice xmlns:rsm="urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:ram="urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100" xmlns:udt="urn:un:unece:uncefact:data:standard:UnqualifiedDataType:100" xmlns:qdt="urn:un:unece:uncefact:data:standard:QualifiedDataType:100">\n  <!-- generated by: mustangproject.org -->\n  <rsm:ExchangedDocumentContext>\n    <ram:GuidelineSpecifiedDocumentContextParameter>\n      <ram:ID>urn:cen.eu:en16931:2017</ram:ID>\n    </ram:GuidelineSpecifiedDocumentContextParameter>\n  </rsm:ExchangedDocumentContext>\n  <rsm:ExchangedDocument>\n    <ram:ID>TEST-DOC-2026-00000002</ram:ID>\n    <ram:TypeCode>381</ram:TypeCode>\n    <ram:IssueDateTime>\n      <udt:DateTimeString format="102">20260115</udt:DateTimeString>\n    </ram:IssueDateTime>\n    <ram:IncludedNote>\n      <ram:Content>Ursprüngliche Zahlung beim Zahlungsanbieter verbucht am 15.01.2026 13:01 (Europe/Berlin).</ram:Content>\n    </ram:IncludedNote>\n  </rsm:ExchangedDocument>\n  <rsm:SupplyChainTradeTransaction>\n    <ram:IncludedSupplyChainTradeLineItem>\n      <ram:AssociatedDocumentLineDocument>\n        <ram:LineID>1</ram:LineID>\n      </ram:AssociatedDocumentLineDocument>\n      <ram:SpecifiedTradeProduct>\n        <ram:Name>Document Workspace Abonnement</ram:Name>\n        <ram:Description>SaaS-Abonnement pro</ram:Description>\n      </ram:SpecifiedTradeProduct>\n      <ram:SpecifiedLineTradeAgreement>\n        <ram:NetPriceProductTradePrice>\n          <ram:ChargeAmount>2.5200</ram:ChargeAmount>\n          <ram:BasisQuantity unitCode="C62">1.0000</ram:BasisQuantity>\n        </ram:NetPriceProductTradePrice>\n      </ram:SpecifiedLineTradeAgreement>\n      <ram:SpecifiedLineTradeDelivery>\n        <ram:BilledQuantity unitCode="C62">1.0000</ram:BilledQuantity>\n      </ram:SpecifiedLineTradeDelivery>\n      <ram:SpecifiedLineTradeSettlement>\n        <ram:ApplicableTradeTax>\n          <ram:TypeCode>VAT</ram:TypeCode>\n          <ram:CategoryCode>S</ram:CategoryCode>\n          <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>\n        </ram:ApplicableTradeTax>\n        <ram:SpecifiedTradeSettlementLineMonetarySummation>\n          <ram:LineTotalAmount>2.52</ram:LineTotalAmount>\n        </ram:SpecifiedTradeSettlementLineMonetarySummation>\n      </ram:SpecifiedLineTradeSettlement>\n    </ram:IncludedSupplyChainTradeLineItem>\n    <ram:ApplicableHeaderTradeAgreement>\n      <ram:SellerTradeParty>\n        <ram:Name>Vernac Local Demonstration</ram:Name>\n        <ram:PostalTradeAddress>\n          <ram:PostcodeCode>28199</ram:PostcodeCode>\n          <ram:LineOne>Beispielstrasse 1</ram:LineOne>\n          <ram:CityName>Bremen</ram:CityName>\n          <ram:CountryID>DE</ram:CountryID>\n        </ram:PostalTradeAddress>\n        <ram:URIUniversalCommunication>\n          <ram:URIID schemeID="EM">seller@example.test</ram:URIID>\n        </ram:URIUniversalCommunication>\n        <ram:SpecifiedTaxRegistration>\n          <ram:ID schemeID="VA">DE123456789</ram:ID>\n        </ram:SpecifiedTaxRegistration>\n      </ram:SellerTradeParty>\n      <ram:BuyerTradeParty>\n        <ram:ID>ACCOUNT-101aa119-1a80-45f8-8631-85a7d2c886d8</ram:ID>\n        <ram:Name>Customer Snapshot</ram:Name>\n        <ram:PostalTradeAddress>\n          <ram:PostcodeCode>28199</ram:PostcodeCode>\n          <ram:LineOne>Example 1</ram:LineOne>\n          <ram:CityName>Bremen</ram:CityName>\n          <ram:CountryID>DE</ram:CountryID>\n        </ram:PostalTradeAddress>\n        <ram:URIUniversalCommunication>\n          <ram:URIID schemeID="EM">baseline-owner@case.example.test</ram:URIID>\n        </ram:URIUniversalCommunication>\n      </ram:BuyerTradeParty>\n    </ram:ApplicableHeaderTradeAgreement>\n    <ram:ApplicableHeaderTradeDelivery/>\n    <ram:ApplicableHeaderTradeSettlement>\n      <ram:InvoiceCurrencyCode>EUR</ram:InvoiceCurrencyCode>\n      <ram:ApplicableTradeTax>\n        <ram:CalculatedAmount>0.48</ram:CalculatedAmount>\n        <ram:TypeCode>VAT</ram:TypeCode>\n        <ram:BasisAmount>2.52</ram:BasisAmount>\n        <ram:CategoryCode>S</ram:CategoryCode>\n        <ram:RateApplicablePercent>19.00</ram:RateApplicablePercent>\n      </ram:ApplicableTradeTax>\n      <ram:BillingSpecifiedPeriod>\n        <ram:StartDateTime>\n          <udt:DateTimeString format="102">20260115</udt:DateTimeString>\n        </ram:StartDateTime>\n        <ram:EndDateTime>\n          <udt:DateTimeString format="102">20260215</udt:DateTimeString>\n        </ram:EndDateTime>\n      </ram:BillingSpecifiedPeriod>\n      <ram:SpecifiedTradePaymentTerms>\n        <ram:Description>Bereits erstattet.</ram:Description>\n        <ram:DueDateDateTime>\n          <udt:DateTimeString format="102">20260115</udt:DateTimeString>\n        </ram:DueDateDateTime>\n      </ram:SpecifiedTradePaymentTerms>\n      <ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n        <ram:LineTotalAmount>2.52</ram:LineTotalAmount>\n        <ram:ChargeTotalAmount>0.00</ram:ChargeTotalAmount>\n        <ram:AllowanceTotalAmount>0.00</ram:AllowanceTotalAmount>\n        <ram:TaxBasisTotalAmount>2.52</ram:TaxBasisTotalAmount>\n        <ram:TaxTotalAmount currencyID="EUR">0.48</ram:TaxTotalAmount>\n        <ram:GrandTotalAmount>3.00</ram:GrandTotalAmount>\n        <ram:TotalPrepaidAmount>3.00</ram:TotalPrepaidAmount>\n        <ram:DuePayableAmount>0.00</ram:DuePayableAmount>\n      </ram:SpecifiedTradeSettlementHeaderMonetarySummation>\n      <ram:InvoiceReferencedDocument>\n        <ram:IssuerAssignedID>TEST-DOC-2026-00000001</ram:IssuerAssignedID>\n        <ram:FormattedIssueDateTime>\n          <qdt:DateTimeString format="102">20260115</qdt:DateTimeString>\n        </ram:FormattedIssueDateTime>\n      </ram:InvoiceReferencedDocument>\n    </ram:ApplicableHeaderTradeSettlement>\n  </rsm:SupplyChainTradeTransaction>\n</rsm:CrossIndustryInvoice>\n	43300bf4-d4e0-48f6-b6bf-456e35a0c6ef	79337465-f018-4cdb-a6cd-1d7479a06011
\.


--
-- Data for Name: LocalCustomer; Type: TABLE DATA; Schema: org.vernac.saas.payments.local; Owner: -
--

COPY "org.vernac.saas.payments.local"."LocalCustomer" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, mandate) FROM stdin;
2026-01-15 13:00:30+01	0	727	2026-11-16 18:20:17.897729+01	2	9f9aece2-f3ce-4531-b373-4ca63df85a49	VALID
2026-01-15 13:02:30+01	0	0	2026-01-15 13:02:30+01	1	825f112a-2b92-4aac-a1f6-18666e3b0c2e	ABSENT
\.


--
-- Data for Name: LocalPayment; Type: TABLE DATA; Schema: org.vernac.saas.payments.local; Owner: -
--

COPY "org.vernac.saas.payments.local"."LocalPayment" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", amount, checkout, cursor, customer, "customerOperation", id, kind, mandate, refunded, status, "@nanoRemainder:postedAt", "postedAt") FROM stdin;
2026-01-15 13:01:00+01	0	552	2026-11-16 18:20:24.064781+01	3	1200	https://localhost:8443/billing	1	localc_9f9aece2f3ce4531b3734ca63df85a49	9f9aece2-f3ce-4531-b373-4ca63df85a49	833dd900-9c84-4a2f-876b-22943e5b421d	CHECKOUT	localm_9f9aece2f3ce4531b3734ca63df85a49	300	PAID	0	2026-01-15 13:01:30+01
2026-01-15 13:03:00+01	0	849	2026-11-16 18:20:25.087352+01	2	1200	https://localhost:8443/billing	1	localc_825f112a2b924aaca1f618666e3b0c2e	825f112a-2b92-4aac-a1f6-18666e3b0c2e	159e8a10-536b-43de-b8b4-56d871ee30b4	CHECKOUT	\N	0	PENDING	\N	\N
\.


--
-- Data for Name: LocalRefund; Type: TABLE DATA; Schema: org.vernac.saas.payments.local; Owner: -
--

COPY "org.vernac.saas.payments.local"."LocalRefund" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", amount, id, "paymentOperation") FROM stdin;
2026-01-15 13:02:00+01	0	0	2026-01-15 13:02:00+01	1	300	4da1fc57-0721-47d5-bbe9-69c18f6c287b	833dd900-9c84-4a2f-876b-22943e5b421d
\.


--
-- Data for Name: BillingAgreement; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."BillingAgreement" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:nextBillingAt", "@updatedAt", "@version", account, customer, id, mandate, "nextBillingAt", profile) FROM stdin;
2026-01-15 13:02:00+01	0	0	0	2026-01-15 13:02:00+01	1	101aa119-1a80-45f8-8631-85a7d2c886d8	localc_9f9aece2f3ce4531b3734ca63df85a49	cb7afcaa-b40e-41e4-bbef-4095f2d8e948	localm_9f9aece2f3ce4531b3734ca63df85a49	2026-02-15 13:00:00+01	local-v1
\.


--
-- Data for Name: PaymentAttempt; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."PaymentAttempt" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:due", "@nanoRemainder:financialStarted", "@nanoRemainder:lease.until", "@updatedAt", "@version", account, calls, checkout, customer, "customerOperation", due, "financialStarted", id, key, kind, "lease.token", "lease.until", mandate, operation, "order", profile, reason, refunded, remote, stage, state, "@nanoRemainder:postedAt", "postedAt") FROM stdin;
2026-11-16 18:20:17.617519+01	143	906	0	0	\N	2026-11-16 18:20:24.100193+01	12	101aa119-1a80-45f8-8631-85a7d2c886d8	2	https://localhost:8443/billing	localc_9f9aece2f3ce4531b3734ca63df85a49	9f9aece2-f3ce-4531-b373-4ca63df85a49	2026-01-15 13:02:30+01	2026-01-15 13:01:00+01	ac5a63a1-791b-43ab-a620-5ab411026571	65d8b4e7-fe62-49c1-882b-2defdde2b771/1	FIRST	\N	\N	localm_9f9aece2f3ce4531b3734ca63df85a49	833dd900-9c84-4a2f-876b-22943e5b421d	65d8b4e7-fe62-49c1-882b-2defdde2b771	local-v1	NONE	300	localp_833dd9009c844a2f876b22943e5b421d	DONE	PAID	0	2026-01-15 13:01:30+01
2026-11-16 18:20:24.959648+01	418	935	0	0	\N	2026-11-16 18:20:25.091103+01	9	0508fb2b-57c8-4441-9697-17231605d935	2	https://localhost:8443/billing	localc_825f112a2b924aaca1f618666e3b0c2e	825f112a-2b92-4aac-a1f6-18666e3b0c2e	2026-01-15 13:04:00+01	2026-01-15 13:03:00+01	9b545242-b00f-488e-87f0-39b5490d2884	80dfb6ff-d64f-46c5-a0f7-cd14ffc582b4/1	FIRST	\N	\N	\N	159e8a10-536b-43de-b8b4-56d871ee30b4	80dfb6ff-d64f-46c5-a0f7-cd14ffc582b4	local-v1	NONE	0	localp_159e8a10536b43deb8b456d871ee30b4	WAITING	PENDING	\N	\N
\.


--
-- Data for Name: PaymentOrder; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."PaymentOrder" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:period.end", "@nanoRemainder:period.start", "@updatedAt", "@version", account, attempts, cycle, id, "paidAttempt", "period.end", "period.start", reason, request, status, subscription, "terms.benefits", "terms.currency", "terms.interval", "terms.plan", "terms.price", "terms.revision") FROM stdin;
2026-11-16 18:20:17.611786+01	639	497	0	0	2026-11-16 18:20:21.422997+01	5	101aa119-1a80-45f8-8631-85a7d2c886d8	1	101aa119-1a80-45f8-8631-85a7d2c886d8/cb7afcaa-b40e-41e4-bbef-4095f2d8e948/ac2bb6852eac8dddb47a715e4e04646289ac7e01c8bdc82d2c125132dd558d2f	65d8b4e7-fe62-49c1-882b-2defdde2b771	ac5a63a1-791b-43ab-a620-5ab411026571	2026-02-15 13:00:00+01	2026-01-15 13:00:00+01	NONE	101aa119-1a80-45f8-8631-85a7d2c886d8/0d35a500-c842-420d-ab2a-e196e68d6661	APPLIED	cb7afcaa-b40e-41e4-bbef-4095f2d8e948	document-characters=10000,documents=100,monthly-exports=100	EUR	MONTHLY	pro	1200	v1
2026-11-16 18:20:24.958246+01	496	725	0	0	2026-11-16 18:20:24.958885+01	2	0508fb2b-57c8-4441-9697-17231605d935	1	0508fb2b-57c8-4441-9697-17231605d935/2b2ffe8a-266c-4750-a746-8f2ce836d0f4/5b32ad09490170b1c410b9a317e2def6f5e09ccf6634524c34f476de8c574f31	80dfb6ff-d64f-46c5-a0f7-cd14ffc582b4	\N	2026-02-15 13:02:00+01	2026-01-15 13:02:00+01	NONE	0508fb2b-57c8-4441-9697-17231605d935/29c80f12-7fef-47ad-b52e-d6a119a55bc0	OPEN	2b2ffe8a-266c-4750-a746-8f2ce836d0f4	document-characters=10000,documents=100,monthly-exports=100	EUR	MONTHLY	pro	1200	v1
\.


--
-- Data for Name: PaymentRefund; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."PaymentRefund" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:due", "@nanoRemainder:financialStarted", "@nanoRemainder:lease.until", "@updatedAt", "@version", account, amount, attempt, calls, due, "financialStarted", id, "lease.token", "lease.until", operation, reason, remote, state) FROM stdin;
2026-11-16 18:20:24.045038+01	522	937	0	0	\N	2026-11-16 18:20:24.100939+01	5	101aa119-1a80-45f8-8631-85a7d2c886d8	300	ac5a63a1-791b-43ab-a620-5ab411026571	1	2026-01-15 13:02:00+01	2026-01-15 13:02:00+01	096d44e9-ebbe-461e-9da4-93c6f7035eaf	\N	\N	4da1fc57-0721-47d5-bbe9-69c18f6c287b	NONE	localr_4da1fc57072147d5bbe969c18f6c287b	REFUNDED
\.


--
-- Data for Name: ProviderRegistration; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."ProviderRegistration" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", environment, id, key, kind) FROM stdin;
2026-11-16 18:20:10.303802+01	853	853	2026-11-16 18:20:10.303802+01	1	SIMULATED	4653722b-2aca-4f57-a386-86156c4d656d	local-v1	LOCAL
\.


--
-- Data for Name: RegisteredPaymentResource; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."RegisteredPaymentResource" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", attempt, id, profile, remote, route) FROM stdin;
2026-11-16 18:20:17.816302+01	457	457	2026-11-16 18:20:17.816302+01	1	ac5a63a1-791b-43ab-a620-5ab411026571	bf748a38-1f3e-4e5e-9a97-0adb2477b83f	local-v1	localp_833dd9009c844a2f876b22943e5b421d	local-v1/localp_833dd9009c844a2f876b22943e5b421d
2026-11-16 18:20:25.049635+01	879	879	2026-11-16 18:20:25.049635+01	1	9b545242-b00f-488e-87f0-39b5490d2884	6e376684-3084-43af-805d-0f99fe4de988	local-v1	localp_159e8a10536b43deb8b456d871ee30b4	local-v1/localp_159e8a10536b43deb8b456d871ee30b4
\.


--
-- Data for Name: RegisteredRefundResource; Type: TABLE DATA; Schema: org.vernac.saas.payments.persistence; Owner: -
--

COPY "org.vernac.saas.payments.persistence"."RegisteredRefundResource" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@updatedAt", "@version", id, refund, route) FROM stdin;
2026-11-16 18:20:24.070435+01	199	199	2026-11-16 18:20:24.070435+01	1	a7a10a75-c71d-4f3c-9607-4ff48a5c8437	096d44e9-ebbe-461e-9da4-93c6f7035eaf	local-v1/localr_4da1fc57072147d5bbe969c18f6c287b
\.


--
-- Data for Name: QuotaBudget; Type: TABLE DATA; Schema: org.vernac.saas.restrictions; Owner: -
--

COPY "org.vernac.saas.restrictions"."QuotaBudget" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:window.end", "@nanoRemainder:window.start", "@updatedAt", "@version", account, benefit, consumed, id, key, reserved, "window.end", "window.start") FROM stdin;
2026-11-16 18:20:22.261147+01	655	790	0	0	2026-11-16 18:20:22.309061+01	2	2eaaa406-9407-471a-b05e-6074f63b8f09	monthly-exports	2	e4650d24-fc37-448b-b205-26234b95e361	2eaaa406-9407-471a-b05e-6074f63b8f09/monthly-exports/8364982cde157c40c925ee099c26a56305fd4be309f1465905aa427b60ade024	0	2026-02-01 01:00:00+01	2026-01-01 01:00:00+01
\.


--
-- Data for Name: QuotaReservation; Type: TABLE DATA; Schema: org.vernac.saas.restrictions; Owner: -
--

COPY "org.vernac.saas.restrictions"."QuotaReservation" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:window.end", "@nanoRemainder:window.start", "@updatedAt", "@version", account, amount, benefit, budget, id, key, status, "window.end", "window.start") FROM stdin;
\.


--
-- Data for Name: Subscription; Type: TABLE DATA; Schema: org.vernac.saas.subscriptions; Owner: -
--

COPY "org.vernac.saas.subscriptions"."Subscription" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:period.end", "@nanoRemainder:period.start", "@updatedAt", "@version", account, id, "period.end", "period.start", renewal, "renewalTerms.benefits", "renewalTerms.currency", "renewalTerms.interval", "renewalTerms.plan", "renewalTerms.price", "renewalTerms.revision", status, "terms.benefits", "terms.currency", "terms.interval", "terms.plan", "terms.price", "terms.revision") FROM stdin;
2026-11-16 18:20:15.330394+01	110	108	0	0	2026-11-16 18:20:15.330546+01	1	2eaaa406-9407-471a-b05e-6074f63b8f09	a1368d88-e251-4738-aadf-0cbe40a2841c	9999-01-01 01:00:00+01	2026-01-15 13:00:00+01	ENABLED	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1	ACTIVE	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1
2026-11-16 18:20:17.298007+01	21	973	0	0	2026-11-16 18:20:17.298074+01	1	0508fb2b-57c8-4441-9697-17231605d935	2b2ffe8a-266c-4750-a746-8f2ce836d0f4	9999-01-01 01:00:00+01	2026-01-15 13:00:00+01	ENABLED	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1	ACTIVE	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1
2026-11-16 18:20:22.56438+01	923	202	0	0	2026-11-16 18:20:22.56448+01	1	a6b56c9f-3b87-4cfb-9650-4d1ea626472e	557a43ae-ea4a-43f4-be83-9510ef22787f	9999-01-01 01:00:00+01	2026-01-15 13:02:00+01	ENABLED	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1	ACTIVE	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1
2026-11-16 18:20:13.604224+01	370	621	0	0	2026-11-16 18:20:24.712805+01	3	101aa119-1a80-45f8-8631-85a7d2c886d8	cb7afcaa-b40e-41e4-bbef-4095f2d8e948	2026-02-15 13:00:00+01	2026-01-15 13:00:00+01	ENABLED	document-characters=1000,documents=3,monthly-exports=2	EUR	MONTHLY	free	0	v1	ACTIVE	document-characters=10000,documents=100,monthly-exports=100	EUR	MONTHLY	pro	1200	v1
\.


--
-- Data for Name: SubscriptionSettlement; Type: TABLE DATA; Schema: org.vernac.saas.subscriptions; Owner: -
--

COPY "org.vernac.saas.subscriptions"."SubscriptionSettlement" ("@createdAt", "@nanoRemainder:@createdAt", "@nanoRemainder:@updatedAt", "@nanoRemainder:period.end", "@nanoRemainder:period.start", "@updatedAt", "@version", id, operation, "period.end", "period.start", subscription, "terms.benefits", "terms.currency", "terms.interval", "terms.plan", "terms.price", "terms.revision", status) FROM stdin;
2026-11-16 18:20:21.416948+01	46	46	0	0	2026-11-16 18:20:21.416948+01	1	b9fdc624-9989-4a25-a158-e1a820fbf72b	payment-order/65d8b4e7-fe62-49c1-882b-2defdde2b771	2026-02-15 13:00:00+01	2026-01-15 13:00:00+01	cb7afcaa-b40e-41e4-bbef-4095f2d8e948	document-characters=10000,documents=100,monthly-exports=100	EUR	MONTHLY	pro	1200	v1	SETTLED
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: public; Owner: -
--

COPY public.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
1	20261115153000000	identity sessions	SQL	V20261115153000000__identity_sessions.sql	1998883064	vernac	2026-11-16 18:20:09.5284	6	t
2	20261116010000000	initial documents	SQL	V20261116010000000__initial_documents.sql	-844240011	vernac	2026-11-16 18:20:09.541449	4	t
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: vernac_module_02461e43c5c7661d9593ea86c5b38038; Owner: -
--

COPY vernac_module_02461e43c5c7661d9593ea86c5b38038.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"vernac_module_02461e43c5c7661d9593ea86c5b38038"	\N	vernac	2026-11-16 18:20:09.169587	0	t
1	20261115213000000	initial payment processing	SQL	V20261115213000000__initial_payment_processing.sql	-1931287812	vernac	2026-11-16 18:20:09.177629	38	t
2	20261115223000000	payment reversal	SQL	V20261115223000000__payment_reversal.sql	-1931338057	vernac	2026-11-16 18:20:09.225231	2	t
3	20261115224500000	refund resource registry	SQL	V20261115224500000__refund_resource_registry.sql	1327872095	vernac	2026-11-16 18:20:09.232613	5	t
4	20261115230000000	provider posting time	SQL	V20261115230000000__provider_posting_time.sql	690788171	vernac	2026-11-16 18:20:09.24215	2	t
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: vernac_module_223223bacb1f2ab497f2765c2eb0f1e8; Owner: -
--

COPY vernac_module_223223bacb1f2ab497f2765c2eb0f1e8.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"vernac_module_223223bacb1f2ab497f2765c2eb0f1e8"	\N	vernac	2026-11-16 18:20:09.060675	0	t
1	20261115233000000	initial invoicing	SQL	V20261115233000000__initial_invoicing.sql	615389890	vernac	2026-11-16 18:20:09.070503	22	t
2	20261115234500000	invoice mail notices	SQL	V20261115234500000__invoice_mail_notices.sql	-1432837210	vernac	2026-11-16 18:20:09.103847	7	t
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: vernac_module_6064720f0e9bcf7784a9cd54030bbfb2; Owner: -
--

COPY vernac_module_6064720f0e9bcf7784a9cd54030bbfb2.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"vernac_module_6064720f0e9bcf7784a9cd54030bbfb2"	\N	vernac	2026-11-16 18:20:08.776682	0	t
1	20261112150000000	initial identity state	SQL	V20261112150000000__initial_identity_state.sql	354121849	vernac	2026-11-16 18:20:08.79947	12	t
2	20261112195431850	identity capacity ledger	SQL	V20261112195431850__identity_capacity_ledger.sql	-1450470639	vernac	2026-11-16 18:20:08.832151	3	t
3	20261112210742206	identity one time challenges	SQL	V20261112210742206__identity_one_time_challenges.sql	2019547965	vernac	2026-11-16 18:20:08.843825	6	t
4	20261112213009661	identity global admission	SQL	V20261112213009661__identity_global_admission.sql	-2140707889	vernac	2026-11-16 18:20:08.859455	4	t
5	20261112232415175	identity credentials and security epoch	SQL	V20261112232415175__identity_credentials_and_security_epoch.sql	1980490374	vernac	2026-11-16 18:20:08.871299	5	t
6	20261113081431590	identity keyed admission	SQL	V20261113081431590__identity_keyed_admission.sql	792258887	vernac	2026-11-16 18:20:08.882696	9	t
7	20261113090127123	identity pending registration lifetime	SQL	V20261113090127123__identity_pending_registration_lifetime.sql	1765931226	vernac	2026-11-16 18:20:08.898742	3	t
8	20261113112251688	identity login address ownership	SQL	V20261113112251688__identity_login_address_ownership.sql	1774698071	vernac	2026-11-16 18:20:08.90811	5	t
9	20261113185958756	identity encryption admission	SQL	V20261113185958756__identity_encryption_admission.sql	1859250529	vernac	2026-11-16 18:20:08.91851	6	t
10	20261113191127959	identity protected mail jobs	SQL	V20261113191127959__identity_protected_mail_jobs.sql	1331484428	vernac	2026-11-16 18:20:08.931106	6	t
11	20261113192804743	identity mail worker cursors	SQL	V20261113192804743__identity_mail_worker_cursors.sql	1642011739	vernac	2026-11-16 18:20:08.944388	3	t
12	20261115135135914	account access metadata	SQL	V20261115135135914__account_access_metadata.sql	670402228	vernac	2026-11-16 18:20:08.952503	13	t
13	20261115142559842	account invitations	SQL	V20261115142559842__account_invitations.sql	1707125731	vernac	2026-11-16 18:20:08.971979	7	t
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6; Owner: -
--

COPY vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6"	\N	vernac	2026-11-16 18:20:09.381693	0	t
1	20261115203000000	initial restrictions	SQL	V20261115203000000__initial_restrictions.sql	1442562330	vernac	2026-11-16 18:20:09.389177	10	t
2	20261115204500000	reservation dispatch	SQL	V20261115204500000__reservation_dispatch.sql	-1196893332	vernac	2026-11-16 18:20:09.404498	1	t
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: vernac_module_b274b5f08da39dfeb273e3b9cf17453c; Owner: -
--

COPY vernac_module_b274b5f08da39dfeb273e3b9cf17453c.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"vernac_module_b274b5f08da39dfeb273e3b9cf17453c"	\N	vernac	2026-11-16 18:20:09.292748	0	t
1	20261115210000000	initial local payments	SQL	V20261115210000000__initial_local_payments.sql	1453345205	vernac	2026-11-16 18:20:09.3006	9	t
2	20261115230000000	provider posting time	SQL	V20261115230000000__provider_posting_time.sql	1678819255	vernac	2026-11-16 18:20:09.315513	3	t
\.


--
-- Data for Name: flyway_schema_history; Type: TABLE DATA; Schema: vernac_module_f6523acb5b7e870448bad5ceed3dddb3; Owner: -
--

COPY vernac_module_f6523acb5b7e870448bad5ceed3dddb3.flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, installed_on, execution_time, success) FROM stdin;
0	\N	<< Flyway Schema Creation >>	SCHEMA	"vernac_module_f6523acb5b7e870448bad5ceed3dddb3"	\N	vernac	2026-11-16 18:20:09.452809	0	t
1	20261115193000000	initial subscriptions	SQL	V20261115193000000__initial_subscriptions.sql	1222623923	vernac	2026-11-16 18:20:09.459445	11	t
2	20261115221500000	settlement reversal	SQL	V20261115221500000__settlement_reversal.sql	-393630987	vernac	2026-11-16 18:20:09.477231	1	t
\.


--
-- Name: Document @pk:Document; Type: CONSTRAINT; Schema: example.documentworkspace; Owner: -
--

ALTER TABLE ONLY "example.documentworkspace"."Document"
    ADD CONSTRAINT "@pk:Document" PRIMARY KEY (id);


--
-- Name: Document @unique:tenant:Document; Type: CONSTRAINT; Schema: example.documentworkspace; Owner: -
--

ALTER TABLE ONLY "example.documentworkspace"."Document"
    ADD CONSTRAINT "@unique:tenant:Document" UNIQUE ("@tenantId", id);


--
-- Name: spring_session_attributes spring_session_attributes_pkey; Type: CONSTRAINT; Schema: identity_sessions; Owner: -
--

ALTER TABLE ONLY identity_sessions.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_pkey PRIMARY KEY (session_primary_id, attribute_name);


--
-- Name: spring_session spring_session_pkey; Type: CONSTRAINT; Schema: identity_sessions; Owner: -
--

ALTER TABLE ONLY identity_sessions.spring_session
    ADD CONSTRAINT spring_session_pkey PRIMARY KEY (primary_id);


--
-- Name: Account @pk:Account; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."Account"
    ADD CONSTRAINT "@pk:Account" PRIMARY KEY (id);


--
-- Name: AccountInvitation @pk:AccountInvitation; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountInvitation"
    ADD CONSTRAINT "@pk:AccountInvitation" PRIMARY KEY (id);


--
-- Name: AccountMembership @pk:AccountMembership; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountMembership"
    ADD CONSTRAINT "@pk:AccountMembership" PRIMARY KEY (id);


--
-- Name: Identity @pk:Identity; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."Identity"
    ADD CONSTRAINT "@pk:Identity" PRIMARY KEY (id);


--
-- Name: IdentityAdmissionConfiguration @pk:IdentityAdmissionConfiguration; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityAdmissionConfiguration"
    ADD CONSTRAINT "@pk:IdentityAdmissionConfiguration" PRIMARY KEY (id);


--
-- Name: IdentityAdmissionKey @pk:IdentityAdmissionKey; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityAdmissionKey"
    ADD CONSTRAINT "@pk:IdentityAdmissionKey" PRIMARY KEY (id);


--
-- Name: IdentityCapacity @pk:IdentityCapacity; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityCapacity"
    ADD CONSTRAINT "@pk:IdentityCapacity" PRIMARY KEY (id);


--
-- Name: IdentityChallenge @pk:IdentityChallenge; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityChallenge"
    ADD CONSTRAINT "@pk:IdentityChallenge" PRIMARY KEY (id);


--
-- Name: IdentityCredential @pk:IdentityCredential; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityCredential"
    ADD CONSTRAINT "@pk:IdentityCredential" PRIMARY KEY (id);


--
-- Name: IdentityEncryptionKey @pk:IdentityEncryptionKey; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityEncryptionKey"
    ADD CONSTRAINT "@pk:IdentityEncryptionKey" PRIMARY KEY (id);


--
-- Name: IdentityEncryptionRegistry @pk:IdentityEncryptionRegistry; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityEncryptionRegistry"
    ADD CONSTRAINT "@pk:IdentityEncryptionRegistry" PRIMARY KEY (id);


--
-- Name: IdentityIngress @pk:IdentityIngress; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityIngress"
    ADD CONSTRAINT "@pk:IdentityIngress" PRIMARY KEY (id);


--
-- Name: IdentityLoginAddress @pk:IdentityLoginAddress; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityLoginAddress"
    ADD CONSTRAINT "@pk:IdentityLoginAddress" PRIMARY KEY (id);


--
-- Name: IdentityMailJob @pk:IdentityMailJob; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityMailJob"
    ADD CONSTRAINT "@pk:IdentityMailJob" PRIMARY KEY (id);


--
-- Name: IdentityMailSweep @pk:IdentityMailSweep; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityMailSweep"
    ADD CONSTRAINT "@pk:IdentityMailSweep" PRIMARY KEY (id);


--
-- Name: IdentityPendingRegistration @pk:IdentityPendingRegistration; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityPendingRegistration"
    ADD CONSTRAINT "@pk:IdentityPendingRegistration" PRIMARY KEY (id);


--
-- Name: PersonalAccount @pk:PersonalAccount; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."PersonalAccount"
    ADD CONSTRAINT "@pk:PersonalAccount" PRIMARY KEY (id);


--
-- Name: Account @unique:Account:tenant; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."Account"
    ADD CONSTRAINT "@unique:Account:tenant" UNIQUE (tenant);


--
-- Name: AccountMembership @unique:AccountMembership:memberKey; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountMembership"
    ADD CONSTRAINT "@unique:AccountMembership:memberKey" UNIQUE ("memberKey");


--
-- Name: IdentityEncryptionKey @unique:IdentityEncryptionKey:fingerprint; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityEncryptionKey"
    ADD CONSTRAINT "@unique:IdentityEncryptionKey:fingerprint" UNIQUE (fingerprint);


--
-- Name: IdentityEncryptionKey @unique:IdentityEncryptionKey:keyName; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityEncryptionKey"
    ADD CONSTRAINT "@unique:IdentityEncryptionKey:keyName" UNIQUE ("keyName");


--
-- Name: IdentityLoginAddress @unique:IdentityLoginAddress:loginKey; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityLoginAddress"
    ADD CONSTRAINT "@unique:IdentityLoginAddress:loginKey" UNIQUE ("loginKey");


--
-- Name: IdentityMailJob @unique:IdentityMailJob:challenge; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."IdentityMailJob"
    ADD CONSTRAINT "@unique:IdentityMailJob:challenge" UNIQUE (challenge);


--
-- Name: PersonalAccount @unique:PersonalAccount:account; Type: CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."PersonalAccount"
    ADD CONSTRAINT "@unique:PersonalAccount:account" UNIQUE (account);


--
-- Name: BillingProfile @pk:BillingProfile; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."BillingProfile"
    ADD CONSTRAINT "@pk:BillingProfile" PRIMARY KEY (id);


--
-- Name: Invoice @pk:Invoice; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."Invoice"
    ADD CONSTRAINT "@pk:Invoice" PRIMARY KEY (id);


--
-- Name: InvoiceNotice @pk:InvoiceNotice; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."InvoiceNotice"
    ADD CONSTRAINT "@pk:InvoiceNotice" PRIMARY KEY (id);


--
-- Name: InvoiceSequence @pk:InvoiceSequence; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."InvoiceSequence"
    ADD CONSTRAINT "@pk:InvoiceSequence" PRIMARY KEY (id);


--
-- Name: IssuedInvoiceDocument @pk:IssuedInvoiceDocument; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."IssuedInvoiceDocument"
    ADD CONSTRAINT "@pk:IssuedInvoiceDocument" PRIMARY KEY (id);


--
-- Name: Invoice @unique:Invoice:number; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."Invoice"
    ADD CONSTRAINT "@unique:Invoice:number" UNIQUE (number);


--
-- Name: Invoice @unique:Invoice:source; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."Invoice"
    ADD CONSTRAINT "@unique:Invoice:source" UNIQUE (source);


--
-- Name: InvoiceNotice @unique:InvoiceNotice:invoice; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."InvoiceNotice"
    ADD CONSTRAINT "@unique:InvoiceNotice:invoice" UNIQUE (invoice);


--
-- Name: InvoiceSequence @unique:InvoiceSequence:key; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."InvoiceSequence"
    ADD CONSTRAINT "@unique:InvoiceSequence:key" UNIQUE (key);


--
-- Name: IssuedInvoiceDocument @unique:IssuedInvoiceDocument:invoice; Type: CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."IssuedInvoiceDocument"
    ADD CONSTRAINT "@unique:IssuedInvoiceDocument:invoice" UNIQUE (invoice);


--
-- Name: LocalCustomer @pk:LocalCustomer; Type: CONSTRAINT; Schema: org.vernac.saas.payments.local; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.local"."LocalCustomer"
    ADD CONSTRAINT "@pk:LocalCustomer" PRIMARY KEY (id);


--
-- Name: LocalPayment @pk:LocalPayment; Type: CONSTRAINT; Schema: org.vernac.saas.payments.local; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.local"."LocalPayment"
    ADD CONSTRAINT "@pk:LocalPayment" PRIMARY KEY (id);


--
-- Name: LocalRefund @pk:LocalRefund; Type: CONSTRAINT; Schema: org.vernac.saas.payments.local; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.local"."LocalRefund"
    ADD CONSTRAINT "@pk:LocalRefund" PRIMARY KEY (id);


--
-- Name: BillingAgreement @pk:BillingAgreement; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."BillingAgreement"
    ADD CONSTRAINT "@pk:BillingAgreement" PRIMARY KEY (id);


--
-- Name: PaymentAttempt @pk:PaymentAttempt; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentAttempt"
    ADD CONSTRAINT "@pk:PaymentAttempt" PRIMARY KEY (id);


--
-- Name: PaymentOrder @pk:PaymentOrder; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentOrder"
    ADD CONSTRAINT "@pk:PaymentOrder" PRIMARY KEY (id);


--
-- Name: PaymentRefund @pk:PaymentRefund; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentRefund"
    ADD CONSTRAINT "@pk:PaymentRefund" PRIMARY KEY (id);


--
-- Name: ProviderRegistration @pk:ProviderRegistration; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."ProviderRegistration"
    ADD CONSTRAINT "@pk:ProviderRegistration" PRIMARY KEY (id);


--
-- Name: RegisteredPaymentResource @pk:RegisteredPaymentResource; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."RegisteredPaymentResource"
    ADD CONSTRAINT "@pk:RegisteredPaymentResource" PRIMARY KEY (id);


--
-- Name: RegisteredRefundResource @pk:RegisteredRefundResource; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."RegisteredRefundResource"
    ADD CONSTRAINT "@pk:RegisteredRefundResource" PRIMARY KEY (id);


--
-- Name: PaymentAttempt @unique:PaymentAttempt:key; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentAttempt"
    ADD CONSTRAINT "@unique:PaymentAttempt:key" UNIQUE (key);


--
-- Name: PaymentAttempt @unique:PaymentAttempt:operation; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentAttempt"
    ADD CONSTRAINT "@unique:PaymentAttempt:operation" UNIQUE (operation);


--
-- Name: PaymentOrder @unique:PaymentOrder:request; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentOrder"
    ADD CONSTRAINT "@unique:PaymentOrder:request" UNIQUE (request);


--
-- Name: PaymentRefund @unique:PaymentRefund:operation; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentRefund"
    ADD CONSTRAINT "@unique:PaymentRefund:operation" UNIQUE (operation);


--
-- Name: ProviderRegistration @unique:ProviderRegistration:key; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."ProviderRegistration"
    ADD CONSTRAINT "@unique:ProviderRegistration:key" UNIQUE (key);


--
-- Name: RegisteredPaymentResource @unique:RegisteredPaymentResource:route; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."RegisteredPaymentResource"
    ADD CONSTRAINT "@unique:RegisteredPaymentResource:route" UNIQUE (route);


--
-- Name: RegisteredRefundResource @unique:RegisteredRefundResource:route; Type: CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."RegisteredRefundResource"
    ADD CONSTRAINT "@unique:RegisteredRefundResource:route" UNIQUE (route);


--
-- Name: QuotaBudget @pk:QuotaBudget; Type: CONSTRAINT; Schema: org.vernac.saas.restrictions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.restrictions"."QuotaBudget"
    ADD CONSTRAINT "@pk:QuotaBudget" PRIMARY KEY (id);


--
-- Name: QuotaReservation @pk:QuotaReservation; Type: CONSTRAINT; Schema: org.vernac.saas.restrictions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.restrictions"."QuotaReservation"
    ADD CONSTRAINT "@pk:QuotaReservation" PRIMARY KEY (id);


--
-- Name: QuotaBudget @unique:QuotaBudget:key; Type: CONSTRAINT; Schema: org.vernac.saas.restrictions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.restrictions"."QuotaBudget"
    ADD CONSTRAINT "@unique:QuotaBudget:key" UNIQUE (key);


--
-- Name: QuotaReservation @unique:QuotaReservation:key; Type: CONSTRAINT; Schema: org.vernac.saas.restrictions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.restrictions"."QuotaReservation"
    ADD CONSTRAINT "@unique:QuotaReservation:key" UNIQUE (key);


--
-- Name: Subscription @pk:Subscription; Type: CONSTRAINT; Schema: org.vernac.saas.subscriptions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.subscriptions"."Subscription"
    ADD CONSTRAINT "@pk:Subscription" PRIMARY KEY (id);


--
-- Name: SubscriptionSettlement @pk:SubscriptionSettlement; Type: CONSTRAINT; Schema: org.vernac.saas.subscriptions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.subscriptions"."SubscriptionSettlement"
    ADD CONSTRAINT "@pk:SubscriptionSettlement" PRIMARY KEY (id);


--
-- Name: Subscription @unique:Subscription:account; Type: CONSTRAINT; Schema: org.vernac.saas.subscriptions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.subscriptions"."Subscription"
    ADD CONSTRAINT "@unique:Subscription:account" UNIQUE (account);


--
-- Name: SubscriptionSettlement @unique:SubscriptionSettlement:operation; Type: CONSTRAINT; Schema: org.vernac.saas.subscriptions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.subscriptions"."SubscriptionSettlement"
    ADD CONSTRAINT "@unique:SubscriptionSettlement:operation" UNIQUE (operation);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: vernac_module_02461e43c5c7661d9593ea86c5b38038; Owner: -
--

ALTER TABLE ONLY vernac_module_02461e43c5c7661d9593ea86c5b38038.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: vernac_module_223223bacb1f2ab497f2765c2eb0f1e8; Owner: -
--

ALTER TABLE ONLY vernac_module_223223bacb1f2ab497f2765c2eb0f1e8.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: vernac_module_6064720f0e9bcf7784a9cd54030bbfb2; Owner: -
--

ALTER TABLE ONLY vernac_module_6064720f0e9bcf7784a9cd54030bbfb2.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6; Owner: -
--

ALTER TABLE ONLY vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: vernac_module_b274b5f08da39dfeb273e3b9cf17453c; Owner: -
--

ALTER TABLE ONLY vernac_module_b274b5f08da39dfeb273e3b9cf17453c.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: flyway_schema_history flyway_schema_history_pk; Type: CONSTRAINT; Schema: vernac_module_f6523acb5b7e870448bad5ceed3dddb3; Owner: -
--

ALTER TABLE ONLY vernac_module_f6523acb5b7e870448bad5ceed3dddb3.flyway_schema_history
    ADD CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank);


--
-- Name: spring_session_ix1; Type: INDEX; Schema: identity_sessions; Owner: -
--

CREATE UNIQUE INDEX spring_session_ix1 ON identity_sessions.spring_session USING btree (session_id);


--
-- Name: spring_session_ix2; Type: INDEX; Schema: identity_sessions; Owner: -
--

CREATE INDEX spring_session_ix2 ON identity_sessions.spring_session USING btree (expiry_time);


--
-- Name: spring_session_ix3; Type: INDEX; Schema: identity_sessions; Owner: -
--

CREATE INDEX spring_session_ix3 ON identity_sessions.spring_session USING btree (principal_name);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON public.flyway_schema_history USING btree (success);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: vernac_module_02461e43c5c7661d9593ea86c5b38038; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON vernac_module_02461e43c5c7661d9593ea86c5b38038.flyway_schema_history USING btree (success);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: vernac_module_223223bacb1f2ab497f2765c2eb0f1e8; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON vernac_module_223223bacb1f2ab497f2765c2eb0f1e8.flyway_schema_history USING btree (success);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: vernac_module_6064720f0e9bcf7784a9cd54030bbfb2; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON vernac_module_6064720f0e9bcf7784a9cd54030bbfb2.flyway_schema_history USING btree (success);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON vernac_module_8c0aa620e8c239e028dbfab5a4bd42d6.flyway_schema_history USING btree (success);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: vernac_module_b274b5f08da39dfeb273e3b9cf17453c; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON vernac_module_b274b5f08da39dfeb273e3b9cf17453c.flyway_schema_history USING btree (success);


--
-- Name: flyway_schema_history_s_idx; Type: INDEX; Schema: vernac_module_f6523acb5b7e870448bad5ceed3dddb3; Owner: -
--

CREATE INDEX flyway_schema_history_s_idx ON vernac_module_f6523acb5b7e870448bad5ceed3dddb3.flyway_schema_history USING btree (success);


--
-- Name: spring_session_attributes spring_session_attributes_session_primary_id_fkey; Type: FK CONSTRAINT; Schema: identity_sessions; Owner: -
--

ALTER TABLE ONLY identity_sessions.spring_session_attributes
    ADD CONSTRAINT spring_session_attributes_session_primary_id_fkey FOREIGN KEY (session_primary_id) REFERENCES identity_sessions.spring_session(primary_id) ON DELETE CASCADE;


--
-- Name: AccountInvitation @fk:reference:account; Type: FK CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountInvitation"
    ADD CONSTRAINT "@fk:reference:account" FOREIGN KEY (account) REFERENCES "org.vernac.saas.identity.persistence"."Account"(id);


--
-- Name: AccountMembership @fk:reference:account; Type: FK CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountMembership"
    ADD CONSTRAINT "@fk:reference:account" FOREIGN KEY (account) REFERENCES "org.vernac.saas.identity.persistence"."Account"(id);


--
-- Name: PersonalAccount @fk:reference:account; Type: FK CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."PersonalAccount"
    ADD CONSTRAINT "@fk:reference:account" FOREIGN KEY (account) REFERENCES "org.vernac.saas.identity.persistence"."Account"(id);


--
-- Name: AccountInvitation @fk:reference:issuer; Type: FK CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountInvitation"
    ADD CONSTRAINT "@fk:reference:issuer" FOREIGN KEY (issuer) REFERENCES "org.vernac.saas.identity.persistence"."Identity"(id);


--
-- Name: AccountMembership @fk:reference:subject; Type: FK CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."AccountMembership"
    ADD CONSTRAINT "@fk:reference:subject" FOREIGN KEY (subject) REFERENCES "org.vernac.saas.identity.persistence"."Identity"(id);


--
-- Name: PersonalAccount @fk:reference:subject; Type: FK CONSTRAINT; Schema: org.vernac.saas.identity.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.identity.persistence"."PersonalAccount"
    ADD CONSTRAINT "@fk:reference:subject" FOREIGN KEY (subject) REFERENCES "org.vernac.saas.identity.persistence"."Identity"(id);


--
-- Name: InvoiceNotice @fk:reference:invoice; Type: FK CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."InvoiceNotice"
    ADD CONSTRAINT "@fk:reference:invoice" FOREIGN KEY (invoice) REFERENCES "org.vernac.saas.invoicing"."Invoice"(id);


--
-- Name: IssuedInvoiceDocument @fk:reference:invoice; Type: FK CONSTRAINT; Schema: org.vernac.saas.invoicing; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.invoicing"."IssuedInvoiceDocument"
    ADD CONSTRAINT "@fk:reference:invoice" FOREIGN KEY (invoice) REFERENCES "org.vernac.saas.invoicing"."Invoice"(id);


--
-- Name: LocalPayment @fk:reference:customerOperation; Type: FK CONSTRAINT; Schema: org.vernac.saas.payments.local; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.local"."LocalPayment"
    ADD CONSTRAINT "@fk:reference:customerOperation" FOREIGN KEY ("customerOperation") REFERENCES "org.vernac.saas.payments.local"."LocalCustomer"(id);


--
-- Name: LocalRefund @fk:reference:paymentOperation; Type: FK CONSTRAINT; Schema: org.vernac.saas.payments.local; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.local"."LocalRefund"
    ADD CONSTRAINT "@fk:reference:paymentOperation" FOREIGN KEY ("paymentOperation") REFERENCES "org.vernac.saas.payments.local"."LocalPayment"(id);


--
-- Name: PaymentRefund @fk:reference:attempt; Type: FK CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentRefund"
    ADD CONSTRAINT "@fk:reference:attempt" FOREIGN KEY (attempt) REFERENCES "org.vernac.saas.payments.persistence"."PaymentAttempt"(id);


--
-- Name: RegisteredPaymentResource @fk:reference:attempt; Type: FK CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."RegisteredPaymentResource"
    ADD CONSTRAINT "@fk:reference:attempt" FOREIGN KEY (attempt) REFERENCES "org.vernac.saas.payments.persistence"."PaymentAttempt"(id);


--
-- Name: PaymentAttempt @fk:reference:order; Type: FK CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."PaymentAttempt"
    ADD CONSTRAINT "@fk:reference:order" FOREIGN KEY ("order") REFERENCES "org.vernac.saas.payments.persistence"."PaymentOrder"(id);


--
-- Name: RegisteredRefundResource @fk:reference:refund; Type: FK CONSTRAINT; Schema: org.vernac.saas.payments.persistence; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.payments.persistence"."RegisteredRefundResource"
    ADD CONSTRAINT "@fk:reference:refund" FOREIGN KEY (refund) REFERENCES "org.vernac.saas.payments.persistence"."PaymentRefund"(id);


--
-- Name: QuotaReservation @fk:reference:budget; Type: FK CONSTRAINT; Schema: org.vernac.saas.restrictions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.restrictions"."QuotaReservation"
    ADD CONSTRAINT "@fk:reference:budget" FOREIGN KEY (budget) REFERENCES "org.vernac.saas.restrictions"."QuotaBudget"(id);


--
-- Name: SubscriptionSettlement @fk:reference:subscription; Type: FK CONSTRAINT; Schema: org.vernac.saas.subscriptions; Owner: -
--

ALTER TABLE ONLY "org.vernac.saas.subscriptions"."SubscriptionSettlement"
    ADD CONSTRAINT "@fk:reference:subscription" FOREIGN KEY (subscription) REFERENCES "org.vernac.saas.subscriptions"."Subscription"(id);


--
-- Name: Document @tenant; Type: POLICY; Schema: example.documentworkspace; Owner: -
--

CREATE POLICY "@tenant" ON "example.documentworkspace"."Document" USING (("@tenantId" = (NULLIF(current_setting('vernac.tenant_id'::text, true), ''::text))::uuid)) WITH CHECK (("@tenantId" = (NULLIF(current_setting('vernac.tenant_id'::text, true), ''::text))::uuid));


--
-- Name: Document; Type: ROW SECURITY; Schema: example.documentworkspace; Owner: -
--

ALTER TABLE "example.documentworkspace"."Document" ENABLE ROW LEVEL SECURITY;

--
-- PostgreSQL database dump complete
--

\unrestrict 4Cct6haiZdkyfue9On95Hd39AdrqybKJhhbSSyQPEyMY2mLaSRiyjH4Iy431o2S

