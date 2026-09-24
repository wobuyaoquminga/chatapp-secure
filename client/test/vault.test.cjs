const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Vault}=require('../vault.cjs');
// Only exercises journal commit/recovery. Real Windows DPAPI is exercised by Electron smoke tests.
const fake={isEncryptionAvailable:()=>true,encryptString:s=>Buffer.from(s),decryptString:b=>b.toString()};
test('vault recovers last complete checkpoint after an interrupted append',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    const vault=new Vault(dir,'http://localhost','alice',fake);
    vault.write({counter:1});vault.write({counter:2});fs.appendFileSync(vault.file,'truncated');
    assert.deepEqual(vault.read(),{counter:2});vault.write({counter:3});assert.deepEqual(vault.read(),{counter:3});
  }finally{for(const f of fs.readdirSync(dir))fs.unlinkSync(path.join(dir,f));fs.rmdirSync(dir);}
});
test('vault fails closed without secure storage and on corrupt committed checkpoint',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    assert.throws(()=>new Vault(dir,'http://localhost','alice',{isEncryptionAvailable:()=>false}));
    const vault=new Vault(dir,'http://localhost','alice',fake);vault.write({counter:1});
    fs.appendFileSync(vault.file,Buffer.from('not-json').toString('base64')+'\n');assert.throws(()=>vault.read());
  }finally{for(const f of fs.readdirSync(dir))fs.unlinkSync(path.join(dir,f));fs.rmdirSync(dir);}
});
