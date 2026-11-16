"""The 0.6.0 gate must reject incomplete, stale, skipped and changed candidates."""
import copy, importlib.util, json, pathlib, tempfile, unittest
spec=importlib.util.spec_from_file_location('release_gate',pathlib.Path(__file__).parents[1]/'release.py')
release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)
class AcceptanceTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=pathlib.Path(self.temp.name);self.sha='a'*40
  self.bundle={'schema_version':1,'version':'0.6.0','source_sha':self.sha,'decisions':{k:'ACCEPTED' for k in release.DECISION_IDS},'blocking_findings':[],
   'requirements':{k:{'status':'VERIFIED','evidence':['review']} for k in release.ACCEPTANCE_IDS},'evidence':{},'artifacts':{},'dependencies':{},'dependency_reports':{}}
  for name,kind in [('review','review'),('mollie','sandbox'),('stripe','sandbox'),('brevo','sandbox'),('ide','ide-smoke')]:
   p=self.file('evidence/'+name+'.txt','fixture '+name)
   self.bundle['evidence'][name]={'kind':kind,'status':'PASSED','source_sha':self.sha,'file':str(p.relative_to(self.root)),'sha256':release.digest(p),'reviewed_by':'fixture-reviewer','provider':name}
  self.bundle['requirements']['A09']['evidence']=['mollie','stripe'];self.bundle['requirements']['A11']['evidence']=['brevo'];self.bundle['requirements']['A16']['evidence']=['ide']
  for suite in release.REQUIRED_TESTS:
   p=self.file('reports/'+suite+'.xml','<testsuite name="org.vernac.'+suite+'" tests="1" failures="0" errors="0" skipped="0"><testcase name="fixture"/></testsuite>')
   if suite=='SaaSScenarios':
    p.write_text('<testsuite name="SaaSScenarios" tests="29" failures="0" errors="0" skipped="0">'+''.join(f'<testcase name="E{i:02} S{(i-1)%20+1:02} fixture"/>' for i in range(1,30))+'</testsuite>')
   self.bundle['evidence']['suite-'+suite]={'kind':'junit','status':'PASSED','source_sha':self.sha,'file':str(p.relative_to(self.root)),'sha256':release.digest(p)}
  self.file('tests/upgrades/registry.json',json.dumps({'minimum_supported':'0.6.0','baselines':['0.6.0']}))
  paths={name:self.file('tests/upgrades/0.6.0/'+name,'synthetic-fixture') for name in ['database.sql','expectations.json','test-runtime.json']}
  migration=self.file('fixture/migration.sql','fixture migration')
  manifest={'version':'0.6.0','state':'FROZEN','synthetic_only':True,'files':{name:release.digest(path) for name,path in paths.items()},'migration_files':{'fixture/migration.sql':release.digest(migration)}}
  mp=self.file('tests/upgrades/0.6.0/manifest.json',json.dumps(manifest))
  record=self.bundle['evidence']['suite-UpgradeBaseline'];up=self.root/record['file']
  up.write_text('<testsuite name="UpgradeBaseline" tests="2" failures="0" errors="0" skipped="0"><properties><property name="baseline.0.6.0" value="'+release.digest(mp)+'"/></properties><testcase name="0.6.0 U01 data"/><testcase name="0.6.0 U02 pending"/></testsuite>')
  record['sha256']=release.digest(up)
  for module in set(release.published_modules('0.6.0'))|{'vernac-intellij'}:
   filename='vernac-intellij-0.6.0-plugin.zip' if module=='vernac-intellij' else '.flattened-pom.xml' if module=='vernac' else module+'-0.6.0.jar'
   p=self.file('artifacts/'+module+'/'+filename,'fixture-artifact-'+module)
   self.bundle['artifacts'][module]={'file':str(p.relative_to(self.root)),'sha256':release.digest(p)}
   if module!='vernac':
    dep=self.file('dependencies/'+module+'.jar','fixture-dependency');self.bundle['dependencies'][str(dep)]=release.digest(dep)
    report=self.file('classpaths/'+module+'.txt',str(dep))
    self.bundle['dependency_reports'][module]={'file':str(report.relative_to(self.root)),'sha256':release.digest(report)}
  self.inventory={'requirements':copy.deepcopy(self.bundle['requirements']),'decisions':copy.deepcopy(self.bundle['decisions']),'recommendation':'GO','blocking_findings':[],
   'module_inventory':list(release.published_modules('0.6.0'))+['vernac-intellij'],'required_suites':list(set(release.REQUIRED_TESTS))}
  self.bundle['approval']={'decision':'GO','approved_by':'fixture-maintainer','source_sha':self.sha,'plugin_sha256':self.bundle['artifacts']['vernac-intellij']['sha256']}
 def file(self,name,contents):
  p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(contents);return p
 def validate(self):
  self.file('docs/development/saas-release-inventory-0.6.0.json',json.dumps(self.inventory))
  p=self.file('acceptance.json',json.dumps(self.bundle));return release.acceptance(p,'0.6.0',self.sha,self.root)
 def test_complete_fixture_passes(self):self.validate()
 def test_every_required_capability_must_remain_present_and_verified(self):
  for key in release.ACCEPTANCE_IDS:
   with self.subTest(key=key):
    saved=self.bundle['requirements'].pop(key)
    with self.assertRaisesRegex(ValueError,'requirement'):self.validate()
    self.bundle['requirements'][key]=saved
    saved['status']='UNKNOWN'
    with self.assertRaisesRegex(ValueError,'Unverified'):self.validate()
    saved['status']='VERIFIED'
 def test_stale_source_or_record_is_rejected(self):
  self.bundle['source_sha']='b'*40
  with self.assertRaisesRegex(ValueError,'Stale'):self.validate()
  self.bundle['source_sha']=self.sha;self.bundle['evidence']['review']['source_sha']='b'*40
  with self.assertRaisesRegex(ValueError,'stale evidence'):self.validate()
 def test_missing_required_report_and_skipped_report_are_rejected(self):
  key='suite-'+release.REQUIRED_TESTS[0];record=self.bundle['evidence'].pop(key)
  with self.assertRaisesRegex(ValueError,'Missing required'):self.validate()
  self.bundle['evidence'][key]=record;p=self.root/record['file'];p.write_text('<testsuite name="fixture" tests="1" skipped="1"/>');record['sha256']=release.digest(p)
  with self.assertRaisesRegex(ValueError,'skipped'):self.validate()
 def test_changed_artifact_and_dependency_are_rejected(self):
  p=self.root/self.bundle['artifacts']['vernac-intellij']['file'];old=p.read_text();p.write_text('changed')
  with self.assertRaisesRegex(ValueError,'Artifact digest'):self.validate()
  p.write_text(old);path=next(iter(self.bundle['dependencies']));pathlib.Path(path).write_text('changed')
  with self.assertRaisesRegex(ValueError,'Dependency digest'):self.validate()
 def test_partial_dependency_inventory_is_rejected(self):
  self.bundle['dependencies'].pop(next(iter(self.bundle['dependencies'])))
  with self.assertRaisesRegex(ValueError,'Incomplete dependency'):self.validate()
 def test_simulator_cannot_replace_real_provider_evidence(self):
  self.bundle['evidence']['stripe']['provider']='local'
  with self.assertRaisesRegex(ValueError,'Real provider'):self.validate()
 def test_unknown_decision_blocker_missing_approval_or_wrong_candidate_blocks(self):
  for field,value in [('decisions',{**self.bundle['decisions'],'D99':'ACCEPTED'}),('blocking_findings',['open']),('approval',{}),('approval',{**self.bundle['approval'],'plugin_sha256':'f'*64})]:
   old=self.bundle[field];self.bundle[field]=value
   with self.assertRaises(ValueError):self.validate()
   self.bundle[field]=old
 def test_repository_blocker_cannot_be_overridden_by_external_go(self):
  self.inventory['blocking_findings']=['unresolved-lifecycle']
  with self.assertRaisesRegex(ValueError,'Source inventory retains'):self.validate()
  self.inventory['blocking_findings']=[];self.inventory['required_suites'].pop()
  with self.assertRaisesRegex(ValueError,'inventory is incomplete'):self.validate()
 def test_outside_evidence_path_is_rejected(self):
  self.bundle['evidence']['review']['file']='../outside.txt'
  with self.assertRaisesRegex(ValueError,'unsafe evidence'):self.validate()

 def test_saas_report_cannot_hide_missing_or_duplicated_scenarios(self):
  record=self.bundle['evidence']['suite-SaaSScenarios'];path=self.root/record['file'];good=path.read_text()
  for changed in (good.replace('E29 ','E28 '),good.replace('<testcase name="E29 S09 fixture"/>',''),good.replace('S20 fixture','S19 fixture')):
   path.write_text(changed);record['sha256']=release.digest(path)
   with self.assertRaisesRegex(ValueError,'SaaS'):self.validate()
  path.write_text(good)

 def test_upgrade_gate_rejects_candidate_stale_or_missing_cases(self):
  mp=self.root/'tests/upgrades/0.6.0/manifest.json';original=mp.read_text()
  manifest=json.loads(original);manifest['state']='CANDIDATE';mp.write_text(json.dumps(manifest))
  with self.assertRaisesRegex(ValueError,'frozen baseline'):self.validate()
  mp.write_text(original)
  record=self.bundle['evidence']['suite-UpgradeBaseline'];path=self.root/record['file'];good=path.read_text()
  for invalid in (good.replace('U02 pending','U01 pending'),good.replace('tests="2"','tests="0"')):
   path.write_text(invalid);record['sha256']=release.digest(path)
   with self.assertRaisesRegex(ValueError,'UpgradeBaseline'):self.validate()
  path.write_text(good);record['sha256']=release.digest(path)
  self.file('tests/upgrades/0.6.0/database.sql','changed')
  with self.assertRaisesRegex(ValueError,'Baseline file changed'):self.validate()

if __name__=='__main__':unittest.main()
