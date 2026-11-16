// Copyright 2026 Danny Reinhold; SPDX-License-Identifier: Apache-2.0
import {chromium} from "playwright";
import {readFile} from "node:fs/promises";
import {createHash} from "node:crypto";
const path=process.env.IDENTITY_BROWSER_SECRETS_FILE;
const snapshot=JSON.parse(await readFile(path+".snapshot","utf8"));
const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}:{})});
const context=await browser.newContext({baseURL:"https://localhost:8443",ignoreHTTPSErrors:true,storageState:path+".state"});
function check(value,code){if(!value)throw new Error(code);}
try {
 const page=await context.newPage();await page.goto("/workspace");
 check(await page.getByRole("button",{name:"Dokument löschen",exact:true}).count()===4,"restart lost tenant documents or shared session");
 await page.goto("/billing/accounts/"+snapshot.account);
 check(await page.getByRole("link",{name:"PDF / ZUGFeRD",exact:true}).count()===1,"restart duplicated invoice");
 for(const [suffix,hash] of [["",snapshot.pdfHash],["/xml",snapshot.xmlHash]]){
  const response=await context.request.get(snapshot.pdf+suffix);
  check(response.status()===200,"restart denied owned invoice");
  check(createHash("sha256").update(await response.body()).digest("hex")===hash,"restart changed immutable invoice");
 }
 const order=await context.request.get(snapshot.order);
 check(order.status()===200&&(await order.text()).includes("<p>Aktiviert</p>"),"restart lost settled order");
 console.log("SaaS browser restart: shared session, tenant documents, paid order and immutable invoice bytes passed.");
 // Focused lifecycle checks: merchant authority, corrections and retained document access after closure.
 const {request}=await import("playwright");const {randomUUID}=await import("node:crypto");
 const merchant=await request.newContext({baseURL:"https://localhost:8443",ignoreHTTPSErrors:true,extraHTTPHeaders:{Authorization:"Bearer "+process.env.BILLING_OPERATOR_CAPABILITY}});
 try {
  const status=await merchant.post("/__operator/billing-status");check(status.status()===200,"merchant status denied");
  check(Object.keys(await status.json()).sort().join(",")===["INVOICE_SYNC","MAIL","PAYMENT","REFUND","RENDER","RENEWAL"].sort().join(","),"unbounded worker metric labels");
  check((await context.request.post("/__operator/billing-status")).status()===403,"customer worker diagnostics");
  await page.goto("/auth/account?account="+snapshot.account);
  const closure=await page.locator('form[action="/auth/account/close"]').evaluate(f=>Object.fromEntries(new FormData(f)));
  check((await context.request.post("/auth/account/close",{form:closure,maxRedirects:0})).status()===409,"active paid account closed");
  const orderId=snapshot.order.split("/").at(-1);
  async function refund(cents){
   const body=[snapshot.account,orderId,randomUUID(),String(cents)].join("\n");
   const options={headers:{"Content-Type":"text/plain;charset=UTF-8"},data:body};
   check((await context.request.post("/__operator/refunds",options)).status()===403,"customer owner granted merchant refund authority");
   const response=await merchant.post("/__operator/refunds",options);check(response.status()===202&&!response.headers()["set-cookie"],"merchant refund registration");
   const repeated=await merchant.post("/__operator/refunds",options);check(repeated.status()===202&&(await repeated.json()).refund===(await response.json()).refund,"refund request identity");
  }
  async function invoices(count){
   const until=Date.now()+45000;
   while(Date.now()<until){await page.goto("/billing/accounts/"+snapshot.account);if(await page.getByRole("link",{name:"PDF / ZUGFeRD",exact:true}).count()===count)return;await new Promise(r=>setTimeout(r,300));}
   throw new Error("verified correction missing");
  }
  await refund(300);await invoices(2);
  check((await context.request.get(snapshot.order)).status()===200,"partial refund lost order access");
  const partial=await context.request.get(snapshot.pdf);check(createHash("sha256").update(await partial.body()).digest("hex")===snapshot.pdfHash,"partial refund changed original invoice");
  await refund(900);await invoices(3);
  const until=Date.now()+15000;let closed=false;
  while(Date.now()<until){const response=await context.request.post("/auth/account/close",{form:closure,maxRedirects:0});if(response.status()===303){closed=true;break;}check(response.status()===409,"unexpected closure failure");await new Promise(r=>setTimeout(r,300));}
  check(closed,"settled fully reversed account could not close");
  const workspace=await context.request.get("/workspace",{maxRedirects:0});
  check(workspace.status()===403 || workspace.status()===303&&workspace.headers().location==="/auth/accounts","closed account still exposes tenant workspace");
  for(const [suffix,hash] of [["",snapshot.pdfHash],["/xml",snapshot.xmlHash]]){
   const response=await context.request.get(snapshot.pdf+suffix);check(response.status()===200,"closed account lost retained invoice ownership");
   check(createHash("sha256").update(await response.body()).digest("hex")===hash,"closed account changed fiscal snapshot");
  }
  console.log("SaaS lifecycle: merchant-only idempotent partial/full refunds, correction documents, paid-closure denial and retained invoices after closure passed.");
 }finally{await merchant.dispose();}
} finally {await context.close();await browser.close();}
