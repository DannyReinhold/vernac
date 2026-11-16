// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {chromium,request} from "playwright";
const origin="https://localhost:8443",capability=process.env.IDENTITY_PREVIEW_CAPABILITY;
const password="an independent browser fixture passphrase 83957",replacement="another independent browser fixture phrase 19238";
function check(value,code){if(!value)throw new Error(code);}
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}:{})});
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
 for(const cookie of await page.context().cookies()){secrets.push(cookie.value);if(cookie.name==="__Host-VERNACSESSION")secrets.push(Buffer.from(cookie.value,"base64").toString("utf8"));}
}
try{
 check((await anonymous.request.get("/__local/mail")).status()===403,"anonymous local inbox");
 check((await anonymous.request.get("/billing")).status()===401,"anonymous billing");
 const page=await register(owner,"browser-owner@example.test");await login(page,"browser-owner@example.test",password);
 const personal=await page.locator('form[action="/auth/account/select"] input[name="account"]').first().inputValue();
 await page.getByRole("button",{name:"Open workspace",exact:true}).first().click();await page.waitForURL("**/workspace");
 await page.getByRole("heading",{name:"Deine Dokumente",exact:true}).waitFor();
 async function create(title,status=303){await page.goto("/workspace");await page.getByLabel("Titel",{exact:true}).fill(title);await page.getByLabel("Text",{exact:true}).fill("Product content");check((await submit(page,"Dokument anlegen")).status()===status,"capacity outcome");}
 await create("First");await create("Second");await create("Third");await create("Over capacity",409);
 await page.goto("/workspace");check(await page.getByRole("button",{name:"Dokument löschen",exact:true}).count()===3,"capacity count");
 check((await owner.request.post("/documents/delete",{form:{id:"00000000-0000-0000-0000-000000000001"}})).status()===403,"product csrf");
 for(let i=0;i<3;i++){await page.goto("/workspace");const form=page.locator('form[action="/documents/export"]').first();const values=await form.evaluate(f=>Object.fromEntries(new FormData(f)));const response=await owner.request.post("/documents/export",{form:values});check(response.status()===(i<2?200:409),"local quota");}
 await page.goto("/billing/accounts/"+personal);await page.getByRole("heading",{name:"Tarif und Abrechnung",exact:true}).waitFor();
 check((await owner.request.post("/billing/subscription",{form:{account:personal,action:"cancel"}})).status()===403,"billing csrf");
 await page.getByLabel("Name / Unternehmen",{exact:true}).fill("Browser Customer");await page.getByLabel("Straße und Hausnummer",{exact:true}).fill("Example 1");await page.getByLabel("Postleitzahl",{exact:true}).fill("28199");await page.getByLabel("Ort",{exact:true}).fill("Bremen");await page.getByLabel("E-Mail",{exact:true}).fill("browser-owner@example.test");check((await submit(page,"Rechnungsadresse speichern")).status()===303,"profile save");
 await page.waitForURL("**/billing/accounts/"+personal);
 const checkout=page.locator('form[action="/billing/checkout"]').first();const values=await checkout.evaluate(f=>Object.fromEntries(new FormData(f)));
 const ordered=await owner.request.post("/billing/checkout",{form:values,maxRedirects:0});check(ordered.status()===303,"checkout creation");const orderUrl=ordered.headers().location;
 const duplicate=await owner.request.post("/billing/checkout",{form:values,maxRedirects:0});check(duplicate.status()===303&&duplicate.headers().location===orderUrl,"stable checkout identity");
 const deadline=Date.now()+45000;let activated=false;
 while(Date.now()<deadline){const response=await owner.request.get(orderUrl);if((await response.text()).includes("<p>Aktiviert</p>")){activated=true;break;}await new Promise(r=>setTimeout(r,300));}check(activated,"verified local payment did not activate");
 await create("Paid capacity");
 let pdfPath;const invoiceDeadline=Date.now()+45000;
 while(Date.now()<invoiceDeadline){await page.goto("/billing/accounts/"+personal);const links=page.getByRole("link",{name:"PDF / ZUGFeRD",exact:true});if(await links.count()){pdfPath=await links.first().getAttribute("href");break;}await new Promise(r=>setTimeout(r,300));}check(pdfPath,"issued invoice missing");
 const pdf=await owner.request.get(pdfPath);check(pdf.status()===200&&pdf.headers()["content-type"].startsWith("application/pdf"),"protected PDF");check((await pdf.body()).subarray(0,5).toString()==="%PDF-","PDF content");
 const xml=await owner.request.get(pdfPath+"/xml");check(xml.status()===200&&(await xml.text()).includes("CrossIndustryInvoice"),"protected XML");
 check((await anonymous.request.get(pdfPath)).status()===401,"anonymous invoice");
 const foreign=await register(recipient,"browser-recipient@example.test");await login(foreign,"browser-recipient@example.test",password);
 check((await recipient.request.get("/billing/accounts/"+personal)).status()===403,"foreign billing");check((await recipient.request.get(pdfPath)).status()===403,"foreign invoice");check((await recipient.request.get(orderUrl)).status()===403,"foreign payment");
 check((await owner.request.get(pdfPath+"?account="+personal)).status()===409,"invoice query override");
 const {createHash}=await import("node:crypto");
 const snapshot={account:personal,pdf:pdfPath,order:orderUrl,pdfHash:createHash("sha256").update(await pdf.body()).digest("hex"),xmlHash:createHash("sha256").update(await xml.body()).digest("hex")};await owner.storageState({path:process.env.IDENTITY_BROWSER_SECRETS_FILE+".state"});
 await import("node:fs/promises").then(fs=>fs.writeFile(process.env.IDENTITY_BROWSER_SECRETS_FILE+".snapshot",JSON.stringify(snapshot)));
 console.log("SaaS browser: Free capacity, quota, verified paid activation, idempotent checkout and protected invoice assertions passed.");
}finally{
 await import("node:fs/promises").then(fs=>fs.writeFile(process.env.IDENTITY_BROWSER_SECRETS_FILE,JSON.stringify(secrets)));
 await owner.close();await recipient.close();await anonymous.close();await operator.dispose();await browser.close();
}
