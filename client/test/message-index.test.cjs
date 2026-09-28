const {test}=require('node:test');
const assert=require('node:assert/strict');
const F=require('../ui/features.js');

const message=(sender,recipient,clientId,createdAt,body)=>({sender,recipient,clientId,createdAt,body,status:'sent'});

test('message index isolates the signed-in user and account snapshot',()=>{
 const alice=[message('alice','bob','1','2026-06-01T10:00:00Z','private on server A'),message('eve','bob','2','2026-06-01T11:00:00Z','other user')];
 const serverA=F.messageIndex(alice,'alice','https://a.example');
 assert.deepEqual(serverA.messages('bob').map(m=>m.clientId),['1']);
 assert.equal(serverA.search('bob',{query:'other user'}).total,0);
 assert.equal(serverA.hasBodyMatch('bob','other user'),false);
 const serverB=F.messageIndex([message('alice','bob','1','2026-06-01T10:00:00Z','private on server B')],'alice','https://b.example');
 assert.equal(serverB.search('bob',{query:'server A'}).total,0);
 assert.equal(serverB.search('bob',{query:'server B'}).total,1);
 assert.equal(F.messageIndex(alice,'eve','https://a.example').search('bob',{query:'other user'}).total,1);
});

test('a new snapshot invalidates cached search and message previews',()=>{
 const first=[message('alice','bob','1','2026-06-01T10:00:00Z','draft')];
 const old=F.messageIndex(first,'alice','a');
 assert.equal(old.search('bob',{query:'final'}).total,0);
 const updated=[{...first[0],body:'final',status:'delivered'},message('bob','alice','2','2026-06-01T11:00:00Z','new')];
 const current=F.messageIndex(updated,'alice','a');
 assert.equal(current.search('bob',{query:'final'}).total,1);
 assert.equal(current.search('bob',{query:'draft'}).total,0);
 assert.equal(current.messages('bob').at(-1).body,'new');
 assert.equal(current.messages('bob')[0].status,'delivered');
});

test('index keeps ascending chat order and stable newest-first search ties',()=>{
 const input=[message('bob','alice','late','2026-06-03T00:00:00Z','hit'),message('alice','bob','early1','2026-06-01T00:00:00Z','hit'),message('bob','alice','early2','2026-06-01T00:00:00Z','hit'),message('alice','bob','middle','2026-06-02T00:00:00Z','hit')];
 const index=F.messageIndex(input,'alice','a');
 assert.deepEqual(index.messages('bob').map(m=>m.clientId),['early1','early2','middle','late']);
 assert.deepEqual(index.search('bob',{query:'hit'}).items.map(m=>m.clientId),F.search(input,'alice','bob',{query:'hit'}).items.map(m=>m.clientId));
});

test('query result is reused across pages and updates for query or date filters',()=>{
 const input=Array.from({length:45},(_,i)=>message('alice','bob',String(i),i<25?'2026-06-01T10:00:00Z':'2026-06-02T10:00:00Z',i===5?'other':'find '+i));
 input.push(message('alice','eve','secret','2026-06-02T10:00:00Z','find secret'));
 const index=F.messageIndex(input,'alice','a');
 const first=index.search('bob',{query:'find',page:0}),second=index.search('bob',{query:'find',page:1}),last=index.search('bob',{query:'find',page:2});
 assert.equal(first.total,44);assert.equal(first.items.length,20);assert.equal(second.items.length,20);assert.equal(last.items.length,4);
 assert.equal(new Set([...first.items,...second.items,...last.items]).size,44);
 assert.deepEqual(second.items,F.search(input,'alice','bob',{query:'find',page:1}).items);
 assert.equal(index.search('bob',{query:'find',from:'2026-06-02',to:'2026-06-02'}).total,20);
 assert.equal(index.search('bob',{query:'other'}).total,1);
 assert.equal(index.search('bob',{query:'find'}).total,44);
});

test('paging the same query does not read every message body again',()=>{
 let reads=0;
 const input=Array.from({length:60},(_,i)=>({sender:'alice',recipient:'bob',clientId:String(i),createdAt:'2026-06-01T10:00:00Z',get body(){reads++;return 'match '+i;}}));
 const index=F.messageIndex(input,'alice','a');
 assert.equal(reads,0,'building the list index does not read message bodies');
 index.search('bob',{query:'match',page:0});const afterFirst=reads;
 index.search('bob',{query:'match',page:1});
 assert.equal(reads,afterFirst);
});

test('indexed search excludes invalid timestamps exactly like local search',()=>{
 const input=[message('alice','bob','bad','invalid-date','find'),message('alice','bob','valid','2026-06-01T10:00:00Z','find')];
 const index=F.messageIndex(input,'alice','a');
 for(const options of [{query:'find'},{query:'find',from:'2026-06-01',to:'2026-06-01'}]){
  assert.deepEqual(index.search('bob',options),F.search(input,'alice','bob',options));
 }
});
