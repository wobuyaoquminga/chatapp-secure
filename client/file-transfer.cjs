const crypto=require('node:crypto');
const fs=require('node:fs/promises');
const path=require('node:path');
const Format=require('./ui/file-format.js');

function encrypt(plain,id,key=crypto.randomBytes(32),iv=crypto.randomBytes(12)){
  if(!Buffer.isBuffer(plain)||plain.length>Format.MAX||!Buffer.isBuffer(key)||key.length!==32||!Buffer.isBuffer(iv)||iv.length!==12)throw Error('文件数据无效');
  const cipher=crypto.createCipheriv('aes-256-gcm',key,iv);cipher.setAAD(Buffer.from(id,'ascii'));
  const blob=Buffer.concat([cipher.update(plain),cipher.final(),cipher.getAuthTag()]);
  return {blob,key:key.toString('base64'),iv:iv.toString('base64'),sha256:crypto.createHash('sha256').update(blob).digest('hex')};
}
function decrypt(blob,meta){
  if(!Format.parse(Format.encode(meta))||!Buffer.isBuffer(blob)||blob.length!==meta.size+16||crypto.createHash('sha256').update(blob).digest('hex')!==meta.sha256)throw Error('文件密文完整性校验失败');
  try{const decipher=crypto.createDecipheriv('aes-256-gcm',Buffer.from(meta.key,'base64'),Buffer.from(meta.iv,'base64'));decipher.setAAD(Buffer.from(meta.id,'ascii'));decipher.setAuthTag(blob.subarray(blob.length-16));return Buffer.concat([decipher.update(blob.subarray(0,-16)),decipher.final()]);}
  catch{throw Error('文件认证标签校验失败');}
}
function endpoint(server,id){const origin=new URL(server);if(origin.origin!==server||origin.username||origin.password||origin.pathname!=='/'||origin.search||origin.hash||!['http:','https:'].includes(origin.protocol)||origin.protocol==='http:'&&!['localhost','127.0.0.1','[::1]'].includes(origin.hostname))throw Error('文件服务器地址无效');if(!/^[0-9a-f-]{36}$/.test(id))throw Error('文件编号无效');return server+'/api/files/'+id;}
function errorFor(response){return ({404:'服务器不支持文件传输，或文件已过期',403:'文件访问权限已变化',413:'文件超过服务器的 10 MiB 限制',429:'文件操作过于频繁，请稍后重试',507:'服务器文件空间或数量额度已满'})[response.status]||'文件请求失败 ('+response.status+')';}
async function readBounded(response,limit){let total=0;const chunks=[];if(!response.body)throw Error('服务器文件响应为空');const reader=response.body.getReader();try{for(;;){const {done,value}=await reader.read();if(done)break;total+=value.length;if(total>limit)throw Error('服务器返回的数据超过大小限制');chunks.push(Buffer.from(value));}return Buffer.concat(chunks,total);}finally{reader.cancel().catch(()=>{});}}
async function upload({server,token,id,peer,toAccountId,blob,signal,fetchImpl=fetch}){
  const url=new URL(endpoint(server,id));url.searchParams.set('to',peer);url.searchParams.set('toAccountId',toAccountId);
  const response=await fetchImpl(url,{method:'PUT',redirect:'error',signal,headers:{Authorization:'Bearer '+token,'Content-Type':'application/octet-stream'},body:blob});
  if(!response.ok)throw Error(errorFor(response));let data;try{data=JSON.parse((await readBounded(response,65536)).toString('utf8'));}catch(error){if(error.message.includes('大小限制'))throw error;throw Error('服务器文件回执无效');}
  if(data?.id!==id||typeof data.expiresAt!=='string'||!Number.isFinite(Date.parse(data.expiresAt)))throw Error('服务器文件回执无效');return data;
}
async function download({server,token,id,size,signal,fetchImpl=fetch}){
  const response=await fetchImpl(endpoint(server,id),{method:'GET',redirect:'error',signal,headers:{Authorization:'Bearer '+token}});
  if(!response.ok)throw Error(errorFor(response));
  const blob=await readBounded(response,size+16);if(blob.length!==size+16)throw Error('服务器返回的文件大小不符');return blob;
}
async function remove({server,token,id,signal,fetchImpl=fetch}){try{await fetchImpl(endpoint(server,id),{method:'DELETE',redirect:'error',signal,headers:{Authorization:'Bearer '+token}});}catch{}}
function cachePath(root,server,accountId,id){const scope=crypto.createHash('sha256').update(server+'\0'+accountId).digest('hex');return path.join(root,'encrypted-files',scope,id+'.bin');}
async function cachePut(root,server,accountId,id,blob){const target=cachePath(root,server,accountId,id);await fs.mkdir(path.dirname(target),{recursive:true});await fs.writeFile(target,blob,{flag:'w',mode:0o600});}
async function cacheGet(root,server,accountId,id,meta){try{const target=cachePath(root,server,accountId,id),stat=await fs.stat(target);if(stat.size!==meta.size+16)return null;const blob=await fs.readFile(target);return crypto.createHash('sha256').update(blob).digest('hex')===meta.sha256?blob:null;}catch{return null;}}
module.exports={encrypt,decrypt,upload,download,remove,cachePut,cacheGet,endpoint};
