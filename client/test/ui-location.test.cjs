'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const crypto=require('node:crypto');

function ui() {
  const elements=new Map(),sent=[];
  const element=id=>{
    if(!elements.has(id))elements.set(id,{
      value:'',textContent:'',hidden:false,disabled:false,
      addEventListener(){},setAttribute(){},focus(){},replaceChildren(){},
      classList:{add(){},remove(){},toggle(){}},
    });
    return elements.get(id);
  };
  let finishAddress;
  const chat={
    command:async(action,payload)=>{
      if(action==='snapshot'||action==='appInfo')return new Promise(()=>{});
      if(action==='nativePosition')return {ok:true,value:{timestamp:Date.now(),coords:{latitude:31.23,longitude:121.47,accuracy:12}}};
      if(action==='sendLocation')sent.push({action,payload});
      return {ok:true,value:{}};
    },
    reverseLocation:()=>new Promise(resolve=>{finishAddress=resolve;}),
    onCall(){},subscribe(){},onLocationStop(){},
  };
  const document={hidden:false,getElementById:element,addEventListener(){}};
  const window={chat,ChatFeatures:{HOUR:3600000,encode:JSON.stringify},ChatFiles:{},ChatCalls:{create:()=>({closeActions(){},snapshot(){}})},addEventListener(){}};
  const context=vm.createContext({window,document,crypto,setTimeout,clearTimeout,setInterval,clearInterval,navigator:{onLine:true}});
  vm.runInContext(fs.readFileSync(require.resolve('../ui/app.js'),'utf8'),context);
  vm.runInContext("activePeer='bob';context='server\\0alice|server';state={online:true,username:'alice',server:'server'};contactIndex=new Map([['bob',{status:'accepted'}]])",context);
  return {element,context,sent,resolveAddress:value=>finishAddress(value)};
}
const tick=()=>new Promise(resolve=>setImmediate(resolve));

test('fixed location waits for address and drops result after peer switch',async()=>{
  const app=ui(),pending=app.element('sendPosition').onclick();
  await tick();assert.equal(app.sent.length,0);
  vm.runInContext("activePeer='carol'",app.context);
  app.resolveAddress('上海市黄浦区');await pending;
  assert.equal(app.sent.length,0);
});

test('fixed location drops address after account or server switch',async()=>{
  const app=ui(),pending=app.element('sendPosition').onclick();
  await tick();assert.equal(app.sent.length,0);
  vm.runInContext("context='other-server\\0alice|other-server'",app.context);
  app.resolveAddress('上海市黄浦区');await pending;
  assert.equal(app.sent.length,0);
});

test('manual location encrypts the resolved address only for its original peer',async()=>{
  const app=ui();app.element('latitude').value='31.23';app.element('longitude').value='121.47';
  vm.runInContext('render=()=>{}',app.context);
  const pending=app.element('manualPosition').onsubmit({preventDefault(){}});
  await tick();assert.equal(app.sent.length,0);
  app.resolveAddress('上海市黄浦区');await pending;
  assert.equal(app.sent.length,1);
  assert.equal(app.sent[0].payload.peer,'bob');
  assert.equal(JSON.parse(app.sent[0].payload.body).address,'上海市黄浦区');
});

test('manual location sends without an address when none is available',async()=>{
  const app=ui();app.element('latitude').value='31.23';app.element('longitude').value='121.47';
  vm.runInContext('render=()=>{}',app.context);
  const pending=app.element('manualPosition').onsubmit({preventDefault(){}});
  await tick();app.resolveAddress('');await pending;
  assert.equal(app.sent.length,1);
  assert.equal(Object.hasOwn(JSON.parse(app.sent[0].payload.body),'address'),false);
});

test('stopping live share while resolving an address prevents its first send',async()=>{
  const app=ui(),pending=vm.runInContext('startLive()',app.context);
  await tick();assert.equal(app.sent.length,0);
  await vm.runInContext('stopLive()',app.context);
  app.resolveAddress('上海市黄浦区');await pending;
  assert.equal(app.sent.length,0);
});

test('unchanged visible messages reuse their rendered card without parsing again',()=>{
  const app=ui(),message={sender:'alice',recipient:'bob',clientId:'1',body:'x'.repeat(4000),status:'sent'};
  const bubble={_renderState:{body:message.body,status:'sent',sessionStatus:undefined,sessionValue:undefined,live:false,snapshot:false,online:true,outbox:false,accepted:true},nextSibling:null};
  const log=app.element('messages');Object.assign(log,{firstChild:bubble,scrollHeight:0,scrollTop:0,clientHeight:0,insertBefore(){throw Error('unexpected reorder');}});
  app.context.message=message;app.context.bubble=bubble;
  vm.runInContext("renderPeer='bob';messageNodes=new Map([['alice:1',bubble]]);messageIndex={messages:()=>[message],display:()=>({items:[{m:message,key:'alice:1'}],tracker:new Map()})};window.ChatFeatures.parse=()=>{throw Error('unexpected location parse')};window.ChatFiles.parseCard=()=>{throw Error('unexpected file parse')};renderMessages()",app.context);
  assert.equal(app.element('olderMessages').hidden,true);
  const node=()=>({textContent:'',className:'',dataset:{},append(){},classList:{add(){}}});
  Object.assign(bubble,{dataset:{},children:[],classList:{add(){}},replaceChildren(){this.children=[];},append(...children){this.children.push(...children);},querySelector(){return null;}});
  app.context.document.createElement=node;
  app.context.window.ChatFeatures.parse=()=>null;app.context.window.ChatFiles.parseCard=()=>null;
  message.status='delivered';vm.runInContext('renderMessages()',app.context);
  assert.equal(bubble._renderState.status,'delivered','in-place status mutation invalidates the card');
  vm.runInContext('state.online=false;renderMessages()',app.context);
  assert.equal(bubble._renderState.online,false,'connection change invalidates the card');
});

test('updated live address invalidates its existing location card',()=>{
  const app=ui(),message={sender:'alice',recipient:'bob',clientId:'1',body:'location',status:'sent',createdAt:new Date().toISOString()};
  const previous={kind:'live',seq:0,latitude:31.23,longitude:121.47,accuracy:12,address:'旧地址',recordedAt:new Date().toISOString()};
  const session={value:previous,status:'共享中'};
  const bubble={dataset:{signature:'old'},children:[],classList:{add(){}},replaceChildren(){this.children=[];},append(...children){this.children.push(...children);},querySelector(){return null;},nextSibling:null,
    _renderState:{body:message.body,status:'sent',sessionStatus:'共享中',sessionValue:previous,live:false,snapshot:false,online:true,outbox:false,accepted:true}};
  const log=app.element('messages');Object.assign(log,{firstChild:bubble,scrollHeight:0,scrollTop:0,clientHeight:0});
  app.context.message=message;app.context.bubble=bubble;app.context.session=session;
  app.context.document.createElement=()=>({textContent:'',className:'',dataset:{},append(){},classList:{add(){}}});
  app.context.window.ChatFiles.parseCard=()=>null;
  session.value={...previous,seq:1,address:'新地址'};
  vm.runInContext("renderPeer='bob';messageNodes=new Map([['alice\\0session',bubble]]);messageIndex={messages:()=>[message],display:()=>({items:[{m:message,key:'alice\\0session',session}],tracker:new Map()})};renderMessages()",app.context);
  assert.equal(bubble.children[1].textContent,'新地址');
  assert.equal(bubble._renderState.sessionValue,session.value);
});
