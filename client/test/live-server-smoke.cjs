// Optional integration smoke test. Requires an isolated, empty loopback QA server.
// CHAT_QA_SERVER=http://127.0.0.1:18086 node client/test/live-server-smoke.cjs
'use strict';
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const server=process.env.CHAT_QA_SERVER;
if(!server||!/^http:\/\/(127\.0\.0\.1|localhost):\d+$/.test(server))throw new Error('Set CHAT_QA_SERVER to an isolated loopback QA server; production servers are forbidden.');
const out=path.resolve(process.env.CHAT_QA_DIR||path.join(require('node:os').tmpdir(),'chat-live-qa-'+Date.now()));
fs.mkdirSync(out,{recursive:true});
if(!process.versions.electron){
  const env={...process.env,CHAT_QA_DIR:out};delete env.ELECTRON_RUN_AS_NODE;
  const child=spawn(path.resolve(__dirname,'../node_modules/electron/dist/electron.exe'),[__filename],{env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);
  child.on('exit',code=>process.exitCode=code??1);child.on('error',error=>{console.error(error);process.exitCode=1;});return;
}
const {app,safeStorage}=require('electron');
const {Controller}=require('../controller.cjs');
app.setPath('userData',path.join(out,'electron-profile'));
const results=[];
let a,b;
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate,label){const start=Date.now();while(Date.now()-start<20000){if(predicate())return;await delay(50);}throw new Error('Timeout: '+label);}
async function check(name,task){await task();results.push({name,pass:true});console.log('PASS '+name);}
const messages=c=>Object.values(c.engine?.state.messages||{});
const contains=(c,body)=>messages(c).some(m=>m.body===body);
async function run(){
  await app.whenReady();assert.equal(safeStorage.isEncryptionAvailable(),true,'Windows secure storage required');
  const suffix=Date.now().toString(36),alice='回归甲_'+suffix,bob='回归乙_'+suffix,password=crypto.randomBytes(18).toString('hex');
  const options={retryBaseMs:150,retryMaxMs:1000};
  a=new Controller(path.join(out,'alice'),safeStorage,()=>{},options);
  b=new Controller(path.join(out,'bob'),safeStorage,()=>{},options);
  await check('新版服务器响应与两个真实客户端登录',async()=>{
    assert.equal((await fetch(server+'/api/contacts')).status,401);
    await a.serial(()=>a.login({server,username:alice,password,register:true}));
    await b.serial(()=>b.login({server,username:bob,password,register:true}));
    await until(()=>a.online&&b.online,'both online');
  });
  await check('Signal 首条请求与接受前只能发一条',async()=>{
    await a.serial(()=>a.addContact({peer:bob}));
    await a.serial(()=>a.send({peer:bob,body:'首条加密请求'}));
    await until(()=>contains(b,'首条加密请求')&&b.contactState[alice]?.status==='pending_incoming','first message');
    await assert.rejects(a.serial(()=>a.send({peer:bob,body:'禁止的第二条'})),/等待对方接受/);
    await b.serial(()=>b.acceptContact({peer:alice}));
    await until(()=>a.contactState[bob]?.status==='accepted','accepted');
  });
  await check('安全码一致、双向消息与送达 ACK',async()=>{
    const sa=await a.serial(()=>a.safety({peer:bob})),sb=await b.serial(()=>b.safety({peer:alice}));
    assert.equal(sa.code,sb.code);
    await a.serial(()=>a.safety({peer:bob,confirm:true,expectedCode:sa.code}));
    await b.serial(()=>b.safety({peer:alice,confirm:true,expectedCode:sb.code}));
    await b.serial(()=>b.send({peer:alice,body:'已接受后的真实回复'}));
    await a.serial(()=>a.send({peer:bob,body:'双向消息已接通'}));
    await until(()=>contains(a,'已接受后的真实回复')&&contains(b,'双向消息已接通'),'bidirectional messages');
    await until(()=>messages(a).some(m=>m.body==='双向消息已接通'&&m.status==='对方客户端已接收'),'delivery ACK');
  });
  await check('超过短令牌有效期后原 WebSocket 自动续期且继续加密发送',async()=>{
    assert(a.refreshToken&&b.refreshToken,'new server must issue RAM refresh credentials');
    const socketA=a.socket,socketB=b.socket,tokenA=a.token,tokenB=b.token;
    await delay(12500);
    assert.equal(a.socket,socketA);assert.equal(b.socket,socketB);assert(a.online&&b.online);
    assert.notEqual(a.token,tokenA);assert.notEqual(b.token,tokenB);
    await a.serial(()=>a.send({peer:bob,body:'令牌到期自动续期后的加密消息'}));
    await until(()=>contains(b,'令牌到期自动续期后的加密消息'),'post-expiration delivery');
  });
  await check('离线密文重连领取、本机历史加密保存',async()=>{
    b.logout();await until(()=>a.contactState[bob]?.online===false,'offline presence');
    await a.serial(()=>a.send({peer:bob,body:'接收方离线时的密文'}));
    await until(()=>messages(a).some(m=>m.body==='接收方离线时的密文'&&m.id),'server persisted');
    await b.serial(()=>b.login({server,username:bob,password,register:false}));
    await until(()=>b.online&&contains(b,'接收方离线时的密文'),'offline delivery');
    await b.queue;
    assert.equal(Object.values(b.vault.read().messages).some(m=>m.body==='首条加密请求'),true);
    const journal=fs.readFileSync(b.vault.file);
    assert.equal(journal.includes(Buffer.from('首条加密请求','utf8')),false,'history must not be plaintext on disk');
    assert.equal(fs.readFileSync(b.vault.historyFile).includes(Buffer.from('首条加密请求','utf8')),false);
  });
  await check('异常 WebSocket 断开后自动重连',async()=>{
    a.socket.terminate();await until(()=>!a.online,'disconnected');await until(()=>a.online,'reconnected');
    await a.serial(()=>a.send({peer:bob,body:'异常断线恢复后的消息'}));
    await until(()=>contains(b,'异常断线恢复后的消息'),'reconnected delivery');
  });
  await check('删除联系人保留历史并恢复首次联系审批',async()=>{
    await a.serial(()=>a.removeContact({peer:bob}));
    await until(()=>!b.contactState[alice],'revocation reached peer');
    assert.equal(contains(a,'已接受后的真实回复'),true);
    await b.serial(()=>b.send({peer:alice,body:'删除后的重新申请'}));
    await until(()=>contains(a,'删除后的重新申请')&&a.contactState[bob]?.status==='pending_incoming','fresh consent');
    await assert.rejects(b.serial(()=>b.send({peer:alice,body:'接受之前的第二条'})),/等待对方接受/);
  });
}
run().then(()=>{fs.writeFileSync(path.join(out,'live-smoke-result.json'),JSON.stringify({server,pass:true,checks:results},null,2));console.log('ALL '+results.length+' LIVE CHECKS PASSED');}).catch(error=>{console.error(error.stack);fs.writeFileSync(path.join(out,'live-smoke-result.json'),JSON.stringify({server,pass:false,checks:results,error:error.message},null,2));process.exitCode=1;}).finally(async()=>{a?.logout();b?.logout();await Promise.allSettled([a?.queue,b?.queue]);app.exit(process.exitCode||0);});
