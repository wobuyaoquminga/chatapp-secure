const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Controller}=require('../controller.cjs');
const {SignalEngine}=require('../signal.cjs');
const fake={isEncryptionAvailable:()=>true,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()};
async function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-reset-'));const c=new Controller(dir,fake,()=>{});c.user='ab';c.server='http://localhost:8082';c.online=true;c.engine=await SignalEngine.create('ab');c.vault={write:()=>{}};return {c,close:()=>fs.rmSync(dir,{recursive:true,force:true})};}
test('password reset persists candidate before network and recovers remote commit interruption',async()=>{
 const {c,close}=await fixture();try{
  c.engine.state.messages.old={sender:'ab',recipient:'cd',body:'local history',clientId:'old',id:'1'};
  const old=c.engine.state.identity;let saved,called=false;
  c.vault.write=state=>{saved=structuredClone(state);};
  c.request=async(endpoint,method,body)=>{called=true;assert.equal(endpoint,'/api/keys/reset');assert.equal(body.password,'password123');assert(saved.pendingIdentityReset);assert.equal(Object.keys(saved.pendingIdentityReset.messages).length,0);throw new Error('connection lost after remote commit');};
  await assert.rejects(c.recoverIdentity({identityKey:'different'},'password123'),/connection lost/);
  assert(called);assert.equal(c.engine.state.identity,old);assert.equal(c.engine.state.messages.old.body,'local history');
  const pending=new SignalEngine(saved.pendingIdentityReset),pub=await pending.publicBundle();
  c.request=async()=>{throw new Error('must not reset twice');};
  await c.recoverIdentity({identityKey:pub.identityKey},'password123');
  assert.equal(c.engine.state.identity,pending.state.identity);assert.equal(c.engine.state.messages.old.body,'local history');assert(!c.engine.state.pendingIdentityReset);assert.equal(c.engine.state.messages.old.archivedServerId,'1');
 }finally{close();}
});
test('verified peer reset retains contact/history, requires new safety confirmation and ignores stale notices',async()=>{
 const {c,close}=await fixture();try{
  const peer=await SignalEngine.create('cd'),bp=await peer.publicBundle(1);
  await c.engine.establish('cd',{...bp,username:'cd',preKey:bp.preKeys[0]});
  const pending=await c.engine.encrypt('cd','preserve');c.engine.state.peerAccountIds={cd:'old'};c.engine.state.verified.cd=bp.identityKey;c.contactState.cd={username:'cd',status:'accepted'};
  const replacement=await SignalEngine.create('cd'),nb=await replacement.publicBundle(1);
  const event={kind:'identity_reset',id:'evt',username:'cd',accountId:'old',identityKey:bp.identityKey,newAccountId:'new',newIdentityKey:nb.identityKey};
  await c.applyAccountEvent(event);assert.equal(c.snapshot().contacts[0],'cd');assert.equal(c.snapshot().messages[0].body,'preserve');assert(!c.engine.state.outbox[pending.clientId]);assert(!c.engine.state.verified.cd);
  await assert.rejects(c.send({peer:'cd',body:'blocked'}),/核对/);
  c.request=async()=>({...nb,accountId:'new'});const safety=await c.safety({peer:'cd'});
  await assert.rejects(c.safety({peer:'cd',confirm:true,expectedCode:'stale'}),/改变/);
  await c.safety({peer:'cd',confirm:true,expectedCode:safety.code});assert(!c.engine.state.identityChanges.cd);
  await c.applyAccountEvent({...event,id:'late'});assert.equal(c.engine.state.verified.cd,nb.identityKey);
  await c.engine.establish('cd',{...nb,username:'cd',preKey:nb.preKeys[0]});const encrypted=await c.engine.encrypt('cd','new message');assert.equal((await replacement.decrypt({...encrypted,sender:'ab',recipient:'cd',id:'2'})).body,'new message');
  c.engine.state.identityChanges.cd={identityKey:nb.identityKey};
  await c.applyAccountDeletion({id:'deleted-new',username:'cd',accountId:'new',identityKey:nb.identityKey});
  assert(!c.engine.state.identityChanges.cd,'deletion must clear the obsolete verification requirement');
 }finally{close();}
});
test('a missed reset notice still requires manual safety verification before sending',async()=>{
 const {c,close}=await fixture();try{
  const old=await SignalEngine.create('cd'),oldBundle=await old.publicBundle(1);
  await c.engine.establish('cd',{...oldBundle,username:'cd',preKey:oldBundle.preKeys[0]});
  c.engine.state.peerAccountIds={cd:'old-account'};
  c.engine.state.verified.cd=oldBundle.identityKey;
  c.contactState.cd={username:'cd',status:'accepted'};
  const replacement=await SignalEngine.create('cd'),newBundle=await replacement.publicBundle(1);
  c.request=async()=>({...newBundle,accountId:'new-account'});
  const safety=await c.safety({peer:'cd'});
  assert.equal(c.engine.state.peerAccountIds.cd,'new-account');
  assert(c.engine.state.identityChanges.cd);
  await assert.rejects(c.send({peer:'cd',body:'blocked'}),/核对/);
  await c.safety({peer:'cd',confirm:true,expectedCode:safety.code});
  assert(!c.engine.state.identityChanges.cd);
 }finally{close();}
});
