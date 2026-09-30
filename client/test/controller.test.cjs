const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Controller,validUser}=require('../controller.cjs');

const fakeStorage={isEncryptionAvailable:()=>true,encryptString:text=>Buffer.from(text),decryptString:data=>data.toString()};
function fixture(){
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-controller-'));
  const controller=new Controller(dir,fakeStorage,()=>{});
  controller.user='小明';controller.server='http://localhost:8082';controller.online=true;
  controller.vault={write:()=>{}};
  controller.engine={state:{messages:{'小明:1':{clientId:'1',sender:'小明',recipient:'小红',body:'历史正文',createdAt:'2026-01-01T00:00:00Z'}},contacts:{},verified:{},hiddenSessions:{},hiddenContacts:{}},decrypt:async()=>{}};
  controller.contactState={'小红':{username:'小红',status:'accepted',online:true}};
  return {controller,close:()=>fs.rmSync(dir,{recursive:true,force:true})};
}
test('All usernames allow two characters',()=>{
  for(const name of ['ab','小明','张三_1','alice','abc_123'])assert.equal(validUser(name),true);
  for(const name of ['a','Aaa','小','小 明','a-b','😀😀','a'.repeat(33)])assert.equal(validUser(name),false);
});
test('clearing a session and revoking a contact preserve encrypted local history',async()=>{
  const {controller,close}=fixture();
  try{
    const calls=[];controller.request=async(...args)=>{calls.push(args);return {};};
    assert.deepEqual(controller.snapshot().sessions,['小红']);
    assert.deepEqual(controller.snapshot().contacts,['小红']);
    await controller.clearConversation({peer:'小红'});
    assert.deepEqual(controller.snapshot().sessions,[]);
    assert.equal(controller.snapshot().messages[0].body,'历史正文');
    await controller.removeContact({peer:'小红'});
    assert.deepEqual(calls,[['/api/contacts/remove','POST',{peer:'小红'}]]);
    assert.deepEqual(controller.snapshot().contacts,[]);
    await controller.openConversation({peer:'小红'});
    assert.deepEqual(controller.snapshot().sessions,['小红']);
    assert.deepEqual(controller.snapshot().contacts,[]);
  }finally{close();}
});
test('failed revocation leaves the accepted relation intact',async()=>{
  const {controller,close}=fixture();
  try{
    controller.request=async()=>{throw new Error('服务器故障');};
    await assert.rejects(controller.removeContact({peer:'小红'}),/服务器故障/);
    assert.deepEqual(controller.snapshot().contacts,['小红']);
    assert.equal(controller.snapshot().messages[0].body,'历史正文');
  }finally{close();}
});
test('revocation event clears the relation and a fresh request requires consent',async()=>{
  const {controller,close}=fixture();
  try{
    controller.engine.state.hiddenContacts['小红']=true;
    await controller.event({type:'contact',contact:{username:'小红',status:'removed',online:false}});
    assert.equal(controller.contactState['小红'],undefined);
    assert.equal(controller.snapshot().messages[0].body,'历史正文');
    await controller.event({type:'message',message:{id:'old-message',sender:'小红'}});
    assert.equal(controller.contactState['小红'],undefined,'a delayed old message must not create a fresh request');
    await controller.event({type:'contact',contact:{username:'小红',status:'pending_incoming',online:true}});
    assert.equal(controller.engine.state.hiddenContacts['小红'],undefined);
    controller.request=()=>{throw new Error('should not request keys');};
    await assert.rejects(controller.send({peer:'小红',body:'回复'}),/先接受/);
  }finally{close();}
});
test('old outgoing history does not block a new first message after revocation',async()=>{
  const {controller,close}=fixture();
  try{
    delete controller.contactState['小红'];
    const calls=[];
    controller.request=async(endpoint,method)=>{calls.push([endpoint,method]);return {identityKey:'identity'};};
    controller.engine.safety=async()=>{};
    controller.engine.hasSession=async()=>true;
    controller.engine.encrypt=async(peer,body)=>({type:'message',peer,body});
    controller.wire=()=>{};
    await controller.send({peer:'小红',body:'新的首条消息'});
    assert.equal(controller.contactState['小红'].status,'pending_outgoing');
    assert.deepEqual(calls,[['/api/keys/%E5%B0%8F%E7%BA%A2',undefined]]);
    await assert.rejects(controller.send({peer:'小红',body:'第二条'}),/等待对方接受/);
  }finally{close();}
});
test('accepting a chat request learns the peer account id so a call works immediately',async()=>{
  const {controller,close}=fixture();
  try{
    const accountId='2f1c8d0e-6f1b-4a3c-9d2e-1b0a7c4f5e60',calls=[];
    controller.contactState['小红']={username:'小红',status:'pending_incoming',online:true};
    controller.engine.state.peerAccountIds={};
    controller.engine.state.identityChanges={};
    controller.engine.safety=async()=>{};
    controller.request=async(endpoint,method)=>{
      calls.push([endpoint,method]);
      if(endpoint==='/api/contacts')return [{username:'小红',status:'accepted',online:true}];
      if(endpoint.startsWith('/api/keys/'))return {accountId,identityKey:'identity'};
      return {};
    };
    await controller.acceptContact({peer:'小红'});
    assert.deepEqual(calls[0],['/api/contacts/accept','POST']);
    assert.equal(controller.engine.state.peerAccountIds['小红'],accountId);
    assert.equal(controller.contactState['小红'].status,'accepted');
  }finally{close();}
});
test('a failed peer lookup does not undo an accepted request',async()=>{
  const {controller,close}=fixture();
  try{
    controller.contactState['小红']={username:'小红',status:'pending_incoming',online:true};
    controller.engine.state.peerAccountIds={};
    controller.engine.state.identityChanges={};
    controller.request=async(endpoint)=>{
      if(endpoint.startsWith('/api/keys/'))throw new Error('服务器故障');
      if(endpoint==='/api/contacts')return [{username:'小红',status:'accepted',online:true}];
      return {};
    };
    await controller.acceptContact({peer:'小红'});
    assert.equal(controller.contactState['小红'].status,'accepted');
  }finally{close();}
});
test('pending request blocks a second outgoing message before consuming a prekey',async()=>{
  const {controller,close}=fixture();
  try{
    controller.contactState['小红'].status='pending_outgoing';
    controller.request=()=>{throw new Error('should not request keys');};
    await assert.rejects(controller.send({peer:'小红',body:'第二条'}),/等待对方接受/);
    controller.contactState['小红'].status='pending_incoming';
    await assert.rejects(controller.send({peer:'小红',body:'回复'}),/先接受/);
  }finally{close();}
});
