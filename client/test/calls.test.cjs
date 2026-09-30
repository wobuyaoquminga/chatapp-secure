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

test('repeated offer keeps the same incoming call and end event identifies its call',()=>{
  const f=fixture(),id=crypto.randomUUID(),offer={type:'call',from:'bob',fromAccountId:peerId,callId:id,mode:'audio',action:'offer',payload:'sdp'};
  try{
    f.controller.receiveCall(offer);
    f.controller.receiveCall(offer);
    assert.equal(f.controller.call.callId,id);
    assert.equal(f.sent.filter(frame=>frame.action==='busy').length,0);
    assert.equal(f.events.filter(event=>event.type==='call').length,1);
    f.controller.cancelCall({callId:id});
    assert.equal(f.events.at(-1).callId,id);
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

// Deterministic clock so pacing can be asserted without sleeping.
function fakeTimers(){
  let now=0,id=0;const jobs=new Map();
  return {
    setTimeout(fn,ms){const t=++id;jobs.set(t,{fn,at:now+(ms||0),repeat:0});return t;},
    clearTimeout(t){jobs.delete(t);},
    setInterval(fn,ms){const t=++id;jobs.set(t,{fn,at:now+(ms||1),repeat:ms||1});return t;},
    clearInterval(t){jobs.delete(t);},
    tick(ms){const end=now+ms;for(;;){let best=-1,bestAt=Infinity;for(const[t,j]of jobs)if(j.at<=end&&j.at<bestAt){best=t;bestAt=j.at;}if(best<0)break;now=bestAt;const job=jobs.get(best);if(job.repeat)job.at=now+job.repeat;else jobs.delete(best);job.fn();}now=end;},
    pending:()=>jobs.size,
  };
}

function uiFixture(options={}) {
  const elements=new Map(),sent=[],pcs=[],notices=[];
  const timers=options.timers||{setTimeout,clearTimeout,setInterval,clearInterval};
  const element=id=>{
    if(!elements.has(id)){
      const classes=new Set(),listeners={};
      elements.set(id,{id,hidden:false,disabled:false,textContent:'',srcObject:null,readyState:0,videoWidth:0,style:{},offsetWidth:280,offsetHeight:235,
        classList:{toggle(name,on){if(on)classes.add(name);else classes.delete(name);},contains:name=>classes.has(name)},
        setAttribute(){},addEventListener(name,handler){listeners[name]=handler;},dispatch(name,event){listeners[name]?.({...event,currentTarget:this});},
        setPointerCapture(){},getBoundingClientRect:()=>({left:20,top:20})});
    }
    return elements.get(id);
  };
  const stream={getTracks:()=>[{stop(){},enabled:true}],getAudioTracks:()=>[{enabled:true}],getVideoTracks:()=>[{enabled:true}]};
  class FakePeer {
    constructor(){pcs.push(this);this.added=[];this.connectionState='new';}
    addTrack(){}
    async createOffer(){return {type:'offer',sdp:'offer'};}
    async createAnswer(){return {type:'answer',sdp:'answer'};}
    async setLocalDescription(description){this.localDescription=description;this.onicecandidate?.({candidate:{toJSON:()=>({candidate:'early-local'})}});}
    async setRemoteDescription(description){this.remoteDescription=description;this.remoteSetCount=(this.remoteSetCount||0)+1;}
    async addIceCandidate(candidate){this.added.push(candidate);}
    close(){}
  }
  class FakeStream {
    constructor(){this.tracks=[];}
    getTracks(){return this.tracks;}
    addTrack(track){this.tracks.push(track);}
    getVideoTracks(){return this.tracks.filter(track=>track.kind==='video');}
  }
  const sandbox={document:{getElementById:element},window:{addEventListener(){}},navigator:{mediaDevices:{getUserMedia:async()=>{if(options.mediaError)throw options.mediaError;return stream;}}},RTCPeerConnection:FakePeer,MediaStream:FakeStream,crypto,...timers,innerWidth:1000,innerHeight:800};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../ui/calls.js'),'utf8'),sandbox);
  const command=async(action,payload)=>{if(action==='callIce')return [{urls:'stun:test'}];if(action==='sendCall')sent.push(payload);return true;};
  const calls=sandbox.window.ChatCalls.create({command,notice:message=>notices.push(message),getState:()=>({online:true,username:'alice',contactStates:[{username:'bob',status:'accepted'}]}),getPeer:()=> 'bob'});
  return {element,calls,sent,pcs,notices};
}

test('WebRTC queues remote ICE until SDP and sends local ICE after offer or answer',async()=>{
  const incoming=uiFixture(),id=crypto.randomUUID();
  await incoming.calls.onEvent({type:'call',event:{from:'bob',callId:id,mode:'audio',action:'offer',payload:JSON.stringify({type:'offer',sdp:'remote'})}});
  await incoming.calls.onEvent({type:'call',event:{from:'bob',callId:id,mode:'audio',action:'ice',payload:JSON.stringify({candidate:'early-remote'})}});
  await incoming.calls.onEvent({type:'call',event:{from:'bob',callId:id,mode:'audio',action:'ice',payload:JSON.stringify({candidate:'early-remote'})}});
  await incoming.element('acceptCall').onclick();
  assert.equal(incoming.pcs[0].added[0].candidate,'early-remote');
  assert.equal(incoming.pcs[0].added.length,1);
  assert.deepEqual(incoming.sent.map(item=>item.action),['answer','ice']);
  incoming.calls.close();

  const outgoing=uiFixture();
  await outgoing.element('startAudioCall').onclick();
  assert.deepEqual(outgoing.sent.map(item=>item.action),['offer','ice']);
  outgoing.calls.close();
});

test('multi-homed ICE burst is paced below the server call-signal budget and never dropped',async()=>{
  const timers=fakeTimers(),f=uiFixture({timers});
  await f.element('startAudioCall').onclick();
  const pc=f.pcs[0];
  const iceSent=()=>f.sent.filter(item=>item.action==='ice').length;
  assert.equal(iceSent(),1,'the buffered candidate leaves with the offer');
  // A Windows host with virtual adapters gathers ~30 host/TCP candidates in one burst.
  for(let i=0;i<30;i++)pc.onicecandidate({candidate:{toJSON:()=>({candidate:'host-'+i,sdpMid:'0',sdpMLineIndex:0})}});
  assert.equal(iceSent(),1,'a burst waits for the pacing tick instead of flooding the socket');
  timers.tick(50);
  assert.equal(iceSent(),2);
  timers.tick(1000);
  assert.ok(iceSent()-2<=20, `at most 20 candidates per second, saw ${iceSent()-2}`);
  timers.tick(5000);
  assert.equal(iceSent(),31,'every gathered candidate is still delivered exactly once');
  f.calls.close();
  assert.equal(timers.pending(),0,'closing the call stops the pacer');
});

test('a call that never connects explains the network instead of reporting a normal end',async()=>{
  const never=uiFixture();
  await never.element('startAudioCall').onclick();
  never.pcs[0].connectionState='failed';never.pcs[0].onconnectionstatechange();
  assert.match(never.notices.at(-1),/TURN/);
  never.calls.close();

  const dropped=uiFixture();
  await dropped.element('startAudioCall').onclick();
  dropped.pcs[0].connectionState='connected';dropped.pcs[0].onconnectionstatechange();
  dropped.pcs[0].connectionState='failed';dropped.pcs[0].onconnectionstatechange();
  assert.equal(dropped.notices.at(-1),'通话连接已结束');
  dropped.calls.close();
});

test('late call end and duplicate answer do not stop the current call',async()=>{
  const f=uiFixture();
  f.calls.snapshot();
  assert.equal(f.element('startAudioCall').disabled,false,'accepted offline contacts remain callable');
  await f.element('startAudioCall').onclick();
  const first=f.sent.find(item=>item.action==='offer').callId;
  f.calls.close();
  await f.element('startAudioCall').onclick();
  const second=f.sent.filter(item=>item.action==='offer').at(-1).callId;
  assert.notEqual(first,second);
  await f.calls.onEvent({type:'call_end',callId:first,reason:'旧通话已结束'});
  assert.equal(f.element('callPanel').hidden,false);
  const answer={type:'call',event:{from:'bob',callId:second,mode:'audio',action:'answer',payload:JSON.stringify({type:'answer',sdp:'remote'})}};
  await f.calls.onEvent(answer);
  await f.calls.onEvent(answer);
  assert.equal(f.pcs.at(-1).remoteSetCount,1);
  f.pcs.at(-1).connectionState='connected';f.pcs.at(-1).onconnectionstatechange();
  assert.match(f.element('callStatus').textContent,/通话中 · 00:00:00/);
  f.element('muteCall').onclick();
  assert.equal(f.element('muteCall').textContent,'取消静音');
  f.calls.close();
});

test('missing microphone reports an actionable error and releases the call',async()=>{
  const f=uiFixture({mediaError:Object.assign(new Error('device unavailable'),{name:'NotFoundError'})});
  await f.element('startAudioCall').onclick();
  assert.equal(f.element('callPanel').hidden,true);
  assert.match(f.notices.at(-1),/未找到可用的麦克风/);
});

test('video call starts at phone size, shows self until connected, and switches to remote video',async()=>{
  const css=fs.readFileSync(path.join(__dirname,'../ui/style.css'),'utf8');
  assert.match(css,/\.callCard\{width:min\(390px,100%\);height:min\(844px,calc\(100dvh - 32px\)\)/);
  assert.match(css,/\.callPanel\.isMinimized\.modeVideo \.callCard\{width:min\(280px,calc\(100vw - 24px\)\);height:min\(200px,calc\(100vh - 24px\)\)/);
  const f=uiFixture();
  await f.element('startVideoCall').onclick();
  assert.equal(f.element('callPanel').hidden,false);
  assert.equal(f.element('callPanel').classList.contains('isMinimized'),false);
  assert.equal(f.element('callPanel').classList.contains('modeVideo'),true);
  assert.equal(f.element('callVideos').classList.contains('hasLocalVideo'),true);
  assert.equal(f.element('callVideos').classList.contains('showRemote'),false);
  f.pcs[0].ontrack({track:{kind:'video'},streams:[{getVideoTracks:()=>[{}]}]});
  assert.equal(f.element('callVideos').classList.contains('showRemote'),false);
  f.pcs[0].connectionState='connected';f.pcs[0].onconnectionstatechange();
  assert.equal(f.element('callVideos').classList.contains('showRemote'),false,'a track without a decoded frame does not hide self');
  f.element('remoteVideo').readyState=2;f.element('remoteVideo').videoWidth=640;
  f.element('remoteVideo').dispatch('loadeddata',{});
  assert.equal(f.element('callVideos').classList.contains('showRemote'),true);
  f.element('minimizeCall').onclick();
  assert.equal(f.element('callPanel').classList.contains('modeVideo'),true);
  assert.equal(f.element('callPanel').classList.contains('isMinimized'),true);
  f.calls.close();
});

test('call minimizes to a draggable overlay and restores to its initial layout',async()=>{
  const css=fs.readFileSync(path.join(__dirname,'../ui/style.css'),'utf8');
  assert.match(css,/\.callPanel\.isMinimized\.modeAudio \.callCard\{width:min\(190px,calc\(100vw - 24px\)\);height:min\(190px,calc\(100vh - 24px\)\)/);
  const f=uiFixture();
  await f.element('startAudioCall').onclick();
  f.element('minimizeCall').onclick();
  assert.equal(f.element('callPanel').classList.contains('isMinimized'),true);
  assert.equal(f.element('callPanel').classList.contains('modeAudio'),true);
  assert.equal(f.element('restoreCall').hidden,false);
  const heading=f.element('callHeading');
  heading.dispatch('pointerdown',{pointerId:1,clientX:30,clientY:30,target:{closest:()=>null}});
  heading.dispatch('pointermove',{pointerId:1,clientX:140,clientY:120});
  heading.dispatch('pointerup',{pointerId:1});
  assert.equal(f.element('callCard').style.left,'130px');
  assert.equal(f.element('callCard').style.top,'110px');
  f.element('restoreCall').onclick();
  assert.equal(f.element('callPanel').classList.contains('isMinimized'),false);
  assert.equal(f.element('callPanel').classList.contains('modeAudio'),true);
  assert.equal(f.element('callCard').style.left,'');
  assert.equal(f.element('callCard').style.top,'');
  f.calls.close();
});

test('separate streamless audio/video tracks preserve remote picture and preview swapping',async()=>{
  const f=uiFixture();
  try {
    await f.element('startVideoCall').onclick();
    const pc=f.pcs[0],video={kind:'video'},audio={kind:'audio'};
    pc.ontrack({track:video,streams:[]});
    const remote=f.element('remoteVideo').srcObject;
    pc.ontrack({track:audio,streams:[]});
    pc.ontrack({track:video,streams:[]});
    assert.equal(f.element('remoteVideo').srcObject,remote,'audio does not replace video');
    assert.equal(remote.getTracks().length,2,'duplicate callback does not duplicate tracks');
    assert.equal(f.element('remoteAudio').srcObject,remote,'audio remains bound independently');
    pc.connectionState='connected';pc.onconnectionstatechange();
    assert.equal(f.element('callVideos').classList.contains('showRemote'),false,'keep self preview while waiting for frames');
    f.element('remoteVideo').readyState=2;f.element('remoteVideo').videoWidth=640;
    f.element('remoteVideo').dispatch('loadeddata',{});
    assert.equal(f.element('callVideos').classList.contains('showRemote'),true);
    assert.equal(f.element('callVideos').classList.contains('selfPrimary'),false);
    const local=f.element('localVideo').srcObject;
    f.element('localVideo').onclick();
    assert.equal(f.element('callVideos').classList.contains('selfPrimary'),true);
    f.element('localVideo').onclick();
    assert.equal(f.element('callVideos').classList.contains('selfPrimary'),true,'main view does not swap');
    f.element('minimizeCall').onclick();
    f.element('remoteVideo').onclick();
    assert.equal(f.element('callVideos').classList.contains('selfPrimary'),false);
    assert.equal(f.element('localVideo').srcObject,local,'preview still uses local camera');
    assert.equal(f.element('remoteVideo').srcObject,remote,'remote video still uses peer tracks');
  } finally { f.calls.close(); }
  pcLateCheck(f);
});

function pcLateCheck(f) {
  f.pcs[0].ontrack({track:{kind:'video'},streams:[]});
  assert.equal(f.element('remoteVideo').srcObject,null,'late track after hangup cannot restore video');
}

test('video preview drags within its container without swapping and transfers position on swap',async()=>{
  const f=uiFixture();
  try {
    await f.element('startVideoCall').onclick();
    const pc=f.pcs[0];pc.ontrack({track:{kind:'video'},streams:[]});
    pc.connectionState='connected';pc.onconnectionstatechange();
    const remote=f.element('remoteVideo');remote.readyState=2;remote.videoWidth=640;remote.dispatch('loadeddata',{});
    const local=f.element('localVideo'),area=f.element('callVideos');
    local.offsetWidth=100;local.offsetHeight=80;area.offsetWidth=400;area.offsetHeight=500;
    local.dispatch('pointerdown',{pointerId:1,clientX:30,clientY:30,target:{closest:()=>null}});
    local.dispatch('pointermove',{pointerId:1,clientX:1000,clientY:1000});
    local.dispatch('pointerup',{pointerId:1});local.onclick();
    assert.equal(local.style.left,'300px');assert.equal(local.style.top,'420px');
    assert.equal(area.classList.contains('selfPrimary'),false,'drag release does not swap');
    local.dispatch('pointerdown',{pointerId:2,clientX:35,clientY:35,target:{closest:()=>null}});
    local.dispatch('pointerup',{pointerId:2});local.onclick();
    assert.equal(area.classList.contains('selfPrimary'),true,'stationary click still swaps');
    assert.equal(local.style.left,'','main video must not inherit preview offsets');
    assert.equal(remote.style.left,'120px','new preview position clamps to its own width');
    f.element('minimizeCall').onclick();
    const card=f.element('callCard');card.dispatch('pointerdown',{pointerId:3,clientX:30,clientY:30,target:{closest:()=>null}});
    card.dispatch('pointermove',{pointerId:3,clientX:-100,clientY:1000});card.dispatch('pointerup',{pointerId:3});
    assert.equal(card.style.left,'0px');assert.equal(card.style.top,'565px');
    assert.equal(pc.connectionState,'connected','drag keeps the call alive');
  } finally {f.calls.close();}
});
