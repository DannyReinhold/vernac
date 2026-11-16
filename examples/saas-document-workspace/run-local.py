#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
"""Persistent loopback-only demonstration. Docker Compose, Python 3, JDK 21+, matching Maven artifacts."""
import base64, json, os, pathlib, secrets, ssl, subprocess, sys, urllib.request
ROOT=pathlib.Path(__file__).resolve().parent
os.chdir(ROOT)
local=ROOT/'.local';local.mkdir(mode=0o700,exist_ok=True)
state=local/'settings.json'
if not state.exists():
    config={'database':secrets.token_urlsafe(32),'runtime':secrets.token_urlsafe(32),'store':secrets.token_urlsafe(24),'preview':secrets.token_urlsafe(32),
        'admission':base64.b64encode(secrets.token_bytes(32)).decode(),'delivery':base64.b64encode(secrets.token_bytes(32)).decode()}
    with state.open('x',opener=lambda path,flags:os.open(path,flags,0o600)) as stream:json.dump(config,stream)
config=json.loads(state.read_text())
env=dict(os.environ,LOCAL_DATABASE_PASSWORD=config['database'])
compose=['docker','compose','-p',ROOT.name]
if '--stop' in sys.argv:
    subprocess.run(compose+['stop'],env=env,check=True);sys.exit()
if '--mail' in sys.argv:
    req=urllib.request.Request('https://localhost:8443/__local/mail',headers={'Authorization':'Bearer '+config['preview']})
    with urllib.request.urlopen(req,context=ssl._create_unverified_context(),timeout=5) as response:
        for item in json.load(response):print(item['recipient']+'\n'+item['text']+'\n')
    sys.exit()
subprocess.run(compose+['up','-d','--wait'],env=env,check=True)
def sql(statement):
    subprocess.run(compose+['exec','-T','db','psql','-X','-q','-v','ON_ERROR_STOP=1','-U','vernac','-d','workspace'],input=statement,text=True,env=env,check=True,stdout=subprocess.DEVNULL)
# Runtime never owns schemas, migrations or tables. Repeated launches preserve all data/keys.
sql("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='workspace_runtime') THEN CREATE ROLE workspace_runtime LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD '"+config['runtime']+"'; END IF; END $$;")
for schema in ('org.vernac.saas.identity.persistence','identity_sessions','org.vernac.saas.subscriptions','org.vernac.saas.restrictions','org.vernac.saas.payments.persistence','org.vernac.saas.payments.local','org.vernac.saas.invoicing','example.documentworkspace'):
    sql('CREATE SCHEMA IF NOT EXISTS "'+schema+'"; GRANT USAGE ON SCHEMA "'+schema+'" TO workspace_runtime; ALTER DEFAULT PRIVILEGES IN SCHEMA "'+schema+'" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO workspace_runtime; GRANT SELECT,INSERT,UPDATE,DELETE ON ALL TABLES IN SCHEMA "'+schema+'" TO workspace_runtime;')
keystore=local/'localhost.p12'
if not keystore.exists():
    subprocess.run(['keytool','-genkeypair','-alias','local','-keyalg','RSA','-keysize','2048','-validity','365','-storetype','PKCS12','-keystore',str(keystore),'-storepass',config['store'],'-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    keystore.chmod(0o600)
if not (ROOT/'target/saas-document-workspace-1.0.0.jar').exists():
    wrapper='mvnw.cmd' if os.name=='nt' else './mvnw'
    subprocess.run([wrapper,'-B','-ntp','package'],env=env,check=True)
url='jdbc:postgresql://127.0.0.1:'+env.get('LOCAL_DB_PORT','55439')+'/workspace'
env.update(SPRING_PROFILES_ACTIVE='local',SPRING_DATASOURCE_URL=url,SPRING_DATASOURCE_USERNAME='workspace_runtime',SPRING_DATASOURCE_PASSWORD=config['runtime'],
    SPRING_FLYWAY_URL=url,SPRING_FLYWAY_USER='vernac',SPRING_FLYWAY_PASSWORD=config['database'],SERVER_SSL_KEY_STORE=str(keystore),SERVER_SSL_KEY_STORE_PASSWORD=config['store'],
    IDENTITY_ADMISSION_KEY_LABEL='local-v1',IDENTITY_ADMISSION_KEY=config['admission'],IDENTITY_DELIVERY_KEY_LABEL='local-v1',IDENTITY_DELIVERY_KEY=config['delivery'],IDENTITY_PREVIEW_CAPABILITY=config['preview'])
print('Open https://localhost:8443/auth/register and accept your local development certificate.\nRead simulated mail in a second terminal: python3 run-local.py --mail\nCtrl+C stops the app; PostgreSQL and all account/document/billing data remain. Use --stop to stop PostgreSQL.',flush=True)
try:subprocess.run(['java','-jar',str(ROOT/'target/saas-document-workspace-1.0.0.jar')],env=env,check=True)
except KeyboardInterrupt:pass
