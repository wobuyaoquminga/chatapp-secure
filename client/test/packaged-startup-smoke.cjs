'use strict';
// Exercise the packaged app with a fresh isolated profile, including a crash after the first save.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process'),WebSocket=require('ws');
const exe=path.resolve(process.env.CLIENT_EXE||path.join(__dirname,'../../release/Chat-win32-x64/Chat.exe'));
const out=process.env.CHAT_STARTUP_QA_DIR||fs.mkdtempSync(path.join(os.tmpdir(),'chat-startup-'));
fs.mkdirSync(out,{recursive:true});
const run=fs.mkdtempSync(path.join(out,'run-'));
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const checks=[];let child,ws,evaluate;

async function start(){
 const env={...process.env,CHAT_TEST_APPDATA:path.join(run,'appdata')};delete env.ELECTRON_RUN_AS_NODE;
 child=spawn(exe,['--profile=qa-migrate-startup','--test-hidden','--remote-debugging-address=127.0.0.1','--remote-debugging-port=18109'],{env,windowsHide:true,stdio:'ignore'});
 let target;
 for(let i=0;i<700;i++){
   if(child.exitCode!==null||child.signalCode!==null)break;
   try{target=(await(await fetch('http://127.0.0.1:18109/json')).json()).find(t=>t.type==='page'&&t.url.includes('index.html'));if(target)break;}catch{}
   await delay(50);
 }
 assert(target,'Packaged renderer did not start within 35 seconds');
 ws=new WebSocket(target.webSocketDebuggerUrl);
 await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
 const pending=new Map();let id=0;
 ws.on('message',raw=>{const result=JSON.parse(raw);if(result.id){const p=pending.get(result.id);pending.delete(result.id);result.error?p.reject(Error(result.error.message)):p.resolve(result.result);}});
 evaluate=async expression=>{
   const key=++id;
   const result=await new Promise((resolve,reject)=>{pending.set(key,{resolve,reject});ws.send(JSON.stringify({id:key,method:'Runtime.evaluate',params:{expression,returnByValue:true,awaitPromise:true}}));});
   if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));
   return result.result.value;
 };
 for(let i=0;i<100&&!await evaluate('!!window.chat');i++)await delay(50);
 assert.equal(await evaluate('!!window.chat'),true,'Packaged preload did not start');
}

async function waitForExit(){
 if(child.exitCode!==null||child.signalCode!==null)return;
 await new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>reject(Error('Packaged app did not exit within 15 seconds')),15000);
   child.once('exit',()=>{clearTimeout(timer);resolve();});
 });
}

(async()=>{
 await start();
 const snapshot=await evaluate("window.chat.command('snapshot').then(r=>({ok:r.ok,error:r.error,accountStatus:r.value?.accountStatus}))");
 assert.equal(snapshot.ok,true,snapshot.error);assert.equal(snapshot.accountStatus,null);checks.push('fresh real Controller snapshot');
 for(let i=0;i<100&&await evaluate("document.getElementById('serverSetup').hidden");i++)await delay(50);
 assert.equal(await evaluate("document.getElementById('serverSetup').hidden"),false);checks.push('first-run server setup visible');
 const info=await evaluate("window.chat.command('appInfo').then(r=>r.value)");
 assert.equal(info.version,require('../package.json').version);
 const saved=await evaluate("window.chat.command('saveServer',{server:'https://qa.example.invalid'}).then(r=>{if(r.ok)render(r.value);return {ok:r.ok,error:r.error};})");
 assert.equal(saved.ok,true,saved.error);assert.equal(await evaluate("document.getElementById('auth').hidden"),false);checks.push('saved-server login form visible');
 ws.terminate();ws=null;
 const firstExit=waitForExit();child.kill();await firstExit;child=null;

 await start();
 const restored=await evaluate("window.chat.command('snapshot').then(r=>({ok:r.ok,error:r.error,selectedServer:r.value?.selectedServer,servers:r.value?.servers}))");
 assert.equal(restored.ok,true,restored.error);
 assert.equal(restored.selectedServer,'https://qa.example.invalid');
 assert.deepEqual(restored.servers,['https://qa.example.invalid']);checks.push('server decrypts after forced restart');
 const savedAgain=await evaluate("window.chat.command('saveServer',{server:'https://qa2.example.invalid'}).then(r=>({ok:r.ok,error:r.error,selectedServer:r.value?.selectedServer}))");
 assert.equal(savedAgain.ok,true,savedAgain.error);
 assert.equal(savedAgain.selectedServer,'https://qa2.example.invalid');checks.push('server saves after forced restart');
 const loggedOut=await evaluate("window.chat.command('logout').then(r=>({ok:r.ok,error:r.error}))");
 assert.equal(loggedOut.ok,true,loggedOut.error);checks.push('logout without engine stays safe');
 assert(!await evaluate("document.getElementById('notice').textContent.includes(\"reading 'state'\")"));
 await evaluate('setTimeout(()=>window.close(),0); true');
 await waitForExit();child=null;ws.terminate();ws=null;
 fs.writeFileSync(path.join(out,'startup-result.json'),JSON.stringify({pass:true,version:info.version,checks},null,2));
 console.log('Packaged startup passed: '+checks.length+' checks; version '+info.version);
})().catch(error=>{
 fs.writeFileSync(path.join(out,'startup-result.json'),JSON.stringify({pass:false,checks,error:error.message},null,2));
 console.error(error.stack);process.exitCode=1;
}).finally(async()=>{
 ws?.terminate();
 if(child&&child.exitCode===null&&child.signalCode===null){const exited=waitForExit();child.kill();await exited.catch(()=>{});}
});
