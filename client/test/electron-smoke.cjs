// Optional QA dependency: Playwright. Run before-restart, restart server, then after-restart.
const {_electron:electron}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const out=path.resolve(process.env.QA_DIR||'qa');fs.mkdirSync(out,{recursive:true});
const mode=process.argv[2]||'before-restart';
const executablePath=process.env.CLIENT_EXE||require('electron');
const packaged=!!process.env.CLIENT_EXE;
const root=path.resolve(__dirname,'..');
const base=process.env.CHAT_URL||'http://localhost:8082';
const waitText=(p,selector,text)=>p.waitForFunction(({selector,text})=>document.querySelector(selector)?.textContent.includes(text),{selector,text},{timeout:25000});
async function launch(profile){
  const app=await electron.launch({executablePath,args:[...(packaged?[]:[root]),'--profile='+profile,'--test-hidden'],timeout:30000});
  const page=await app.firstWindow();await page.waitForSelector('#username');return {app,page};
}
async function auth(p,user,register){
  await p.fill('#server',base);await p.fill('#username',user);await p.fill('#password','test-secure-123');
  await p.click(register?'#register':'#authForm button[type=submit]');
  try{await waitText(p,'#connection','在线');}catch(e){throw new Error('Login failed: '+await p.locator('#notice').innerText());}
}
async function send(p,peer,text){await p.fill('#peer',peer);await p.fill('#body',text);await p.click('#send');await waitText(p,'#messages',text);}
async function capture(app,file){
  const data=await app.evaluate(async({BrowserWindow})=>(await BrowserWindow.getAllWindows()[0].webContents.capturePage(undefined,{stayHidden:true,stayAwake:true})).toPNG().toString('base64'));
  fs.writeFileSync(file,Buffer.from(data,'base64'));
}
(async()=>{
  let state;
  if(mode==='before-restart'){
    const suffix=Date.now().toString(36);state={alice:'alice_'+suffix,bob:'bob_'+suffix,pa:'qa-a-'+suffix,pb:'qa-b-'+suffix,secret:'加密秘密 '+suffix,offline:'离线密文重启 '+suffix};
    fs.writeFileSync(path.join(out,'state.json'),JSON.stringify(state));
  }else state=JSON.parse(fs.readFileSync(path.join(out,'state.json')));
  const a=await launch(state.pa),b=await launch(state.pb);let c;
  const errors=[];for(const p of[a.page,b.page])p.on('pageerror',e=>errors.push(e.message));
  try{
    await auth(a.page,state.alice,mode==='before-restart');await auth(b.page,state.bob,mode==='before-restart');
    await a.page.fill('#peer',state.bob);await b.page.fill('#peer',state.alice);
    if(mode==='before-restart'){
      await a.page.click('#safety');await b.page.click('#safety');
      await a.page.waitForSelector('#safetyPanel:not([hidden])');await b.page.waitForSelector('#safetyPanel:not([hidden])');
      assert.equal(await a.page.locator('#safetyCode').innerText(),await b.page.locator('#safetyCode').innerText());
      await a.page.click('#confirmSafety');await b.page.click('#confirmSafety');
      await waitText(a.page,'#verification','已核对');
      await send(a.page,state.bob,state.secret);await waitText(b.page,'#messages',state.secret);await waitText(a.page,'#messages','对方客户端已接收');
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
      c=await launch(state.pa+'-other');await c.page.fill('#server',base);await c.page.fill('#username',state.alice);await c.page.fill('#password','test-secure-123');await c.page.click('#authForm button[type=submit]');await waitText(c.page,'#notice','绑定另一份');
      await b.page.click('#logout');await send(a.page,state.bob,state.offline);await waitText(a.page,'#messages','服务器已保存密文');
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
