const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const F=require('../ui/features.js');const {SignalEngine}=require('../signal.cjs');const {Vault}=require('../vault.cjs');const {Controller}=require('../controller.cjs');
function payload(extra={}){return {v:1,kind:'live',sessionId:'550e8400-e29b-41d4-a716-446655440000',seq:0,latitude:31.230416,longitude:121.473701,accuracy:12,recordedAt:new Date(Date.now()-1000).toISOString(),expiresAt:new Date(Date.now()+60000).toISOString(),...extra};}
const msg=p=>({sender:'alice',recipient:'bob',body:F.encode(p),createdAt:p.recordedAt});
test('location parser rejects malformed, range, future, illegal fields and normalized dates',()=>{
 for(const change of [{v:2},{seq:0.5},{seq:2147483648},{latitude:91},{longitude:Infinity},{accuracy:100001},{manual:true},{sessionId:'x'},{recordedAt:'2026-02-30T00:00:00Z',expiresAt:'2026-02-30T00:30:00Z'},{recordedAt:new Date(Date.now()+301000).toISOString()},{expiresAt:new Date(Date.now()+7200000).toISOString()}])assert.equal(F.parse(F.PREFIX+JSON.stringify(payload(change))),null);
 assert.equal(F.parse(F.PREFIX+'{'),null);assert.ok(F.parse(F.encode(payload())));
 assert.ok(F.parse(F.encode(payload({kind:'stop',latitude:undefined,longitude:undefined,accuracy:undefined}))));
});
test('session reducers isolate senders, reject late seq, stop tombstones and expiry extension',()=>{
 const first=payload(),next={...first,seq:2,latitude:40},late={...first,seq:1,latitude:41},stop={...first,kind:'stop',seq:0,latitude:undefined,longitude:undefined,accuracy:undefined};
 let reduced=F.sessions([msg(first),msg(next),msg(late)]);assert.equal([...reduced.values()][0].value.latitude,40);
 reduced=F.sessions([msg(first),msg(next),msg(stop),msg({...next,seq:3})]);assert.equal([...reduced.values()][0].status,'已停止');assert.equal([...reduced.values()][0].value.latitude,40);
 reduced=F.sessions([msg(first),msg({...next,expiresAt:new Date(Date.now()+120000).toISOString()})]);assert.equal([...reduced.values()][0].value.seq,0);
 assert.equal([...F.sessions([msg(first)],Date.now()+120000).values()][0].status,'已过期');
 assert.equal(F.sessions([msg(first),{...msg(first),sender:'bob',recipient:'alice'}]).size,2);
});
test('local search isolates current peer, paginates inclusive local dates and summarizes locations',()=>{
 const messages=Array.from({length:45},(_,i)=>({sender:'alice',recipient:'bob',clientId:String(i),body:'find '+i,createdAt:'2026-06-02T10:00:00Z'}));messages.push({sender:'alice',recipient:'eve',body:'find secret',createdAt:'2026-06-02T10:00:00Z'});
 const first=F.search(messages,'alice','bob',{query:'find',from:'2026-06-01',to:'2026-06-03'}),second=F.search(messages,'alice','bob',{query:'find',page:1});assert.equal(first.total,45);assert.equal(first.items.length,20);assert.equal(second.items.length,20);assert.equal(first.items.some(m=>m.recipient==='eve'),false);assert.equal(F.search(messages,'eve','bob').total,0);
 assert.ok(F.summary(F.encode(payload({kind:'pin'}))).startsWith('[位置]'));assert.ok(!F.summary(F.encode(payload())).includes(F.PREFIX));
});
test('location body travels only in libsignal ciphertext, caches survive engine reload',async()=>{
 const a=await SignalEngine.create('alice'),b=await SignalEngine.create('bob'),bundle=await b.publicBundle(2);bundle.username='bob';bundle.preKey=bundle.preKeys[0];await a.establish('bob',bundle);
 const body=F.encode(payload()),wire=await a.encrypt('bob',body);assert.ok(!JSON.stringify(wire).includes('31.230416'));assert.ok(!JSON.stringify(wire).includes(F.PREFIX));
 const received=await b.decrypt({id:'loc1',clientId:wire.clientId,sender:'alice',recipient:'bob',ciphertext:wire.ciphertext});assert.equal(received.body,body);assert.equal(new SignalEngine(structuredClone(b.state)).state.messages['alice:'+wire.clientId].body,body);
});
test('location sends fail closed offline, unaccepted and enforce throttle/terminal session',async()=>{
 const c=Object.create(Controller.prototype);Object.assign(c,{online:false,token:'t',engine:{},contactState:{bob:{status:'accepted'}},liveSessions:new Map()});await assert.rejects(c.sendLocation({peer:'bob',body:F.encode(payload())}),/离线/);
 c.online=true;c.socket={readyState:1};c.contactState.bob.status='pending_outgoing';await assert.rejects(c.sendLocation({peer:'bob',body:F.encode(payload())}),/接受/);c.contactState.bob.status='accepted';let count=0;c.send=async()=>{count++;return {};};await c.sendLocation({peer:'bob',body:F.encode(payload())});await assert.rejects(c.sendLocation({peer:'bob',body:F.encode(payload({seq:1}))}),/十秒/);await c.sendLocation({peer:'bob',body:F.encode(payload({kind:'stop',seq:2}))});await assert.rejects(c.sendLocation({peer:'bob',body:F.encode(payload({seq:3}))}),/停止/);assert.equal(count,2);
});
test('journal compacts encrypted checkpoints and interrupted rename preserves latest committed state',()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'location-journal-'));let enabled=true;const storage={isEncryptionAvailable:()=>enabled,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()},v=new Vault(dir,'https://example.com','alice',storage),state={secret:'private-coordinate',padding:'x'.repeat(1024*1024)};
 try{for(let i=0;i<10;i++)v.write({...state,seq:i});assert.equal(v.read().seq,9);assert.ok(fs.statSync(v.file).size<8*1024*1024);assert.ok(!fs.readFileSync(v.file,'utf8').includes('private-coordinate'));
 const original=fs.renameSync;fs.renameSync=()=>{throw new Error('interrupted');};try{for(let i=10;i<18;i++)v.write({...state,seq:i});}finally{fs.renameSync=original;}assert.equal(v.read().seq,17);assert.equal(fs.existsSync(v.file+'.compact'),false);enabled=false;assert.throws(()=>v.write({seq:18}),/不可用/);enabled=true;assert.equal(v.read().seq,17);
 }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
