const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Controller}=require('../controller.cjs');
const {SignalEngine}=require('../signal.cjs');
const fakeStorage={isEncryptionAvailable:()=>true,encryptString:text=>Buffer.from(text),decryptString:data=>data.toString()};
async function fixture(){
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'chat-deletion-'));
  const controller=new Controller(directory,fakeStorage,()=>{});
  const alice=await SignalEngine.create('alice'),bob=await SignalEngine.create('bob'),carol=await SignalEngine.create('carol');
  const bp=await bob.publicBundle(2),cp=await carol.publicBundle(2);
  await alice.establish('bob',{...bp,username:'bob',preKey:bp.preKeys[0]});
  await alice.establish('carol',{...cp,username:'carol',preKey:cp.preKeys[0]});
  const pending=await alice.encrypt('bob','保留的旧聊天正文');
  await alice.encrypt('carol','另一位联系人');
  alice.state.peerAccountIds={bob:'old-bob',carol:'carol-id'};
  alice.state.verified.bob=bp.identityKey;
  Object.assign(controller,{engine:alice,user:'alice',server:'http://localhost:8082',online:true,vault:{write:()=>{}}});
  controller.contactState={bob:{username:'bob',status:'accepted'},carol:{username:'carol',status:'accepted'}};
  const event={id:'deletion-event-1',username:'bob',accountId:'old-bob',identityKey:bp.identityKey,deletedAt:'2026-09-26T00:00:00Z'};
  return {controller,alice,bob,event,pending,close:()=>fs.rmSync(directory,{recursive:true,force:true})};
}
test('account deletion retires only the deleted identity, removes contact, and preserves history and own keys',async()=>{
  const f=await fixture();try{
    const original=structuredClone(f.alice.state);
    await f.controller.applyAccountDeletion(f.event);
    const s=f.alice.state;
    assert(!s.sessions['bob.1']&&!s.trusted['bob.1']&&!s.verified.bob);
    assert(!s.outbox[f.pending.clientId]);
    assert.equal(s.messages['alice:'+f.pending.clientId].body,'保留的旧聊天正文');
    assert.match(s.messages['alice:'+f.pending.clientId].status,/已销户/);
    for(const field of ['identity','registrationId','signed','pre','kyber','usedKyber','nextKey','pendingUpload'])assert.deepEqual(s[field],original[field]);
    assert.deepEqual(s.sessions['carol.1'],original.sessions['carol.1']);
    assert.deepEqual(s.trusted['carol.1'],original.trusted['carol.1']);
    assert.equal(Object.keys(s.messages).length,Object.keys(original.messages).length);
    assert.deepEqual(f.controller.snapshot().contacts,['carol']);
    assert(f.controller.snapshot().sessions.includes('bob'));
    assert.equal(f.controller.snapshot().deletedPeers.bob.accountId,'old-bob');
  }finally{f.close();}
});
test('fresh registration communicates after deletion while duplicate and late old events cannot clear the new identity',async()=>{
  const f=await fixture();try{
    const reborn=await SignalEngine.create('bob'),bundle=await reborn.publicBundle(2);
    await assert.rejects(f.alice.safety('bob',bundle.identityKey),/改变/);
    await f.controller.applyAccountDeletion(f.event);
    await f.controller.transaction(()=>f.controller.bindPeerIdentity('bob',{...bundle,accountId:'new-bob'}));
    assert(!f.alice.state.deletedPeers.bob);
    await f.alice.establish('bob',{...bundle,username:'bob',preKey:bundle.preKeys[0]});
    const sent=await f.alice.encrypt('bob','新身份双向通信');
    await reborn.decrypt({...sent,sender:'alice',recipient:'bob',id:'10'});
    const reply=await reborn.encrypt('alice','回复成功');
    assert.equal((await f.alice.decrypt({...reply,sender:'bob',recipient:'alice',id:'11'})).body,'回复成功');
    f.controller.contactState.bob={username:'bob',status:'accepted'};
    const session=f.alice.state.sessions['bob.1'];
    await f.controller.applyAccountDeletion(f.event);
    await f.controller.applyAccountDeletion({...f.event,id:'late-old-event'});
    assert.equal(f.alice.state.sessions['bob.1'],session);
    assert.equal(f.alice.state.trusted['bob.1'],bundle.identityKey);
    assert(!f.alice.state.deletedPeers.bob);
    assert(f.controller.snapshot().contacts.includes('bob'));
  }finally{f.close();}
});
test('offline sync saves deletion before ACK and resends no old ciphertext when ACK fails',async()=>{
  const f=await fixture();try{
    const sent=[],order=[];
    f.controller.wire=item=>sent.push(item);
    f.controller.vault.write=()=>order.push('save');
    f.controller.request=async endpoint=>{
      if(endpoint==='/api/account-events')return [f.event];
      if(endpoint==='/api/account-events/ack'){order.push('ack');throw new Error('ACK failure');}
      return [];
    };
    await assert.rejects(f.controller.event({type:'ready'}),/ACK failure/);
    assert.equal(f.controller.online,false);
    assert.deepEqual(sent,[]);
    assert.deepEqual(order,['save','ack']);
    assert(f.alice.state.appliedAccountEvents[f.event.id]);
    f.controller.request=async endpoint=>endpoint==='/api/account-events'?[f.event]:[];
    await f.controller.event({type:'ready'});
    assert.equal(f.controller.online,true);
    assert(sent.length===1&&sent[0].to==='carol');
  }finally{f.close();}
});
test('failed local save rolls back deletion and never sends ACK',async()=>{
  const f=await fixture();try{
    const original=structuredClone(f.alice.state),requests=[];
    f.controller.vault.write=()=>{throw new Error('disk full');};
    f.controller.request=async endpoint=>{requests.push(endpoint);return [f.event];};
    await assert.rejects(f.controller.syncAccountEvents(),/disk full/);
    assert.deepEqual(f.alice.state,original);
    assert.deepEqual(requests,['/api/account-events']);
    assert(f.controller.snapshot().contacts.includes('bob'));
  }finally{f.close();}
});
test('offline deletion followed by a fresh pending request restores only the new incarnation',async()=>{
  const f=await fixture();try{
    await f.controller.applyAccountDeletion(f.event);
    const replacement=await SignalEngine.create('bob'),info=await replacement.publicBundle();
    f.controller.request=async endpoint=>endpoint==='/api/contacts'?[{username:'bob',status:'pending_incoming'}]:{...info,accountId:'new-bob'};
    await f.controller.refreshContacts();
    assert.equal(f.controller.contactState.bob.status,'pending_incoming');
    assert(!f.alice.state.deletedPeers.bob);
    assert.equal(f.alice.state.peerAccountIds.bob,'new-bob');
    await assert.rejects(f.controller.send({peer:'bob',body:'必须先接受'}),/先接受/);
  }finally{f.close();}
});
test('deletion is visible without cached history and can subsequently be hidden by clearing its conversation',async()=>{
  const f=await fixture();try{
    f.alice.state.messages={};f.alice.state.outbox={};f.alice.state.hiddenSessions={bob:true};
    await f.controller.applyAccountDeletion(f.event);
    assert(f.controller.snapshot().sessions.includes('bob'));
    await f.controller.clearConversation({peer:'bob'});
    assert(!f.controller.snapshot().sessions.includes('bob'));
    await f.controller.applyAccountDeletion(f.event);
    assert(!f.controller.snapshot().sessions.includes('bob'),'replayed notice does not reopen a deliberately cleared conversation');
  }finally{f.close();}
});
