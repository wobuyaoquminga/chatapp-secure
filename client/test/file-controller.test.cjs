const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const {Controller}=require('../controller.cjs');
const Format=require('../ui/file-format.js');
const vector=require('../../scripts/file-transfer-vector.json');
const storage={isEncryptionAvailable:()=>true,encryptString:value=>Buffer.from(value),decryptString:value=>value.toString()};

test('controller redacts file keys from full and delta renderer snapshots and blocks text injection',async()=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'chat-file-controller-'));
 try{
  const controller=new Controller(directory,storage,()=>{});const accountId=vector.id;
  const body=Format.encode({v:1,id:vector.id,name:vector.name,size:vector.size,key:vector.key,iv:vector.iv,sha256:vector.sha256,expiresAt:vector.expiresAt});
  const message={sender:'bob',recipient:'alice',clientId:vector.id,body,createdAt:'2026-01-01T00:00:00Z'};
  controller.user='alice';controller.server='http://localhost:8080';controller.accountId=accountId;controller.online=true;controller.socket={readyState:1};controller.contactState={bob:{username:'bob',status:'accepted',accountId}};
  controller.engine={state:{messages:{['bob:'+vector.id]:message},verified:{bob:'identity'},peerAccountIds:{bob:accountId},outbox:{}}};
  const full=controller.snapshot(true);assert.equal(Format.parseCard(full.messages[0].body)?.name,vector.name);assert(!JSON.stringify(full).includes(vector.key));
  controller.pendingMessageChanges={['bob:'+vector.id]:message};const delta=controller.snapshot(false);assert.equal(Format.parseCard(delta.messageChanges['bob:'+vector.id].body)?.name,vector.name);assert(!JSON.stringify(delta).includes(vector.key));
  await assert.rejects(controller.send({peer:'bob',body}),/文件选择器/);
  assert.equal(controller.filePeer('bob'),accountId);
  delete controller.engine.state.verified.bob;assert.throws(()=>controller.filePeer('bob'),/身份已核对/);
 }finally{fs.rmSync(directory,{recursive:true,force:true});}
});
