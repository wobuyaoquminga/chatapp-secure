const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const crypto=require('node:crypto');
const vm=require('node:vm');
const WebSocket=require('ws');
const {Controller}=require('../controller.cjs');
const {MediaLease}=require('../media-permission.cjs');

const storage={isEncryptionAvailable:()=>true,encryptString:text=>Buffer.from(text),decryptString:data=>data.toString()};
const peerId=crypto.randomUUID();
function fixture() {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-call-')),events=[],sent=[];
  const controller=new Controller(dir,storage,event=>events.push(event));
  controller.user='alice';controller.server='https://chat.example';controller.token='token';controller.online=true;
  controller.contactState.bob={username:'bob',status:'accepted'};
  controller.engine={state:{messages:{},verified:{},peerAccountIds:{bob:peerId}}};
  controller.socket={readyState:WebSocket.OPEN,send:text=>sent.push(JSON.parse(text)),close:()=>{}};
  return {controller,events,sent,close:()=>fs.rmSync(dir,{recursive:true,force:true})};
}

test('call signalling stays ephemeral and requires accepted current account generation',()=>{
  const f=fixture(),id=crypto.randomUUID();
  try{
    f.controller.beginCall({peer:'bob',callId:id,mode:'audio'});
    f.controller.sendCall({peer:'bob',callId:id,mode:'audio',action:'offer',payload:'sdp'});
    assert.deepEqual(f.sent[0],{type:'call',to:'bob',toAccountId:peerId,callId:id,action:'offer',mode:'audio',payload:'sdp'});
    assert.equal(f.controller.engine.state.messages[id],undefined);
    f.controller.receiveCall({type:'call',from:'bob',fromAccountId:crypto.randomUUID(),callId:id,mode:'audio',action:'answer',payload:'sdp'});
    assert.equal(f.events.filter(event=>event.type==='call').length,0);
    f.controller.receiveCall({type:'call',from:'bob',fromAccountId:peerId,callId:id,mode:'audio',action:'answer',payload:'sdp'});
    assert.equal(f.events.filter(event=>event.type==='call').length,1);
    f.controller.engine.state.peerAccountIds.bob=crypto.randomUUID();
    assert.throws(()=>f.controller.sendCall({peer:'bob',callId:id,mode:'audio',action:'ice',payload:'{}'}),/身份已变化/);
    f.controller.update();assert.equal(f.controller.call,null);
  }finally{f.close();}
});

test('incoming call is busy while one call exists and old call errors cannot end a new call',async()=>{
  const f=fixture(),id=crypto.randomUUID();
  try{
    f.controller.receiveCall({type:'call',from:'bob',fromAccountId:peerId,callId:id,mode:'video',action:'offer',payload:'sdp'});
    assert.equal(f.controller.call.callId,id);
    assert.throws(()=>f.controller.sendCall({peer:'bob',callId:id,mode:'video',action:'answer',payload:'sdp'}),/尚未接听/);
    f.controller.approveCall({callId:id});
    f.controller.sendCall({peer:'bob',callId:id,mode:'video',action:'answer',payload:'sdp'});
    f.controller.receiveCall({type:'call',from:'bob',fromAccountId:peerId,callId:crypto.randomUUID(),mode:'audio',action:'offer',payload:'sdp'});
    assert.equal(f.sent.at(-1).action,'busy');
    await f.controller.event({type:'call_error',callId:crypto.randomUUID(),error:'old'});
    assert.equal(f.controller.call.callId,id);
    f.controller.logout();
    assert.equal(f.controller.call,null);
  }finally{f.close();}
});

test('media permission requires matching foreground document, server, call and media type',()=>{
  let now=1000;const lease=new MediaLease(()=>now),id=crypto.randomUUID();
  lease.grant({server:'https://chat.example',generation:5,callId:id,mode:'audio'});
  const request={entryUrl:'file:///c:/chat/ui/index.html',requestingUrl:'file:///C:/chat/ui/index.html',isMainFrame:true,mediaTypes:['audio'],server:'https://chat.example',generation:5,call:{callId:id,mode:'audio',userApproved:true}};
  assert.equal(lease.allows(request),true);
  for(const change of [{mediaTypes:['video']},{requestingUrl:'https://evil.example'},{isMainFrame:false},{server:'https://other.example'},{generation:6},{call:{callId:crypto.randomUUID(),mode:'audio',userApproved:true}},{call:{callId:id,mode:'audio',userApproved:false}}])assert.equal(lease.allows({...request,...change}),false);
  now+=20001;assert.equal(lease.allows(request),false);
});

function uiFixture() {
  const elements=new Map(),sent=[],pcs=[];
  const element=id=>{if(!elements.has(id))elements.set(id,{hidden:false,disabled:false,textContent:'',srcObject:null,setAttribute(){}});return elements.get(id);};
  const stream={getTracks:()=>[{stop(){},enabled:true}],getAudioTracks:()=>[{enabled:true}]};
  class FakePeer {
    constructor(){pcs.push(this);this.added=[];this.connectionState='new';}
    addTrack(){}
    async createOffer(){return {type:'offer',sdp:'offer'};}
    async createAnswer(){return {type:'answer',sdp:'answer'};}
    async setLocalDescription(description){this.localDescription=description;this.onicecandidate?.({candidate:{toJSON:()=>({candidate:'early-local'})}});}
    async setRemoteDescription(description){this.remoteDescription=description;}
    async addIceCandidate(candidate){this.added.push(candidate);}
    close(){}
  }
  const sandbox={document:{getElementById:element},window:{addEventListener(){}},navigator:{mediaDevices:{getUserMedia:async()=>stream}},RTCPeerConnection:FakePeer,MediaStream:class {},crypto,setTimeout,clearTimeout};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../ui/calls.js'),'utf8'),sandbox);
  const command=async(action,payload)=>{if(action==='callIce')return [{urls:'stun:test'}];if(action==='sendCall')sent.push(payload);return true;};
  const calls=sandbox.window.ChatCalls.create({command,notice:()=>{},getState:()=>({online:true,username:'alice',contactStates:[{username:'bob',status:'accepted'}]}),getPeer:()=> 'bob'});
  return {element,calls,sent,pcs};
}

test('WebRTC queues remote ICE until SDP and sends local ICE after offer or answer',async()=>{
  const incoming=uiFixture(),id=crypto.randomUUID();
  await incoming.calls.onEvent({type:'call',event:{from:'bob',callId:id,mode:'audio',action:'offer',payload:JSON.stringify({type:'offer',sdp:'remote'})}});
  await incoming.calls.onEvent({type:'call',event:{from:'bob',callId:id,mode:'audio',action:'ice',payload:JSON.stringify({candidate:'early-remote'})}});
  await incoming.element('acceptCall').onclick();
  assert.equal(incoming.pcs[0].added[0].candidate,'early-remote');
  assert.deepEqual(incoming.sent.map(item=>item.action),['answer','ice']);
  incoming.calls.close();

  const outgoing=uiFixture();
  await outgoing.element('startAudioCall').onclick();
  assert.deepEqual(outgoing.sent.map(item=>item.action),['offer','ice']);
  outgoing.calls.close();
});
