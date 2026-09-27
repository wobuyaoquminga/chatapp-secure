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
return {PREFIX,HOUR,parse,encode,summary,sessions,search};
});
