'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),WebSocket=require('ws'),{once}=require('node:events');
const {Controller}=require('../controller.cjs');
const storage={isEncryptionAvailable:()=>true,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()};
function fixture(){const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-refresh-'));const c=new Controller(dir,storage,()=>{});c.server='http://localhost';c.user='alice';c.accountId='account';return {c,close:()=>{c.logout();fs.rmSync(dir,{recursive:true,force:true});}};}
const auth=(token='new')=>({username:'alice',accountId:'account',token,refreshToken:token[0].repeat(43),expiresAt:new Date(Date.now()+60000).toISOString()});
test('RAM refresh tokens rotate and existing WebSocket is reauthenticated without replacing a live call',async()=>{
 const f=fixture(),server=new WebSocket.Server({host:'127.0.0.1',port:0});await once(server,'listening');let peer;
 server.on('connection',s=>{peer=s;s.on('message',data=>{const event=JSON.parse(data);if(event.type==='auth')s.send(JSON.stringify({type:event.token==='old'?'ready':'reauthenticated'}));});});
 try{
  const c=f.c;c.server='http://127.0.0.1:'+server.address().port;c.engine={state:{messages:{},outbox:{},verified:{}}};c.request=async endpoint=>endpoint==='/api/auth/refresh'?auth():[];c.adoptAuth(auth('old'));c.connect();
  for(let i=0;!c.online&&i<100;i++)await new Promise(r=>setTimeout(r,5));assert(c.online);
  const socket=c.socket,call={callId:'call',peer:'bob'};c.call=call;
  assert(await c.refreshSession());await new Promise(r=>setTimeout(r,10));
  assert.equal(c.socket,socket);assert.equal(c.call,call);assert.equal(c.token,'new');assert.equal(c.refreshToken,'n'.repeat(43));assert(c.online);
 }finally{f.close();peer?.terminate();await new Promise(r=>server.close(r));}
});
test('logout/server change rejects in-flight refresh responses and cancels callbacks',async()=>{
 const f=fixture();try{
  const c=f.c;c.adoptAuth(auth('old'));let resolve;c.request=()=>new Promise(r=>resolve=r);const work=c.refreshSession();
  c.logout();c.server='http://localhost:9999';resolve(auth());assert.equal(await work,false);assert.equal(c.token,null);assert.equal(c.refreshToken,null);assert.equal(c.refreshTimer,null);
 }finally{f.close();}
});
test('legacy login schedules no refresh, retries are bounded and changed refresh identity is rejected',async()=>{
 const f=fixture();try{
  const c=f.c;c.adoptAuth({token:'legacy',accountId:'account'});assert.equal(c.refreshTimer,null);assert.equal(await c.refreshSession(),false);
  c.adoptAuth(auth('old'));let attempts=0;c.request=async()=>{attempts++;throw Error('temporary');};
  for(let i=0;i<3;i++){await c.refreshSession();clearTimeout(c.refreshTimer);}
  assert.equal(attempts,3);assert(c.authFailed);assert.equal(c.refreshToken,null);assert.equal(c.refreshTimer,null);
  c.authFailed=false;c.adoptAuth(auth('old'));c.request=async()=>({...auth(),accountId:'other-device'});assert.equal(await c.refreshSession(),false);assert(c.authFailed);assert.equal(c.token,'old');
 }finally{f.close();}
});
test('short access TTL refreshes proactively and refresh credentials never enter a snapshot',async()=>{
 const f=fixture();try{
  const c=f.c;let refreshed=0;c.request=async()=>{refreshed++;return auth();};c.adoptAuth({...auth('old'),expiresAt:new Date(Date.now()+80).toISOString()});
  await new Promise(r=>setTimeout(r,100));assert.equal(refreshed,1);assert.equal(c.token,'new');
  const snapshot=JSON.stringify(c.snapshot());assert(!snapshot.includes(c.refreshToken));assert(!snapshot.includes('"token"'));
 }finally{f.close();}
});
