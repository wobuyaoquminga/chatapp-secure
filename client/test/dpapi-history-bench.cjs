'use strict';
// Actual Windows safeStorage read/write benchmark, isolated from all real accounts.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const out=path.resolve(process.env.CHAT_HISTORY_QA_DIR||path.join(os.tmpdir(),'chat-dpapi-bench-'+Date.now()));fs.mkdirSync(out,{recursive:true});
if(!process.versions.electron){
 const env={...process.env,CHAT_HISTORY_QA_DIR:out};delete env.ELECTRON_RUN_AS_NODE;
 const child=spawn(path.resolve(__dirname,'../node_modules/electron/dist/electron.exe'),[__filename],{env,windowsHide:true,stdio:['ignore','pipe','pipe']});child.stdout.pipe(process.stdout);child.stderr.pipe(process.stderr);child.on('exit',code=>process.exitCode=code??1);return;
}
const {app,safeStorage}=require('electron'),{Controller}=require('../controller.cjs'),{Vault}=require('../vault.cjs');
app.setPath('userData',path.join(out,'electron-profile'));let c;
app.whenReady().then(async()=>{
 assert(safeStorage.isEncryptionAvailable());let sizes=[];
 const measured={isEncryptionAvailable:()=>safeStorage.isEncryptionAvailable(),encryptString:s=>{sizes.push(Buffer.byteLength(s));return safeStorage.encryptString(s);},decryptString:b=>safeStorage.decryptString(b)};
 c=new Controller(path.join(out,'data'),measured,()=>{});c.user='qa_alice';c.server='http://localhost';c.vault=new Vault(path.join(out,'vaults'),c.server,c.user,measured);
 const message=i=>({sender:c.user,recipient:'qa_bob',clientId:String(i),body:'DPAPI 历史性能样本 '+i,createdAt:new Date(1700000000000+i).toISOString(),status:'已保存',ciphertext:'synthetic-ciphertext-'+i});
 c.engine={state:{messages:Object.fromEntries(Array.from({length:20000},(_,i)=>[c.user+':'+i,message(i)])),verified:{},sessions:{},outbox:{},identity:'synthetic-protocol-fixture'}};
 let started=performance.now();c.vault.write(c.engine.state);const initialWriteMs=performance.now()-started;c.rebuildMessageMetadata();sizes=[];
 started=performance.now();await c.transaction(()=>{c.engine.state.messages[c.user+':20000']=message(20000);});await c.transaction(()=>{c.engine.state.messages[c.user+':20000'].status='已送达';});const incrementalWriteMs=performance.now()-started;
 assert(sizes.every(n=>n<1000));started=performance.now();const restored=new Vault(path.join(out,'vaults'),c.server,c.user,measured).read();const readMs=performance.now()-started;
 assert.equal(Object.keys(restored.messages).length,20001);assert.equal(restored.messages[c.user+':20000'].status,'已送达');assert(!fs.readFileSync(c.vault.historyFile).includes(Buffer.from('DPAPI 历史性能样本')));
 const result={pass:true,windowsSecureStorage:true,messages:20000,initialWriteMs,incrementalWriteMs,readMs,incrementalEncryptedPayloadBytes:sizes,historyFileBytes:fs.statSync(c.vault.historyFile).size};
 fs.writeFileSync(path.join(out,'dpapi-history-bench.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}).catch(error=>{console.error(error.stack);process.exitCode=1;}).finally(()=>{c?.logout();app.exit(process.exitCode||0);});
