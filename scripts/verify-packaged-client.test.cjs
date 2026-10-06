'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs/promises'),path=require('node:path'),os=require('node:os'),{pathToFileURL}=require('node:url');
const {verifyPackagedClient}=require('./verify-packaged-client.cjs');
test('release guard rejects stale code and wrong versions in an actual ASAR',async()=>{
 const {createPackage}=await import(pathToFileURL(path.resolve(__dirname,'../client/node_modules/@electron/asar/lib/asar.js')).href);
 const directory=await fs.mkdtemp(path.join(os.tmpdir(),'chat-package-check-'));
 try{
  const source=path.join(directory,'client'),archive=path.join(directory,'app.asar');await fs.mkdir(source);
  await fs.writeFile(path.join(source,'package.json'),JSON.stringify({version:'1.2.3',main:'main.cjs',dependencies:{}}));
  await fs.writeFile(path.join(source,'main.cjs'),'module.exports = 1;');
  await createPackage(source,archive);
  assert.equal(await verifyPackagedClient(source,archive,'1.2.3'),1);
  await assert.rejects(verifyPackagedClient(source,archive,'1.2.4'),/version/);
  await fs.writeFile(path.join(source,'main.cjs'),'module.exports = 2;');
  await assert.rejects(verifyPackagedClient(source,archive,'1.2.3'),/stale source/);
 }finally{await fs.rm(directory,{recursive:true,force:true});}
});
