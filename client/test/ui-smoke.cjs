// Run: node client/test/ui-smoke.cjs. Uses the installed Electron, no server or account.
'use strict';
const fs=require('node:fs');
const path=require('node:path'),os=require('node:os');
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const out=process.env.CHAT_UI_QA_DIR||path.join(os.tmpdir(),'chat-ui-qa');
const resultFile=path.join(out,'ui-smoke-result.json');

if(!process.versions.electron){
  fs.mkdirSync(out,{recursive:true});
  const env={...process.env,CHAT_UI_QA_DIR:out};
  delete env.ELECTRON_RUN_AS_NODE;
  const executable=path.resolve(__dirname,'../node_modules/electron/dist/electron.exe');
  const child=spawn(executable,[__filename],{env,windowsHide:true,stdio:['ignore','pipe','pipe']});
  child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);
  child.on('exit',code=>process.exitCode=code||0);
  child.on('error',error=>{console.error(error);process.exitCode=1;});
  return;
}

const {app,BrowserWindow,ipcMain}=require('electron');
app.disableHardwareAcceleration();
const server='https://qa.example.invalid';
const owner='测试用户';
const longName='这是一个非常非常长的中文联系人名称用于检查列表和标题';
const peers=['小红','小蓝',longName];
const now=Date.now();
const fixture={
  username:'',server:'',selectedServer:server,servers:[server],accounts:[],
  status:'未登录',online:false,contacts:[],sessions:[],contactStates:[],messages:[],
  verifiedPeers:[],deletedPeers:{},identityChanges:{},messageRevision:0,snapshotRevision:0
};
let window,sendCount=0,sequence=0;
const records=[];
let phase='before app setup';
const clone=()=>{fixture.snapshotRevision++;return structuredClone(fixture);};
const publish=()=>window.webContents.send('qa:snapshot',clone());
const delta=message=>{const next=clone();delete next.messages;next.messageChanges={[message.sender+':'+message.clientId]:structuredClone(message)};return next;};
const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(predicate,label,timeout=7000){
  const start=Date.now();
  while(Date.now()-start<timeout){if(await predicate())return;await wait(40);}
  throw new Error('Timeout: '+label);
}
async function js(source){try{return await window.webContents.executeJavaScript(source,true);}catch(error){throw new Error(`renderer eval ${source}: ${error?.message||error}; URL ${window.webContents.getURL()}`);}}
async function click(selector){await js(`document.querySelector(${JSON.stringify(selector)}).click()`);}
async function fill(selector,value){await js(`{const n=document.querySelector(${JSON.stringify(selector)});n.value=${JSON.stringify(value)};n.dispatchEvent(new Event('input',{bubbles:true}));}`);}
async function visible(selector){return js(`!!document.querySelector(${JSON.stringify(selector)})?.getClientRects().length`);}
async function value(selector){return js(`document.querySelector(${JSON.stringify(selector)})?.value`);}
async function text(selector){return js(`document.querySelector(${JSON.stringify(selector)})?.textContent`);}
async function key(keyCode,modifiers=[]){
  window.webContents.sendInputEvent({type:'keyDown',keyCode,modifiers});
  if(keyCode==='Enter'&&modifiers.includes('shift'))window.webContents.sendInputEvent({type:'char',keyCode:'\r',modifiers});
  window.webContents.sendInputEvent({type:'keyUp',keyCode,modifiers});
}
async function shot(name){
  await wait(120);
  let png,lastError;
  for(let attempt=0;attempt<6;attempt++){
    try{png=await window.webContents.capturePage();break;}
    catch(error){lastError=error;await wait(200);}
  }
  if(!png)throw lastError;
  const file=path.join(out,name+'.png');fs.writeFileSync(file,png.toPNG());records.push({screenshot:file});
}
async function check(name,task){const started=Date.now();await task();records.push({check:name,pass:true,ms:Date.now()-started});console.log('PASS '+name);}
function signedIn(){
  fixture.username=owner;fixture.server=server;fixture.status='在线';fixture.online=true;
  fixture.accounts=[{server,user:owner}];fixture.contacts=[...peers];fixture.sessions=['小红','小蓝',longName];
  fixture.contactStates=peers.map(username=>({username,status:'accepted',online:username==='小红'}));
  fixture.messages=Array.from({length:24},(_,i)=>({sender:i%2?owner:'小红',recipient:i%2?'小红':owner,clientId:'seed-'+i,body:'第 '+(i+1)+' 条测试消息',createdAt:new Date(now-2400000+i*60000).toISOString(),status:'对方客户端已接收'}));
  fixture.messageRevision++;
}
ipcMain.handle('qa:command',async(_event,{action,payload})=>{
  if(action==='appInfo')return {ok:true,value:{version:'0.6.1'}};
  if(action==='snapshot')return {ok:true,value:clone()};
  if(action==='retryMessage'){
    const message=fixture.messages.find(item=>item.clientId===payload.clientId);
    if(!message)return {ok:false,error:'消息不存在'};
    message.status='发送中 · 等待服务器确认';fixture.messageRevision++;return {ok:true,value:clone()};
  }
  if(action==='login'){
    if(payload.register&&fixture.accounts.some(a=>a.user===payload.username))return {ok:false,error:'用户名已存在'};
    signedIn();return {ok:true,value:clone()};
  }
  if(action==='logout'){
    fixture.username='';fixture.server='';fixture.status='未登录';fixture.online=false;
    fixture.accountStatus=null;
    fixture.contacts=[];fixture.sessions=[];fixture.contactStates=[];fixture.messages=[];
    fixture.messageRevision++;return {ok:true,value:clone()};
  }
  if(action==='send'){
    sendCount++;await wait(120);
    fixture.messages.push({sender:owner,recipient:payload.peer,clientId:'sent-'+(++sequence),body:payload.body,createdAt:new Date().toISOString(),status:'已加入待发队列'});
    fixture.messageRevision++;const next=delta(fixture.messages.at(-1));window.webContents.send('qa:snapshot',next);return {ok:true,value:next};
  }
  if(action==='openConversation'){
    if(!fixture.sessions.includes(payload.peer))fixture.sessions.push(payload.peer);
    return {ok:true,value:clone()};
  }
  if(action==='history'||action==='refreshContacts')return {ok:true,value:clone()};
  if(action==='revokeLocationPermission'||action==='stopLocations')return {ok:true,value:true};
  return {ok:true,value:clone()};
});
ipcMain.handle('qa:check-update',async()=>({server,platform:'windows-x64',version:'0.6.2',fileName:'Chat-0.6.2.zip',size:100,available:true,notes:'测试更新说明'}));
ipcMain.handle('qa:download-update',async()=>{window.webContents.send('qa:update-progress',{server,received:100,total:100});await wait(30);return {server,fileName:'Chat-0.6.2.zip'};});
ipcMain.handle('qa:cancel-update',async()=>true);
ipcMain.handle('qa:open-download',async()=>true);

async function run(){
  fs.mkdirSync(out,{recursive:true});
  phase='app.setPath';
  app.setPath('userData',path.join(out,'electron-profile'));
  phase='app.whenReady';
  await app.whenReady();
  phase='BrowserWindow';
  window=new BrowserWindow({width:1040,height:900,show:true,title:'Chat UI QA',webPreferences:{
    preload:path.join(__dirname,'ui-smoke-preload.cjs'),contextIsolation:true,
    nodeIntegration:false,sandbox:true,webSecurity:true,devTools:false
  }});
  window.removeMenu();
  const errors=[];
  window.webContents.on('console-message',(_event,details)=>{if(details.level==='error')errors.push(details.message);});
  window.webContents.on('render-process-gone',(_event,details)=>errors.push('renderer gone: '+details.reason));
  phase='loadFile';
  await window.loadFile(path.resolve(__dirname,'../ui/index.html'));
  phase='auth visible';
  await until(()=>visible('#auth'),'auth visible');
  phase='login screenshot';
  await shot('01-login');
  await check('登录及注册表单规则',async()=>{
    assert.equal(await js("document.querySelector('#username').minLength"),2);
    assert.equal(await js("document.querySelector('#username').maxLength"),32);
    assert.equal(await js("document.querySelector('#password').minLength"),8);
    await fill('#username','');await fill('#password','');await click('#register');
    assert.equal(fixture.username,'','invalid registration must not submit');
    await fill('#username',owner);await fill('#password','secure-pass-123');
    await click('#register');await until(()=>visible('#chat'),'chat visible');
  });
  await shot('02-chat');
  await check('会话与联系人切换、中文长名字',async()=>{
    assert.equal(await js("document.querySelectorAll('#peerList .peerPick').length"),3);
    await click('#contactsTab');
    assert.equal(await js("document.querySelectorAll('#peerList .peerPick').length"),3);
    await click('#sessionsTab');
    await click(`#peerList .peerPick[data-peer=${JSON.stringify(longName)}]`);
    assert.equal(await text('#conversationName'),longName);
    await click('#peerList .peerPick[data-peer="小红"]');
    assert.match(await text('#messages'),/第 1 条测试消息/);
  });
  await shot('03-conversation');
  await check('聊天菜单及按钮位置',async()=>{
    assert.equal(await js("document.querySelector('#more').closest('.conversationHeader')!==null"),true);
    await click('#more');assert.equal(await visible('#moreMenu'),true);
    assert.equal(await js("document.querySelector('#more').getAttribute('aria-expanded')"),'true');
    await shot('04-more-menu');
    await click('#findHistory');assert.equal(await visible('#searchPanel'),true);
    await click('#closeSearch');
  });
  await check('800×600窄窗口布局与长名字省略',async()=>{
    window.setContentSize(800,600);await wait(100);
    await click(`#peerList .peerPick[data-peer=${JSON.stringify(longName)}]`);
    const layout=await js(`(()=>{
      const box=s=>document.querySelector(s).getBoundingClientRect();
      const title=document.querySelector('#conversationName');
      const brand=box('.sidebarTop .brand'),word=box('.sidebarTop .brand span');
      const header=box('.conversationHeader'),name=box('#conversationName'),more=box('#more');
      const composer=box('#sendForm'),send=box('#send'),plus=box('#toggleCallActions');
      return {width:innerWidth,height:innerHeight,brand:brand.toJSON(),word:word.toJSON(),header:header.toJSON(),name:name.toJSON(),more:more.toJSON(),composer:composer.toJSON(),send:send.toJSON(),plus:plus.toJSON(),titleOverflow:title.scrollWidth>title.clientWidth,titleEllipsis:getComputedStyle(title).textOverflow};
    })()`);
    assert.equal(layout.width,800);assert(layout.height<=600);
    assert(layout.word.left>=layout.brand.right,'brand text must not overlap logo');
    assert(layout.titleOverflow&&layout.titleEllipsis==='ellipsis','long Chinese name must truncate: '+JSON.stringify(layout));
    assert(layout.name.right<=layout.more.left,'title must not cover menu button');
    assert(layout.header.bottom<layout.composer.top,'header and composer must not overlap');
    assert(layout.send.right<=layout.plus.left,'send and plus must not overlap');
    for(const name of ['more','send','plus']){
      const rect=layout[name];assert(rect.width>0&&rect.height>0,`${name} visible`);
      assert(rect.left>=0&&rect.right<=layout.width&&rect.top>=0&&rect.bottom<=layout.height,`${name} inside viewport`);
    }
    await click('#more');
    const menu=await js("document.querySelector('#moreMenu').getBoundingClientRect().toJSON()");
    assert(menu.left>=0&&menu.right<=layout.width&&menu.bottom<=layout.height,'menu inside viewport');
    await shot('08-narrow-800x600');
    await click('#more');
    window.setContentSize(1040,900);
    await click('#peerList .peerPick[data-peer="小红"]');
  });
  await check('空列表搜索清空与会话联系人提示',async()=>{
    fixture.sessions=[];fixture.contacts=[];publish();
    await until(async()=>await text('#peerList .listEmpty')==='暂无会话','empty sessions');
    await fill('#peerSearch','不存在');
    assert.equal(await text('#peerList .listEmpty'),'没有匹配结果');
    await fill('#peerSearch','');
    assert.equal(await text('#peerList .listEmpty'),'暂无会话');
    await click('#contactsTab');
    assert.equal(await text('#peerList .listEmpty'),'暂无联系人');
    await fill('#peerSearch','不存在');
    assert.equal(await text('#peerList .listEmpty'),'没有匹配结果');
    await fill('#peerSearch','');
    assert.equal(await text('#peerList .listEmpty'),'暂无联系人');
    await shot('09-empty-list');
    fixture.sessions=[...peers];fixture.contacts=[...peers];publish();
    await until(async()=>await js("document.querySelectorAll('#peerList .peerPick').length===3"),'restored peers');
    await click('#sessionsTab');
  });
  await check('不同会话草稿隔离',async()=>{
    await fill('#body','小红草稿');await click('#peerList .peerPick[data-peer="小蓝"]');
    assert.notEqual(await value('#body'),'小红草稿');
    await fill('#body','小蓝草稿');await click('#peerList .peerPick[data-peer="小红"]');
    assert.notEqual(await value('#body'),'小蓝草稿');
  });
  await check('重复点击发送只提交一次',async()=>{
    await fill('#body','去重测试');const before=sendCount;
    await js('window.__qaIndexBeforeSend=messageIndex;true');
    await js("document.querySelector('#send').click();document.querySelector('#send').click()");
    await until(()=>sendCount>before,'send command');await wait(250);
    assert.equal(sendCount-before,1);
    assert.equal((await text('#messages')).split('去重测试').length-1,1);
    assert.equal(await js('messageIndex===window.__qaIndexBeforeSend'),true,'incremental send retains the index');
  });
  await check('真实键盘 Shift+Enter 换行和 Enter 发送',async()=>{
    window.focus();await js("document.querySelector('#body').focus();true");
    await fill('#body','第一行');const before=sendCount;
    await key('Enter',['shift']);
    await until(async()=>await value('#body')==='第一行\n','shift enter newline');
    assert.equal(sendCount,before,'Shift+Enter must not send');
    window.webContents.insertText('第二行');
    await until(async()=>await value('#body')==='第一行\n第二行','second line inserted');
    await key('Enter');
    await until(()=>sendCount===before+1,'enter send');await wait(160);
    assert.equal(sendCount,before+1);
    assert.match(await text('#messages'),/第一行\n第二行/);
  });
  await check('状态刷新保留列表行与焦点',async()=>{
    await js("document.querySelector('#peerSearch').focus()");
    const before=await js("window.__qaRow=document.querySelector('#peerList .peerPick[data-peer=\"小红\"]');window.__qaIndex=messageIndex;window.__qaOldSnapshot=structuredClone(state);document.activeElement.id");
    assert.equal(before,'peerSearch');
    fixture.contactStates[0].online=false;fixture.status='连接中';publish();
    await until(async()=>await text('#connection')==='连接中','connection refresh');
    assert.equal(await js("document.querySelector('#peerList .peerPick[data-peer=\"小红\"]')===window.__qaRow"),true);
    assert.equal(await js('messageIndex===window.__qaIndex'),true,'status-only refresh reuses message index');
    assert.equal(await js('document.activeElement.id'),'peerSearch');
    assert.match(await text('#peerList .peerPick[data-peer="小红"] .peerStatus'),/离线/);
    await js('render(window.__qaOldSnapshot)');
    assert.equal(await text('#connection'),'连接中','stale snapshot cannot overwrite connection');
    assert.equal(await js('messageIndex===window.__qaIndex'),true);
  });
  await check('发送失败状态与原消息重试按钮',async()=>{
    fixture.online=true;fixture.status='在线';
    fixture.accountStatus={serverTime:new Date().toISOString(),lastConnectedAt:new Date().toISOString(),accountExpiresAt:new Date(Date.now()+7*86400000).toISOString(),receivedAt:new Date().toISOString(),retentionDays:7,accountId:'qa-account'};
    const id='qa-failed-message';fixture.outboxIds=[id];
    fixture.messages.push({sender:owner,recipient:'小红',clientId:id,body:'重试前的原消息',createdAt:new Date().toISOString(),status:'发送失败 · 服务暂时不可用'});
    fixture.messageRevision++;publish();
    await until(async()=>await text('#messages')?.then(value=>value.includes('重试前的原消息')),'failed message');
    assert.match(await text('#accountDeadline'),/账号期限/);
    const skewBase=Date.now();fixture.accountStatus.serverTime=new Date(skewBase-3*86400000).toISOString();
    fixture.accountStatus.accountExpiresAt=new Date(skewBase+86400000).toISOString();publish();
    const expectedRegular=new Date(Date.parse(fixture.accountStatus.receivedAt)+Date.parse(fixture.accountStatus.accountExpiresAt)-Date.parse(fixture.accountStatus.serverTime)).toLocaleString();
    await until(async()=>await text('#accountDeadline')?.then(value=>value.includes(expectedRegular)),'clock-corrected calendar date');
    assert(!(await text('#accountDeadline')).includes(new Date(fixture.accountStatus.accountExpiresAt).toLocaleString()),'raw server calendar date must not appear');
    fixture.accountStatus.accountExpiresAt=new Date(skewBase-2*86400000).toISOString();publish();
    await until(async()=>await text('#accountDeadline')?.then(value=>value.includes('约 1 天内到期')),'server-relative deadline');
    const expectedUrgent=new Date(Date.parse(fixture.accountStatus.receivedAt)+Date.parse(fixture.accountStatus.accountExpiresAt)-Date.parse(fixture.accountStatus.serverTime)).toLocaleString();
    assert((await text('#accountDeadline')).includes(expectedUrgent),'urgent calendar date uses server clock offset');
    assert.equal(await js("document.querySelector('#accountDeadline').classList.contains('urgent')"),true);
    fixture.accounts[0].accountId='qa-account';fixture.accounts[0].accountStatus=structuredClone(fixture.accountStatus);
    assert.equal(await visible('.retryMessage'),true);
    assert.match(await text('.meta.failed'),/发送失败/);
    await click('.retryMessage');
    await until(async()=>await text('#messages')?.then(value=>value.includes('发送中 · 等待服务器确认')),'retry status');
    assert.equal(await visible('.retryMessage'),false);
  });
  await shot('05-disconnected');
  await check('两万条单会话历史、增量索引和状态刷新显示缓存',async()=>{
    fixture.messages=Array.from({length:20000},(_,i)=>({sender:owner,recipient:'小红',clientId:'perf-'+i,body:'性能消息 '+i,createdAt:new Date(now-20000+i).toISOString(),status:'已保存'}));fixture.messageRevision++;publish();
    await until(async()=>await text('#messages')?.then(value=>value.includes('性能消息 19999')),'large history');
    await js(`window.__qaLargeIndex=messageIndex;window.__qaDisplay=messageIndex.display('小红');window.__qaBodyReads=0;for(const m of messageIndex.messages('小红')){const body=m.body;Object.defineProperty(m,'body',{configurable:true,get(){window.__qaBodyReads++;return body;},set(value){Object.defineProperty(this,'body',{value,writable:true,configurable:true,enumerable:true});}});}true`);
    fixture.status='性能状态更新';publish();await until(async()=>await text('#connection')==='性能状态更新','presence cache');
    assert.equal(await js('messageIndex===window.__qaLargeIndex'),true);assert.equal(await js("messageIndex.display('小红')===window.__qaDisplay"),true);
    assert((await js('window.__qaBodyReads'))<500,'presence must only touch visible messages, not 20,000 history bodies');
    await fill('#body','大历史后的增量消息');await click('#send');await until(async()=>await text('#messages')?.then(value=>value.includes('大历史后的增量消息')),'delta after large history');
    assert.equal(await js('messageIndex===window.__qaLargeIndex'),true);assert.equal(await js("messageIndex.messages('小红').length"),20001);
    fixture.unread={'小蓝':3};publish();await until(async()=>await text('#peerList .peerPick[data-peer="小蓝"] .peerStatus')?.then(value=>value.includes('3 条未读')),'unread badge');
    assert.equal(await js('document.title'),'(3) Chat');
    await shot('10-large-history');
  });
  await check('设置页面与返回',async()=>{
    await click('#chatSettings');assert.equal(await visible('#settings'),true);
    assert.match(await text('#serverList'),/qa\.example\.invalid/);
    await click('#checkUpdate');await until(async()=>/服务器提供版本 0\.6\.2/.test(await text('#serverVersion')),'update check');
    assert.equal(await visible('#downloadUpdate'),true);assert.match(await text('#updateNotes'),/测试更新说明/);
    await click('#downloadUpdate');await until(async()=>/下载并校验完成/.test(await text('#updateProgressText')),'update download');
    assert.equal(await visible('#openDownload'),true);await click('#openDownload');
    await shot('06-settings');
    await click('#closeSettings');assert.equal(await visible('#chat'),true);
  });
  await check('已保存账号的登录入口',async()=>{
    await click('#chatSettings');await click('#logout');
    await until(()=>visible('#auth'),'auth after logout');
    assert.equal(await js("document.querySelector('#username').readOnly"),true);
    assert.equal(await js("document.querySelector('#register').hidden"),true);
    assert.equal(await value('#username'),owner);
    assert.equal(await visible('#savedAccountDeadline'),true);
    assert.match(await text('#savedAccountDeadline'),/按上次服务器记录估计/);
    assert.match(await text('#savedAccountDeadline'),/请成功连接后确认/);
    await shot('07-saved-login');
    await fill('#password','secure-pass-123');await click('#login');
    await until(()=>visible('#chat'),'chat after login');
    assert.equal(await text('#identity'),owner);
  });
  assert.deepEqual(errors,[],'renderer console errors');
  fs.writeFileSync(resultFile,JSON.stringify({pass:true,records,security:{contextIsolation:true,nodeIntegration:false,sandbox:true,webSecurity:true}},null,2));
  console.log('RESULT '+resultFile);
}
let failed=false;
run().catch(error=>{failed=true;records.push({check:'failure',pass:false,phase,error:error.stack||String(error)});fs.writeFileSync(resultFile,JSON.stringify({pass:false,records},null,2));console.error(phase,error);}).finally(()=>{if(window&&!window.isDestroyed())window.destroy();app.exit(failed?1:0);});
