'use strict';
const {test}=require('node:test'),assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http'),crypto=require('node:crypto');
const U=require('../updates.cjs');
const data=Buffer.alloc(160000,7),sha=crypto.createHash('sha256').update(data).digest('hex');
const manifest={platform:'windows-x64',version:'0.6.1',fileName:'Chat-win32-x64-0.6.1.zip',size:data.length,sha256:sha,downloadPath:'/api/updates/files/windows-x64'};
async function fixture(handler){
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-update-'));
 const server=http.createServer(handler);await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const base='http://127.0.0.1:'+server.address().port;
 return {dir,base,file:path.join(dir,manifest.fileName),close:async()=>{server.closeAllConnections();await new Promise(resolve=>server.close(resolve));fs.rmSync(dir,{recursive:true,force:true});}};
}
function route(req,res,mode='ok'){
 if(req.url==='/api/updates/latest?platform=windows-x64'){res.setHeader('content-type','application/json');res.end(JSON.stringify(manifest));return;}
 if(req.url!==manifest.downloadPath){res.writeHead(404).end();return;}
 if(mode==='redirect'){res.writeHead(302,{location:'https://other.example/evil.zip'}).end();return;}
 if(mode==='truncated'){res.writeHead(200,{'content-length':data.length});res.end(data.subarray(0,300));return;}
 if(mode==='slow'){res.writeHead(200,{'content-length':data.length});res.write(data.subarray(0,1000));return;}
 res.writeHead(200,{'content-length':data.length});res.end(mode==='bad-hash'?Buffer.alloc(data.length,8):data);
}
test('real loopback HTTP check and streamed verified ZIP download',async()=>{
 const f=await fixture((req,res)=>route(req,res));try{
  const info=await U.checkUpdate(f.base,'0.6.0');assert.equal(info.available,true);
  const progress=[];const saved=await U.downloadUpdate(f.base,info,f.file,{onProgress:p=>progress.push(p)});
  assert.equal(saved,f.file);assert.deepEqual(fs.readFileSync(saved),data);assert.equal(progress.at(-1).received,data.length);
  assert.equal((await U.checkUpdate(f.base,'0.6.1')).available,false);
 }finally{await f.close();}
});
test('bad hash, truncated stream and cross-host redirect leave no destination or temp',async()=>{
 for(const mode of ['bad-hash','truncated','redirect']){
  const f=await fixture((req,res)=>route(req,res,mode));try{
   await assert.rejects(U.downloadUpdate(f.base,manifest,f.file),/校验失败|不完整|aborted|premature|返回 302/i);
   assert.deepEqual(fs.readdirSync(f.dir),[]);
  }finally{await f.close();}
 }
});
test('cancel stops a live stream and cleans temporary file',async()=>{
 const f=await fixture((req,res)=>route(req,res,'slow'));const abort=new AbortController();try{
  const run=U.downloadUpdate(f.base,manifest,f.file,{signal:abort.signal,onProgress:()=>abort.abort()});
  await assert.rejects(run);assert.deepEqual(fs.readdirSync(f.dir),[]);
 }finally{await f.close();}
});
test('manifest, version and URL validation reject unsafe inputs',()=>{
 assert.equal(U.compareVersions('0.6.1','0.6.0'),1);assert.equal(U.compareVersions('0.6.0','0.6.1'),-1);
 for(const patch of [{fileName:'../evil.zip'},{size:U.MAX_SIZE+1},{sha256:'no'},{downloadPath:'https://evil.test/a'},{platform:'linux-x64'},{version:'0.6.1-rc'}])assert.throws(()=>U.validateManifest({...manifest,...patch},'0.6.0'));
 assert.throws(()=>U.origin('http://evil.test'));assert.throws(()=>U.origin('https://example.com/path'));
});
