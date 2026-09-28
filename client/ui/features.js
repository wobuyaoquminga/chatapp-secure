(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.ChatFeatures=api;})(globalThis,()=>{
'use strict';
const PREFIX='CHAT_LOCATION_V1:',HOUR=3600000;
const uuid=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
function parse(body){
 if(typeof body!=='string'||!body.startsWith(PREFIX)||body.length>1500)return null;
 try{const p=JSON.parse(body.slice(PREFIX.length));
 const keys=['v','kind','sessionId','seq','latitude','longitude','accuracy','recordedAt','expiresAt'];
 if(!p||Array.isArray(p)||Object.keys(p).some(k=>!keys.includes(k))||p.v!==1||!['pin','live','stop'].includes(p.kind)||!uuid.test(p.sessionId)||!Number.isSafeInteger(p.seq)||p.seq<0||p.seq>2147483647)return null;
 if(typeof p.recordedAt!=='string'||typeof p.expiresAt!=='string'||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(p.recordedAt)||!/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(p.expiresAt))return null;
 const canonical=s=>s.includes('.')?s:s.replace('Z','.000Z');
 const start=Date.parse(p.recordedAt),end=Date.parse(p.expiresAt);
 if(!Number.isFinite(start)||!Number.isFinite(end)||new Date(start).toISOString()!==canonical(p.recordedAt)||new Date(end).toISOString()!==canonical(p.expiresAt))return null;if(!Number.isFinite(start)||!Number.isFinite(end)||end<start||end-start>HOUR||start>Date.now()+300000)return null;

 if(p.kind!=='stop'||p.latitude!==undefined||p.longitude!==undefined||p.accuracy!==undefined){if(!Number.isFinite(p.latitude)||Math.abs(p.latitude)>90||!Number.isFinite(p.longitude)||Math.abs(p.longitude)>180||!Number.isFinite(p.accuracy)||p.accuracy<0||p.accuracy>100000)return null;}
 return p;}catch{return null;}
}
function encode(p){const body=PREFIX+JSON.stringify(p);if(!parse(body))throw new Error('位置数据无效');return body;}
function summary(body){const p=parse(body);return p?(p.kind==='pin'?'[位置] '+'':`[实时位置${p.kind==='stop'?'已停止':''}] `)+(p.latitude===undefined?'':p.latitude.toFixed(5)+', '+p.longitude.toFixed(5)):body;}
function sessions(messages,now=Date.now()){
 const result=new Map();for(const m of messages){const p=parse(m.body);if(!p||p.kind==='pin')continue;const key=m.sender+'\0'+p.sessionId,old=result.get(key);
 if(old&&(old.stopped||(p.kind!=='stop'&&(p.seq<=old.value.seq||Date.parse(p.expiresAt)>old.deadline))))continue;
 const deadline=old?Math.min(old.deadline,Date.parse(p.expiresAt)):Date.parse(p.expiresAt);
 const expired=deadline<=now,stopped=p.kind==='stop'||expired;
 const value=p.kind==='stop'&&old?{...p,seq:Math.max(p.seq,old.value.seq),latitude:old.value.latitude,longitude:old.value.longitude,accuracy:old.value.accuracy,recordedAt:old.value.recordedAt}:p;
 result.set(key,{value,message:m,deadline,stopped,status:p.kind==='stop'?'已停止':expired?'已过期':'共享中'});
 }return result;
}
function search(messages,user,peer,{query='',from='',to='',page=0,pageSize=20}={}){
 const start=from?Date.parse(from+'T00:00:00'): -Infinity,end=to?Date.parse(to+'T23:59:59.999'):Infinity;
 const q=query.trim().toLocaleLowerCase();const all=messages.filter(m=>((m.sender===user&&m.recipient===peer)||(m.sender===peer&&m.recipient===user))&&Date.parse(m.createdAt)>=start&&Date.parse(m.createdAt)<=end&&summary(m.body).toLocaleLowerCase().includes(q)).sort((a,b)=>b.createdAt.localeCompare(a.createdAt));
 const size=Math.max(1,Math.min(50,pageSize)),index=Math.max(0,Number.isSafeInteger(page)?page:0);return {total:all.length,items:all.slice(index*size,(index+1)*size)};
}
// One index belongs to one account snapshot. The caller replaces it whenever
// the snapshot or account changes; only the most recent search is retained.
function messageIndex(messages,user,server){
 const byPeer=new Map(),bodyLower=new WeakMap();let cachedSearch=null,cachedRows=null;
 for(const m of messages){
  let peer;
  if(m.sender===user&&m.recipient!==user)peer=m.recipient;
  else if(m.recipient===user&&m.sender!==user)peer=m.sender;
  else continue;
  if(!byPeer.has(peer))byPeer.set(peer,[]);
  byPeer.get(peer).push(m);
 }
 for(const rows of byPeer.values())rows.sort((a,b)=>a.createdAt.localeCompare(b.createdAt));
 function forPeer(peer){return byPeer.get(peer)||[];}
 function lowerBody(m){if(!bodyLower.has(m))bodyLower.set(m,m.body.toLocaleLowerCase());return bodyLower.get(m);}
 function hasBodyMatch(peer,query){return forPeer(peer).some(m=>lowerBody(m).includes(query));}
 function newestRows(peer){
  if(cachedRows?.peer===peer)return cachedRows.rows;
  const oldest=forPeer(peer),rows=[];
  // Reverse timestamp groups, leaving equal-timestamp messages in input order.
  for(let end=oldest.length;end>0;){let start=end-1;
   while(start>0&&oldest[start-1].createdAt.localeCompare(oldest[end-1].createdAt)===0)start--;
   for(let i=start;i<end;i++)rows.push({message:oldest[i],when:null,summaryLower:null});end=start;
  }
  cachedRows={peer,rows};return rows;
 }
 function find(peer,{query='',from='',to='',page=0,pageSize=20}={}){
  const q=query.trim().toLocaleLowerCase(),key=JSON.stringify([peer,q,from,to]);
  if(!cachedSearch||cachedSearch.key!==key){
   const start=from?Date.parse(from+'T00:00:00'):-Infinity,end=to?Date.parse(to+'T23:59:59.999'):Infinity;
   const matches=[];
   for(const row of newestRows(peer)){
    if(row.when===null)row.when=Date.parse(row.message.createdAt);
    if(!(row.when>=start&&row.when<=end))continue;
    if(!q){matches.push(row.message);continue;}
    if(row.summaryLower===null){
     const body=row.message.body;
     if(body.startsWith(PREFIX))row.summaryLower=summary(body).toLocaleLowerCase();
     else row.summaryLower=lowerBody(row.message);
    }
    if(row.summaryLower.includes(q))matches.push(row.message);
   }
   cachedSearch={key,matches};
  }
  const size=Math.max(1,Math.min(50,pageSize)),index=Math.max(0,Number.isSafeInteger(page)?page:0);
  return {total:cachedSearch.matches.length,items:cachedSearch.matches.slice(index*size,(index+1)*size)};
 }
 return {user,server,messages:forPeer,hasBodyMatch,search:find};
}
return {PREFIX,HOUR,parse,encode,summary,sessions,search,messageIndex};
});
