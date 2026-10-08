const {test}=require('node:test');const assert=require('node:assert/strict');
const {ReverseGeocoder,format}=require('../reverse-geocoding.cjs');

test('reverse address uses only a real road and optional house number',()=>{
 assert.equal(format({status:'1',regeocode:{roadinters:[{first_name:'南京东路',second_name:'河南中路',distance:'30'}]}}),'南京东路与河南中路交叉口附近');
 assert.equal(format({status:'1',regeocode:{roadinters:[{first_name:'南京东路',second_name:'河南中路',distance:'300'}],addressComponent:{streetNumber:{street:'南京东路',number:'1号',distance:'20'}}}}),'南京东路1号附近');
 assert.equal(format({status:'1',regeocode:{addressComponent:{district:'黄浦区'}}}),'');
 assert.equal(format({status:'0',regeocode:{roadinters:[{first_name:'A',second_name:'B',distance:'0'}]}}),'');
});

test('missing or malformed private key does not send coordinates',async()=>{
 let calls=0;const request=()=>{calls++;return {address:{road:'A'}};};
 for(const amapKey of ['', 'bad', 'https://example.test/reverse']){
  const resolver=new ReverseGeocoder({amapKey,request});assert.equal(await resolver.resolve(31,121),'');
 }assert.equal(calls,0);
});

test('Amap converts WGS84 before reverse lookup and caches with rate limit',async()=>{
 let now=100000,calls=[];const resolver=new ReverseGeocoder({amapKey:'1234567890abcdef1234567890abcdef',now:()=>now,delay:async ms=>{now+=ms;},request:async(url)=>{calls.push({at:now,url:String(url)});return url.pathname.includes('convert')?{status:'1',locations:'121.004000,31.003000'}:{status:'1',regeocode:{roads:[{name:'A路',distance:'10'}]}};}});
 assert.equal(await resolver.resolve(31,121),'A路附近');
 assert.equal(await resolver.resolve(31,121),'A路附近');
 assert.equal(await resolver.resolve(32,122),'A路附近');
 assert.equal(calls.length,4);for(let i=1;i<calls.length;i++)assert(calls[i].at-calls[i-1].at>=1100);
 assert.match(calls[0].url,/coordsys=gps/);assert.match(calls[0].url,/locations=121.000000%2C31.000000/);
 assert.match(calls[1].url,/location=121.004000%2C31.003000/);
 assert.equal(await resolver.resolve(33,123,{live:true}),'A路附近');
 assert.equal(await resolver.resolve(34,124,{live:true}),'');
 assert.equal(calls.length,6);
});

test('revoked lease and changed account drop pending or late geocoder results',async()=>{
 let authorized=true,finish;const resolver=new ReverseGeocoder({amapKey:'1234567890abcdef1234567890abcdef',request:()=>new Promise(resolve=>{finish=resolve;})});
 const pending=resolver.resolve(31,121,{authorized:()=>authorized});
 while(!finish)await new Promise(resolve=>setImmediate(resolve));
 authorized=false;resolver.cancel();finish({status:'1',locations:'121.004000,31.003000'});
 assert.equal(await pending,'');assert.equal(resolver.cache.size,0);
 assert.equal(await resolver.resolve(31,121,{authorized:()=>authorized}),'');
});

test('empty address expires quickly and a full queue falls back without new requests',async()=>{
 let now=100000,calls=0;const key='1234567890abcdef1234567890abcdef';
 const empty=new ReverseGeocoder({amapKey:key,now:()=>now,delay:async ms=>{now+=ms;},request:async url=>{calls++;return url.pathname.includes('convert')?{status:'1',locations:'121,31'}:{status:'1',regeocode:{}};}});
 assert.equal(await empty.resolve(31,121),'');assert.equal(await empty.resolve(31,121),'');assert.equal(calls,2);
 now+=31000;assert.equal(await empty.resolve(31,121),'');assert.equal(calls,4);
 let finish,authorized=true;const queued=new ReverseGeocoder({amapKey:key,request:()=>new Promise(resolve=>{finish=resolve;})});
 const jobs=[1,2,3,4].map(n=>queued.resolve(n,121,{authorized:()=>authorized}));
 assert.equal(await queued.resolve(5,121,{authorized:()=>authorized}),'');assert.equal(queued.pending.size,4);
 while(!finish)await new Promise(resolve=>setImmediate(resolve));authorized=false;queued.cancel();finish({status:'1',locations:'121,31'});
 assert.deepEqual(await Promise.all(jobs),['','','','']);
});
