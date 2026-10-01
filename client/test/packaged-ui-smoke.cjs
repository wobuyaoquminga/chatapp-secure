'use strict';
// Exercises the delivered EXE's real main/preload/renderer against an isolated server.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),crypto=require('node:crypto');
const {spawn}=require('node:child_process'),WebSocket=require('ws');
const server=process.env.CHAT_QA_SERVER,exe=path.resolve(process.env.CLIENT_EXE||path.join(__dirname,'../../release/Chat-win32-x64/Chat.exe'));
if(!server||!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(server))throw Error('Isolated loopback QA server required');
const out=path.resolve(process.env.CHAT_PACKAGED_QA_DIR||path.join(require('node:os').tmpdir(),'chat-packaged-ui-'+Date.now()));fs.mkdirSync(out,{recursive:true});
const delay=ms=>new Promise(r=>setTimeout(r,ms));let a,b;const checks=[];
async function launch(port,label){
 const env={...process.env};delete env.ELECTRON_RUN_AS_NODE;
 const child=spawn(exe,['--profile=qa-audit-'+label+'-'+Date.now().toString(36),'--test-hidden','--remote-debugging-address=127.0.0.1','--remote-debugging-port='+port],{env,windowsHide:true,stdio:'ignore'});
 let target;for(let i=0;i<200;i++){try{target=(await(await fetch('http://127.0.0.1:'+port+'/json')).json()).find(t=>t.type==='page'&&t.url.includes('index.html'));if(target)break;}catch{}await delay(50);}
 if(!target){child.kill();throw Error('EXE renderer debugger unavailable');}
 const ws=new WebSocket(target.webSocketDebuggerUrl),pending=new Map();let id=0;await new Promise((resolve,reject)=>{ws.once('open',resolve);ws.once('error',reject);});
 ws.on('message',raw=>{const message=JSON.parse(raw);if(message.id){const work=pending.get(message.id);pending.delete(message.id);if(message.error)work.reject(Error(message.error.message));else work.resolve(message.result);}});
 function send(method,params){return new Promise((resolve,reject)=>{const key=++id;pending.set(key,{resolve,reject});ws.send(JSON.stringify({id:key,method,params}));});}
 async function evaluate(expression){const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true,userGesture:true});if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result.value;}
 async function command(action,payload){return evaluate(`window.chat.command(${JSON.stringify(action)},${JSON.stringify(payload)}).then(r=>{if(!r.ok)throw Error(r.error);if(r.value&&typeof r.value==='object'&&'snapshotRevision' in r.value)render(r.value);return true;})`);}
 async function until(expression,label){for(let i=0;i<300;i++){if(await evaluate(expression))return;await delay(50);}throw Error('Timeout '+label);}
 async function screenshot(name){
  try{const r=await Promise.race([send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false}),delay(3000).then(()=>{throw Error('Hidden production window capture timed out');})]);fs.writeFileSync(path.join(out,name+'.png'),Buffer.from(r.data,'base64'));}
  catch(error){checks.push({screenshot:name,available:false,reason:error.message});}
 }
 return {child,ws,evaluate,command,until,screenshot};
}
async function check(name,task){await task();checks.push({name,pass:true});console.log('PASS '+name);}
async function sendUI(client,text){await client.evaluate(`{document.getElementById('body').value=${JSON.stringify(text)};document.getElementById('sendForm').requestSubmit();}`);}
(async()=>{
 const suffix=Date.now().toString(36),alice='成品甲_'+suffix,bob='成品乙_'+suffix,password=crypto.randomBytes(16).toString('hex');
 a=await launch(18102,'a');b=await launch(18103,'b');
 await check('成品EXE使用实际main/preload与新版服务器登录',async()=>{
  for(const [c,username]of [[a,alice],[b,bob]]){await c.until('!!window.chat&&typeof render==="function"','preload ready');await c.command('login',{server,username,password,register:true});await c.until('state.online','online');assert.equal(await c.evaluate('state.messages===undefined'),true,'login reply uses delta');}
 });
 await check('真实界面增量收发、未读与接受聊天审批',async()=>{
  await a.command('addContact',{peer:bob});await a.evaluate(`openPeer(${JSON.stringify(bob)})`);await sendUI(a,'成品首条加密消息');
  await b.until(`state.unread[${JSON.stringify(alice)}]===1`,'unread');assert.match(await b.evaluate('document.title'),/\(1\)/);
  await b.evaluate(`openPeer(${JSON.stringify(alice)})`);await b.until("document.getElementById('messages').textContent.includes('成品首条加密消息')",'rendered first message');assert.equal(await b.evaluate(`state.unread[${JSON.stringify(alice)}]`),undefined);
  await b.command('acceptContact',{peer:alice});await a.until(`state.contactStates.some(c=>c.username===${JSON.stringify(bob)}&&c.status==='accepted')`,'accepted');
  await sendUI(b,'成品真实回复');await a.until("document.getElementById('messages').textContent.includes('成品真实回复')",'bidirectional UI');
 });
 await check('超过10秒TTL后真实界面继续增量显示消息',async()=>{
  await delay(12500);assert(await a.evaluate('state.online'));assert(await b.evaluate('state.online'));
  await sendUI(a,'成品自动续期后的消息');await b.until("document.getElementById('messages').textContent.includes('成品自动续期后的消息')",'renewed UI delivery');
 });
 await check('成品退出重登保留本机历史并加载一次基线',async()=>{
  await b.command('logout');await b.command('login',{server,username:bob,password,register:false});await b.until('state.online','relogin');await b.evaluate(`openPeer(${JSON.stringify(alice)})`);
  await b.until("document.getElementById('messages').textContent.includes('成品首条加密消息')&&document.getElementById('messages').textContent.includes('成品自动续期后的消息')",'retained history');await b.screenshot('packaged-retained-history');
 });
 fs.writeFileSync(path.join(out,'packaged-ui-result.json'),JSON.stringify({pass:true,exe,server,checks},null,2));
})().catch(error=>{console.error(error.stack);fs.writeFileSync(path.join(out,'packaged-ui-result.json'),JSON.stringify({pass:false,exe,server,checks,error:error.message},null,2));process.exitCode=1;}).finally(()=>{for(const client of [a,b]){client?.ws.close();client?.child.kill();}});
