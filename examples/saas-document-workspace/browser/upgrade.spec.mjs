// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {test,expect,register,bearer,post,form,paid,profile,action,refund} from './fixtures.mjs';
import {readFile} from 'node:fs/promises';
import {createHash,randomUUID} from 'node:crypto';
const sha=b=>createHash('sha256').update(b).digest('hex');
async function document(user,title,text){await user.page.goto('/workspace');expect((await post(user.context,user.page,'/documents/create',{title,text})).status()).toBe(303);}
async function bytes(user,id){const pdf=await user.context.request.get('/billing/invoices/'+id),xml=await user.context.request.get('/billing/invoices/'+id+'/xml');expect(pdf.status()).toBe(200);expect(xml.status()).toBe(200);return {pdf:sha(await pdf.body()),xml:sha(await xml.body())};}
async function select(user,account){await user.page.goto('/auth/accounts');const values=await user.page.locator('form[action="/auth/account/select"]').filter({has:user.page.locator('input[value="'+account+'"]')}).evaluate(f=>Object.fromEntries(new FormData(f)));expect((await user.context.request.post('/auth/account/select',{form:values,maxRedirects:0})).status()).toBe(303);user.account=account;await user.page.goto('/workspace');}
async function restored(browser,app,expected){const users={};for(const [name,saved] of Object.entries(expected.users)){const context=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true,storageState:saved.storage});users[name]={...saved,context,page:await context.newPage()};app.secrets.push(saved.address);}return users;}
const capturing=!!process.env.VERNAC_UPGRADE_CAPTURE;
if(capturing){
 test('capture synthetic 0.6.0 candidate through public application boundaries',async({app,customer,browser})=>{
  const owner=await register(customer,app,'baseline-owner@case.example.test');
  const memberContext=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
  const pendingContext=await browser.newContext({baseURL:app.origin,ignoreHTTPSErrors:true});
  try{
   const member=await register(memberContext,app,'baseline-member@case.example.test');
   const pending=await register(pendingContext,app,'baseline-pending@case.example.test');
   const order=await paid(owner,app);
   await document(owner,'Unicode äöü — 你好','Retained document <markup> & exact text');
   await document(owner,'Second paid document','Paid capacity persists');
   await document(owner,'Third paid document','Capacity boundary');
   await document(owner,'Fourth paid document','Over Free capacity');
   await document(member,'Foreign private document','Must remain isolated');
   await member.page.goto('/workspace');
   for(let i=0;i<3;i++)expect((await post(member.context,member.page,'/documents/export')).status()).toBe(i<2?200:409);
   // Organization and reader membership, alongside each user's personal account.
   await owner.page.goto('/auth/accounts');expect((await post(customer,owner.page,'/auth/account/create')).status()).toBe(303);
   const org=(await app.facts()).accounts.find(a=>![owner.account,member.account,pending.account].includes(a.id)).id;
   const personal=owner.account;await select(owner,org);await document(owner,'Shared organization document','Read-only membership');
   await owner.page.goto('/auth/account?account='+org);expect((await post(customer,owner.page,'/auth/account/invite',{address:member.address,roles:'example.workspace_reader'})).status()).toBe(202);
   expect((await bearer(member.page,await app.mail(member.address,'invitation'))).status()).toBe(303);await member.page.waitForURL('**/auth/accounts');
   await select(owner,personal);
   expect((await refund(app,owner,order,300,randomUUID())).status()).toBe(202);
   await app.until(f=>f.invoices.filter(i=>i.account===owner.account&&i.status==='READY').length===2);
   await profile(owner,'Future customer profile');expect((await action(owner,'schedule',{plan:'free',revision:'v1'})).status()).toBe(303);
   await profile(pending);await app.control('faults',{scenario:'PENDING'});
   const {checkout}=await import('./fixtures.mjs');const pendingOrder=await checkout(pending);
   await app.until(f=>f.attempts.some(a=>a.order===pendingOrder.id&&a.state==='PENDING'),{advance:30});
   await app.tick(3);const facts=await app.facts();
   expect(facts.accounts).toHaveLength(4);expect(facts.providerPayments).toBe(2);expect(facts.providerRefunds).toBe(1);
   const invoices={};for(const invoice of facts.invoices)invoices[invoice.id]=await bytes(owner,invoice.id);
   const users={};for(const [name,user] of Object.entries({owner,member,pending}))users[name]={account:user.account,address:user.address,storage:await user.context.storageState()};
   await app.advance(0);
   await app.snapshot(process.env.VERNAC_UPGRADE_CAPTURE,{users,facts,invoices,organization:org,paidOrder:order.id,pendingOrder:pendingOrder.id});
  }finally{await memberContext.close();await pendingContext.close();}
 });
}else{
 test('U01 versioned data, sessions, RLS, memberships and immutable invoices survive restore and repeated startup',async({app,browser})=>{
  const expected=JSON.parse(await readFile(process.env.VERNAC_UPGRADE_BASELINE+'/expectations.json','utf8'));
  const users=await restored(browser,app,expected);
  try{
   expect(await app.facts()).toEqual(expected.facts);
   for(const user of Object.values(users))expect((await user.context.request.get('/auth/accounts')).status()).toBe(200);
   const {owner,member}=users;await owner.page.goto('/workspace');
   expect(await owner.page.locator('form[action="/documents/delete"]').count()).toBe(4);
   await expect(owner.page.getByText('Unicode äöü — 你好',{exact:true})).toBeVisible();
   const foreign=(await form(owner.page,'/documents/delete')).id;
   for(const [id,hashes] of Object.entries(expected.invoices)){
    expect(await bytes(owner,id)).toEqual(hashes);expect((await member.context.request.get('/billing/invoices/'+id)).status()).toBe(403);
   }
   await member.page.goto('/workspace');expect(await member.page.locator('form[action="/documents/delete"]').count()).toBe(1);
   expect((await post(member.context,member.page,'/documents/export')).status()).toBe(409);
   expect((await post(member.context,member.page,'/documents/create',{title:'Too large',text:'x'.repeat(1001)})).status()).toBe(409);
   const values=await form(member.page,'/documents/delete');expect((await member.context.request.post('/documents/delete',{form:{...values,id:foreign},maxRedirects:0})).status()).toBe(404);
   await select(member,expected.organization);await member.page.goto('/workspace');
   await expect(member.page.getByText('Shared organization document',{exact:true})).toBeVisible();
   expect((await post(member.context,member.page,'/documents/create',{title:'Forbidden',text:'Reader'})).status()).toBe(403);
   expect((await member.context.request.get('/billing/accounts/'+expected.organization)).status()).toBe(403);
   await app.history();await app.restart();await app.history();
   expect(await app.facts()).toEqual(expected.facts);
   for(const [id,hashes] of Object.entries(expected.invoices))expect(await bytes(owner,id)).toEqual(hashes);
  }finally{for(const user of Object.values(users))await user.context.close();}
 });
 test('U02 restored pending payment and partial refund continue without duplicate financial effects',async({app,browser})=>{
  const expected=JSON.parse(await readFile(process.env.VERNAC_UPGRADE_BASELINE+'/expectations.json','utf8'));
  const users=await restored(browser,app,expected);
  try{
   await app.advance(60);await app.until(f=>f.orders.find(o=>o.id===expected.pendingOrder).status==='APPLIED',{advance:30});
   expect((await app.facts()).providerPayments).toBe(2);
   const response=await refund(app,users.owner,{id:expected.paidOrder},900,randomUUID());expect(response.status()).toBe(202);
   const f=await app.until(f=>f.orders.find(o=>o.id===expected.paidOrder).status==='CLOSED',{advance:30});
   expect(f.providerRefunds).toBe(2);expect(f.refunds.reduce((sum,r)=>sum+r.amount,0)).toBe(1200);
   await app.until(f=>f.invoices.filter(i=>i.kind==='CORRECTION'&&i.status==='READY').length===2);
   for(const [id,hashes] of Object.entries(expected.invoices))expect(await bytes(users.owner,id)).toEqual(hashes);
   await app.restart();await app.tick(5);expect((await app.facts()).providerPayments).toBe(2);expect((await app.facts()).providerRefunds).toBe(2);await app.history();
  }finally{for(const user of Object.values(users))await user.context.close();}
 });
}
