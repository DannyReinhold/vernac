// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {chromium,request} from "playwright";
const origin="https://localhost:8443",capability=process.env.IDENTITY_PREVIEW_CAPABILITY;
const password="an independent browser fixture passphrase 83957",replacement="another independent browser fixture phrase 19238";
function check(value,code){if(!value)throw new Error(code);}
const browser=await chromium.launch({headless:true});
const operator=await request.newContext({baseURL:origin,ignoreHTTPSErrors:true,extraHTTPHeaders:{Authorization:"Bearer "+capability}});
const owner=await browser.newContext({baseURL:origin,ignoreHTTPSErrors:true});
const recipient=await browser.newContext({baseURL:origin,ignoreHTTPSErrors:true});
const anonymous=await browser.newContext({baseURL:origin,ignoreHTTPSErrors:true});
const secrets=[];
async function mail(address,route){
 const deadline=Date.now()+15000;
 while(Date.now()<deadline){
  const response=await operator.get("/__local/mail");check(response.status()===200,"local inbox denied");
  const items=await response.json();
  for(const item of items.slice().reverse()){
   if(item.recipient!==address)continue;
   const match=item.text.match(new RegExp("https://localhost:8443/auth/"+route+"#[^\\s]+"));
   if(match){secrets.push(new URL(match[0]).hash.substring(1));return match[0];}
  }
  await new Promise(resolve=>setTimeout(resolve,100));
 }
 throw new Error("committed local mail missing");
}
async function submit(page,label="Continue"){
 const response=page.waitForResponse(r=>r.request().method()==="POST");
 await page.getByRole("button",{name:label,exact:true}).click();return response;
}
async function bearer(page,link,withPassword){
 await page.goto(link);await page.locator("#confirm-action").waitFor();
 await page.waitForFunction(()=>location.hash==="" && document.getElementById("confirm-action").disabled===false);
 check(!page.url().includes("#"),"fragment not scrubbed");
 check(await page.evaluate(()=>localStorage.length===0&&sessionStorage.length===0),"browser storage contains data");
 check(await page.locator("#action-token").inputValue()===new URL(link).hash.substring(1),"token handoff failed");
 if(withPassword)await page.getByLabel("New password", {exact:false}).fill(withPassword);
 return submit(page);
}
async function register(context,address){
 const page=await context.newPage();await page.goto("/auth/register");await page.getByLabel("Email address").fill(address);
 check((await submit(page)).status()===202,"registration response");
 check((await bearer(page,await mail(address,"verify"),password)).status()===303,"verification response");
 await page.waitForURL("**/identity/login");
 check((await context.request.get("/auth/accounts")).status()===401,"verification auto logged in");
 return page;
}
async function login(page,address,secret){
 await page.goto("/identity/login");await page.getByLabel("Email address").fill(address);await page.getByLabel("Password",{exact:true}).fill(secret);
 check((await submit(page,"Sign in")).status()===303,"login response");
 await page.waitForURL("**/auth/accounts");
}
try{
 check((await anonymous.request.get("/__local/mail")).status()===403,"anonymous local inbox");
 const rejected=await anonymous.request.post("/auth/register",{form:{address:"csrf-fixture@example.test"}});
 check(rejected.status()===403,"missing csrf accepted");
 const ownerPage=await register(owner,"browser-owner@example.test");
 await login(ownerPage,"browser-owner@example.test",password);
 const cookie=(await owner.cookies()).find(c=>c.name==="__Host-VERNACSESSION");
 check(cookie&&cookie.secure&&cookie.httpOnly&&cookie.path==="/"&&cookie.sameSite==="Lax","cookie policy");
 const personal=await ownerPage.locator('form[action="/auth/account/select"] input[name="account"]').first().inputValue();
 const selectResponse=ownerPage.waitForResponse(r=>r.request().method()==="POST"&&r.url().endsWith("/auth/account/select"));
 await ownerPage.getByRole("button",{name:"Open workspace",exact:true}).first().click();
 check((await selectResponse).status()===303,"selection");
 await ownerPage.waitForURL("**/workspace");await ownerPage.getByRole("heading",{name:"Your workspace",exact:true}).waitFor();
 const memberPage=await register(recipient,"browser-recipient@example.test");
 await login(memberPage,"browser-recipient@example.test",password);
 await ownerPage.goto("/auth/account?account="+personal);
 await ownerPage.getByLabel("Email address").fill("browser-recipient@example.test");
 await ownerPage.locator('form[action="/auth/account/invite"] input[name="roles"]').fill("example.workspace_reader");
 check((await submit(ownerPage,"Send invitation")).status()===202,"invitation request");
 const invitation=await mail("browser-recipient@example.test","invitation");
 check((await bearer(memberPage,invitation)).status()===303,"invitation acceptance");
 await memberPage.waitForURL("**/auth/accounts");
 const select=memberPage.locator('form[action="/auth/account/select"]').filter({has:memberPage.locator('input[value="'+personal+'"]')});
 await select.getByRole("button",{name:"Open workspace",exact:true}).click();
 await memberPage.waitForURL("**/workspace");await memberPage.getByRole("heading",{name:"Your workspace",exact:true}).waitFor();
 const recovery=await anonymous.newPage();await recovery.goto("/auth/recovery-request");
 await recovery.getByLabel("Email address").fill("browser-owner@example.test");check((await submit(recovery)).status()===202,"recovery request");
 check((await bearer(recovery,await mail("browser-owner@example.test","recover"),replacement)).status()===303,"recovery completion");
 check((await owner.request.get("/auth/accounts")).status()===401,"old session survived recovery");
 const relogin=await owner.newPage();await login(relogin,"browser-owner@example.test",replacement);
 await relogin.goto("/auth/password");await relogin.getByLabel("Current password").fill(replacement);
 await relogin.getByLabel("New password",{exact:false}).fill(password);
 check((await submit(relogin)).status()===303,"password change");
 check((await owner.request.get("/auth/accounts")).status()===401,"password change kept session");
 console.log("Chromium verified registration, fragment scrubbing, CSRF, cookie policy, workspace selection, invitation and password/session revocation.");
 // Only the parent process receives this private file for an exact no-secret log assertion.
 const fs=await import("node:fs/promises");await fs.writeFile(process.env.IDENTITY_BROWSER_SECRETS_FILE,JSON.stringify(secrets),{mode:0o600});
}catch(error){console.error("Identity Chromium acceptance failed.");process.exitCode=1;}
finally{await Promise.all([operator.dispose(),owner.close(),recipient.close(),anonymous.close()]);await browser.close();}
