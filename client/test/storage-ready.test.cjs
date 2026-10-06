const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os');
const {setTimeout:delay}=require('node:timers/promises');
const {waitForStorageKey}=require('../storage-ready.cjs');

test('storage gate waits for the Windows wrapped key before allowing account writes',async()=>{
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'chat-key-ready-'));
  try{
    let ready=false;
    const wait=waitForStorageKey(directory,{timeout:2000}).then(()=>{ready=true;});
    await delay(80);assert.equal(ready,false);
    await fs.writeFile(path.join(directory,'Local State'),JSON.stringify({os_crypt:{encrypted_key:Buffer.from('DPAPI-synthetic-test-key').toString('base64')}}));
    await wait;assert.equal(ready,true);
  }finally{await fs.rm(directory,{recursive:true,force:true});}
});

test('storage gate refuses missing or malformed key without overwriting existing files',async()=>{
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'chat-key-invalid-'));
  try{
    const file=path.join(directory,'Local State');
    await assert.rejects(waitForStorageKey(directory,{timeout:1}),/系统加密密钥尚未保存/);
    await fs.writeFile(file,'invalid-existing-state');
    await assert.rejects(waitForStorageKey(directory,{timeout:1}),/系统加密密钥尚未保存/);
    assert.equal(await fs.readFile(file,'utf8'),'invalid-existing-state');
  }finally{await fs.rm(directory,{recursive:true,force:true});}
});
