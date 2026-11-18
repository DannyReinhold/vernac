// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
// Internal acceptance only. The application itself starts solely through Boot/JDK/Docker.
import {spawn} from 'node:child_process';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
import {createRequire} from 'node:module';
import path from 'node:path';
import assert from 'node:assert/strict';
const root=path.resolve(process.argv[2]);
const {chromium}=createRequire(path.join(root,'browser/package.json'))('playwright');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let app,browser,logs='';
const environment={...process.env};
// Explicitly verify that a normal developer launch never invokes Python.
const noPython=path.join(root,'target/no-python');await mkdir(noPython,{recursive:true});
for(const name of ['python','python3'])await writeFile(path.join(noPython,name),'#!/bin/sh\necho "Unexpected Python dependency" >&2\nexit 91\n',{mode:0o755});
environment.PATH=noPython+path.delimiter+environment.PATH;
async function command(args){
 return new Promise((resolve,reject)=>{
  const p=spawn('./mvnw',['-q','-ntp',...args],{cwd:root,env:environment});let output='';
  p.stdout.on('data',chunk=>output+=chunk);p.stderr.on('data',chunk=>output+=chunk);
  p.once('error',reject);p.once('exit',code=>code===0?resolve(output):reject(new Error('Local Java tool failed')));
 });
}
async function start(context){
 logs='';app=spawn('./mvnw',['-q','-ntp','spring-boot:run'],{cwd:root,env:environment,detached:true});
 app.stdout.on('data',c=>logs+=c);app.stderr.on('data',c=>logs+=c);
 for(let i=0;i<180;i++){
  if(app.exitCode!==null)throw new Error('Normal Wizard startup failed');
  try{if((await context.request.get('/auth/register',{timeout:1500})).status()===200)return;}catch{}
  await delay(1000);
 }
 throw new Error('Normal Wizard startup timed out');
}
async function stop(){
 if(!app)return;const processToStop=app;app=null;
 if(processToStop.exitCode!==null)return;
 process.kill(-processToStop.pid,'SIGTERM');
 for(let i=0;i<100&&processToStop.exitCode===null;i++)await delay(100);
 if(processToStop.exitCode===null)process.kill(-processToStop.pid,'SIGKILL');
}
try{
 browser=await chromium.launch({headless:true});
 const context=await browser.newContext({baseURL:'https://localhost:8443',ignoreHTTPSErrors:true});
 await start(context);
 const page=await context.newPage(),address='wizard-normal@example.test',password='Wizard normal passage 2026! Strong';
 const protectedPage=await context.request.get("/workspace",{maxRedirects:0});
 assert.ok([302,303,401,403].includes(protectedPage.status()),"anonymous workspace access must require login");
 await page.goto('/');
 assert.equal(await page.locator('body').evaluate(body=>getComputedStyle(body).backgroundColor),'rgb(239, 245, 244)');
 await page.getByRole('link',{name:'Legal notice'}).click();
 await page.getByRole('link',{name:'Back to home'}).click();
 await page.getByRole('link',{name:'Sign in'}).click();
 assert.equal(new URL(page.url()).pathname,'/identity/login');
 assert.equal(await page.locator('body').evaluate(body=>getComputedStyle(body).backgroundColor),'rgb(239, 245, 244)');
 await page.goBack();
 await page.getByRole('link',{name:'View plans'}).click();
 await page.getByRole('heading',{name:'Choose your plan'}).waitFor();
 assert.equal(await page.getByRole('link',{name:'Choose Pro'}).getAttribute('href'),'/billing');
 await page.getByRole('link',{name:'Register for Free'}).click();await page.getByLabel('Email address').fill(address);
 let response=page.waitForResponse(r=>r.request().method()==='POST');await page.getByRole('button',{name:'Continue',exact:true}).click();assert.equal((await response).status(),202);
 await page.getByRole('heading',{name:'Check your inbox'}).waitFor();
 await page.getByText('Using the local demo?',{exact:true}).click();
 await page.getByText('./mvnw exec:java -Dexec.args=mail',{exact:true}).waitFor();
 let mail='';
 for(let i=0;i<30;i++){mail=await command(['exec:java','-Dexec.args=mail']);if(mail.includes('/auth/verify#'))break;await delay(200);}
 assert.ok(logs.includes('Local mail simulation outcome: ACCEPTED'),'the development log explains local mail');
 assert.ok(!logs.includes(address),'the development log excludes the recipient');
 assert.ok(!logs.includes('/auth/verify#'),'the development log excludes confirmation links');
 assert(mail.includes(address));const link=mail.match(/https:\/\/localhost:8443\/auth\/verify#[^\s]+/)?.[0];assert(link);
 await page.goto(link);await page.waitForFunction(()=>location.hash===''&&!document.getElementById('confirm-action').disabled);
 await page.getByLabel('New password',{exact:false}).fill(password);response=page.waitForResponse(r=>r.request().method()==='POST');await page.getByRole('button',{name:'Continue',exact:true}).click();assert.equal((await response).status(),303);
 await page.waitForURL('**/identity/login');await page.getByLabel('Email address').fill(address);await page.getByLabel('Password',{exact:true}).fill(password);
 response=page.waitForResponse(r=>r.request().method()==='POST');await page.getByRole('button',{name:'Sign in',exact:true}).click();assert.equal((await response).status(),303);await page.waitForURL('**/auth/accounts');
 await page.getByRole('button',{name:'Open workspace',exact:true}).first().click();await page.waitForURL('**/workspace');
 await page.getByRole('link',{name:'Settings'}).click();
 await page.getByRole('heading',{name:'Your profile'}).waitFor();
 await page.getByRole('link',{name:'Back to documents'}).click();
 assert.equal(await page.getByRole('link',{name:'Plan and billing'}).count(),0);
 await page.getByText('New document',{exact:true}).click();
 await page.getByLabel('Title',{exact:true}).fill('Wizard restart äöü');await page.getByLabel('Text',{exact:true}).fill('Persisted by the ordinary developer path');
 response=page.waitForResponse(r=>r.request().method()==='POST');await page.getByRole('button',{name:'Create document',exact:true}).click();assert.equal((await response).status(),303);await page.waitForURL('**/workspace');
 await page.getByText('Wizard restart äöü',{exact:true}).waitFor();assert.equal(await page.getByText('Wizard restart äöü',{exact:true}).count(),1);
 const exportForm=await page.locator('form[action="/documents/export"]').evaluate(form=>Object.fromEntries(new FormData(form)));
 for(let used=0;used<3;used++){
  const download=await context.request.post('/documents/export',{form:exportForm,headers:{Accept:'text/plain'}});
  assert.equal(download.status(),200,'generated project permits three monthly exports');
  assert.equal(download.headers()['content-disposition'],'attachment; filename="document.txt"');
  assert.equal(await download.text(),'Wizard restart äöü\n\nPersisted by the ordinary developer path');
 }
 const exhausted=await context.request.post('/documents/export',{form:exportForm});
 assert.equal(exhausted.status(),409,'fourth export exceeds the generated project allowance');
 const state=await readFile(path.join(root,'.local/settings.json')),store=await readFile(path.join(root,'.local/localhost.p12'));
 assert((await command(['exec:java','-Dexec.args=status'])).includes('RENEWAL'));
 await stop();await start(context);await page.goto('/workspace');await page.getByText('Wizard restart äöü',{exact:true}).waitFor();assert.equal(await page.getByText('Wizard restart äöü',{exact:true}).count(),1);
 assert.deepEqual(await readFile(path.join(root,'.local/settings.json')),state);assert.deepEqual(await readFile(path.join(root,'.local/localhost.p12')),store);
 const accountState=JSON.parse(state);const capabilities=Object.values(accountState);assert(capabilities.every(secret=>!logs.includes(secret)));
 await mkdir(path.join(root,'target/acceptance-reports'),{recursive:true});
 await writeFile(path.join(root,'target/acceptance-reports/normal-start.json'),JSON.stringify({status:'passed',checks:['normal Maven start without Python',environment.SPRING_DOCKER_COMPOSE_ENABLED==='false'?'external disposable database':'Compose database','registration and local Java mail client','verification and login','product mutation','three exact UTF-8 exports and fourth denied','operator isolation client','restart with retained session, document and keys']}));
 console.log('Normal Wizard start, Java mail/operator tools and durable restart passed.');
}catch(error){
 let safe=logs;try{for(const value of Object.values(JSON.parse(await readFile(path.join(root,'.local/settings.json'),'utf8'))))safe=safe.replaceAll(value,'[redacted]');}catch{}
 await writeFile(path.join(root,'target/normal-start.log'),safe);throw error;
}finally{await stop();if(browser)await browser.close();}
