// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {test,expect,PASSWORD,register,login,bearer,form,post,billing,profile,checkout,paid,action,close,refund} from './fixtures.mjs';
import {randomUUID,createHash} from 'node:crypto';
const sha=b=>createHash('sha256').update(b).digest('hex');
async function document(user,title='Document',text='Content'){await user.page.goto('/workspace');return post(user.context,user.page,'/documents/create',{title,text});}
async function advanceToEnd(app,user,offset=0){const f=await app.facts(),s=f.subscriptions.find(s=>s.account===user.account),now=await app.advance(0);await app.advance(Math.ceil((Number(s.end)-now.millis)/1000)+offset);await login(user.context,user.page,user.address);await select(user,user.account);}
async function select(user,account){await user.page.goto('/auth/accounts');const f=user.page.locator('form[action="/auth/account/select"]').filter({has:user.page.locator('input[value="'+account+'"]')});const values=await f.evaluate(f=>Object.fromEntries(new FormData(f)));expect((await user.context.request.post('/auth/account/select',{form:values,maxRedirects:0})).status()).toBe(303);user.account=account;await user.page.goto('/workspace');}
async function invite(owner,member,app,roles='example.workspace_reader'){
 await owner.page.goto('/auth/account?account='+owner.account);expect((await post(owner.context,owner.page,'/auth/account/invite',{address:member.address,roles})).status()).toBe(202);
 expect((await bearer(member.page,await app.mail(member.address,'invitation'))).status()).toBe(303);await member.page.waitForURL('**/auth/accounts');await select(member,owner.account);
}
async function invoiceBytes(user,id){const path='/billing/invoices/'+id;const pdf=await user.context.request.get(path),xml=await user.context.request.get(path+'/xml');expect(pdf.status()).toBe(200);expect(xml.status()).toBe(200);return {pdf:await pdf.body(),xml:await xml.text()};}
function xmlValue(xml,name){return [...xml.matchAll(new RegExp('<(?:[\\w-]+:)?'+name+'(?:\\s[^>]*)?>([^<]+)</(?:[\\w-]+:)?'+name+'>','g'))].map(m=>m[1]);}
async function corrections(app,user,count){return app.until(f=>f.invoices.filter(i=>i.account===user.account&&i.status==='READY').length===count);}

test('E01 S01 S02 registration, duplicate request, token replay, secure login and logout',async({app,customer,browser})=>{
 const user=await register(customer,app);await test.step('secure session and neutral duplicate registration',async()=>{
  const cookie=(await customer.cookies()).find(c=>c.name==='__Host-VERNACSESSION');expect(cookie?.secure&&cookie.httpOnly&&cookie.sameSite==='Lax').toBe(true);
  const anonymous=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
  try{const p=await anonymous.newPage();await p.goto('/auth/register');expect((await post(anonymous,p,'/auth/register',{address:user.address})).status()).toBe(202);}finally{await anonymous.close();}
 });
 await test.step('one-time token cannot create a second account',async()=>{const link=await app.mail(user.address,'verify');await user.page.goto('/auth/verify');const response=await post(customer,user.page,'/auth/verify',{token:new URL(link).hash.slice(1),password:PASSWORD});expect([400,409]).toContain(response.status());expect((await app.facts()).accounts).toHaveLength(1);});
 await user.page.goto('/identity/login');const logout=await user.page.locator('form[action="/identity/logout"]').count()?await form(user.page,'/identity/logout'):null;
 if(logout)expect((await customer.request.post('/identity/logout',{form:logout,maxRedirects:0})).status()).toBe(204);
 else {await user.page.goto('/workspace');const csrf=await user.page.locator('input[name="_csrf"]').first().inputValue();expect((await customer.request.post('/identity/logout',{form:{_csrf:csrf},maxRedirects:0})).status()).toBe(204);}
 expect((await customer.request.get('/auth/accounts')).status()).toBe(401);
});

test('E02 S03 recovery and password change revoke old sessions',async({app,customer,browser})=>{
 const user=await register(customer,app),replacement='replacement acceptance passphrase 982734';app.secrets.push(replacement);
 const anon=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
 try{const p=await anon.newPage();await p.goto('/auth/recovery-request');expect((await post(anon,p,'/auth/recovery-request',{address:user.address})).status()).toBe(202);
 expect((await bearer(p,await app.mail(user.address,'recover'),replacement)).status()).toBe(303);expect((await customer.request.get('/auth/accounts')).status()).toBe(401);
 await login(customer,user.page,user.address,replacement);await user.page.goto('/auth/password');expect((await post(customer,user.page,'/auth/password',{currentPassword:replacement,password:PASSWORD})).status()).toBe(303);expect((await customer.request.get('/auth/accounts')).status()).toBe(401);
 }finally{await anon.close();}
});

test('E03 S01 expired verification cannot activate identity',async({app,customer})=>{
 const page=await customer.newPage(),address='expired@case.example.test';app.secrets.push(address);await page.goto('/auth/register');expect((await post(customer,page,'/auth/register',{address})).status()).toBe(202);const link=await app.mail(address,'verify');
 await app.advance(3*86400);const response=await bearer(page,link,PASSWORD);expect([400,409]).toContain(response.status());expect((await app.facts()).accounts).toHaveLength(0);
});

test('E04 S04 S05 S06 S07 organization, invitation, rights and owner transfer',async({app,customer,browser})=>{
 const owner=await register(customer,app);await owner.page.goto('/auth/accounts');expect((await post(customer,owner.page,'/auth/account/create')).status()).toBe(303);
 const facts=await app.facts(),org=facts.accounts.find(a=>a.id!==owner.account).id;await select(owner,org);
 const other=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
 try{const member=await register(other,app,'member@case.example.test');await invite(owner,member,app);
 expect((await document(member)).status()).toBe(403);expect((await other.request.get('/billing/accounts/'+org)).status()).toBe(403);
 await owner.page.goto('/auth/account?account='+org);const row=owner.page.getByRole('row').filter({hasText:member.address});const roles=await row.locator('form[action="/auth/account/roles"]').evaluate(f=>Object.fromEntries(new FormData(f)));
 expect((await customer.request.post('/auth/account/roles',{form:{...roles,roles:'example.workspace_editor'},maxRedirects:0})).status()).toBe(303);expect((await document(member)).status()).toBe(303);
 const transfer=await row.locator('form[action="/auth/account/transfer"]').evaluate(f=>Object.fromEntries(new FormData(f)));
 expect((await customer.request.post('/auth/account/transfer',{form:transfer,maxRedirects:0})).status()).toBe(303);expect((await customer.request.get('/billing/accounts/'+org)).status()).toBe(403);expect((await other.request.get('/billing/accounts/'+org)).status()).toBe(200);
 await member.page.goto('/auth/account?account='+org);const previous=member.page.getByRole('row').filter({hasText:owner.address});const removal=await previous.locator('form[action="/auth/account/remove"]').evaluate(f=>Object.fromEntries(new FormData(f)));expect((await other.request.post('/auth/account/remove',{form:removal,maxRedirects:0})).status()).toBe(303);expect((await customer.request.get('/workspace')).status()).toBe(403);
 }finally{await other.close();}
});

test('E05 S05 revoked invitation and wrong recipient cannot join',async({app,customer,browser})=>{
 const owner=await register(customer,app);const other=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
 try{const wrong=await register(other,app,'wrong@case.example.test');await owner.page.goto('/auth/account?account='+owner.account);const intended='intended@case.example.test';app.secrets.push(intended);expect((await post(customer,owner.page,'/auth/account/invite',{address:intended,roles:'example.workspace_reader'})).status()).toBe(202);const link=await app.mail(intended,'invitation');expect([400,403,409]).toContain((await bearer(wrong.page,link)).status());
 await owner.page.goto('/auth/account?account='+owner.account);expect((await post(customer,owner.page,'/auth/account/revoke')).status()).toBe(303);expect([400,403,409]).toContain((await bearer(wrong.page,link)).status());
 }finally{await other.close();}
});

test('E06 S08 S09 concurrent last slot, rollback and monthly quota rollover',async({app,customer})=>{
 const user=await register(customer,app);expect((await document(user,'A')).status()).toBe(303);expect((await document(user,'B')).status()).toBe(303);
 await user.page.goto('/workspace');const values=await form(user.page,'/documents/create');expect((await customer.request.post('/documents/create',{form:{...values,title:'Too long',text:'x'.repeat(1001)},maxRedirects:0})).status()).toBe(409);
 const races=await Promise.all(['C','D'].map(title=>customer.request.post('/documents/create',{form:{...values,title,text:'Content'},maxRedirects:0})));expect(races.map(r=>r.status()).sort()).toEqual([303,409]);
 await user.page.goto('/workspace');expect(await user.page.locator('form[action="/documents/delete"]').count()).toBe(3);
 const exportForm=await form(user.page,'/documents/export');for(let i=0;i<3;i++)expect((await customer.request.post('/documents/export',{form:exportForm,maxRedirects:0})).status()).toBe(i<2?200:409);
 await app.advance(18*86400);await login(customer,user.page,user.address);await select(user,user.account);await user.page.goto('/workspace');expect((await post(customer,user.page,'/documents/export')).status()).toBe(200);
 expect((await post(customer,user.page,'/documents/delete')).status()).toBe(303);expect((await document(user,'Replacement')).status()).toBe(303);
});

test('E07 S10 S11 S16 paid conversion is idempotent and invoice contains exact fiscal facts',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);const order=await checkout(user);expect((await customer.request.post('/billing/checkout',{form:order.values,maxRedirects:0})).headers().location).toBe(order.url);
 const f=await app.until(f=>f.orders[0]?.status==='APPLIED',{advance:30});expect(f.providerPayments).toBe(1);expect(f.subscriptions[0].plan).toBe('pro');
 const ready=await corrections(app,user,1),invoice=ready.invoices[0];expect(invoice.gross).toBe(1200);expect(invoice.net+invoice.tax).toBe(1200);expect(invoice.original).toBeNull();
 const bytes=await invoiceBytes(user,invoice.id);expect(bytes.pdf.subarray(0,5).toString()).toBe('%PDF-');expect(xmlValue(bytes.xml,'GrandTotalAmount')).toContain('12.00');expect(bytes.xml).toContain(invoice.number);expect(bytes.xml).toContain('Customer Snapshot');
});

test('E08 S11 S12 pending payment never grants paid rights before verified success',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await app.control('faults',{scenario:'PENDING'});const order=await checkout(user);await app.until(f=>f.attempts[0]?.state==='PENDING',{advance:30});
 let f=await app.facts();expect(f.subscriptions[0].plan).toBe('free');expect(f.invoices).toHaveLength(0);expect(f.attempts[0].state).toBe('PENDING');
 await app.control('faults',{scenario:'PAID'});await app.advance(30);f=await app.until(f=>f.orders[0].status==='APPLIED');expect(f.providerPayments).toBe(1);expect(f.subscriptions[0].plan).toBe('pro');expect((await customer.request.get(order.url)).status()).toBe(200);
});

for(const outcome of ['FAILED','CANCELLED','EXPIRED'])test(`E${outcome==='FAILED'?'09':outcome==='CANCELLED'?'10':'11'} S11 S12 ${outcome} payment retries on the original order`,async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await app.control('faults',{scenario:outcome});const order=await checkout(user);await app.until(f=>f.attempts[0]?.state===outcome,{advance:30});
 let f=await app.facts();expect(f.attempts[0].state).toBe(outcome);expect(f.subscriptions[0].plan).toBe('free');expect(f.invoices).toHaveLength(0);
 await app.control('faults',{scenario:'PAID'});await user.page.goto(order.url);expect((await post(customer,user.page,'/billing/payment/retry')).status()).toBe(303);f=await app.until(f=>f.orders[0].status==='APPLIED',{advance:30});expect(f.orders).toHaveLength(1);expect(f.attempts).toHaveLength(2);expect(f.providerPayments).toBe(2);expect(f.subscriptions[0].plan).toBe('pro');
});

test('E12 S12 S20 lost checkout response recovers one external payment across restart',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await app.control('faults',{lostCheckout:'true'});await checkout(user);await app.tick(3);
 let f=await app.facts();expect(f.providerPayments).toBe(1);expect(f.subscriptions[0].plan).toBe('free');await app.restart();await app.advance(30);f=await app.until(f=>f.orders[0].status==='APPLIED',{advance:30});expect(f.providerPayments).toBe(1);expect(f.orders).toHaveLength(1);
});

test('E13 S13 S16 recurring boundary produces one new period and one invoice',async({app,customer})=>{
 const user=await register(customer,app);await paid(user,app);const before=(await app.facts()).subscriptions[0];await advanceToEnd(app,user);
 const f=await app.until(f=>f.orders.length===2&&f.orders.every(o=>o.status==='APPLIED'),{advance:30});expect(f.providerPayments).toBe(2);expect(Number(f.subscriptions[0].start)).toBe(Number(before.end));expect(f.subscriptions[0].plan).toBe('pro');await corrections(app,user,2);
});

test('E14 S13 S12 failed renewal denies expired benefits then recovers without backbilling',async({app,customer})=>{
 const user=await register(customer,app);await paid(user,app);await app.control('faults',{scenario:'FAILED'});await advanceToEnd(app,user);await app.until(f=>f.attempts.at(-1)?.state==='FAILED',{advance:30});
 let f=await app.facts();expect(f.orders).toHaveLength(2);expect(f.attempts.at(-1).state).toBe('FAILED');expect((await document(user,'No expired benefit','x'.repeat(1001))).status()).toBe(409);
 await app.control('faults',{scenario:'PAID'});const renewal=f.orders.at(-1);await user.page.goto('/billing/accounts/'+user.account+'/payments/'+renewal.id);expect((await post(customer,user.page,'/billing/payment/retry')).status()).toBe(303);f=await app.until(f=>f.orders.find(o=>o.id===renewal.id).status==='APPLIED',{advance:30});expect(f.orders).toHaveLength(2);expect((await document(user,'Recovered','x'.repeat(1001))).status()).toBe(303);
});

test('E15 S14 S18 cancelled renewal expires without a charge and permits closure',async({app,customer})=>{
 const user=await register(customer,app);await paid(user,app);expect((await action(user,'cancel')).status()).toBe(303);expect((await action(user,'resume')).status()).toBe(303);expect((await action(user,'cancel')).status()).toBe(303);expect((await close(user)).status()).toBe(409);await advanceToEnd(app,user);await app.tick(5);expect((await app.facts()).providerPayments).toBe(1);expect((await close(user)).status()).toBe(303);expect((await app.facts()).accounts[0].status).toBe('CLOSED');
});

test('E16 S14 S09 explicit Free downgrade preserves data and imposes Free limits',async({app,customer})=>{
 const user=await register(customer,app);await paid(user,app);for(let i=0;i<4;i++)expect((await document(user,'Paid '+i)).status()).toBe(303);
 expect((await action(user,'schedule',{plan:'free',revision:'v1'})).status()).toBe(303);await advanceToEnd(app,user);await app.tick(3);let f=await app.facts();expect(f.subscriptions[0].plan).toBe('free');expect(f.providerPayments).toBe(1);await user.page.goto('/workspace');expect(await user.page.locator('form[action="/documents/delete"]').count()).toBe(4);expect((await document(user,'Over Free')).status()).toBe(409);
 expect((await post(customer,user.page,'/documents/delete')).status()).toBe(303);await user.page.goto('/workspace');expect((await post(customer,user.page,'/documents/delete')).status()).toBe(303);expect((await document(user,'Fits Free')).status()).toBe(303);
});

test('E17 S15 S16 S18 partial/full refunds assert amounts, references, rights and retained documents',async({app,customer})=>{
 const user=await register(customer,app),order=await paid(user,app);const original=(await app.facts()).invoices[0],bytes=await invoiceBytes(user,original.id);
 const op=randomUUID();expect((await refund(app,user,order,300,op)).status()).toBe(202);expect((await refund(app,user,order,300,op)).status()).toBe(202);let f=await corrections(app,user,2);expect(f.providerRefunds).toBe(1);
 const partial=f.invoices.find(i=>i.kind==='CORRECTION');expect(partial.gross).toBe(300);expect(partial.net+partial.tax).toBe(300);expect(partial.original).toBe(original.number);expect(f.subscriptions[0].status).toBe('ACTIVE');expect((await close(user)).status()).toBe(409);
 const partialBytes=await invoiceBytes(user,partial.id);expect(xmlValue(partialBytes.xml,'GrandTotalAmount')).toContain('3.00');expect(partialBytes.xml).toContain(original.number);expect(xmlValue(partialBytes.xml,'TypeCode')).toContain('381');
 expect((await refund(app,user,order,900,randomUUID())).status()).toBe(202);f=await corrections(app,user,3);await app.until(f=>f.orders[0].status==='CLOSED');f=await app.facts();expect(f.invoices.filter(i=>i.kind==='CORRECTION').reduce((s,i)=>s+i.gross,0)).toBe(1200);expect(f.subscriptions[0].status).toBe('ENDED');expect((await document(user)).status()).toBe(409);
 expect((await close(user)).status()).toBe(303);const retained=await invoiceBytes(user,original.id);expect(sha(retained.pdf)).toBe(sha(bytes.pdf));expect(retained.xml).toBe(bytes.xml);
});

test('E18 S15 S20 lost refund response retains reservation and never duplicates refund',async({app,customer})=>{
 const user=await register(customer,app),order=await paid(user,app);await app.control('faults',{lostRefund:'true'});const operation=randomUUID();expect((await refund(app,user,order,1200,operation)).status()).toBe(202);await app.tick();let f=await app.facts();expect(f.providerRefunds).toBe(1);expect((await refund(app,user,order,1,randomUUID())).status()).toBe(409);expect((await close(user)).status()).toBe(409);
 await app.restart();await app.advance(30);await app.until(f=>f.orders[0].status==='CLOSED',{advance:30});f=await app.facts();expect(f.providerRefunds).toBe(1);expect(f.refunds).toHaveLength(1);await corrections(app,user,2);
});

test('E19 S16 S17 rendering failure keeps one number and recovers its immutable invoice',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await app.control('faults',{render:'true'});await checkout(user);await app.until(f=>f.orders[0].status==='APPLIED',{advance:30});await app.tick();let f=await app.facts();expect(f.invoices).toHaveLength(1);const original=f.invoices[0];expect(original.status).not.toBe('READY');expect(f.orders[0].status).toBe('APPLIED');
 await app.control('faults',{render:'false'});await billing(user);expect((await post(customer,user.page,'/billing/invoice/retry')).status()).toBe(303);f=await corrections(app,user,1);expect(f.invoices[0].number).toBe(original.number);expect(f.invoices[0].id).toBe(original.id);expect(f.providerPayments).toBe(1);
});

test('E20 S17 S20 mail outage preserves invoice and retries one notice after restart',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await app.control('faults',{mail:'true'});await checkout(user);await app.until(f=>f.orders[0].status==='APPLIED',{advance:30});let f=await corrections(app,user,1);await app.tick();expect(f.invoices[0].status).toBe('READY');const bytes=await invoiceBytes(user,f.invoices[0].id);expect((await app.facts()).notices).toHaveLength(1);
 await app.restart();await app.advance(60);await app.until(f=>f.notices[0]?.status==='SIMULATED',{advance:30});f=await app.facts();expect(f.notices).toHaveLength(1);expect(f.providerPayments).toBe(1);expect(sha((await invoiceBytes(user,f.invoices[0].id)).pdf)).toBe(sha(bytes.pdf));
});

test('E21 S20 real renewal database failure cannot starve rendering and mail',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await checkout(user);await app.until(f=>f.orders[0].status==='APPLIED',{advance:30});const before=await app.control('progress');await app.control('permission',{table:'renewal',deny:'true'});
 try{const progress=await app.control('tick');expect(progress.RENEWAL.failed).toBeGreaterThan(before.RENEWAL.failed);expect(progress.RENDER.completed).toBeGreaterThan(before.RENDER.completed);expect(progress.MAIL.completed).toBeGreaterThan(before.MAIL.completed);const f=await corrections(app,user,1);expect(f.notices[0].status).toBe('SIMULATED');}finally{await app.control('permission',{table:'renewal',deny:'false'});}
 expect((await app.control('tick')).RENEWAL.completed).toBeGreaterThan(before.RENEWAL.completed);
});

test('E22 S18 S11 parallel closure versus checkout cannot orphan a new charge',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await billing(user);const orderForm=await form(user.page,'/billing/checkout');await user.page.goto('/auth/account?account='+user.account);const closure=await form(user.page,'/auth/account/close');
 await app.control('barrier',{account:user.account});const closing=customer.request.post('/auth/account/close',{form:closure,maxRedirects:0});const ordering=customer.request.post('/billing/checkout',{form:orderForm,maxRedirects:0});
 try{let blocked=0;for(let i=0;i<100;i++){blocked=(await app.control('blocked')).count;if(blocked>=2)break;await new Promise(r=>setTimeout(r,20));}expect(blocked,'both operations must overlap at the database lock').toBeGreaterThanOrEqual(2);}finally{await app.control('barrier',{action:'release'});}
 const [c,o]=await Promise.all([closing,ordering]);expect([303,409]).toContain(c.status());expect([303,409]).toContain(o.status());expect(c.status()===303&&o.status()===303).toBe(false);
 await app.tick(8);const f=await app.facts();if(f.accounts[0].status==='CLOSED'){expect(f.orders).toHaveLength(0);expect(f.providerPayments).toBe(0);}else{expect(c.status()).toBe(409);expect(f.orders).toHaveLength(1);}
});

test('E23 S18 S13 cancellation boundary and parallel renewal/closure produce no charge',async({app,customer})=>{
 const user=await register(customer,app);await paid(user,app);expect((await action(user,'cancel')).status()).toBe(303);await advanceToEnd(app,user);
 const [closed]=await Promise.all([close(user),app.tick(3)]);expect(closed.status()).toBe(303);const f=await app.facts();expect(f.accounts[0].status).toBe('CLOSED');expect(f.providerPayments).toBe(1);expect(f.orders).toHaveLength(1);
});

test('E24 S18 S15 concurrent full refund and closure retain financial responsibility',async({app,customer})=>{
 const user=await register(customer,app),order=await paid(user,app);const [c,r]=await Promise.all([close(user),refund(app,user,order,1200,randomUUID())]);expect(c.status()).toBe(409);expect(r.status()).toBe(202);expect((await close(user)).status()).toBe(409);await app.until(f=>f.orders[0].status==='CLOSED');expect((await close(user)).status()).toBe(303);expect((await app.facts()).providerRefunds).toBe(1);
});

test('E25 S08 S16 S15 tenant isolation and operator capability reject forged access',async({app,customer,browser})=>{
 const owner=await register(customer,app),order=await paid(owner,app);const invoice=(await app.facts()).invoices[0];const other=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
 try{const foreign=await register(other,app,'foreign@case.example.test');for(const url of [order.url,'/billing/accounts/'+owner.account,'/billing/invoices/'+invoice.id])expect((await other.request.get(url)).status()).toBe(403);
 expect((await other.request.post('/__operator/refunds',{headers:{'Content-Type':'text/plain;charset=UTF-8'},data:[owner.account,order.id,randomUUID(),'1200'].join('\n')})).status()).toBe(403);
 expect((await other.request.post('/__acceptance/facts',{data:{}})).status()).toBe(403);expect((await customer.request.post('/billing/subscription',{form:{account:owner.account,action:'cancel'}})).status()).toBe(403);expect((await other.request.get('/workspace')).status()).toBe(200);expect(await foreign.page.locator('form[action="/documents/delete"]').count()).toBe(0);
 }finally{await other.close();}
});

test('E26 S19 last-owner identity closure is refused until account closes',async({app,customer})=>{
 const user=await register(customer,app);await user.page.goto('/auth/accounts');expect((await post(customer,user.page,'/auth/identity/close')).status()).toBe(409);expect((await close(user)).status()).toBe(303);await user.page.goto('/auth/accounts');expect((await post(customer,user.page,'/auth/identity/close')).status()).toBe(303);expect((await customer.request.get('/auth/accounts')).status()).toBe(401);
});

test('E27 S10 S16 S20 populated database migration replay preserves sessions and fiscal snapshots',async({app,customer})=>{
 const user=await register(customer,app);await paid(user,app);expect((await document(user)).status()).toBe(303);const f=await app.facts(),original=f.invoices[0],bytes=await invoiceBytes(user,original.id);await profile(user,'Changed Future Buyer');await app.restart();
 expect((await customer.request.get('/auth/accounts')).status()).toBe(200);await user.page.goto('/workspace');expect(await user.page.locator('form[action="/documents/delete"]').count()).toBe(1);const retained=await invoiceBytes(user,original.id);expect(sha(retained.pdf)).toBe(sha(bytes.pdf));expect(retained.xml).toBe(bytes.xml);expect(retained.xml).toContain('Customer Snapshot');expect(retained.xml).not.toContain('Changed Future Buyer');const after=await app.facts();expect(after.orders).toHaveLength(1);expect(after.invoices).toHaveLength(1);expect(after.providerPayments).toBe(1);
});

test('E28 S08 S11 S16 machine ingress and tenant roots reject untrusted identifiers',async({app,customer,browser})=>{
 const owner=await register(customer,app);expect((await document(owner)).status()).toBe(303);await owner.page.goto('/workspace');const foreignId=(await form(owner.page,'/documents/delete')).id;await paid(owner,app);const invoice=(await app.facts()).invoices[0];
 const anonymous=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
 try{
  expect((await anonymous.request.get('/__local/mail')).status()).toBe(403);expect((await anonymous.request.get('/billing/invoices/'+invoice.id)).status()).toBe(401);
  const unknown=await anonymous.request.post('/billing/provider-notice',{form:{id:'unknown_resource'}});expect(unknown.status()).toBe(400);expect(unknown.headers()['set-cookie']).toBeUndefined();
  expect((await anonymous.request.post('/billing/provider-notice?account='+owner.account,{form:{id:'unknown_resource'}})).status()).toBe(400);
  expect((await anonymous.request.post('/billing/provider-notice',{headers:{'Content-Type':'application/x-www-form-urlencoded'},data:'x'.repeat(65537)})).status()).toBe(413);
  expect((await customer.request.get('/billing/invoices/'+invoice.id+'?account='+owner.account)).status()).toBe(409);
  const foreign=await register(anonymous,app,'root-isolation@case.example.test');expect((await document(foreign)).status()).toBe(303);await foreign.page.goto('/workspace');const values=await form(foreign.page,'/documents/delete');expect((await anonymous.request.post('/documents/delete',{form:{...values,id:foreignId},maxRedirects:0})).status()).toBe(404);
  expect((await customer.request.post('/documents/delete',{form:{id:foreignId},maxRedirects:0})).status()).toBe(403);
 }finally{await anonymous.close();}
});


test('E29 S11 S16 S20 invoice database outage cannot block payment and recovers one invoice',async({app,customer})=>{
 const user=await register(customer,app);await profile(user);await checkout(user);const before=await app.control('progress');await app.control('permission',{table:'invoice',deny:'true'});
 try{for(let i=0;i<5;i++){await app.tick();await app.advance(30);}const progress=await app.control('progress');expect(progress.INVOICE_SYNC.failed).toBeGreaterThan(before.INVOICE_SYNC.failed);expect(progress.PAYMENT.completed).toBeGreaterThan(before.PAYMENT.completed);}finally{await app.control('permission',{table:'invoice',deny:'false'});}
 const facts=await app.facts();expect(facts.orders[0].status).toBe('APPLIED');expect(facts.subscriptions[0].plan).toBe('pro');expect(facts.invoices).toHaveLength(0);const restored=await corrections(app,user,1);expect(restored.providerPayments).toBe(1);expect(restored.invoices).toHaveLength(1);
});
