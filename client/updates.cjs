'use strict';
const fs=require('node:fs');
const path=require('node:path');
const crypto=require('node:crypto');
const http=require('node:http');
const https=require('node:https');

const PLATFORM='windows-x64', DOWNLOAD_PATH='/api/updates/files/windows-x64';
const MAX_SIZE=512*1024*1024;
function origin(server){
  const u=new URL(server);
  if(!['http:','https:'].includes(u.protocol)||u.username||u.password||u.pathname!=='/'||u.search||u.hash)throw Error('服务器地址无效');
  if(u.protocol==='http:'&&!['localhost','127.0.0.1','[::1]'].includes(u.hostname))throw Error('远程更新服务器必须使用 HTTPS');
  return u.origin;
}
function compareVersions(a,b){
  if(!/^\d+\.\d+\.\d+$/.test(a)||!/^\d+\.\d+\.\d+$/.test(b))throw Error('更新版本格式无效');
  const x=a.split('.').map(Number),y=b.split('.').map(Number);
  if([...x,...y].some(n=>!Number.isSafeInteger(n)))throw Error('更新版本格式无效');
  for(let i=0;i<3;i++)if(x[i]!==y[i])return x[i]>y[i]?1:-1;
  return 0;
}
function validateManifest(value,installed){
  if(!value||typeof value!=='object'||Array.isArray(value)||value.platform!==PLATFORM||
    typeof value.version!=='string'||typeof value.fileName!=='string'||
    !/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}\.zip$/i.test(value.fileName)||
    value.fileName.includes('..')||/^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])\./i.test(value.fileName)||value.downloadPath!==DOWNLOAD_PATH||
    !Number.isSafeInteger(value.size)||value.size<=0||value.size>MAX_SIZE||
    typeof value.sha256!=='string'||!/^[a-f0-9]{64}$/i.test(value.sha256)||
    (value.notes!==undefined&&(typeof value.notes!=='string'||value.notes.length>4000)))throw Error('服务器更新清单无效');
  const relation=compareVersions(value.version,installed);
  return {platform:PLATFORM,version:value.version,fileName:value.fileName,size:value.size,sha256:value.sha256.toLowerCase(),downloadPath:DOWNLOAD_PATH,...(value.notes===undefined?{}:{notes:value.notes}),available:relation>0};
}
function request(url,signal){
  return new Promise((resolve,reject)=>{
    const u=new URL(url),agent=u.protocol==='https:'?https:http;
    const req=agent.get(u,{signal,timeout:15000,headers:{'Accept':'application/json','User-Agent':'Chat-Windows-Updater'}},resolve);
    req.on('timeout',()=>req.destroy(Error('更新服务器请求超时')));
    req.on('error',reject);
  });
}
async function checkedResponse(url,signal){
  const response=await request(url,signal);
  if(response.statusCode!==200){response.destroy();throw Error(response.statusCode===404?'服务器尚未提供 Windows 更新包':`更新服务器返回 ${response.statusCode}`);}
  return response;
}
async function checkUpdate(server,installed,signal){
  const base=origin(server),response=await checkedResponse(base+'/api/updates/latest?platform='+PLATFORM,signal);
  const chunks=[];let size=0;
  try{
    for await(const chunk of response){size+=chunk.length;if(size>16*1024)throw Error('服务器更新清单过大');chunks.push(chunk);}
    let value;try{value=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{throw Error('服务器更新清单无效');}
    return {server:base,...validateManifest(value,installed)};
  }finally{response.destroy();}
}
async function downloadUpdate(server,manifest,destination,{signal,onProgress=()=>{},isCurrent=()=>true}={}){
  const base=origin(server),m=validateManifest(manifest,'0.0.0');
  if(!m.available||!isCurrent())throw Error('更新服务器已变化，请重新检查');
  const temp=destination+'.'+crypto.randomBytes(8).toString('hex')+'.part';
  let output,response,deadline;
  try{
    response=await checkedResponse(base+DOWNLOAD_PATH,signal);
    response.setTimeout(15000,()=>response.destroy(Error('更新包下载超时')));
    deadline=setTimeout(()=>response.destroy(Error('更新包下载超时')),10*60*1000);deadline.unref?.();
    const declared=Number(response.headers['content-length']);
    if(response.headers['content-length']!==undefined&&(!Number.isSafeInteger(declared)||declared!==m.size))throw Error('更新包大小与清单不符');
    output=await fs.promises.open(temp,'wx');
    const hash=crypto.createHash('sha256');let received=0;
    for await(const chunk of response){
      if(signal?.aborted||!isCurrent())throw Error('下载已取消或服务器已变化');
      received+=chunk.length;if(received>m.size)throw Error('更新包大小与清单不符');
      hash.update(chunk);
      let offset=0;while(offset<chunk.length){const written=await output.write(chunk,offset,chunk.length-offset);if(!written.bytesWritten)throw Error('更新包写入失败');offset+=written.bytesWritten;}
      onProgress({received,total:m.size});
    }
    if(received!==m.size)throw Error('更新包不完整');
    if(hash.digest('hex')!==m.sha256)throw Error('更新包 SHA-256 校验失败');
    if(signal?.aborted||!isCurrent())throw Error('下载已取消或服务器已变化');
    await output.close();output=null;
    if(signal?.aborted||!isCurrent())throw Error('下载已取消或服务器已变化');
    await fs.promises.rename(temp,destination);
    return destination;
  }finally{
    clearTimeout(deadline);response?.destroy();if(output)await output.close().catch(()=>{});
    await fs.promises.rm(temp,{force:true}).catch(()=>{});
  }
}
module.exports={PLATFORM,DOWNLOAD_PATH,MAX_SIZE,origin,compareVersions,validateManifest,checkUpdate,downloadUpdate};
