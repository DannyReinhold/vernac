// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {test as base,expect,request} from '@playwright/test';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {writeFile} from 'node:fs/promises';
export const PASSWORD='acceptance fixture strong phrase 482937';
export const test=base.extend({
 app:async({},use)=>{
  const process=spawn('python3',['../acceptance_runtime.py'],{stdio:['pipe','pipe','pipe']});
  const lines=createInterface({input:process.stdout});const queue=[],waiters=[];
  lines.on('line',line=>{const value=JSON.parse(line);if(waiters.length)waiters.shift()(value);else queue.push(value);});
  const receive=()=>new Promise((resolve,reject)=>{let timer=setTimeout(()=>reject(new Error('Runtime protocol timeout')),60000);const done=v=>{clearTimeout(timer);if(v.error)reject(new Error(v.startup));else resolve(v);};if(queue.length)done(queue.shift());else waiters.push(done);});
  let ready=await receive();const secrets=[PASSWORD];
  const control=await request.newContext({baseURL:ready.origin,ignoreHTTPSErrors:true,extraHTTPHeaders:{Authorization:'Bearer '+ready.control}});
  const merchant=await request.newContext({baseURL:ready.origin,ignoreHTTPSErrors:true,extraHTTPHeaders:{Authorization:'Bearer '+ready.merchant}});
  const inbox=await request.newContext({baseURL:ready.origin,ignoreHTTPSErrors:true,extraHTTPHeaders:{Authorization:'Bearer '+ready.preview}});
  const app={origin:ready.origin,expiredSession:ready.expiredSession,merchant,secrets,
   async control(route,data={}){const r=await control.post('/__acceptance/'+route,{data});expect(r.status(),'fixture control '+route).toBe(200);return r.json();},
   async facts(){return this.control('facts');},async tick(n=1){for(let i=0;i<n;i++)await this.control('tick');},
   async advance(seconds){return this.control('time',{seconds:String(seconds)});},
   async until(predicate,{limit=16,advance=0}={}){for(let i=0;i<limit;i++){await this.tick();const facts=await this.facts();if(await predicate(facts))return facts;if(advance)await this.advance(advance);}throw new Error('Durable outcome not reached');},
   async mail(address,route){for(let i=0;i<80;i++){const r=await inbox.get('/__local/mail');expect(r.status()).toBe(200);const items=await r.json();for(const item of items.slice().reverse()){if(item.recipient!==address)continue;const match=item.text.match(new RegExp('https://localhost:8443/auth/'+route+'#[^\\s]+'));if(match){secrets.push(new URL(match[0]).hash.slice(1));return match[0];}}await new Promise(r=>setTimeout(r,100));}throw new Error('Expected local mail missing');},
   async snapshot(directory,expected){process.stdin.write(JSON.stringify({action:'snapshot',directory,expected})+'\n');expect((await receive()).captured).toBe(true);},
   async history(){process.stdin.write(JSON.stringify({action:'history'})+'\n');expect((await receive()).historyPreserved).toBe(true);},
   async restart(){await writeFile(ready.secretsFile,JSON.stringify(secrets),{mode:0o600});process.stdin.write(JSON.stringify({action:'restart'})+'\n');ready=await receive();},
  };
  try{await use(app);}finally{
   await writeFile(ready.secretsFile,JSON.stringify(secrets),{mode:0o600});process.stdin.write(JSON.stringify({action:'stop'})+'\n');
   let stopped;try{stopped=await receive();expect(stopped.noSecretLog).toBe(true);}finally{process.stdin.end();await control.dispose();await merchant.dispose();await inbox.dispose();lines.close();}
   const code=await new Promise(resolve=>{if(process.exitCode!==null)resolve(process.exitCode);else process.once('exit',resolve);});expect(code,'runtime cleanup').toBe(0);
  }
 },
 customer:async({browser,app},use)=>{const context=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});await use(context);await context.close();}
});
export {expect};
export async function form(page,action,extra={}){return {...await page.locator('form[action="'+action+'"]').first().evaluate(f=>Object.fromEntries(new FormData(f))),...extra};}
export async function post(context,page,action,extra={}){return context.request.post(action,{form:await form(page,action,extra),maxRedirects:0});}
export async function bearer(page,link,password){await page.goto('about:blank');await test.step('bearer navigation',()=>page.goto(link));await test.step('bearer fragment scrubbed',()=>page.waitForFunction(()=>location.hash===''&&!document.getElementById('confirm-action').disabled));if(password)await page.getByLabel('New password',{exact:false}).fill(password);const response=page.waitForResponse(r=>r.request().method()==='POST');await test.step('bearer submit',()=>page.getByRole('button',{name:'Continue',exact:true}).click());return test.step('bearer response',()=>response);}
export async function login(context,page,address,password=PASSWORD){await page.goto('/identity/login');await page.getByLabel('Email address').fill(address);await page.getByLabel('Password',{exact:true}).fill(password);const response=page.waitForResponse(r=>r.request().method()==='POST');await page.getByRole('button',{name:'Sign in',exact:true}).click();expect((await response).status()).toBe(303);await page.waitForURL('**/auth/accounts');for(const c of await context.cookies())context._acceptanceSecrets?.push(c.value);}
export async function register(context,app,address='owner@case.example.test'){
 context._acceptanceSecrets=app.secrets;app.secrets.push(address);const page=await context.newPage();await page.goto('/auth/register');await page.getByLabel('Email address').fill(address);
 const pending=page.waitForResponse(r=>r.request().method()==='POST');await page.getByRole('button',{name:'Continue',exact:true}).click();expect((await pending).status()).toBe(202);
 expect((await bearer(page,await app.mail(address,'verify'),PASSWORD)).status()).toBe(303);await page.waitForURL('**/identity/login');
 expect((await context.request.get('/auth/accounts')).status()).toBe(401);await login(context,page,address);
 const account=await page.locator('form[action="/auth/account/select"] input[name="account"]').first().inputValue();
 await page.getByRole('button',{name:'Open workspace',exact:true}).first().click();await page.waitForURL('**/workspace');
 await expect(page.getByRole('heading',{name:'Your documents',exact:true})).toBeVisible();
 return {context,page,account,address};
}
export async function billing(user){await user.page.goto('/billing/accounts/'+user.account);await expect(user.page.getByRole('heading',{name:'Plan and billing',exact:true})).toBeVisible();}
export async function profile(user,name='Customer Snapshot'){await billing(user);const r=await post(user.context,user.page,'/billing/profile',{name,street:'Example 1',postalCode:'28199',city:'Bremen',email:user.address});expect(r.status()).toBe(303);}
export async function checkout(user){await billing(user);const values=await form(user.page,'/billing/checkout');const response=await user.context.request.post('/billing/checkout',{form:values,maxRedirects:0});expect(response.status()).toBe(303);return {url:response.headers().location,id:response.headers().location.split('/').at(-1),values};}
export async function paid(user,app){await profile(user);const order=await checkout(user);await app.until(f=>f.orders.find(o=>o.id===order.id)?.status==='APPLIED',{advance:30});await app.until(f=>f.invoices.some(i=>i.status==='READY'));return order;}
export async function action(user,name,extra={}){await billing(user);const selector=user.page.locator('form[action="/billing/subscription"]').filter({has:user.page.locator('input[name="action"][value="'+name+'"]')}).first();const values=await selector.evaluate(f=>Object.fromEntries(new FormData(f)));return user.context.request.post('/billing/subscription',{form:{...values,...extra},maxRedirects:0});}
export async function close(user){await user.page.goto('/auth/account?account='+user.account);return post(user.context,user.page,'/auth/account/close');}
export async function refund(app,user,order,amount,operation){const r=await app.merchant.post('/__operator/refunds',{headers:{'Content-Type':'text/plain;charset=UTF-8'},data:[user.account,order.id,operation,String(amount)].join('\n')});return r;}
