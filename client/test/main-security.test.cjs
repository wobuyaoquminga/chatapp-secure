'use strict';
// Executes the actual production main process with an Electron boundary stub.
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),{EventEmitter}=require('node:events'),{pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..'),entryUrl=pathToFileURL(path.join(root,'ui','index.html')).href.toLowerCase();
async function fixture(){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-main-')),handlers=new Map(),permissions={},windows=[],notices=[];let controller;
 const app=new EventEmitter();app.commandLine={getSwitchValue:()=>'',appendSwitch:()=>{}};app.paths={appData:dir};app.getPath=n=>app.paths[n];app.setPath=(n,v)=>app.paths[n]=v;app.requestSingleInstanceLock=()=>true;app.whenReady=()=>Promise.resolve();app.setAppUserModelId=()=>{};app.quit=()=>{};app.isPackaged=true;
 class Window extends EventEmitter{constructor(options){super();this.options=options;this.focused=false;this.webContents=new EventEmitter();this.webContents.mainFrame={url:entryUrl};this.webContents.send=()=>{};this.webContents.setWindowOpenHandler=h=>this.openHandler=h;windows.push(this);}isDestroyed(){return false;}isFocused(){return this.focused;}isMinimized(){return false;}flashFrame(v){this.flashing=v;}removeMenu(){}loadFile(){}show(){}focus(){this.focused=true;this.emit('focus');}destroy(){} }
 class Notification extends EventEmitter{static isSupported(){return true;}constructor(options){super();this.options=options;notices.push(this);}show(){this.shown=true;}close(){this.closed=true;}}
 class Controller{constructor(_dir,_storage,notify){controller=this;this.notify=notify;this.generation=1;this.server='http://localhost';this.user='alice';this.online=true;this.contactState={bob:{status:'accepted'}};this.engine={state:{}};this.commands=[];}serial(task){return Promise.resolve().then(task);}stopLocations(){return true;}setForeground(v){this.foreground=v;}send(payload){this.commands.push(['send',payload]);return true;}sendLocation(payload){this.commands.push(['sendLocation',payload]);return true;}logout(){}normalizeServer(s){return s;}callPeer(){return 'peer-account';}}
 const native={NativeLocation:class{cancel(){}async query(authorized){if(!authorized())throw Error('unauthorized');return {coords:{latitude:1,longitude:2}};}}};
 const session={defaultSession:{setPermissionRequestHandler:h=>permissions.request=h,setPermissionCheckHandler:h=>permissions.check=h}};
 const electron={app,BrowserWindow:Window,Notification,ipcMain:{handle:(n,h)=>handlers.set(n,h)},safeStorage:{},session,shell:{openExternal:async()=>{}}};
 const customRequire=name=>name==='electron'?electron:name==='./controller.cjs'?{Controller}:name==='./native-location.cjs'?native:name.startsWith('./')?require(path.join(root,name)):require(name);
 vm.runInNewContext(fs.readFileSync(path.join(root,'main.cjs'),'utf8'),{require:customRequire,__dirname:root,process:{platform:'win32',argv:['electron','main.cjs'],env:{}},console,URL,setTimeout,clearTimeout,Promise});
 await new Promise(r=>setImmediate(r));const window=windows[0],event={sender:window.webContents,senderFrame:window.webContents.mainFrame};
 return {window,controller,handlers,permissions,event,notices,close:()=>fs.rmSync(dir,{recursive:true,force:true})};
}
test('production BrowserWindow, navigation and CSP retain the security baseline',async()=>{
 const f=await fixture();try{
  const p=f.window.options.webPreferences;assert.equal(p.contextIsolation,true);assert.equal(p.nodeIntegration,false);assert.equal(p.sandbox,true);assert.equal(p.devTools,false);assert.notEqual(p.webSecurity,false);
  assert.deepEqual(JSON.parse(JSON.stringify(f.window.openHandler({url:'https://attacker.invalid'}))),{action:'deny'});
  let prevented=false;f.window.webContents.emit('will-navigate',{preventDefault:()=>prevented=true});assert(prevented);
  const html=fs.readFileSync(path.join(root,'ui','index.html'),'utf8');assert.match(html,/script-src 'self'/);assert.match(html,/connect-src 'none'/);assert.match(html,/base-uri 'none'/);
 }finally{f.close();}
});
test('actual production IPC rejects foreign senders/subframes and both location send entry points while backgrounded',async()=>{
 const f=await fixture();try{
  const run=f.handlers.get('chat:command');
  await assert.rejects(run({...f.event,sender:{}},{action:'send',payload:{}}),/sender rejected/);
  await assert.rejects(run({...f.event,senderFrame:{url:entryUrl}},{action:'send',payload:{}}),/sender rejected/);
  await assert.rejects(f.handlers.get('chat:open-map')({...f.event,senderFrame:{url:'https://attacker.invalid'}},1,2),/sender rejected/);
  const F=require('../ui/features.js'),packet={v:1,kind:'pin',sessionId:'8b61a31b-e672-471d-a55c-76a0f546ac15',seq:0,latitude:1,longitude:2,accuracy:0,recordedAt:new Date().toISOString(),expiresAt:new Date(Date.now()+60000).toISOString()},body=F.encode(packet);
  for(const action of ['send','sendLocation']){const result=await run(f.event,{action,payload:{peer:'bob',body}});assert.equal(result.ok,false);assert.match(result.error,/前台/);}
  assert.deepEqual(f.controller.commands,[]);
  assert.equal((await run(f.event,{action:'locationPermission',payload:{peer:'bob'}})).ok,false);
  assert.equal(f.permissions.check(f.window.webContents,'geolocation','', {requestingUrl:entryUrl,isMainFrame:true}),false);
  f.window.focused=true;assert.equal((await run(f.event,{action:'locationPermission',payload:{peer:'bob'}})).ok,true);
  assert.equal(f.permissions.check(f.window.webContents,'geolocation','',{requestingUrl:entryUrl,isMainFrame:true}),true);
  assert.equal(f.permissions.check({},'geolocation','',{requestingUrl:entryUrl,isMainFrame:true}),false);
  assert.equal(f.permissions.check(f.window.webContents,'geolocation','',{requestingUrl:entryUrl,isMainFrame:false}),false);
  assert.equal(f.permissions.check(f.window.webContents,'media','',{requestingUrl:entryUrl,isMainFrame:true,mediaTypes:['audio']}),false);
  f.controller.call={peer:'bob',callId:'call',mode:'video',userApproved:true,accountId:'peer-account'};
  assert.equal((await run(f.event,{action:'callMediaPermission',payload:{peer:'bob',callId:'call',mode:'video'}})).ok,true);
  assert.equal(f.permissions.check(f.window.webContents,'media','',{requestingUrl:entryUrl,isMainFrame:true,mediaTypes:['audio','video']}),true);
  f.window.focused=false;f.window.emit('blur');assert.equal(f.permissions.check(f.window.webContents,'media','',{requestingUrl:entryUrl,isMainFrame:true,mediaTypes:['audio']}),false);
 }finally{f.close();}
});
test('actual main notifies background messages without plaintext, rejects stale account events, and foreground suppresses notices',async()=>{
 const f=await fixture();try{
  const frame={type:'message',peer:'bob',generation:1,server:'http://localhost',username:'alice'};f.controller.notify(frame);
  assert.equal(f.notices.length,1);assert.equal(f.notices[0].options.body,'bob 发来了新消息');assert.equal(f.window.flashing,true);
  f.controller.notify({...frame,generation:0});f.controller.notify({...frame,server:'http://localhost:2'});assert.equal(f.notices.length,1);
  f.window.focus();f.controller.notify(frame);assert.equal(f.notices.length,1);assert.equal(f.controller.foreground,true);
  f.controller.notify({username:'',status:'未登录'});assert.equal(f.notices[0].closed,true);
 }finally{f.close();}
});
