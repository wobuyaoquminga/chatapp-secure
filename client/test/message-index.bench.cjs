// Run with: node test/message-index.bench.cjs
// Synthetic UI read benchmark; it does not measure Electron rendering or disk I/O.
const assert=require('node:assert/strict');
const {performance}=require('node:perf_hooks');
const F=require('../ui/features.js');

const user='alice',server='https://example.test',peerCount=20,perPeer=1000,pages=20;
const names=Array.from({length:peerCount},(_,i)=>'peer'+i);
const messages=[];
for(let i=0;i<perPeer;i++)for(let p=0;p<peerCount;p++)messages.push({
 sender:i%2?user:names[p],recipient:i%2?names[p]:user,
 clientId:`${p}:${i}`,body:`find ${p} ${i}`,
 createdAt:new Date(Date.UTC(2026,5,1)+((i*37)%perPeer)*1000).toISOString()
});

function oldPeers(){return names.map(name=>({name,messages:messages.filter(m=>(m.sender===user&&m.recipient===name)||(m.sender===name&&m.recipient===user)).sort((a,b)=>a.createdAt.localeCompare(b.createdAt))})).sort((a,b)=>(b.messages.at(-1)?.createdAt||'').localeCompare(a.messages.at(-1)?.createdAt||'')||a.name.localeCompare(b.name));}
function indexedPeers(index){return names.map(name=>({name,messages:index.messages(name)})).sort((a,b)=>(b.messages.at(-1)?.createdAt||'').localeCompare(a.messages.at(-1)?.createdAt||'')||a.name.localeCompare(b.name));}
function measure(run){const start=performance.now();const value=run();return {ms:performance.now()-start,value};}
function median(values){return values.sort((a,b)=>a-b)[Math.floor(values.length/2)].toFixed(2);}

const oldList=oldPeers(),index=F.messageIndex(messages,user,server);
assert.deepEqual(indexedPeers(index).map(row=>[row.name,row.messages.at(-1)?.clientId]),oldList.map(row=>[row.name,row.messages.at(-1)?.clientId]));
for(let page=0;page<pages;page++)assert.deepEqual(index.search(names[0],{query:'find',page}).items,F.search(messages,user,names[0],{query:'find',page}).items);

const oldListTimes=[],indexedListTimes=[],oldTotalTimes=[],indexedTotalTimes=[],buildTimes=[],indexedReadTimes=[];
for(let iteration=0;iteration<7;iteration++){
 oldListTimes.push(measure(oldPeers).ms);
 indexedListTimes.push(measure(()=>indexedPeers(F.messageIndex(messages,user,server))).ms);
 oldTotalTimes.push(measure(()=>{oldPeers();for(let page=0;page<pages;page++)F.search(messages,user,names[0],{query:'find',page});}).ms);
 const built=measure(()=>F.messageIndex(messages,user,server));buildTimes.push(built.ms);
 const read=measure(()=>{indexedPeers(built.value);for(let page=0;page<pages;page++)built.value.search(names[0],{query:'find',page});});indexedReadTimes.push(read.ms);indexedTotalTimes.push(built.ms+read.ms);
}
console.log(`Synthetic local benchmark: ${messages.length} messages, ${peerCount} peers, ${pages} pages of one query; median of 7 runs (ms)`);
console.log(`Previous list only: ${median(oldListTimes)}`);
console.log(`Index construction + list only: ${median(indexedListTimes)}`);
console.log(`Previous list + pages: ${median(oldTotalTimes)}`);
console.log(`Index construction: ${median(buildTimes)}`);
console.log(`Indexed list + pages after construction: ${median(indexedReadTimes)}`);
console.log(`Index construction + list + pages: ${median(indexedTotalTimes)}`);
