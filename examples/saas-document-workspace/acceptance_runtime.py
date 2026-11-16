#!/usr/bin/env python3
# Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
"""One isolated test runtime. Private JSON protocol over pipes; never expose fixture routes in a JAR."""
import base64,json,os,pathlib,secrets,ssl,subprocess,sys,tempfile,time,urllib.request,zipfile
ROOT=pathlib.Path(__file__).resolve().parent
SCHEMAS=('org.vernac.saas.identity.persistence','identity_sessions','org.vernac.saas.subscriptions','org.vernac.saas.restrictions','org.vernac.saas.payments.persistence','org.vernac.saas.payments.local','org.vernac.saas.invoicing','example.documentworkspace')
def main():
    admin=os.environ['VERNAC_PG_TEST_URL'].removeprefix('jdbc:')
    db='case_'+secrets.token_hex(8);role='case_runtime_'+secrets.token_hex(8);password=secrets.token_urlsafe(32)
    credentials=dict(os.environ,PGPASSWORD=os.environ['VERNAC_PG_TEST_PASSWORD'])
    def sql(database,statement):
        subprocess.run(['psql','-X','-q','-v','ON_ERROR_STOP=1','-d',database,'-U',os.environ['VERNAC_PG_TEST_USER'],'-c',statement],env=credentials,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    url=admin.rsplit('/',1)[0]+'/'+db;process=None;created_db=created_role=False;logs=[]
    def stop():
        nonlocal process
        if process:
            process.terminate()
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:process.kill();process.wait()
            process=None
    try:
        with zipfile.ZipFile(ROOT/'target/saas-document-workspace-1.0.0.jar') as jar:
            if any('/acceptance/support/' in name for name in jar.namelist()):raise RuntimeError('Fixture leaked into production JAR')
        sql(admin,'CREATE DATABASE "'+db+'"');created_db=True
        sql(admin,'CREATE ROLE "'+role+'" LOGIN NOSUPERUSER NOBYPASSRLS PASSWORD \''+password+"'");created_role=True
        for schema in SCHEMAS:
            sql(url,'CREATE SCHEMA "'+schema+'"; GRANT USAGE ON SCHEMA "'+schema+'" TO "'+role+'"; ALTER DEFAULT PRIVILEGES IN SCHEMA "'+schema+'" GRANT SELECT,INSERT,UPDATE,DELETE ON TABLES TO "'+role+'"')
        with tempfile.TemporaryDirectory(prefix='vernac-case-') as temporary:
            tmp=pathlib.Path(temporary);storepass=secrets.token_urlsafe(24);keystore=tmp/'localhost.p12'
            subprocess.run(['keytool','-genkeypair','-alias','local','-keyalg','RSA','-keysize','2048','-validity','2','-storetype','PKCS12','-keystore',str(keystore),'-storepass',storepass,'-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            preview,merchant,control=[secrets.token_urlsafe(32) for _ in range(3)]
            admission,delivery=[base64.b64encode(secrets.token_bytes(32)).decode() for _ in range(2)]
            env=dict(os.environ,VERNAC_ACCEPTANCE_FIXTURE='true',SPRING_PROFILES_ACTIVE='local',BILLING_WORKER_ENABLED='false',
                SPRING_DATASOURCE_URL='jdbc:'+url,SPRING_DATASOURCE_USERNAME=role,SPRING_DATASOURCE_PASSWORD=password,
                SPRING_FLYWAY_URL='jdbc:'+url,SPRING_FLYWAY_USER=os.environ['VERNAC_PG_TEST_USER'],SPRING_FLYWAY_PASSWORD=os.environ['VERNAC_PG_TEST_PASSWORD'],
                SERVER_SSL_KEY_STORE=str(keystore),SERVER_SSL_KEY_STORE_PASSWORD=storepass,
                IDENTITY_ADMISSION_KEY_LABEL='case-v1',IDENTITY_ADMISSION_KEY=admission,IDENTITY_DELIVERY_KEY_LABEL='case-v1',IDENTITY_DELIVERY_KEY=delivery,
                IDENTITY_PREVIEW_CAPABILITY=preview,BILLING_OPERATOR_CAPABILITY=merchant,ACCEPTANCE_CAPABILITY=control,ACCEPTANCE_CLOCK_FILE=str(tmp/'clock.txt'))
            classpath=os.pathsep.join([str(ROOT/'target/test-classes'),str(ROOT/'target/classes'),(ROOT/'target/acceptance-classpath.txt').read_text().strip()])
            def start():
                nonlocal process
                log=tmp/('application-'+str(len(logs))+'.log');logs.append(log)
                with log.open('w') as out:process=subprocess.Popen(['java','-cp',classpath,'acceptance.support.AcceptanceApplication'],env=env,stdout=out,stderr=subprocess.STDOUT)
                for _ in range(180):
                    if process.poll() is not None:break
                    try:
                        with urllib.request.urlopen('https://localhost:8443/identity/login',context=ssl._create_unverified_context(),timeout=1) as response:
                            if response.status==200:
                                print(json.dumps({'ready':True,'origin':'https://localhost:8443','preview':preview,'merchant':merchant,'control':control,'secretsFile':str(tmp/'seen-secrets.json')}),flush=True);return
                    except Exception:time.sleep(.2)
                # Fixed class names only, never driver values, body payloads or credentials.
                import re
                classes=sorted(set(re.findall(r'(?:org|java|jakarta|example|acceptance)\.[\w.$]+(?:Exception|Error)',log.read_text())))
                raise RuntimeError('Startup: '+','.join(classes))
            start()
            for line in sys.stdin:
                command=json.loads(line)
                if command['action']=='restart':stop();start()
                elif command['action']=='stop':break
                else:raise RuntimeError('Unknown runtime command')
            stop()
            seen=[password,storepass,preview,merchant,control,admission,delivery]
            if (tmp/'seen-secrets.json').exists():seen+=json.loads((tmp/'seen-secrets.json').read_text())
            for log in logs:
                content=log.read_text()
                if any(value and value in content for value in seen):raise RuntimeError('Seeded secret appeared in application log')
            print(json.dumps({'stopped':True,'noSecretLog':True}),flush=True)
    except Exception as failure:
        print(json.dumps({'error':type(failure).__name__,'startup':str(failure) if isinstance(failure,RuntimeError) and str(failure).startswith('Startup:') else 'Fixture failed'}),flush=True)
        return 1
    finally:
        stop()
        try:
            if created_db:sql(admin,'DROP DATABASE IF EXISTS "'+db+'" WITH (FORCE)')
            if created_role:sql(admin,'DROP ROLE IF EXISTS "'+role+'"')
        except Exception:return 1
    return 0
if __name__=='__main__':sys.exit(main())
