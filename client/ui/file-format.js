(function(root,factory){const api=factory();if(typeof module==='object')module.exports=api;else root.ChatFiles=api;})(globalThis,()=>{
'use strict';
const PREFIX='CHAT_FILE_V1:',CARD='CHAT_FILE_CARD_V1:',MAX=10485760;
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const B64=/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;
function safeName(name){return typeof name==='string'&&name.length>0&&Array.from(name).length<=128&&name===name.trim()&&name!=='.'&&name!=='..'&&!/[<>:"/\\|?*\x00-\x1f\x7f]/.test(name)&&!/[. ]$/.test(name)&&!/^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(name);}
function sanitizeName(name){const value=String(name||''),last=Math.max(value.lastIndexOf('/'),value.lastIndexOf('\\'));const base=Array.from(value.slice(last+1).trim().replace(/[<>:"/\\|?*\x00-\x1f\x7f]/g,'_').replace(/[. ]+$/,'')).slice(0,128).join('').replace(/[. ]+$/,'');return safeName(base)?base:'file';}
function fields(p,full){
 if(!p||typeof p!=='object'||Array.isArray(p))return false;
 const keys=full?['v','id','name','size','key','iv','sha256','expiresAt']:['v','id','name','size'];
 if(Object.keys(p).length!==keys.length||keys.some(k=>!Object.hasOwn(p,k)))return false;
 if(p.v!==1||!UUID.test(p.id)||!safeName(p.name)||!Number.isSafeInteger(p.size)||p.size<0||p.size>MAX)return false;
 if(!full)return true;
 const base=(value,length)=>typeof value==='string'&&B64.test(value)&&atob(value).length===length&&btoa(atob(value))===value;
 return base(p.key,32)&&base(p.iv,12)&&typeof p.sha256==='string'&&/^[0-9a-f]{64}$/.test(p.sha256)&&typeof p.expiresAt==='string'&&/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(p.expiresAt)&&Number.isFinite(Date.parse(p.expiresAt))&&new Date(p.expiresAt).toISOString().startsWith(p.expiresAt.slice(0,19));
}
function parse(body){if(typeof body!=='string'||!body.startsWith(PREFIX)||body.length>=4000)return null;try{const p=JSON.parse(body.slice(PREFIX.length));return fields(p,true)?p:null;}catch{return null;}}
function parseCard(body){if(typeof body!=='string'||!body.startsWith(CARD)||body.length>500)return null;try{const p=JSON.parse(body.slice(CARD.length));return fields(p,false)?p:null;}catch{return null;}}
function publicBody(body){if(typeof body!=='string'||!body.startsWith(PREFIX))return body;const p=parse(body);return p?CARD+JSON.stringify({v:1,id:p.id,name:p.name,size:p.size}):'[文件] 无效描述';}
function encode(p){if(!fields(p,true))throw new Error('文件信息无效');return PREFIX+JSON.stringify(p);}
function mediaKind(name){const extension=String(name||'').toLowerCase().split('.').pop();return ['jpg','jpeg','png','gif','webp'].includes(extension)?'image':['mp4','webm'].includes(extension)?'video':null;}
return {PREFIX,CARD,MAX,safeName,sanitizeName,parse,parseCard,publicBody,encode,mediaKind};
});
