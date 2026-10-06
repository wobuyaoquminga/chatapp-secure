const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');

function fixture(){
  const elements=new Map(),pending=[];
  const element=id=>{
    if(!elements.has(id))elements.set(id,{value:'',hidden:false,textContent:'',setAttribute(){},addEventListener(){},focus(){},querySelector(){return null;},replaceChildren(){}});
    return elements.get(id);
  };
  const sandbox={
    document:{getElementById:element,addEventListener(){}},
    window:{addEventListener(){},ChatFeatures:{},ChatCalls:{create:()=>({snapshot(){},closeActions(){}})},chat:{
      onCall(){},onLocationStop(){},subscribe(){},
      command:(action,payload)=>action==='send'
        ?new Promise(resolve=>pending.push({payload,resolve}))
        :Promise.resolve({ok:false,error:'test-only command'})
    }},
    setTimeout,clearTimeout,setInterval,clearInterval
  };
  vm.createContext(sandbox);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../ui/app.js'),'utf8'),sandbox);
  vm.runInContext("activePeer='bob';render=()=>{};",sandbox);
  return {element,pending,sandbox,close:()=>vm.runInContext('clearInterval(expiryTimer)',sandbox)};
}

test('a retyped identical message stays in the draft after an earlier send completes',async()=>{
  const f=fixture();
  try{
    const body=f.element('body');body.value='相同正文';body.oninput();
    const sending=f.element('sendForm').onsubmit({preventDefault(){}});
    assert.equal(f.pending[0].payload.body,'相同正文');
    body.value='';body.oninput();body.value='相同正文';body.oninput();
    f.pending[0].resolve({ok:true,value:{}});await sending;
    assert.equal(body.value,'相同正文');
    const next=f.element('sendForm').onsubmit({preventDefault(){}});
    f.pending[1].resolve({ok:true,value:{}});await next;
    assert.equal(body.value,'');
  }finally{f.close();}
});

test('switching away and back preserves a new draft when the old send completes',async()=>{
  const f=fixture();
  try{
    const body=f.element('body');body.value='草稿';body.oninput();
    const sending=f.element('sendForm').onsubmit({preventDefault(){}});
    await vm.runInContext("openPeer('alice')",f.sandbox);
    await vm.runInContext("openPeer('bob')",f.sandbox);
    body.value='草稿';
    f.pending[0].resolve({ok:true,value:{}});await sending;
    assert.equal(body.value,'草稿');
  }finally{f.close();}
});

test('rapid duplicate submissions encrypt and enqueue one message',async()=>{
  const f=fixture();
  try{
    f.element('body').value='只发送一次';
    const first=f.element('sendForm').onsubmit({preventDefault(){}});
    await f.element('sendForm').onsubmit({preventDefault(){}});
    assert.equal(f.pending.length,1);
    f.pending[0].resolve({ok:true,value:{}});await first;
    assert.equal(f.element('body').value,'');
  }finally{f.close();}
});

test('drafts stay with their conversation instead of leaking into another recipient',async()=>{
  const f=fixture();
  try{
    const body=f.element('body');body.value='给 bob 的草稿';body.oninput();
    await vm.runInContext("openPeer('alice')",f.sandbox);
    assert.equal(body.value,'');body.value='给 alice 的草稿';body.oninput();
    await vm.runInContext("openPeer('bob')",f.sandbox);
    assert.equal(body.value,'给 bob 的草稿');
    await vm.runInContext("openPeer('alice')",f.sandbox);
    assert.equal(body.value,'给 alice 的草稿');
  }finally{f.close();}
});

test('a late safety code response cannot open a panel under a different contact',async()=>{
  const f=fixture();
  try{
    let finish;
    f.sandbox.window.chat.command=(action)=>action==='safety'?new Promise(resolve=>{finish=resolve}):Promise.resolve({ok:false,error:'test-only command'});
    f.element('safetyPanel').hidden=true;
    const fetching=f.element('safety').onclick();
    await vm.runInContext("openPeer('alice')",f.sandbox);
    finish({ok:true,value:{code:'1234512345',verified:false}});await fetching;
    assert.equal(f.element('safetyPanel').hidden,true);
    assert.equal(f.element('safetyCode').textContent,'');
  }finally{f.close();}
});
