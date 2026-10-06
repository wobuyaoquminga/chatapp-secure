const {test}=require('node:test');
const assert=require('node:assert/strict');
const crypto=require('node:crypto');
const fs=require('node:fs/promises');
const os=require('node:os');
const path=require('node:path');
const Format=require('../ui/file-format.js');
const Files=require('../file-transfer.cjs');
const vector=require('../../scripts/file-transfer-vector.json');
const descriptor=()=>({v:1,id:vector.id,name:vector.name,size:vector.size,key:vector.key,iv:vector.iv,sha256:vector.sha256,expiresAt:vector.expiresAt});

test('shared Android/Windows AES-256-GCM fixture has exact bytes and authenticates AAD',()=>{
 const plain=Buffer.from(vector.plain,'base64'),blob=Buffer.from(vector.ciphertext,'base64');
 const encrypted=Files.encrypt(plain,vector.id,Buffer.from(vector.key,'base64'),Buffer.from(vector.iv,'base64'));
 assert.equal(encrypted.blob.toString('base64'),vector.ciphertext);assert.equal(encrypted.sha256,vector.sha256);
 assert.deepEqual(Files.decrypt(blob,descriptor()),plain);
 const modified=Buffer.from(blob);modified[0]^=1;
 assert.throws(()=>Files.decrypt(modified,{...descriptor(),sha256:crypto.createHash('sha256').update(modified).digest('hex')}),/认证标签/);
 assert.throws(()=>Files.decrypt(blob,{...descriptor(),id:'12345678-1234-4234-8234-123456789abd'}),/认证标签/);
 assert.throws(()=>Files.decrypt(blob,{...descriptor(),key:Buffer.alloc(32,7).toString('base64')}),/认证标签/);
 assert.throws(()=>Files.decrypt(blob.subarray(1),descriptor()),/完整性/);
});

test('descriptor parser rejects malformed names, lengths, base64, fields and redacts keys',()=>{
 const body=Format.encode(descriptor());assert.deepEqual(Format.parse(body),descriptor());
 const publicBody=Format.publicBody(body);assert.equal(Format.parseCard(publicBody)?.name,vector.name);assert(!publicBody.includes(vector.key));
 for(const name of ['../a','a\\b','CON.txt','bad:name','a.',' '.repeat(3),'x'.repeat(129)])assert.equal(Format.parse(Format.PREFIX+JSON.stringify({...descriptor(),name})),null);
 for(const value of [{size:-1},{size:Format.MAX+1},{size:1.5},{key:'AA=='},{iv:'AA=='},{sha256:'A'.repeat(64)},{id:vector.id.toUpperCase()},{extra:1},{expiresAt:'bad'}])assert.equal(Format.parse(Format.PREFIX+JSON.stringify({...descriptor(),...value})),null);
 assert.equal(Format.publicBody(Format.PREFIX+'{bad}'),'[文件] 无效描述');
 assert.equal(Format.sanitizeName('C:\\temp\\ CON.txt '),'file');assert.equal(Format.sanitizeName('a<>b?.txt'),'a__b_.txt');
 assert.equal(Format.sanitizeName('dir\n/ok.txt'),'ok.txt');assert.equal(Format.sanitizeName('dir\n\\ok.txt'),'ok.txt');
 assert.equal(Files.encrypt(Buffer.alloc(0),vector.id).blob.length,16);
 assert.throws(()=>Files.encrypt(Buffer.alloc(Format.MAX+1),vector.id),/无效/);
});

test('upload bounds JSON and errors are actionable; download bounds encrypted bytes',async()=>{
 const args={server:'http://localhost:8080',token:'secret',id:vector.id,peer:'bob',toAccountId:vector.id,blob:Buffer.from('abc')};
 let called;
 const response=await Files.upload({...args,fetchImpl:async(url,options)=>{called={url:String(url),options};return new Response(JSON.stringify({id:vector.id,expiresAt:vector.expiresAt}));}});
 assert.equal(response.id,vector.id);assert(called.url.includes('to=bob'));assert(called.url.includes('toAccountId='));assert.equal(called.options.headers.Authorization,'Bearer secret');assert.equal(called.options.redirect,'error');
 await assert.rejects(Files.upload({...args,fetchImpl:async()=>new Response('x'.repeat(65537))}),/大小限制/);
 for(const [status,pattern] of [[404,/不支持/],[413,/10 MiB/],[429,/稍后重试/],[507,/额度已满/]])await assert.rejects(Files.upload({...args,fetchImpl:async()=>new Response('',{status})}),pattern);
 const blob=Buffer.from(vector.ciphertext,'base64');
 assert.deepEqual(await Files.download({...args,size:vector.size,fetchImpl:async()=>new Response(blob)}),blob);
 await assert.rejects(Files.download({...args,size:vector.size,fetchImpl:async()=>new Response(Buffer.alloc(vector.size+17))}),/大小限制/);
 await assert.rejects(Files.download({...args,size:vector.size,fetchImpl:async()=>new Response(Buffer.alloc(1))}),/大小不符/);
 await assert.rejects(Files.download({...args,server:'http://evil.example',size:vector.size,fetchImpl:async()=>new Response(blob)}),/服务器地址/);
});

test('encrypted cache is scoped to server/account and never stores plaintext',async()=>{
 const root=await fs.mkdtemp(path.join(os.tmpdir(),'chat-file-test-'));try{
  const blob=Buffer.from(vector.ciphertext,'base64'),meta=descriptor();
  await Files.cachePut(root,'https://one.example','account-a',vector.id,blob);
  assert.deepEqual(await Files.cacheGet(root,'https://one.example','account-a',vector.id,meta),blob);
  assert.equal(await Files.cacheGet(root,'https://two.example','account-a',vector.id,meta),null);
  assert.equal(await Files.cacheGet(root,'https://one.example','account-b',vector.id,meta),null);
  const folders=await fs.readdir(path.join(root,'encrypted-files'));const files=await fs.readdir(path.join(root,'encrypted-files',folders[0]));
  assert.equal(files.length,1);assert.deepEqual(await fs.readFile(path.join(root,'encrypted-files',folders[0],files[0])),blob);
 }finally{await fs.rm(root,{recursive:true,force:true});}
});
