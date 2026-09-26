// Optional QA dependency: Playwright. Run accounts, features, migration, then before-restart, restart the server, then after-restart.
const {_electron:electron}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const out=path.resolve(process.env.QA_DIR||'qa');fs.mkdirSync(out,{recursive:true});
const mode=process.argv[2]||'before-restart';
const executablePath=process.env.CLIENT_EXE||require('electron');
const packaged=!!process.env.CLIENT_EXE;
const root=path.resolve(__dirname,'..');
const base=process.env.CHAT_URL||'http://localhost:8082';
const secondBase=process.env.CHAT_URL_2||'https://other.example.com';
const waitText=(p,selector,text)=>p.waitForFunction(({selector,text})=>document.querySelector(selector)?.textContent.includes(text),{selector,text},{timeout:25000});
const waitRows=(p,count)=>p.waitForFunction(count=>document.querySelectorAll('#accountList .account').length===count,count,{timeout:25000});
async function launch(profile,appDataRoot){
  const app=await electron.launch({executablePath,args:[...(packaged?[]:[root]),'--profile='+profile,'--test-hidden'],
    env:appDataRoot?{...process.env,CHAT_TEST_APPDATA:appDataRoot}:process.env,timeout:30000});
  const page=await app.firstWindow();await page.waitForFunction(()=>['serverSetup','auth','chat'].some(id=>!document.getElementById(id)?.hidden));return {app,page};
}
async function auth(p,user,register,expectedServer=base){
  if(await p.locator('#serverSetup').isVisible()){
    await p.fill('#server',base);await p.click('#setupForm button[type=submit]');await p.waitForSelector('#auth:not([hidden])');
  }
  if(register){
    if(await p.locator('#otherAccount').isVisible())await p.click('#otherAccount');
    await p.fill('#username',user);
  }
  else{
    // The selected server filters the saved accounts; a known account only needs its password.
    await p.click(`#accountList .account[data-user="${user}"] .accountPick`);
    assert.equal(await p.locator('#authServer').innerText(),expectedServer);
    assert.equal(await p.locator('#username').inputValue(),user);
    assert.equal(await p.locator('#usernameLabel').isVisible(),false);
  }
  await p.fill('#password','test-secure-123');
  await p.click(register?'#register':'#authForm button[type=submit]');
  try{await waitText(p,'#connection','在线');}catch(e){throw new Error('Login failed: '+await p.locator('#notice').innerText());}
}
async function openContact(p,peer){
  await p.click('#contactsTab');
  const existing=p.locator(`#peerList .peerPick[data-peer="${peer}"]`);
  if(await existing.count())await existing.click();
  else{await p.fill('#peer',peer);await p.click('#contactForm button');await waitText(p,'#conversationName',peer);}
}
async function send(p,peer,text){await openContact(p,peer);await p.fill('#body',text);await p.click('#send');await waitText(p,'#messages',text);}
async function logout(p){await p.click('#chatSettings');await p.click('#logout');await p.waitForSelector('#auth:not([hidden])');}
async function capture(app,file){
  const data=await app.evaluate(async({BrowserWindow})=>(await BrowserWindow.getAllWindows()[0].webContents.capturePage(undefined,{stayHidden:true,stayAwake:true})).toPNG().toString('base64'));
  fs.writeFileSync(file,Buffer.from(data,'base64'));
}
// One profile holds several accounts: the server address is remembered, so switching accounts
// only ever needs the password, and removing an entry keeps the private keys of that account.
async function accountsCheck(){
  const suffix=Date.now().toString(36),carol='carol_'+suffix,dave='dave_'+suffix,eve='eve_'+suffix,frank='frank_'+suffix;
  const {app,page}=await launch('qa-accounts-'+suffix);const errors=[];
  page.on('pageerror',e=>errors.push(e.message));
  try{
    await auth(page,carol,true);
    await logout(page);await waitRows(page,1);
    assert.equal(await page.locator('#authServer').innerText(),base);
    assert.equal(await page.locator('#usernameLabel').isVisible(),false);
    await page.click('#otherAccount');
    assert.equal(await page.locator('#usernameLabel').isVisible(),true);
    await auth(page,dave,true);
    await logout(page);await waitRows(page,2);
    assert.deepEqual(await page.locator('#accountList .accountPick').allInnerTexts(),[dave,carol]);
    await auth(page,carol,false);
    assert.equal(await page.locator('#identity').innerText(),carol);
    await logout(page);await waitRows(page,2);
    await page.click('#authSettings');await page.fill('#newServer',secondBase);await page.click('#addServerForm button');
    assert.equal(await page.locator('#authServer').innerText(),secondBase);
    assert.equal(await page.locator('#accountList .account').count(),0,'other server must not show prior accounts');
    if(process.env.CHAT_URL_2){
      await auth(page,eve,true,secondBase);await logout(page);
      await auth(page,frank,true,secondBase);await logout(page);
      await auth(page,eve,false,secondBase);await openContact(page,frank);
      assert.equal(await page.locator('#conversationName').innerText(),frank);
      await page.click('#chatSettings');await page.locator('#serverList .serverPick').filter({hasText:base}).click();
      await waitRows(page,2);
      assert.equal(await page.locator('#conversationContent').isVisible(),false,'old server conversation must clear');
      await auth(page,carol,false);await page.click('#contactsTab');
      assert.equal(await page.locator(`#peerList .peerPick[data-peer="${frank}"]`).count(),0,'other server contacts must not leak');
      await page.click('#chatSettings');await page.locator('#serverList .serverPick').filter({hasText:secondBase}).click();
      await waitRows(page,2);await auth(page,eve,false,secondBase);
      assert.equal(await page.locator(`#peerList .peerPick[data-peer="${frank}"]`).count(),0,'an unaccepted chat must not appear as a contact');
      await logout(page);
    }
    await page.click('#authSettings');await page.locator('#serverList .serverPick').filter({hasText:base}).click();
    await waitRows(page,2);
    await page.click(`#accountList .account[data-user="${dave}"] .accountForget`);
    await waitRows(page,1);await waitText(page,'#notice','已从列表移除');
    assert.deepEqual(await page.locator('#accountList .accountPick').allInnerTexts(),[carol]);
    const userData=await app.evaluate(({app})=>app.getPath('userData'));
    assert.equal(fs.readdirSync(path.join(userData,'vaults')).filter(f=>f.endsWith('.vault')).length,process.env.CHAT_URL_2?4:2);
    assert.deepEqual(errors,[]);
    console.log('PASS: several accounts per server, server isolation, password-only switching, removal keeps local keys');
  }finally{await app.close();}
}
async function featuresCheck(){
  const suffix=Date.now().toString(36),sender='小明_'+suffix,receiver='小红_'+suffix;
  const a=await launch('qa-features-a-'+suffix),b=await launch('qa-features-b-'+suffix),errors=[];
  for(const page of [a.page,b.page])page.on('pageerror',error=>errors.push(error.message));
  try{
    await auth(a.page,sender,true);await auth(b.page,receiver,true);
    assert.equal(await a.page.locator('#identity').innerText(),sender);
    await openContact(a.page,receiver);
    await a.page.fill('#body','第一条中文请求');await a.page.click('#send');
    await waitText(a.page,'#messages','第一条中文请求');
    await waitText(a.page,'#conversationStatus','等待对方接受');
    assert.equal(await a.page.locator('#send').isDisabled(),true);
    await a.page.click('#contactsTab');
    assert.equal(await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).count(),0,'sender is not a contact before consent');
    const second=await a.page.evaluate(peer=>window.chat.command('send',{peer,body:'第二条应被禁止'}),receiver);
    assert.equal(second.ok,false);assert.match(second.error,/等待对方接受/);
    await b.page.click('#sessionsTab');
    await b.page.locator(`#peerList .peerPick[data-peer="${sender}"]`).waitFor();
    await b.page.locator(`#peerList .peerPick[data-peer="${sender}"]`).click();
    await waitText(b.page,'#messages','第一条中文请求');
    await waitText(b.page,'#conversationStatus','等待你接受');
    assert.equal(await b.page.locator('#send').isDisabled(),true);
    await b.page.click('#contactsTab');
    assert.equal(await b.page.locator(`#peerList .peerPick[data-peer="${sender}"]`).count(),0);
    await b.page.click('#acceptContact');
    await b.page.locator(`#peerList .peerPick[data-peer="${sender}"]`).waitFor();
    await waitText(a.page,'#conversationStatus','在线');
    await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).waitFor();
    await waitText(a.page,`#peerList .peerPick[data-peer="${receiver}"] .peerStatus`,'在线');
    await waitText(b.page,`#peerList .peerPick[data-peer="${sender}"] .peerStatus`,'在线');
    await b.page.fill('#body','已接受，可以双向聊天');await b.page.click('#send');
    await waitText(a.page,'#messages','已接受，可以双向聊天');
    await a.page.fill('#body','收到');await a.page.click('#send');await waitText(b.page,'#messages','收到');
    await a.page.click('#sessionsTab');await a.page.fill('#peerSearch',receiver);
    assert.equal(await a.page.locator('#peerList .peerPick').count(),1);
    await a.page.fill('#peerSearch','没有这个会话');
    assert.equal(await a.page.locator('#peerList .peerPick').count(),0);
    await a.page.click('#contactsTab');await a.page.fill('#peerSearch',receiver);
    assert.equal(await a.page.locator('#peerList .peerPick').count(),1);
    await a.page.fill('#peerSearch','没有这个联系人');
    assert.equal(await a.page.locator('#peerList .peerPick').count(),0);
    await a.page.fill('#peerSearch','');
    await a.page.click('#removeContact');
    await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).waitFor({state:'detached'});
    await a.page.evaluate(()=>window.chat.command('refreshContacts'));
    assert.equal(await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).count(),0,'server refresh must preserve revoked relation');
    assert.match(await a.page.locator('#messages').innerText(),/第一条中文请求/);
    await waitText(b.page,'#conversationStatus','尚未建立联系');
    await b.page.fill('#body','重新申请联系');await b.page.click('#send');
    await waitText(b.page,'#conversationStatus','等待对方接受');
    await waitText(a.page,'#messages','重新申请联系');
    await waitText(a.page,'#conversationStatus','等待你接受');
    assert.equal(await a.page.locator('#send').isDisabled(),true);
    await a.page.click('#acceptContact');
    await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).waitFor();
    assert.match(await a.page.locator('#messages').innerText(),/已接受，可以双向聊天/);
    await a.page.click('#clearConversation');
    await logout(a.page);await auth(a.page,sender,false);
    await a.page.click('#sessionsTab');
    assert.equal(await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).count(),0,'session stays cleared after login');
    await a.page.click('#contactsTab');
    await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).click();
    assert.match(await a.page.locator('#messages').innerText(),/第一条中文请求/);
    await a.page.click('#sessionsTab');
    await a.page.locator(`#peerList .peerPick[data-peer="${receiver}"]`).waitFor();
    await logout(b.page);await waitText(a.page,'#conversationStatus','离线');
    assert.deepEqual(errors,[]);
    console.log('PASS: Chinese usernames, consent gate, online presence, search, relation revocation, renewed consent, session clearing, retained history');
  }finally{await a.app.close();await b.app.close();}
}
// A profile written by the older ChatApp build must survive the rename: keys, contacts and cached
// history move to the new location untouched, and an already existing new directory always wins.
async function migrationCheck(){
  const suffix=Date.now().toString(36),user='mig_'+suffix,profile='qa-migrate-'+suffix;
  const appDataRoot=path.join(out,'migration-appdata-'+suffix);
  fs.mkdirSync(appDataRoot,{recursive:true});
  let session=await launch(profile,appDataRoot);const errors=[];
  session.page.on('pageerror',e=>errors.push(e.message));
  const roots=async()=>({legacy:path.join(appDataRoot,'ChatAppSecure',profile),
    current:path.join(appDataRoot,'Chat',profile),
    actual:await session.app.evaluate(({app})=>app.getPath('userData'))});
  const vault=root=>{const dir=path.join(root,'vaults');
    const files=fs.existsSync(dir)?fs.readdirSync(dir).filter(f=>f.endsWith('.vault')):[];
    return files.length?fs.readFileSync(path.join(dir,files[0])):null;};
  try{
    await auth(session.page,user,true);
    let where=await roots();
    assert.equal(where.actual,where.current,'a new profile must live under Chat');
    assert.ok(fs.existsSync(where.current),'a new profile must live under Chat');
    assert.equal(fs.existsSync(where.legacy),false,'a new profile must not create the legacy directory');
    const original=vault(where.current);assert.ok(original,'expected one vault');
    // Simulate an installation that still keeps its profile in the old location.
    await session.app.close();
    // Model the old install with the app-owned files. Chromium cache files are
    // irrelevant to the migration and may still be locked after the window closes.
    assert.equal(path.basename(where.current),profile);
    fs.mkdirSync(where.legacy,{recursive:true});
    fs.cpSync(path.join(where.current,'vaults'),path.join(where.legacy,'vaults'),{recursive:true});
    fs.copyFileSync(path.join(where.current,'accounts.journal'),path.join(where.legacy,'accounts.journal'));
    fs.rmSync(where.current,{recursive:true,force:true});
    session=await launch(profile,appDataRoot);
    where=await roots();
    assert.ok(fs.existsSync(where.current),'the legacy profile must be migrated on the next launch');
    if(fs.existsSync(where.legacy))assert.deepEqual(vault(where.legacy),original,
      'copy fallback must leave the old encrypted profile intact');
    assert.deepEqual(vault(where.current),original,'the vault must move byte for byte');
    await waitRows(session.page,1);
    await auth(session.page,user,false);
    assert.equal(await session.page.locator('#identity').innerText(),user);
    // With both directories present the current one wins and the legacy one is left untouched.
    await logout(session.page);await session.app.close();
    fs.mkdirSync(path.join(where.legacy,'vaults'),{recursive:true});
    fs.writeFileSync(path.join(where.legacy,'vaults','stale.vault'),'stale');
    session=await launch(profile,appDataRoot);
    assert.equal(await session.page.locator('#accountList .account').count(),1,'an existing new directory must win');
    // The journal is append-only, so the migrated prefix must still be present byte for byte.
    const migrated=vault(where.current);
    assert.ok(migrated.length>=original.length&&migrated.subarray(0,original.length).equals(original),
      'the current directory keeps the migrated journal records');
    assert.equal(fs.readFileSync(path.join(where.legacy,'vaults','stale.vault'),'utf8'),'stale','the legacy directory must be left untouched');
    assert.deepEqual(errors,[]);
    console.log('PASS: legacy ChatAppSecure profile migrates to Chat keeping keys, newer directory wins');
  }finally{await session.app.close();}
}
(async()=>{
  if(mode==='accounts'){await accountsCheck();return;}
  if(mode==='features'){await featuresCheck();return;}
  if(mode==='migration'){await migrationCheck();return;}
  let state;
  if(mode==='before-restart'){
    const suffix=Date.now().toString(36);state={alice:'alice_'+suffix,bob:'bob_'+suffix,pa:'qa-a-'+suffix,pb:'qa-b-'+suffix,secret:'加密秘密 '+suffix,offline:'离线密文重启 '+suffix};
    fs.writeFileSync(path.join(out,'state.json'),JSON.stringify(state));
  }else state=JSON.parse(fs.readFileSync(path.join(out,'state.json')));
  const a=await launch(state.pa),b=await launch(state.pb);let c;
  const errors=[];for(const p of[a.page,b.page])p.on('pageerror',e=>errors.push(e.message));
  try{
    await auth(a.page,state.alice,mode==='before-restart');await auth(b.page,state.bob,mode==='before-restart');
    await openContact(a.page,state.bob);await openContact(b.page,state.alice);
    if(mode==='before-restart'){
      await a.page.click('#safety');await b.page.click('#safety');
      await a.page.waitForSelector('#safetyPanel:not([hidden])');await b.page.waitForSelector('#safetyPanel:not([hidden])');
      assert.equal(await a.page.locator('#safetyCode').innerText(),await b.page.locator('#safetyCode').innerText());
      await a.page.click('#confirmSafety');await b.page.click('#confirmSafety');
      await waitText(a.page,'#verification','已核对');
      await send(a.page,state.bob,state.secret);await waitText(b.page,'#messages',state.secret);await waitText(a.page,'#messages','对方客户端已接收');
      await b.page.waitForSelector('#acceptContact:not([hidden])');await b.page.click('#acceptContact');
      await waitText(a.page,'#conversationStatus','在线');
      assert.ok(!(await a.page.locator('#notice').innerText()).includes('等待服务器确认'),'Delivered messages must not leave a stale waiting notice');
      await send(b.page,state.alice,'Signal 加密回复');await waitText(a.page,'#messages','Signal 加密回复');
      await send(a.page,state.bob,'<img src=x onerror=alert(1)>');await waitText(b.page,'#messages','<img');assert.equal(await b.page.locator('#messages img').count(),0);
      const data=await (await fetch(base+'/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:state.alice,password:'test-secure-123'})})).json();
      const history=await (await fetch(base+'/api/messages?peer='+state.bob,{headers:{Authorization:'Bearer '+data.token}})).json();
      assert.equal(history.length,3);assert.ok(history.every(m=>!('body'in m)&&m.ciphertext&&!JSON.stringify(m).includes(state.secret)));
      fs.writeFileSync(path.join(out,'server-ciphertext-sample.json'),JSON.stringify(history,null,2));
      await a.page.evaluate(()=>window.scrollTo(0,0));
      await capture(a.app,path.join(out,'encrypted-chat.png'));
      const vaultDir=path.join(await a.app.evaluate(({app})=>app.getPath('userData')),'vaults');
      const files=fs.readdirSync(vaultDir);assert.equal(files.filter(f=>f.endsWith('.vault')).length,1);
      const raw=fs.readFileSync(path.join(vaultDir,files.find(f=>f.endsWith('.vault'))));assert.ok(!raw.includes(Buffer.from(state.secret)));
      // A fresh device cannot overwrite the account's existing identity.
      c=await launch(state.pa+'-other');await c.page.fill('#server',base);await c.page.click('#setupForm button[type=submit]');await c.page.fill('#username',state.alice);await c.page.fill('#password','test-secure-123');await c.page.click('#authForm button[type=submit]');await waitText(c.page,'#notice','绑定另一份');
      await logout(b.page);await send(a.page,state.bob,state.offline);await waitText(a.page,'#messages','服务器已保存密文');
      console.log('PASS: native Electron, Signal bidirectional, safety verification, server ciphertext only, DPAPI vault, second-device rejection, offline save');
    }else{
      await waitText(b.page,'#messages',state.offline);
      await send(b.page,state.alice,'客户端与服务器重启后仍可加密');await waitText(a.page,'#messages','客户端与服务器重启后仍可加密');
      await a.page.click('#history');await waitText(a.page,'#notice','已同步');
      const local=a.page.locator('.bubble').filter({hasText:state.offline});assert.ok((await local.innerText()).includes('对方客户端已接收'));
      await capture(b.app,path.join(out,'after-restart.png'));
      console.log('PASS: both clients and server restart, private state recovery, offline decryption/ACK, preserved history, ratchet continuation');
    }
    assert.deepEqual(errors,[]);
  }finally{await a.app.close();await b.app.close();if(c)await c.app.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
