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
} finally {await context.close();await browser.close();}
