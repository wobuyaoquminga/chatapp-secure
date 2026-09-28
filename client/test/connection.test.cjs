const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const WebSocket=require('ws');
const {once}=require('node:events');
const {Controller}=require('../controller.cjs');

const storage={isEncryptionAvailable:()=>true,encryptString:text=>Buffer.from(text),decryptString:data=>data.toString()};
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function waitFor(check,description) {
  const deadline=Date.now()+2000;
  while(!check()){
    if(Date.now()>deadline)assert.fail('Timed out waiting for '+description);
    await pause(5);
  }
}
async function fixture(serverOptions={}) {
  const dir=fs.mkdtempSync(path.join(os.tmpdir(),'chat-connection-'));
  const server=new WebSocket.Server({host:'127.0.0.1',port:0,...serverOptions});
  await once(server,'listening');
  const controller=new Controller(dir,storage,()=>{},
    {readyTimeoutMs:60,heartbeatIntervalMs:25,pongTimeoutMs:15,retryBaseMs:20,retryMaxMs:60,random:()=>1});
  controller.server='http://127.0.0.1:'+server.address().port;
  controller.user='alice';controller.token='test-token';
  controller.engine={state:{messages:{},outbox:{},verified:{},hiddenSessions:{},hiddenContacts:{}},accepted:()=>{}};
  controller.request=async endpoint=>{
    if(endpoint==='/api/account-events'||endpoint==='/api/contacts')return [];
    throw new Error('Unexpected request: '+endpoint);
  };
  return {server,controller,async close(){controller.logout();for(const socket of server.clients)socket.terminate();await new Promise(resolve=>server.close(resolve));fs.rmSync(dir,{recursive:true,force:true});}};
}

test('a dropped local WebSocket reconnects and successful ready sync resets backoff',async()=>{
  const f=await fixture();let connections=0;let first;
  try{
    f.server.on('connection',socket=>{
      connections++;if(!first)first=socket;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'first ready sync');
    first.terminate();
    await waitFor(()=>connections>=2&&f.controller.online,'reconnected ready sync');
    assert.equal(f.controller.retryAttempt,0);
  }finally{await f.close();}
});

test('missing ready frame times out; logout cancels retry and connection timers',async()=>{
  const f=await fixture();let connections=0;
  try{
    f.server.on('connection',()=>{connections++;});
    f.controller.connect();
    await waitFor(()=>connections>=2,'ready timeout reconnect');
    assert.ok(f.controller.retryAttempt>=1);
    f.controller.logout();
    const afterLogout=connections;
    await pause(180);
    assert.equal(connections,afterLogout);
    assert.equal(f.controller.retry,null);
    assert.equal(f.controller.readyTimeout,null);
    assert.equal(f.controller.heartbeat,null);
    assert.equal(f.controller.pongTimeout,null);
  }finally{await f.close();}
});

test('receiving ready keeps a slow account sync from triggering the ready timeout',async()=>{
  const f=await fixture();let connections=0;
  try{
    f.controller.request=async endpoint=>{
      if(endpoint==='/api/account-events'){await pause(130);return [];}
      if(endpoint==='/api/contacts')return [];
      throw new Error('Unexpected request: '+endpoint);
    };
    f.server.on('connection',socket=>{
      connections++;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'slow ready sync');
    assert.equal(connections,1);
    assert.equal(f.controller.retryAttempt,0);
  }finally{await f.close();}
});

test('an unresponsive local WebSocket is terminated after an unanswered ping',async()=>{
  const f=await fixture({autoPong:false});let connections=0,pings=0;
  try{
    f.server.on('connection',socket=>{
      connections++;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
      socket.on('ping',()=>{pings++;});
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'ready sync');
    await waitFor(()=>pings>=1&&connections>=2,'heartbeat reconnect');
  }finally{await f.close();}
});

test('vault write failure during a live event pauses the connection without retrying',async()=>{
  const f=await fixture();let connections=0;let socket;
  try{
    f.server.on('connection',peer=>{
      connections++;socket=peer;
      peer.on('message',()=>peer.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.online,'ready sync');
    f.controller.vault={write:()=>{throw new Error('disk failure');}};
    socket.send(JSON.stringify({type:'accepted',message:{id:'one'}}));
    await waitFor(()=>f.controller.storageFailed&&f.controller.socket===null,'connection paused after storage failure');
    assert.match(f.controller.status,/本地保存失败.*已暂停连接/);
    await pause(140);
    assert.equal(connections,1);
    assert.equal(f.controller.retry,null);
  }finally{await f.close();}
});

test('ready sync authorization failure stops reconnecting and asks for login',async()=>{
  const f=await fixture();let connections=0;
  try{
    f.controller.request=async()=>{const error=new Error('expired token');error.status=401;throw error;};
    f.server.on('connection',socket=>{
      connections++;
      socket.on('message',()=>socket.send(JSON.stringify({type:'ready'})));
    });
    f.controller.connect();
    await waitFor(()=>f.controller.authFailed&&f.controller.socket===null,'authorization failure');
    assert.match(f.controller.status,/认证已失效.*重新登录/);
    await pause(140);
    assert.equal(connections,1);
    assert.equal(f.controller.retry,null);
  }finally{await f.close();}
});
