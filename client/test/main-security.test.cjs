'use strict';
// Executes the actual production main process with an Electron boundary stub.
const {test}=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm');
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),http=require('node:http'),crypto=require('node:crypto'),{EventEmitter}=require('node:events'),{pathToFileURL}=require('node:url');
const root=path.resolve(__dirname,'..'),entryUrl=pathToFileURL(path.join(root,'ui','index.html')).href.toLowerCase();
async function fixture(){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-main-')),handlers=new Map(),permissions={},windows=[],notices=[],links=[],shown=[];let controller;
 const app=new EventEmitter();app.commandLine={getSwitchValue:()=>'',appendSwitch:()=>{}};app.paths={appData:dir,downloads:dir};app.getPath=n=>app.paths[n];app.getVersion=()=> '0.6.0';app.setPath=(n,v)=>app.paths[n]=v;app.requestSingleInstanceLock=()=>true;app.whenReady=()=>Promise.resolve();app.setAppUserModelId=()=>{};app.quit=()=>{};app.isPackaged=true;
 class Window extends EventEmitter{constructor(options){super();this.options=options;this.focused=false;this.webContents=new EventEmitter();this.webContents.mainFrame={url:entryUrl};this.webContents.send=()=>{};this.webContents.setWindowOpenHandler=h=>this.openHandler=h;windows.push(this);}isDestroyed(){return false;}isFocused(){return this.focused;}isMinimized(){return false;}flashFrame(v){this.flashing=v;}removeMenu(){}loadURL(){}loadFile(){}show(){}focus(){this.focused=true;this.emit('focus');}destroy(){} }
 class Notification extends EventEmitter{static isSupported(){return true;}constructor(options){super();this.options=options;notices.push(this);}show(){this.shown=true;}close(){this.closed=true;}}
 class Controller{constructor(_dir,_storage,notify){controller=this;this.notify=notify;this.generation=1;this.server='http://localhost';this.user='alice';this.online=true;this.contactState={bob:{status:'accepted'}};this.engine={state:{}};this.commands=[];}snapshot(){return {selectedServer:this.selectedServer||this.server};}serial(task){return Promise.resolve().then(task);}stopLocations(){return true;}setForeground(v){this.foreground=v;}send(payload){this.commands.push(['send',payload]);return true;}sendLocation(payload){this.commands.push(['sendLocation',payload]);return true;}logout(){}normalizeServer(s){return s;}callPeer(){return 'peer-account';}}
 const native={NativeLocation:class{cancel(){}async query(authorized){if(!authorized())throw Error('unauthorized');return {coords:{latitude:1,longitude:2}};}}};
 const session={defaultSession:{setPermissionRequestHandler:h=>permissions.request=h,setPermissionCheckHandler:h=>permissions.check=h}};
 const dialog={showSaveDialog:async()=>({filePath:path.join(dir,'update.zip')}),showOpenDialog:async()=>({canceled:true})};
 const electron={app,BrowserWindow:Window,Notification,ipcMain:{handle:(n,h)=>handlers.set(n,h)},safeStorage:{},session,shell:{openExternal:async url=>{links.push(url);},showItemInFolder:file=>shown.push(file)},dialog};
 const customRequire=name=>name==='electron'?electron:name==='./controller.cjs'?{Controller}:name==='./storage-ready.cjs'?{waitForStorageKey:async()=>{}}:name==='./native-location.cjs'?native:name.startsWith('./')?require(path.join(root,name)):require(name);
 vm.runInNewContext(fs.readFileSync(path.join(root,'main.cjs'),'utf8'),{require:customRequire,__dirname:root,process:{platform:'win32',argv:['electron','main.cjs'],env:{}},console,URL,Buffer,AbortController,AbortSignal,setTimeout,clearTimeout,Promise});
 await new Promise(r=>setImmediate(r));const window=windows[0],event={sender:window.webContents,senderFrame:window.webContents.mainFrame};
 return {app,dialog,window,controller,handlers,permissions,event,notices,links,shown,dir,close:()=>fs.rmSync(dir,{recursive:true,force:true})};
}
const FileFormat=require('../ui/file-format.js'),Files=require('../file-transfer.cjs');
async function mediaMessage(f,name,bytes){
 const id=crypto.randomUUID(),clientId=crypto.randomUUID(),encrypted=Files.encrypt(bytes,id),meta={v:1,id,name,size:bytes.length,key:encrypted.key,iv:encrypted.iv,sha256:encrypted.sha256,expiresAt:new Date(Date.now()+3600000).toISOString()};
 f.controller.accountId='account-a';f.controller.token='token';f.controller.engine.state.messages={['bob:'+clientId]:{sender:'bob',recipient:'alice',clientId,body:FileFormat.encode(meta)}};
 await Files.cachePut(f.app.getPath('userData'),f.controller.server,f.controller.accountId,id,encrypted.blob);
 return {id,clientId,meta,encrypted};
}
test('updates open only the fixed GitHub release page from the trusted window',async()=>{
 const f=await fixture();try{
  const open=f.handlers.get('chat:open-updates');
  await assert.rejects(open({...f.event,sender:{}}),/sender rejected/);
  await assert.rejects(open({...f.event,senderFrame:{url:'https://attacker.invalid'}}),/sender rejected/);
  await open(f.event);
  assert.deepEqual(f.links,['https://github.com/wobuyaoquminga/chatapp-secure/releases/latest']);
  assert.equal((await f.handlers.get('chat:command')(f.event,{action:'appInfo'})).value.version,'0.6.0');
 }finally{f.close();}
});
test('update IPC downloads from the selected server and opens only its verified file',async()=>{
 const bytes=Buffer.from('zip fixture'),manifest={platform:'windows-x64',version:'0.6.1',fileName:'Chat-0.6.1.zip',size:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),downloadPath:'/api/updates/files/windows-x64'};
 const server=http.createServer((req,res)=>{if(req.url?.startsWith('/api/updates/latest?'))res.end(JSON.stringify(manifest));else if(req.url===manifest.downloadPath)res.end(bytes);else res.writeHead(404).end();});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const f=await fixture();
 try{
  f.controller.selectedServer='http://127.0.0.1:'+server.address().port;f.controller.server='https://old.invalid';
  const check=f.handlers.get('chat:check-update'),download=f.handlers.get('chat:download-update'),open=f.handlers.get('chat:open-download');
  await assert.rejects(check({...f.event,sender:{}}),/sender rejected/);
  assert.equal((await check(f.event)).version,'0.6.1');
  await assert.rejects(download({...f.event,senderFrame:{url:'https://evil.invalid'}}),/sender rejected/);
  const result=await download(f.event);assert.equal(result.fileName,'update.zip');assert.deepEqual(fs.readFileSync(path.join(f.dir,'update.zip')),bytes);
  await open(f.event);assert.deepEqual(f.shown,[path.join(f.dir,'update.zip')]);
  f.controller.selectedServer='https://other.invalid';await assert.rejects(open(f.event),/没有可打开/);
 }finally{f.close();await new Promise(resolve=>server.close(resolve));}
});
test('update IPC permits one download and cancellation removes the partial file',async()=>{
 const bytes=Buffer.alloc(1000,1),manifest={platform:'windows-x64',version:'0.6.1',fileName:'Chat-0.6.1.zip',size:bytes.length,sha256:crypto.createHash('sha256').update(bytes).digest('hex'),downloadPath:'/api/updates/files/windows-x64'};
 const server=http.createServer((req,res)=>{if(req.url?.startsWith('/api/updates/latest?'))res.end(JSON.stringify(manifest));else if(req.url===manifest.downloadPath){res.writeHead(200,{'content-length':bytes.length});res.write(bytes.subarray(0,1));}else res.writeHead(404).end();});
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const f=await fixture();
 try{
  f.controller.selectedServer='http://127.0.0.1:'+server.address().port;
  const first=f.handlers.get('chat:download-update')(f.event);await new Promise(resolve=>setTimeout(resolve,50));
  await assert.rejects(f.handlers.get('chat:download-update')(f.event),/已有更新包正在下载/);
  await f.handlers.get('chat:cancel-update')(f.event);await assert.rejects(first);
  assert.deepEqual(fs.readdirSync(f.dir),[]);
 }finally{f.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});
test('production BrowserWindow, navigation and CSP retain the security baseline',async()=>{
 const f=await fixture();try{
  const p=f.window.options.webPreferences;assert.equal(p.contextIsolation,true);assert.equal(p.nodeIntegration,false);assert.equal(p.sandbox,true);assert.equal(p.devTools,false);assert.notEqual(p.webSecurity,false);
  assert.deepEqual(JSON.parse(JSON.stringify(f.window.openHandler({url:'https://attacker.invalid'}))),{action:'deny'});
  let prevented=false;f.window.webContents.emit('will-navigate',{preventDefault:()=>prevented=true});assert(prevented);
  const html=fs.readFileSync(path.join(root,'ui','index.html'),'utf8');assert.match(html,/script-src 'self'/);assert.match(html,/connect-src 'none'/);assert.match(html,/img-src 'self' blob:/);assert.match(html,/media-src 'self' blob:/);assert.match(html,/base-uri 'none'/);
 }finally{f.close();}
});
test('file IPC rejects foreign frames and invalid message references before a native save dialog',async()=>{
 const f=await fixture();try{
  const send=f.handlers.get('chat:send-file'),save=f.handlers.get('chat:save-file');
  await assert.rejects(send({...f.event,sender:{}},'bob'),/sender rejected/);
  await assert.rejects(send({...f.event,senderFrame:{url:'https://attacker.invalid'}},'bob'),/sender rejected/);
  await assert.rejects(save({...f.event,sender:{}},'123','bob'),/sender rejected/);
  f.window.focused=true;
  await assert.rejects(save(f.event,'not-a-uuid','bob'),/文件请求无效/);
  await assert.rejects(save(f.event,'12345678-1234-4234-8234-123456789abc','bob'),/没有可保存的文件/);
 }finally{f.close();}
});
test('media preview decrypts only a real peer history message through the trusted frame',async()=>{
 const f=await fixture();try{
  f.window.focused=true;const bytes=Buffer.from('89504e470d0a1a0a00000000','hex'),{clientId}=await mediaMessage(f,'photo.png',bytes),preview=f.handlers.get('chat:preview-file');
  await assert.rejects(preview({...f.event,sender:{}},clientId,'bob'),/sender rejected/);
  await assert.rejects(preview({...f.event,senderFrame:{url:'https://invalid.example'}},clientId,'bob'),/sender rejected/);
  await assert.rejects(preview(f.event,clientId,'mallory'),/没有可保存的文件/);
  await assert.rejects(preview(f.event,'file:///secret.png','bob'),/文件请求无效/);
  assert.deepEqual(JSON.parse(JSON.stringify(await preview(f.event,clientId,'bob'))),{mime:'image/png',kind:'image',base64:bytes.toString('base64')});
 }finally{f.close();}
});
test('media preview rejects an authenticated HTML/SVG payload with a picture extension',async()=>{
 const f=await fixture();try{
  f.window.focused=true;const {clientId}=await mediaMessage(f,'photo.png',Buffer.from('<svg><script>alert(1)</script></svg>'));
  await assert.rejects(f.handlers.get('chat:preview-file')(f.event,clientId,'bob'),/媒体格式与文件内容不符/);
  assert.equal(fs.readdirSync(path.join(f.app.getPath('userData'),'encrypted-files')).length,1);
 }finally{f.close();}
});
test('closing preview aborts pending download and account changes reject a late response',async()=>{
 const f=await fixture(),original=Files.download;try{
  f.window.focused=true;const {clientId,meta,encrypted}=await mediaMessage(f,'photo.png',Buffer.from('89504e470d0a1a0a00000000','hex'));
  await fs.promises.rm(path.join(f.app.getPath('userData'),'encrypted-files'),{recursive:true,force:true});
  Files.download=({signal})=>new Promise((resolve,reject)=>{signal.addEventListener('abort',()=>reject(Object.assign(Error('aborted'),{name:'AbortError'})),{once:true});});
  const first=f.handlers.get('chat:preview-file')(f.event,clientId,'bob');await new Promise(resolve=>setImmediate(resolve));
  await f.handlers.get('chat:cancel-file-preview')(f.event);await assert.rejects(first,/取消或超时/);
  let finish;Files.download=()=>new Promise(resolve=>{finish=resolve;});
  const second=f.handlers.get('chat:preview-file')(f.event,clientId,'bob');while(!finish)await new Promise(resolve=>setImmediate(resolve));
  f.controller.accountId='account-b';finish(encrypted.blob);await assert.rejects(second,/账号已变化/);
  assert.equal(meta.name,'photo.png');
 }finally{Files.download=original;f.close();}
});
test('opening another preview waits for the canceled preview to release its file task',async()=>{
 const f=await fixture(),original=Files.download;try{
  f.window.focused=true;const bytes=Buffer.from('89504e470d0a1a0a00000000','hex'),{clientId,encrypted}=await mediaMessage(f,'photo.png',bytes);
  await fs.promises.rm(path.join(f.app.getPath('userData'),'encrypted-files'),{recursive:true,force:true});
  let calls=0;Files.download=({signal})=>++calls===1?new Promise((_resolve,reject)=>{const aborted=()=>reject(Object.assign(Error('aborted'),{name:'AbortError'}));if(signal.aborted)aborted();else signal.addEventListener('abort',aborted,{once:true});}):Promise.resolve(encrypted.blob);
  const preview=f.handlers.get('chat:preview-file'),first=preview(f.event,clientId,'bob');
  while(!calls)await new Promise(resolve=>setImmediate(resolve));
  const second=preview(f.event,clientId,'bob');
  await assert.rejects(first,/取消或超时/);
  assert.equal((await second).base64,bytes.toString('base64'));
  assert.equal(calls,2);
 }finally{Files.download=original;f.close();}
});
test('a durable file message keeps its upload if a later send step fails',async()=>{
 const f=await fixture(),originalUpload=Files.upload,originalRemove=Files.remove;try{
  f.window.focused=true;f.controller.accountId='account-a';f.controller.token='token';f.controller.filePeer=()=> 'peer-account';f.controller.engine.state.messages={};
  const selected=path.join(f.dir,'note.txt');fs.writeFileSync(selected,'hello');f.dialog.showOpenDialog=async()=>({filePaths:[selected]});
  Files.upload=async()=>({expiresAt:new Date(Date.now()+3600000).toISOString()});let removed=0;Files.remove=async()=>{removed++;};
  f.controller.sendFileDescriptor=({body})=>{f.controller.engine.state.messages['alice:queued']={body};throw Error('later failure');};
  await assert.rejects(f.handlers.get('chat:send-file')(f.event,'bob','file'),/later failure/);
  assert.equal(removed,0,'the encrypted outbox still needs the uploaded file');
  f.controller.sendFileDescriptor=()=>{throw Error('before queue');};
  await assert.rejects(f.handlers.get('chat:send-file')(f.event,'bob','file'),/before queue/);
  assert.equal(removed,1,'unreferenced uploads should be removed');
 }finally{Files.upload=originalUpload;Files.remove=originalRemove;f.close();}
});
test('image selection rejects wrong file kind after the native picker returns',async()=>{
 const f=await fixture();try{
  f.window.focused=true;f.controller.filePeer=()=> 'peer-account';const file=path.join(f.dir,'wrong.mp4');fs.writeFileSync(file,Buffer.from('000000106674797069736f6d00000000','hex'));
  let options;f.dialog.showOpenDialog=async(_window,value)=>{options=value;return {filePaths:[file]};};
  await assert.rejects(f.handlers.get('chat:send-file')(f.event,'bob','image'),/所选文件类型不符/);
  assert.deepEqual(Array.from(options.filters[0].extensions),['jpg','jpeg','png','gif','webp']);
  await assert.rejects(f.handlers.get('chat:send-file')(f.event,'bob','javascript'),/文件类型无效/);
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
  const reverse=f.handlers.get('chat:reverse-location');
  await assert.rejects(reverse({...f.event,sender:{}},31,121,'bob'),/sender rejected/);
  await assert.rejects(reverse({...f.event,senderFrame:{url:'https://attacker.invalid'}},31,121,'bob'),/sender rejected/);
  await assert.rejects(reverse(f.event,91,121,'bob'),/坐标无效/);
  await assert.rejects(reverse(f.event,31,121,'eve'),/授权已失效/);
  assert.equal(await reverse(f.event,31,121,'bob'),'');
  f.controller.generation++;await assert.rejects(reverse(f.event,31,121,'bob'),/授权已失效/);f.controller.generation--;
  f.controller.server='https://changed.example';await assert.rejects(reverse(f.event,31,121,'bob'),/授权已失效/);f.controller.server='http://localhost';
  assert.equal((await run(f.event,{action:'revokeLocationPermission'})).ok,true);
  await assert.rejects(reverse(f.event,31,121,'bob'),/授权已失效/);
  assert.equal((await run(f.event,{action:'locationPermission',payload:{peer:'bob'}})).ok,true);
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
test('account changes after upload clean up the original account file before it is queued',async()=>{
 const f=await fixture(),originalUpload=Files.upload,originalRemove=Files.remove;
 try{
  f.window.focused=true;f.controller.filePeer=()=> 'peer-account';f.controller.token='original-token';
  const file=path.join(f.dir,'sample.txt');fs.writeFileSync(file,'file fixture');
  f.dialog.showOpenDialog=async()=>({filePaths:[file]});
  let uploaded,removed,queued=false;
  Files.upload=async options=>{
   uploaded=options;f.controller.generation++;f.controller.token='new-token';
   f.controller.server='https://new.example.invalid';f.controller.engine={state:{}};
   return {expiresAt:new Date(Date.now()+3600000).toISOString()};
  };
  Files.remove=async options=>{removed=options;};
  f.controller.sendFileDescriptor=()=>{queued=true;};
  await assert.rejects(f.handlers.get('chat:send-file')(f.event,'bob','file'),/账号已改变/);
  assert.equal(queued,false);
  assert.equal(removed.id,uploaded.id);
  assert.equal(removed.server,'http://localhost');
  assert.equal(removed.token,'original-token');
 }finally{Files.upload=originalUpload;Files.remove=originalRemove;f.close();}
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
