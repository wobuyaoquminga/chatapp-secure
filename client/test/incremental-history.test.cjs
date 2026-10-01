'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Vault}=require('../vault.cjs'),{Controller}=require('../controller.cjs');
const storage={isEncryptionAvailable:()=>true,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()};
const message=i=>({sender:'alice',recipient:'bob',clientId:String(i),body:'历史 '+i,createdAt:new Date(1700000000000+i).toISOString(),status:'已保存',ciphertext:'encrypted-'+i});
test('legacy history migrates losslessly and failed protocol commit discards the uncommitted history suffix',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-delta-'));
 try{
  let vault=new Vault(dir,'http://localhost','alice',storage);
  const original={identity:'private',messages:{'alice:1':message(1)},outbox:{},verified:{}};
  // Legacy v1 encrypted checkpoint remains intact as the prefix after migration.
  const legacy=Buffer.from(JSON.stringify(original)).toString('base64')+'\n';fs.writeFileSync(vault.file,legacy);
  assert.deepEqual(vault.read(),original);vault.write(original,{});
  const committed=fs.readFileSync(vault.file),offset=vault.historyEnd;
  const finalLine=committed.toString().trim().split('\n').at(-1);
  assert.throws(()=>JSON.parse(storage.decryptString(Buffer.from(finalLine,'base64'))),'a legacy JSON-only vault reader must refuse the migrated ratchet');
  const append=fs.appendFileSync;fs.appendFileSync=(file,...args)=>{append(file,...args);if(file===vault.file)throw Error('injected fsync failure');};
  try{assert.throws(()=>vault.write({...original,messages:{'alice:1':message(1),'alice:2':message(2)}},{'alice:2':message(2)}),/fsync/);}finally{fs.appendFileSync=append;}
  assert.deepEqual(fs.readFileSync(vault.file),committed);
  assert(fs.statSync(vault.historyFile).size>offset);
  vault=new Vault(dir,'http://localhost','alice',storage);
  assert.deepEqual(vault.read(),original);
  const updated={...original,messages:{'alice:1':{...message(1),status:'送达'}}};
  vault.write(updated,updated.messages);assert.deepEqual(new Vault(dir,'http://localhost','alice',storage).read(),updated);
  assert(fs.readFileSync(vault.file).subarray(0,Buffer.byteLength(legacy)).equals(Buffer.from(legacy)));
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('a corrupted committed history fails closed and cannot overwrite the original vault',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-delta-'));
 try{
  const vault=new Vault(dir,'http://localhost','alice',storage);vault.write({messages:{'alice:1':message(1)}});
  const original=fs.readFileSync(vault.file);fs.writeFileSync(vault.historyFile,Buffer.alloc(vault.historyEnd,65));
  assert.throws(()=>new Vault(dir,'http://localhost','alice',storage).read(),/损坏/);
  assert.deepEqual(fs.readFileSync(vault.file),original);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
test('20,000-message history appends one record and protocol checkpoint, retains untouched objects and reopens',async t=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-history-perf-'));const sizes=[];
 const measured={...storage,encryptString:s=>{sizes.push(Buffer.byteLength(s));return Buffer.from(s);}};
 const c=new Controller(dir,measured,()=>{});c.server='http://localhost';c.user='alice';c.vault=new Vault(path.join(dir,'vaults'),c.server,c.user,measured);
 c.engine={state:{identity:'private',messages:Object.fromEntries(Array.from({length:20000},(_,i)=>['alice:'+i,message(i)])),outbox:{},verified:{},sessions:{},trusted:{}}};
 try{
  let started=performance.now();c.vault.write(c.engine.state);t.diagnostic('initial history write ms='+Math.round(performance.now()-started));
  c.rebuildMessageMetadata();const untouched=c.engine.state.messages['alice:0'];sizes.length=0;
  started=performance.now();await c.transaction(()=>{c.engine.state.messages['alice:20000']=message(20000);});
  await c.transaction(()=>{c.engine.state.messages['alice:20000'].status='送达';});
  assert.equal(c.engine.state.messages['alice:0'],untouched);
  assert.equal(Object.keys(c.snapshot(false).messageChanges).length,1);assert.equal(c.snapshot(false).messages,undefined);
  assert(sizes.every(n=>n<1000),'incremental writes must never encrypt the entire history: '+sizes);
  t.diagnostic('two incremental commits ms='+Math.round(performance.now()-started)+'; encrypted payload bytes='+sizes.join(','));
  started=performance.now();const restored=new Vault(path.join(dir,'vaults'),c.server,c.user,measured).read();
  assert.equal(Object.keys(restored.messages).length,20001);assert.equal(restored.messages['alice:20000'].status,'送达');
  t.diagnostic('history reopen ms='+Math.round(performance.now()-started));
 }finally{c.logout();fs.rmSync(dir,{recursive:true,force:true});}
});
test('multiple committed deltas survive one update, failed ratchet/message changes roll back, and presence emits no history',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-delta-')),events=[];
 const c=new Controller(dir,storage,e=>events.push(e));c.user='alice';c.engine={state:{messages:{},sessions:{bob:'old'},verified:{},outbox:{}}};c.vault={write:()=>{}};
 try{
  await c.transaction(()=>{c.engine.state.messages['alice:1']=message(1);});
  await c.transaction(()=>{c.engine.state.messages['alice:2']=message(2);});c.update();
  assert.equal(Object.keys(events.at(-1).messageChanges).length,2);
  const revision=c.messageRevision;c.vault.write=()=>{throw Error('disk full');};
  await assert.rejects(c.transaction(()=>{c.engine.state.messages['alice:1'].body='bad';c.engine.state.sessions.bob='bad';delete c.engine.state.messages['alice:2'];}),/disk full/);
  assert.equal(c.engine.state.sessions.bob,'old');assert.equal(c.engine.state.messages['alice:1'].body,'历史 1');assert(c.engine.state.messages['alice:2']);assert.equal(c.messageRevision,revision);
  assert.equal(c.online,false);assert(c.storageFailed);assert.equal(events.at(-1).messages,undefined);
  c.update();assert.deepEqual(events.at(-1).messageChanges,{});
 }finally{c.logout();fs.rmSync(dir,{recursive:true,force:true});}
});
test('fresh incoming messages create private notification metadata and unread count once; opening/logout clears it',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-unread-')),events=[];
 const c=new Controller(dir,storage,e=>events.push(e));c.user='alice';c.server='http://localhost';c.online=true;c.vault={write:()=>{}};c.wire=()=>{};
 c.engine={state:{messages:{},verified:{}},decrypt:async m=>{const key=m.sender+':'+m.clientId;c.engine.state.messages[key]||={...m,body:'secret plaintext'};}};
 try{
  const frame={type:'message',message:{sender:'bob',recipient:'alice',clientId:'one',id:'server-id'}};
  await c.event(frame);await c.event(frame);assert.equal(c.unread.bob,1);
  const notices=events.filter(e=>e.type==='message');assert.equal(notices.length,1);assert(!JSON.stringify(notices).includes('secret'));
  await c.openConversation({peer:'bob'});assert.equal(c.unread.bob,undefined);c.setForeground(true);
  await c.event({type:'message',message:{...frame.message,clientId:'two'}});assert.equal(c.unread.bob,undefined);
  c.logout();assert.deepEqual(c.unread,{});assert.equal(c.activePeer,'');
 }finally{c.logout();fs.rmSync(dir,{recursive:true,force:true});}
});
test('login publishes one full history baseline before newer status deltas and the asynchronous command reply',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-baseline-')),events=[];
 const c=new Controller(dir,storage,e=>events.push(e));
 try{
  const vault=new Vault(path.join(dir,'vaults'),'http://localhost','alice',storage);vault.write({messages:{'alice:1':message(1)},verified:{},outbox:{}});
  c.request=async endpoint=>endpoint.startsWith('/api/auth/')?{token:'token',accountId:'account'}:{};
  c.recoverIdentity=c.replenish=c.syncAccountEvents=async()=>{};c.refreshContacts=async()=>c.update();c.connect=()=>c.update();
  const reply=await c.login({server:'http://localhost',username:'alice',password:'only-RAM'});
  const baseline=events.find(e=>Array.isArray(e.messages));assert(baseline);assert.equal(baseline.messages.length,1);
  assert.equal(events.filter(e=>Array.isArray(e.messages)).length,1);assert.equal(reply.messages,undefined);
  assert(reply.snapshotRevision>baseline.snapshotRevision);assert(events.at(-1).snapshotRevision>baseline.snapshotRevision);
  assert(!fs.readFileSync(c.vault.file).includes(Buffer.from('only-RAM')));
 }finally{c.logout();fs.rmSync(dir,{recursive:true,force:true});}
});
