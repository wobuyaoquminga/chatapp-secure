'use strict';
const https=require('node:https');

function validCoordinates(latitude,longitude){return typeof latitude==='number'&&Number.isFinite(latitude)&&Math.abs(latitude)<=90&&typeof longitude==='number'&&Number.isFinite(longitude)&&Math.abs(longitude)<=180;}
function validText(value){return typeof value==='string'&&value.trim()&&value.length<=256&&!/[\x00-\x1f\x7f-\x9f]/.test(value);}
function nearby(distance,max){return (typeof distance==='number'||typeof distance==='string'&&/^\d+(?:\.\d+)?$/.test(distance))&&Number.isFinite(Number(distance))&&Number(distance)>=0&&Number(distance)<=max;}
function format(result){
  if(String(result?.status)!=='1')return '';
  const code=result.regeocode;if(!code||typeof code!=='object')return '';
  const intersection=Array.isArray(code.roadinters)?code.roadinters.find(item=>validText(item.first_name)&&validText(item.second_name)&&nearby(item.distance,80)):null;
  if(intersection){const address=intersection.first_name.trim()+'与'+intersection.second_name.trim()+'交叉口附近';if(validText(address))return address;}
  const number=code.addressComponent?.streetNumber;
  if(number&&validText(number.street)&&nearby(number.distance,100)){
    const address=number.street.trim()+(validText(number.number)?number.number.trim():'')+'附近';if(validText(address))return address;
  }
  const road=Array.isArray(code.roads)?code.roads.find(item=>validText(item.name)&&nearby(item.distance,100)):null;
  const address=road?road.name.trim()+'附近':'';
  return validText(address)?address:'';
}
function transport(url,signal){
  return new Promise((resolve,reject)=>{
    let timer,settled=false;
    const done=(error,value)=>{if(settled)return;settled=true;clearTimeout(timer);if(error)reject(error);else resolve(value);};
    const request=https.get(url,{signal,headers:{'User-Agent':'Chat/0.6.9 (https://github.com/wobuyaoquminga/chatapp-secure)','Accept':'application/json'}},response=>{
      if(response.statusCode!==200){response.resume();done(Error('Geocoder unavailable'));return;}
      let body='';response.setEncoding('utf8');response.on('data',chunk=>{body+=chunk;if(body.length>16384)request.destroy(Error('Geocoder response too large'));});
      response.on('aborted',()=>done(Error('Geocoder response aborted')));response.on('error',error=>done(error));
      response.on('end',()=>{try{done(null,JSON.parse(body));}catch(error){done(error);}});
    });
    request.on('error',error=>done(error));timer=setTimeout(()=>request.destroy(Error('Geocoder timeout')),4000);
  });
}
class ReverseGeocoder {
  constructor({amapKey='',request=transport,now=Date.now,delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))}={}){
    this.amapKey=/^[A-Za-z0-9]{16,64}$/.test(amapKey)?amapKey:'';
    this.request=request;this.now=now;this.delay=delay;this.cache=new Map();this.pending=new Map();this.tail=Promise.resolve();this.lastRequest=-Infinity;this.lastLive=-Infinity;this.recent=null;this.abort=null;
  }
  cancel(){this.abort?.abort();this.pending.clear();this.recent=null;this.lastLive=-Infinity;}
  reset(){this.cancel();this.cache.clear();}
  async resolve(latitude,longitude,{live=false,authorized=()=>true}={}){
    if(!this.amapKey||!validCoordinates(latitude,longitude)||!authorized())return '';
    const key=latitude.toFixed(4)+','+longitude.toFixed(4),now=this.now(),cached=this.cache.get(key);
    if(cached&&cached.until>now)return cached.address;
    if(live){
      if(this.recent&&this.recent.until>now&&distanceMetres(latitude,longitude,this.recent.latitude,this.recent.longitude)<30)return this.recent.address;
      if(now-this.lastLive<60000)return '';
      this.lastLive=now;
    }
    if(this.pending.has(key))return this.pending.get(key);
    if(this.pending.size>=4)return '';
    const work=this.tail.then(async()=>{
      if(!authorized())return '';
      try{
        // Windows coordinates are WGS84; Amap reverse lookup expects converted coordinates.
        const convert=new URL('https://restapi.amap.com/v3/assistant/coordinate/convert');
        convert.searchParams.set('key',this.amapKey);convert.searchParams.set('locations',longitude.toFixed(6)+','+latitude.toFixed(6));convert.searchParams.set('coordsys','gps');convert.searchParams.set('output','JSON');
        const converted=await this.fetchJson(convert,authorized);
        if(String(converted?.status)!=='1'||typeof converted.locations!=='string')return '';
        const parts=converted.locations.split(',');if(parts.length!==2||parts.some(part=>! /^-?\d+(?:\.\d+)?$/.test(part)))return '';
        const lon=Number(parts[0]),lat=Number(parts[1]);if(!validCoordinates(lat,lon))return '';
        const reverse=new URL('https://restapi.amap.com/v3/geocode/regeo');
        reverse.searchParams.set('key',this.amapKey);reverse.searchParams.set('location',lon.toFixed(6)+','+lat.toFixed(6));reverse.searchParams.set('extensions','all');reverse.searchParams.set('radius','100');reverse.searchParams.set('output','JSON');
        const address=format(await this.fetchJson(reverse,authorized));if(!authorized())return '';
        this.cache.set(key,{address,until:this.now()+(address?1800000:30000)});if(this.cache.size>256)this.cache.delete(this.cache.keys().next().value);
        if(live&&address)this.recent={latitude,longitude,address,until:this.now()+300000};
        return address;
      }catch{return '';}
    });
    this.tail=work.catch(()=>{});this.pending.set(key,work);
    try{return await work;}finally{if(this.pending.get(key)===work)this.pending.delete(key);}
  }
  async fetchJson(url,authorized){
    const wait=Math.max(0,1100-(this.now()-this.lastRequest));if(wait)await this.delay(wait);
    if(!authorized())throw Error('Location lease expired');
    const abort=new AbortController();this.abort=abort;this.lastRequest=this.now();
    try{return await this.request(url,abort.signal);}finally{if(this.abort===abort)this.abort=null;}
  }
}
function distanceMetres(a,b,c,d){const rad=Math.PI/180,x=(b-d)*rad*Math.cos((a+c)*rad/2),y=(a-c)*rad;return Math.hypot(x,y)*6371000;}
module.exports={ReverseGeocoder,format,validCoordinates};
