const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {Vault,AccountRegistry}=require('../vault.cjs');
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
test('account registry keeps one entry per server and account, most recent first',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    const registry=new AccountRegistry(dir,fake);
    assert.deepEqual(registry.list(),[]);
    registry.remember('http://localhost:8082','alice');
    registry.remember('https://chat.example.com','alice');
    // Reusing an account moves it to the front instead of duplicating it; other servers stay.
    const accounts=registry.remember('http://localhost:8082','alice');
    assert.deepEqual(accounts.map(a=>a.server),['http://localhost:8082','https://chat.example.com']);
    assert.deepEqual(accounts[0],{server:'http://localhost:8082',user:'alice'});
    assert.deepEqual(registry.forget('http://localhost:8082','alice').map(a=>a.server),['https://chat.example.com']);
    assert.deepEqual(new AccountRegistry(dir,fake).list().map(a=>a.server),['https://chat.example.com']);
  }finally{for(const f of fs.readdirSync(dir))fs.unlinkSync(path.join(dir,f));fs.rmdirSync(dir);}
});
test('protected account index preserves deadline summary only for the same account incarnation',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    const registry=new AccountRegistry(dir,fake),server='https://chat.example.com',status={serverTime:'2026-10-03T00:00:00Z',lastConnectedAt:'2026-10-03T00:00:00Z',accountExpiresAt:'2026-10-10T00:00:00Z',retentionDays:7,receivedAt:'2026-10-03T00:00:01Z'};
    registry.remember(server,'alice','account-one');
    assert.equal(registry.recordStatus(server,'alice','wrong-account',status),false);
    assert.equal(registry.list()[0].accountStatus,undefined);
    assert.equal(registry.recordStatus(server,'alice','account-one',status),true);
    registry.saveServer('https://other.example');
    registry.remember(server,'alice','account-one');
    assert.equal(new AccountRegistry(dir,fake).list()[0].accountStatus.accountExpiresAt,status.accountExpiresAt);
    registry.remember(server,'alice','account-two');
    assert.equal(registry.list()[0].accountStatus,undefined,'new registration clears old deadline');
  }finally{for(const f of fs.readdirSync(dir))fs.unlinkSync(path.join(dir,f));fs.rmdirSync(dir);}
});
test('a corrupt account list never blocks the keys of an account vault',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    const registry=new AccountRegistry(dir,fake);registry.remember('https://chat.example.com','alice');
    const vault=new Vault(dir,'https://chat.example.com','alice',fake);vault.write({counter:7});
    fs.appendFileSync(registry.file,Buffer.from('not-json').toString('base64')+'\n');
    assert.throws(()=>registry.list());
    assert.deepEqual(vault.read(),{counter:7});
  }finally{for(const f of fs.readdirSync(dir))fs.unlinkSync(path.join(dir,f));fs.rmdirSync(dir);}
});
test('servers persist independently of account entries and switching keeps per-server accounts isolated',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    const registry=new AccountRegistry(dir,fake);
    registry.saveServer('https://one.example');
    registry.remember('https://one.example','alice');
    registry.saveServer('https://two.example');
    registry.remember('https://two.example','alice');
    registry.remember('https://two.example','bob');
    assert.deepEqual(registry.servers(),['https://two.example','https://one.example']);
    assert.equal(registry.selectedServer(),'https://two.example');
    registry.selectServer('https://one.example');
    assert.deepEqual(registry.list().filter(a=>a.server===registry.selectedServer()).map(a=>a.user),['alice']);
    const reopened=new AccountRegistry(dir,fake);
    assert.equal(reopened.selectedServer(),'https://one.example');
    reopened.forgetServer('https://one.example');
    assert.deepEqual(reopened.servers(),['https://two.example']);
    assert.deepEqual(reopened.list().map(a=>a.user),['bob','alice']);
    assert.throws(()=>reopened.selectServer('https://one.example'));
  }finally{for(const f of fs.readdirSync(dir))fs.unlinkSync(path.join(dir,f));fs.rmdirSync(dir);}
});
test('existing account indexes become server choices without changing account vaults',()=>{
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-vault-'));
  try{
    const registry=new AccountRegistry(dir,fake);
    registry.write({version:1,accounts:[{server:'https://old.example',user:'alice'}]});
    const vault=new Vault(path.join(dir,'vaults'),'https://old.example','alice',fake);
    vault.write({key:'still here'});
    assert.deepEqual(registry.servers(),['https://old.example']);
    registry.saveServer('https://new.example');
    assert.deepEqual(registry.list(),[{server:'https://old.example',user:'alice'}]);
    assert.deepEqual(vault.read(),{key:'still here'});
  }finally{fs.rmSync(dir,{recursive:true,force:true});}
});
