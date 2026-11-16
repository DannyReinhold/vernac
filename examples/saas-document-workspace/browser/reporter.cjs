// Fixed, non-sensitive diagnostics: no request headers, account data or token-bearing traces.
const fs=require('node:fs'),path=require('node:path');
const esc=s=>String(s).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('"','&quot;');
module.exports=class Reporter {
 constructor(){this.results=[];this.globalErrors=0;this.steps=new Map();}
 onBegin(config,suite){this.expected=suite.allTests().map(t=>t.title);}
 onStepBegin(test,result,step){if(step.category==='test.step'){this.steps.set(test.id,step.title);}}
 onTestEnd(test,result){
  const row={name:test.title,id:test.title.match(/^E\d+/)?.[0],usecases:[...test.title.matchAll(/S\d{2}/g)].map(x=>x[0]),
   status:result.status,duration:result.duration,line:test.location.line,lastStep:this.steps.get(test.id)||'fixture',errors:result.errors.length,locations:result.errors.flatMap(e=>[...(e.stack||'').matchAll(/(?:fixtures|saas.spec)\.mjs:\d+:\d+/g)].map(m=>m[0]))};
  this.results.push(row);process.stdout.write(row.id+' '+row.status+(row.errors?' at '+row.lastStep+' '+row.locations.join(','):'')+'\n');
 }
 onError(){this.globalErrors++;process.stdout.write("GLOBAL_TEST_RUNNER_ERROR\n");}
 onEnd(result){
  const directory=path.resolve(__dirname,'../target/acceptance-reports');fs.mkdirSync(directory,{recursive:true});
  fs.writeFileSync(path.join(directory,'scenarios.json'),JSON.stringify({status:result.status,globalErrors:this.globalErrors,expected:this.expected,results:this.results},null,2));
  fs.writeFileSync(path.join(directory,'TEST-SaaSScenarios.xml'),'<testsuite name="SaaSScenarios" tests="'+this.results.length+'" failures="'+this.results.filter(r=>r.status!=='passed').length+'" errors="'+this.globalErrors+'" skipped="'+this.results.filter(r=>r.status==='skipped').length+'">'+this.results.map(r=>'<testcase name="'+esc(r.name)+'" time="'+r.duration/1000+'">'+(r.status==='passed'?'':'<failure message="'+esc(r.lastStep)+' at source line '+r.line+'">Scenario failed; sensitive values omitted.</failure>')+'</testcase>').join('')+'</testsuite>');
 }
};
