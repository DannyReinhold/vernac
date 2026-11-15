#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
"""Disposable PostgreSQL + HTTPS + Chromium acceptance; no external delivery."""
import base64, json, os, pathlib, re, secrets, subprocess, tempfile, time, urllib.request, ssl
ROOT=pathlib.Path(__file__).resolve().parent
admin=os.environ["VERNAC_PG_TEST_URL"].removeprefix("jdbc:")
db="browser_identity_"+secrets.token_hex(8)
role="browser_runtime_"+secrets.token_hex(8)
password=secrets.token_urlsafe(32)
credentials=dict(os.environ,PGPASSWORD=os.environ["VERNAC_PG_TEST_PASSWORD"])
def sql(database,statement):
    subprocess.run(["psql","-X","-q","-v","ON_ERROR_STOP=1","-d",database,"-U",os.environ["VERNAC_PG_TEST_USER"],"-c",statement],env=credentials,check=True,stdout=subprocess.DEVNULL)
url=admin.rsplit("/",1)[0]+"/"+db
process=None
try:
    sql(admin,'CREATE DATABASE "'+db+'"')
    sql(admin,'CREATE ROLE "'+role+'" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD \''+password+"'")
    for schema in ("org.vernac.saas.identity.persistence","identity_sessions"):
        sql(url,'CREATE SCHEMA "'+schema+'"; GRANT USAGE ON SCHEMA "'+schema+'" TO "'+role+'"; ALTER DEFAULT PRIVILEGES IN SCHEMA "'+schema+'" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO "'+role+'"')
    with tempfile.TemporaryDirectory(prefix="vernac-identity-browser-") as temporary:
        tmp=pathlib.Path(temporary)
        storepass=secrets.token_urlsafe(24)
        keystore=tmp/"localhost.p12"
        subprocess.run(["keytool","-genkeypair","-alias","local","-keyalg","RSA","-keysize","2048","-validity","2","-storetype","PKCS12","-keystore",str(keystore),"-storepass",storepass,"-dname","CN=localhost","-ext","SAN=dns:localhost,ip:127.0.0.1"],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        capability=secrets.token_urlsafe(32)
        admission=base64.b64encode(secrets.token_bytes(32)).decode()
        delivery=base64.b64encode(secrets.token_bytes(32)).decode()
        env=dict(os.environ,
            SPRING_PROFILES_ACTIVE="local",
            SPRING_DATASOURCE_URL="jdbc:"+url,SPRING_DATASOURCE_USERNAME=role,SPRING_DATASOURCE_PASSWORD=password,
            SPRING_FLYWAY_URL="jdbc:"+url,SPRING_FLYWAY_USER=os.environ["VERNAC_PG_TEST_USER"],SPRING_FLYWAY_PASSWORD=os.environ["VERNAC_PG_TEST_PASSWORD"],
            SERVER_SSL_KEY_STORE=str(keystore),SERVER_SSL_KEY_STORE_PASSWORD=storepass,
            IDENTITY_ADMISSION_KEY_LABEL="browser-v1",IDENTITY_ADMISSION_KEY=admission,
            IDENTITY_DELIVERY_KEY_LABEL="browser-v1",IDENTITY_DELIVERY_KEY=delivery,
            IDENTITY_PREVIEW_CAPABILITY=capability,IDENTITY_BROWSER_SECRETS_FILE=str(tmp/"browser-secrets.json"))
        with (tmp/"application.log").open("w") as log:
            process=subprocess.Popen(["java","-jar",str(ROOT/"target/identity-auth-demo-1.0.0.jar")],env=env,stdout=log,stderr=subprocess.STDOUT)
            ready=False
            for _ in range(120):
                if process.poll() is not None:break
                try:
                    with urllib.request.urlopen("https://localhost:8443/identity/login",context=ssl._create_unverified_context(),timeout=1) as response:
                        if response.status==200:ready=True;break
                except Exception:time.sleep(.25)
            if not ready:
                # Emit only exception classes, registered bean identities and missing property names.
                # Arbitrary exception messages and driver payloads never enter CI output.
                startup=(tmp/"application.log").read_text()
                classes=sorted(set(re.findall(r"(?:org|java|jakarta|example|com)\.[\w.$]+(?:Exception|Error)",startup)))
                beans=sorted(set(re.findall(r"Error creating bean with name '([A-Za-z0-9_.$]+)'",startup)))
                missing=sorted(set(re.findall(r"(?:Required key|placeholder) ['\"]([A-Za-z0-9_.-]+)['\"]",startup)))
                print("Startup exception classes:",classes)
                print("Startup beans:",beans)
                print("Missing property names:",missing)
                raise RuntimeError("Sample HTTPS startup failed")
            result=subprocess.run(["node","verify.mjs"],cwd=ROOT/"browser",env=env)
            if result.returncode:raise RuntimeError("Chromium acceptance failed")
            process.terminate();process.wait(timeout=20);process=None
        logtext=(tmp/"application.log").read_text()
        sensitive=[password,storepass,capability,admission,delivery,"browser-owner@example.test","browser-recipient@example.test",
            "an independent browser fixture passphrase 83957","another independent browser fixture phrase 19238"]
        sensitive+=json.loads((tmp/"browser-secrets.json").read_text())
        if any(value in logtext for value in sensitive):raise RuntimeError("Sensitive value appeared in sample application logs")
        print("Sample HTTPS startup and no-secret log assertions passed.")
except Exception as failure:
    # The diagnostic is fixed; raw SQL, HTTP links, secrets and driver exceptions stay local.
    print("Identity sample verification failed:",type(failure).__name__)
    raise SystemExit(1)
finally:
    if process is not None:
        process.terminate()
        try:process.wait(timeout=20)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    sql(admin,'DROP DATABASE IF EXISTS "'+db+'" WITH (FORCE)')
    sql(admin,'DROP ROLE IF EXISTS "'+role+'"')
